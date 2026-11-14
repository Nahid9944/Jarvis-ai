package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.AssistantDatabase
import com.example.data.repository.UserManagementRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.Locale

class LiveLocationService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var userManagementRepo: UserManagementRepository

    companion object {
        const val CHANNEL_ID = "nahid_live_location_channel"
        const val NOTIFICATION_ID = 4001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        fun start(context: Context) {
            val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                return
            }
            try {
                val intent = Intent(context, LiveLocationService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Safeguard against ForegroundServiceStartNotAllowedException
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, LiveLocationService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val db = AssistantDatabase.getDatabase(applicationContext)
        userManagementRepo = UserManagementRepository(db.assistantDao(), applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val lat = location.latitude
                val lng = location.longitude

                // Validate coordinates (no 0,0, NaN, or out-of-range)
                if (lat.isNaN() || lng.isNaN() || (lat == 0.0 && lng == 0.0) || lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
                    return
                }

                serviceScope.launch {
                    var addressStr = ""
                    try {
                        val geocoder = Geocoder(applicationContext, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(lat, lng, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val addr = addresses[0]
                            val subLoc = addr.subLocality ?: addr.locality ?: ""
                            val admin = addr.subAdminArea ?: addr.adminArea ?: ""
                            addressStr = listOf(subLoc, admin).filter { it.isNotBlank() }.joinToString(", ")
                        }
                    } catch (e: Exception) {
                        // ignore geocoding failure
                    }

                    userManagementRepo.updateLocation(
                        lat = lat,
                        lng = lng,
                        address = addressStr,
                        accuracy = location.accuracy
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLocationUpdates()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val hasFine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine && !hasCoarse) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForegroundWithNotification()
                    startLocationUpdates()
                } catch (e: Exception) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS লাইভ লোকেশন ট্র্যাকিং",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "JARVIS AI রিয়েল-টাইম অবস্থান টেলিমেট্রি সক্রিয় আছে"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS AI — অবস্থান টেলিমেট্রি")
            .setContentText("রিয়েল-টাইম GPS অবস্থান টেলিমেট্রি সক্রিয় রয়েছে")
            .setSmallIcon(R.drawable.ic_jarvis_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startLocationUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            stopSelf()
            return
        }

        // Battery efficient request: interval 30s, min distance 10m
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L).apply {
            setMinUpdateIntervalMillis(15_000L)
            setMinUpdateDistanceMeters(10f)
            setWaitForAccurateLocation(false)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
