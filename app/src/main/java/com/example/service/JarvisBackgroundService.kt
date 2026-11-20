package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JarvisBackgroundService
 *
 * Continuous Foreground Service that allows the AI Assistant (Jarvis OS)
 * to run continuously in the background, listening to voice commands via microphone
 * and executing NAHID AI personal assistant tasks even when the app is minimized.
 */
class JarvisBackgroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "jarvis_ai_background_channel"
        const val NOTIFICATION_ID = 5001
        const val ACTION_START = "ACTION_START_JARVIS"
        const val ACTION_STOP = "ACTION_STOP_JARVIS"
        const val ACTION_PAUSE = "ACTION_PAUSE_JARVIS"
        const val ACTION_RESUME = "ACTION_RESUME_JARVIS"
        const val ACTION_EMERGENCY_STOP = "ACTION_EMERGENCY_STOP_JARVIS"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        private var onPauseRuntimeHook: (() -> Unit)? = null
        private var onResumeRuntimeHook: (() -> Unit)? = null

        fun registerRuntimeHooks(
            onPause: (() -> Unit)?,
            onResume: (() -> Unit)?
        ) {
            onPauseRuntimeHook = onPause
            onResumeRuntimeHook = onResume
        }

        fun start(context: Context): Boolean {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMic) return false

            return try {
                val intent = Intent(context, JarvisBackgroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, JarvisBackgroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun pause(context: Context) {
            try {
                val intent = Intent(context, JarvisBackgroundService::class.java).apply {
                    action = ACTION_PAUSE
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun resume(context: Context) {
            try {
                val intent = Intent(context, JarvisBackgroundService::class.java).apply {
                    action = ACTION_RESUME
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EMERGENCY_STOP -> {
                com.example.util.JarvisEmergencyStopCoordinator.triggerEmergencyStop(this)
                _isPaused.value = false
                stopBackgroundMode()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                _isPaused.value = false
                try {
                    onPauseRuntimeHook?.invoke()
                } catch (_: Throwable) {
                }
                stopBackgroundMode()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                _isPaused.value = true
                try {
                    onPauseRuntimeHook?.invoke()
                } catch (_: Throwable) {
                }
                com.example.audio.HandsFreeSessionController.updateHudState(
                    com.example.audio.AssistantVoiceHudState.PAUSED
                )
                updateNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                _isPaused.value = false
                com.example.util.JarvisEmergencyStopCoordinator.resetEmergencyStop()
                try {
                    onResumeRuntimeHook?.invoke()
                } catch (_: Throwable) {
                }
                val owner = com.example.audio.MicrophoneCoordinator.currentOwner.value
                val nextHud = when {
                    owner == com.example.audio.MicOwner.GEMINI_LIVE_STREAM -> com.example.audio.AssistantVoiceHudState.LISTENING
                    JarvisWakeWordService.isRunning.value -> com.example.audio.AssistantVoiceHudState.WAKE_WORD_ARMED
                    else -> com.example.audio.AssistantVoiceHudState.IDLE
                }
                com.example.audio.HandsFreeSessionController.updateHudState(nextHud)
                updateNotification()
                return START_STICKY
            }
            ACTION_START, null -> {
                val hasMic = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startBackgroundMode()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val paused = _isPaused.value
        val micOwner = com.example.audio.MicrophoneCoordinator.currentOwner.value
        val wakeRunning = JarvisWakeWordService.isRunning.value
        val titleText = when {
            paused -> "NAHID AI • ব্যাকগ্রাউন্ড বিরতি"
            micOwner == com.example.audio.MicOwner.GEMINI_LIVE_STREAM -> "NAHID AI • লাইভ ভয়েস সক্রিয়"
            wakeRunning || micOwner == com.example.audio.MicOwner.OFFLINE_SPOTTER -> "NAHID AI • ওয়েক-ওয়ার্ড প্রস্তুত"
            else -> "NAHID AI • ব্যাকগ্রাউন্ড সার্ভিস (স্ট্যান্ডবাই)"
        }
        val bodyText = when {
            paused -> "মাইক্রোফোন ও ব্যাকগ্রাউন্ড লিসেনিং বিরতিতে আছে"
            micOwner == com.example.audio.MicOwner.GEMINI_LIVE_STREAM -> "লাইভ ভয়েস মাইক্রোফোন স্ট্রিম চালু আছে"
            wakeRunning || micOwner == com.example.audio.MicOwner.OFFLINE_SPOTTER -> "ওয়েক-ওয়ার্ড সক্রিয় — ডাকার সাথে সাথেই ভয়েস সেশন চালু হবে"
            else -> "ফোরগ্রাউন্ড সার্ভিস প্রস্তুত — লাইভ ভয়েস বা ওয়েক-ওয়ার্ড সক্রিয় করলে অডিও চালু হবে"
        }

        val pauseResumeIntent = Intent(this, JarvisBackgroundService::class.java).apply {
            action = if (paused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePending = PendingIntent.getService(
            this,
            10,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JarvisBackgroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emergencyIntent = Intent(this, JarvisBackgroundService::class.java).apply {
            action = ACTION_EMERGENCY_STOP
        }
        val emergencyPendingIntent = PendingIntent.getService(
            this,
            11,
            emergencyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setSmallIcon(R.drawable.ic_jarvis_logo)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "চালু করুন" else "বিরতি",
                pauseResumePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "বন্ধ করুন",
                stopPendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "জরুরি স্টপ",
                emergencyPendingIntent
            )
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun startBackgroundMode() {
        _isRunning.value = true
        _isPaused.value = false
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopBackgroundMode() {
        _isRunning.value = false
        _isPaused.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis AI Background Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "জারভিস এআই সার্বক্ষণিক ব্যাকগ্রাউন্ড ভয়েস অ্যাসিস্ট্যান্ট চ্যানেল"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        _isPaused.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
