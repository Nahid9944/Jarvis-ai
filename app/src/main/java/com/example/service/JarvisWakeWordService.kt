package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class WakeWordSensitivity(val level: Int, val titleBengali: String, val descriptionBengali: String) {
    LOW(1, "কম (শান্ত পরিবেশ)", "কঠোর ফিল্টারিং — শুধুমাত্র স্পষ্ট 'Jarvis' বা 'জারভিস' শব্দেই সক্রিয় হবে। অপ্রয়োজনীয় ট্রিগার রোধ করে।"),
    MEDIUM(2, "মধ্যম (সাধারণ)", "স্ট্যান্ডার্ড সংবেদনশীলতা — স্বাভাবিক স্বরে 'Jarvis', 'Hey Jarvis', 'জারভিস', 'শোনো জারভিস' ইত্যাদি দ্রুত শনাক্ত করবে।"),
    HIGH(3, "উচ্চ (অতি সংবেদনশীল)", "উচ্চ সংবেদনশীলতা — ফিসফিসানি, দূরবর্তী বা আংশিক উচ্চারণেও তাৎক্ষণিক সক্রিয় হয়।")
}

class JarvisWakeWordService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isCurrentlyListening = false
    private var isPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "JarvisWakeWordService"
        const val CHANNEL_ID = "jarvis_wakeword_channel"
        const val NOTIFICATION_ID = 5050
        const val ACTION_START = "com.example.service.START_WAKEWORD"
        const val ACTION_STOP = "com.example.service.STOP_WAKEWORD"
        const val ACTION_PAUSE = "com.example.service.PAUSE_WAKEWORD"
        const val ACTION_RESUME = "com.example.service.RESUME_WAKEWORD"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _wakeWordEvents = MutableSharedFlow<String>(extraBufferCapacity = 5)
        val wakeWordEvents: SharedFlow<String> = _wakeWordEvents.asSharedFlow()

        private val _currentHearingText = MutableStateFlow("")
        val currentHearingText: StateFlow<String> = _currentHearingText.asStateFlow()

        private val _sensitivityState = MutableStateFlow(WakeWordSensitivity.MEDIUM)
        val sensitivityState: StateFlow<WakeWordSensitivity> = _sensitivityState.asStateFlow()

        private val LOW_SENSITIVITY_PATTERNS = listOf(
            "jarvis",
            "hey jarvis",
            "জারভিস",
            "এই জারভিস"
        )

        private val MEDIUM_SENSITIVITY_PATTERNS = listOf(
            "jarvis",
            "hey jarvis",
            "hi jarvis",
            "ok jarvis",
            "hello jarvis",
            "jarvis ai",
            "জারভিস",
            "এই জারভিস",
            "হ্যালো জারভিস",
            "ওহে জারভিস",
            "শোনো জারভিস"
        )

        private val HIGH_SENSITIVITY_PATTERNS = listOf(
            "jarvis",
            "hey jarvis",
            "hi jarvis",
            "ok jarvis",
            "hello jarvis",
            "jarvis ai",
            "জারভিস",
            "এই জারভিস",
            "হ্যালো জারভিস",
            "ওহে জারভিস",
            "শোনো জারভিস",
            "javis",
            "charvis",
            "সারভিস",
            "জার্ভিস",
            "জারবিস",
            "হে জারভিস",
            "জার্ভ",
            "jarv",
            "service",
            "jar vis",
            "jarvi"
        )

        fun setSensitivity(context: Context, sensitivity: WakeWordSensitivity) {
            _sensitivityState.value = sensitivity
            try {
                val prefs = context.getSharedPreferences("jarvis_wakeword_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("sensitivity_level", sensitivity.name).apply()
            } catch (e: Exception) {
                // ignore
            }
        }

        fun getSensitivity(context: Context): WakeWordSensitivity {
            return try {
                val prefs = context.getSharedPreferences("jarvis_wakeword_prefs", Context.MODE_PRIVATE)
                val saved = prefs.getString("sensitivity_level", WakeWordSensitivity.MEDIUM.name)
                WakeWordSensitivity.valueOf(saved ?: WakeWordSensitivity.MEDIUM.name)
            } catch (e: Exception) {
                WakeWordSensitivity.MEDIUM
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _sensitivityState.value = getSensitivity(this)
        createNotificationChannel()
        setupSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                mainHandler.removeCallbacksAndMessages(null)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isPaused = true
                mainHandler.removeCallbacksAndMessages(null)
                stopListening()
                return START_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                if (!GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
                    startListening()
                }
                return START_STICKY
            }
        }

        startForegroundNotification()
        _isRunning.value = true
        isPaused = false
        startListening()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Wake-Word Engine")
            .setContentText("Hands-free active: Say 'Jarvis' or 'জারভিস' anytime")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Wake-Word Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground listening channel for 'Jarvis' wake-word activation"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer not available on this device")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createListener())
            }

            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up SpeechRecognizer", e)
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isCurrentlyListening = true
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isCurrentlyListening = false
        }

        override fun onError(error: Int) {
            isCurrentlyListening = false
            _currentHearingText.value = ""
            // Schedule smooth restart after silence/timeout
            if (!isPaused && _isRunning.value && !GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
                mainHandler.postDelayed({
                    startListening()
                }, 400)
            }
        }

        override fun onResults(results: Bundle?) {
            isCurrentlyListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val best = matches[0]
                _currentHearingText.value = best
                checkTextForWakeWord(best, isPartial = false)
            }

            if (!isPaused && _isRunning.value && !GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
                mainHandler.postDelayed({
                    startListening()
                }, 300)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!partials.isNullOrEmpty()) {
                val partial = partials[0]
                _currentHearingText.value = partial
                checkTextForWakeWord(partial, isPartial = true)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun checkTextForWakeWord(spokenText: String, isPartial: Boolean) {
        val sensitivity = _sensitivityState.value
        // At LOW sensitivity, ignore partial speech to prevent accidental triggers
        if (sensitivity == WakeWordSensitivity.LOW && isPartial) return

        val lower = spokenText.lowercase().trim()
        val patterns = when (sensitivity) {
            WakeWordSensitivity.LOW -> LOW_SENSITIVITY_PATTERNS
            WakeWordSensitivity.MEDIUM -> MEDIUM_SENSITIVITY_PATTERNS
            WakeWordSensitivity.HIGH -> HIGH_SENSITIVITY_PATTERNS
        }

        val detected = patterns.any { pattern -> lower.contains(pattern) }
        if (detected) {
            triggerWakeActivation(spokenText)
        }
    }

    private fun triggerWakeActivation(text: String) {
        Log.i(TAG, "Wake-Word DETECTED in: '$text'")
        vibratePhone()

        serviceScope.launch {
            _wakeWordEvents.emit(text)
        }

        // Launch or bring Jarvis MainActivity to foreground
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_WAKE_TRIGGERED", true)
            putExtra("EXTRA_SPOKEN_TEXT", text)
        }
        startActivity(launchIntent)
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(120)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startListening() {
        if (isPaused || isCurrentlyListening || GeminiLiveAnnouncementAdapter.isInActiveCall.value) return
        if (!com.example.audio.MicrophoneCoordinator.tryAcquire(com.example.audio.MicOwner.WAKE_WORD_SERVICE, preempt = false)) {
            Log.d(TAG, "Wake-word recognizer deferred: mic owned by ${com.example.audio.MicrophoneCoordinator.currentOwner.value}")
            return
        }
        try {
            if (speechRecognizer == null) {
                setupSpeechRecognizer()
            }
            speechRecognizer?.startListening(recognizerIntent)
            isCurrentlyListening = true
        } catch (e: Exception) {
            isCurrentlyListening = false
            com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.WAKE_WORD_SERVICE)
            // Re-setup on fatal error
            setupSpeechRecognizer()
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        isCurrentlyListening = false
        com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.WAKE_WORD_SERVICE)
    }

    override fun onDestroy() {
        _isRunning.value = false
        isPaused = true
        mainHandler.removeCallbacksAndMessages(null)
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        speechRecognizer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
