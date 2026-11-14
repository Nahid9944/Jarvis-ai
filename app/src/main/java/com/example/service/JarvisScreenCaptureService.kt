package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class JarvisScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayMetrics = DisplayMetrics()
    private var projectionCallback: MediaProjection.Callback? = null

    companion object {
        const val CHANNEL_ID = "jarvis_screen_capture_channel"
        const val NOTIFICATION_ID = 4040
        const val ACTION_START_CAPTURE = "com.example.service.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.service.STOP_CAPTURE"
        const val ACTION_PAUSE_CAPTURE = "com.example.service.PAUSE_CAPTURE"
        const val ACTION_RESUME_CAPTURE = "com.example.service.RESUME_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private var instance: JarvisScreenCaptureService? = null

        fun isRunning(): Boolean = _isServiceRunning.value

        fun captureCurrentScreen(onResult: (Bitmap?) -> Unit) {
            val service = instance
            if (service != null && service.mediaProjection != null) {
                service.captureSingleFrame(onResult)
            } else {
                // Fallback to Accessibility Service screenshot if available
                val accessibility = JarvisAccessibilityService.instance
                if (accessibility != null) {
                    accessibility.captureCurrentScreenBitmap(onResult)
                } else {
                    onResult(null)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_CAPTURE

        when (action) {
            ACTION_STOP_CAPTURE -> {
                com.example.util.JarvisScreenCaptureManager.onProjectionStopped()
                _isServiceRunning.value = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_CAPTURE -> {
                // Paused state handled in manager, service keeps projection alive for fast resume
                return START_NOT_STICKY
            }
            ACTION_RESUME_CAPTURE -> {
                return START_NOT_STICKY
            }
        }

        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null) {
            val initialized = setupMediaProjection(resultCode, resultData)
            if (initialized) {
                _isServiceRunning.value = true
            } else {
                _isServiceRunning.value = false
                com.example.util.JarvisScreenCaptureManager.onProjectionStopped()
                stopSelf()
                return START_NOT_STICKY
            }
        } else if (mediaProjection == null) {
            // Cannot recreate projection without token
            _isServiceRunning.value = false
            com.example.util.JarvisScreenCaptureManager.onProjectionStopped()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Screen Perception Active")
            .setContentText("Visual telemetry and screen AI analysis are ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Screen Perception",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground channel for Jarvis MediaProjection Screen Capture"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent): Boolean {
        return try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(resultCode, data) ?: return false
            mediaProjection = projection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    try {
                        virtualDisplay?.release()
                        virtualDisplay = null
                        imageReader?.close()
                        imageReader = null
                    } catch (e: Exception) {
                        // ignore
                    }
                    _isServiceRunning.value = false
                    com.example.util.JarvisScreenCaptureManager.onProjectionStopped()
                    stopSelf()
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))

            createVirtualDisplayInternal(projection)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun createVirtualDisplayInternal(projection: MediaProjection) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentBounds = windowManager.currentWindowMetrics.bounds
            displayMetrics.widthPixels = currentBounds.width()
            displayMetrics.heightPixels = currentBounds.height()
            displayMetrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        }

        // Downscale dimensions slightly for fast real-time Gemini inference
        val width = (displayMetrics.widthPixels).coerceAtLeast(480)
        val height = (displayMetrics.heightPixels).coerceAtLeast(800)
        val dpi = displayMetrics.densityDpi

        virtualDisplay?.release()
        imageReader?.close()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection.createVirtualDisplay(
            "JarvisScreenCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun updateVirtualDisplayDimensions() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val currentBounds = windowManager.currentWindowMetrics.bounds
            displayMetrics.widthPixels = currentBounds.width()
            displayMetrics.heightPixels = currentBounds.height()
            displayMetrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        }

        val width = (displayMetrics.widthPixels).coerceAtLeast(480)
        val height = (displayMetrics.heightPixels).coerceAtLeast(800)
        val dpi = displayMetrics.densityDpi

        val vd = virtualDisplay
        if (vd != null) {
            try {
                val oldReader = imageReader
                val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                imageReader = newReader
                vd.setSurface(newReader.surface)
                vd.resize(width, height, dpi)
                oldReader?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            mediaProjection?.let { projection ->
                createVirtualDisplayInternal(projection)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            updateVirtualDisplayDimensions()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun captureSingleFrame(onResult: (Bitmap?) -> Unit) {
        val reader = imageReader
        if (reader == null) {
            onResult(null)
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            var image: Image? = null
            try {
                // Try to acquire the latest image, or wait a short tick
                var attempts = 0
                while (image == null && attempts < 5) {
                    image = reader.acquireLatestImage()
                    if (image == null) {
                        delay(60)
                        attempts++
                    }
                }

                if (image != null) {
                    val width = image.width
                    val height = image.height
                    val planes = image.planes
                    val buffer: ByteBuffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    // Crop out rowPadding if necessary
                    val finalBitmap = if (rowPadding == 0) {
                        bitmap
                    } else {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    }

                    withContext(Dispatchers.Main) {
                        onResult(finalBitmap)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        // Fallback to accessibility screenshot if available
                        JarvisAccessibilityService.instance?.captureCurrentScreenBitmap(onResult) ?: onResult(null)
                    }
                }
            } catch (e: Exception) {
                image?.close()
                withContext(Dispatchers.Main) {
                    JarvisAccessibilityService.instance?.captureCurrentScreenBitmap(onResult) ?: onResult(null)
                }
            }
        }
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        instance = null
        try {
            projectionCallback?.let { cb ->
                mediaProjection?.unregisterCallback(cb)
            }
            projectionCallback = null
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            // ignore
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
