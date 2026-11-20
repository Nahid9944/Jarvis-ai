package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.audio.AssistantVoiceHudState
import com.example.audio.HandsFreeSessionController
import com.example.audio.MicrophoneCoordinator
import com.example.audio.MicOwner
import com.example.audio.OfflineWakeWordEngine
import com.example.audio.WakeWordModelManager
import com.example.util.JarvisEmergencyStopCoordinator
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

data class WakeWordTriggerPayload(
    val matchedPhrase: String,
    val remainderCommand: String,
    val fullText: String
)

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
        const val ACTION_EMERGENCY_STOP = "com.example.service.EMERGENCY_STOP_WAKEWORD"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        internal fun setRunningForTest(running: Boolean) {
            _isRunning.value = running
        }

        private val _isPausedState = MutableStateFlow(false)
        val isPausedState: StateFlow<Boolean> = _isPausedState.asStateFlow()

        private val _wakeWordEvents = MutableSharedFlow<WakeWordTriggerPayload>(extraBufferCapacity = 5)
        val wakeWordEvents: SharedFlow<WakeWordTriggerPayload> = _wakeWordEvents.asSharedFlow()

        private val _currentHearingText = MutableStateFlow("")
        val currentHearingText: StateFlow<String> = _currentHearingText.asStateFlow()

        private val _sensitivityState = MutableStateFlow(WakeWordSensitivity.MEDIUM)
        val sensitivityState: StateFlow<WakeWordSensitivity> = _sensitivityState.asStateFlow()

        fun setSensitivity(context: Context, sensitivity: WakeWordSensitivity) {
            _sensitivityState.value = sensitivity
            try {
                val prefs = context.getSharedPreferences("jarvis_wakeword_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("sensitivity_level", sensitivity.name).apply()
            } catch (_: Exception) {
            }
        }

        fun getSensitivity(context: Context): WakeWordSensitivity {
            return try {
                val prefs = context.getSharedPreferences("jarvis_wakeword_prefs", Context.MODE_PRIVATE)
                val saved = prefs.getString("sensitivity_level", WakeWordSensitivity.MEDIUM.name)
                WakeWordSensitivity.valueOf(saved ?: WakeWordSensitivity.MEDIUM.name)
            } catch (_: Exception) {
                WakeWordSensitivity.MEDIUM
            }
        }

        fun start(context: Context): Boolean {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return false

            WakeWordModelManager.setOptInEnabled(context, true)
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_START
            }
            return try {
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
            WakeWordModelManager.setOptInEnabled(context, false)
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_PAUSE
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun resume(context: Context) {
            val intent = Intent(context, JarvisWakeWordService::class.java).apply {
                action = ACTION_RESUME
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        WakeWordModelManager.init(this)
        _sensitivityState.value = getSensitivity(this)
        createNotificationChannel()
        setupSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EMERGENCY_STOP -> {
                JarvisEmergencyStopCoordinator.triggerEmergencyStop(this)
                mainHandler.removeCallbacksAndMessages(null)
                stopListening()
                _isPausedState.value = false
                _isRunning.value = false
                HandsFreeSessionController.updateHudState(AssistantVoiceHudState.PAUSED)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                mainHandler.removeCallbacksAndMessages(null)
                stopListening()
                _isPausedState.value = false
                _isRunning.value = false
                HandsFreeSessionController.updateHudState(AssistantVoiceHudState.IDLE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isPaused = true
                _isPausedState.value = true
                mainHandler.removeCallbacksAndMessages(null)
                stopListening()
                updateNotification()
                HandsFreeSessionController.updateHudState(AssistantVoiceHudState.PAUSED)
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (JarvisEmergencyStopCoordinator.isEmergencyStopActive.value) {
                    JarvisEmergencyStopCoordinator.resetEmergencyStop()
                }
                isPaused = false
                _isPausedState.value = false
                updateNotification()
                HandsFreeSessionController.updateHudState(AssistantVoiceHudState.WAKE_WORD_ARMED)
                if (!GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
                    startListening()
                }
                return START_STICKY
            }
        }

        // Permission check required on Android 14+ before starting foregroundServiceType="microphone"
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            Log.w(TAG, "Stop wake-word service: RECORD_AUDIO not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        _isRunning.value = true
        isPaused = false
        _isPausedState.value = false
        HandsFreeSessionController.updateHudState(AssistantVoiceHudState.WAKE_WORD_ARMED)
        startListening()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeIntent = Intent(this, JarvisWakeWordService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePending = PendingIntent.getService(
            this,
            2,
            pauseOrResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JarvisWakeWordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emergencyIntent = Intent(this, JarvisWakeWordService::class.java).apply {
            action = ACTION_EMERGENCY_STOP
        }
        val emergencyPending = PendingIntent.getService(
            this,
            4,
            emergencyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cap = WakeWordModelManager.inspectCapabilities(this)
        val phrasesPreview = WakeWordModelManager.getCustomWakePhrases(this).take(3).joinToString(" / ")
        val statusText = if (isPaused) {
            "বিরতিতে আছে — চালু করতে Resume চাপুন"
        } else {
            "সক্রিয় ($phrasesPreview) • ${if (cap.isOnDeviceRecognitionAvailable) "On-Device" else "Hybrid"}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isPaused) "NAHID AI • ওয়েক-ওয়ার্ড বিরতি" else "NAHID AI • হ্যান্ডস-ফ্রি সক্রিয়")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "চালু করুন" else "বিরতি",
                pauseResumePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "বন্ধ করুন",
                stopPending
            )
            .addAction(
                android.R.drawable.ic_delete,
                "জরুরি স্টপ",
                emergencyPending
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

    private fun startForegroundNotification() {
        val notification = buildNotification()
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
                "NAHID AI Wake-Word Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground listening channel for hands-free voice wake-word activation"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun setupSpeechRecognizer() {
        val hasStandard = SpeechRecognizer.isRecognitionAvailable(this)
        val hasOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            } catch (_: Throwable) {
                false
            }
        if (!hasStandard && !hasOnDevice) {
            Log.e(TAG, "SpeechRecognizer not available on this device")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasOnDevice -> {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    } catch (_: Throwable) {
                        SpeechRecognizer.createSpeechRecognizer(this)
                    }
                }
                else -> SpeechRecognizer.createSpeechRecognizer(this)
            }.apply {
                setRecognitionListener(createListener())
            }

            val prefLang = try {
                com.example.ai.AiSettingsRepository(this).settingsState.value.preferredLanguage
            } catch (_: Exception) {
                "bn"
            }
            val recognitionLocale = when (prefLang) {
                "en" -> "en-US"
                "bn" -> "bn-BD"
                else -> Locale.getDefault().toLanguageTag()
            }

            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale)
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
            MicrophoneCoordinator.release(MicOwner.WAKE_WORD_SERVICE)
            if (!isPaused && _isRunning.value && !GeminiLiveAnnouncementAdapter.isInActiveCall.value &&
                !JarvisEmergencyStopCoordinator.isEmergencyStopActive.value
            ) {
                mainHandler.postDelayed({
                    startListening()
                }, 450)
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

            if (!isPaused && _isRunning.value && !GeminiLiveAnnouncementAdapter.isInActiveCall.value &&
                !JarvisEmergencyStopCoordinator.isEmergencyStopActive.value
            ) {
                mainHandler.postDelayed({
                    startListening()
                }, 350)
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
        if (isPaused || JarvisEmergencyStopCoordinator.isEmergencyStopActive.value) return
        val cooldown = WakeWordModelManager.getCooldownMs(this)
        if (!OfflineWakeWordEngine.canTriggerNow(cooldownMs = cooldown)) return

        val phrases = WakeWordModelManager.getCustomWakePhrases(this)
        val match = OfflineWakeWordEngine.matchWakePhrase(
            spokenText = spokenText,
            configuredPhrases = phrases,
            sensitivity = _sensitivityState.value,
            isPartial = isPartial
        )
        if (match.matched) {
            OfflineWakeWordEngine.markTriggered()
            triggerWakeActivation(spokenText, match.matchedPhrase, match.remainderCommand)
        }
    }

    private fun triggerWakeActivation(fullText: String, matchedPhrase: String, remainderCommand: String) {
        Log.i(TAG, "Wake-Word DETECTED phrase='$matchedPhrase' full='$fullText'")
        vibratePhone()

        // Pause SpeechRecognizer cleanly and transfer mic ownership via HandsFreeSessionController
        stopListening()

        val cleanRemainder = remainderCommand.trim()
        serviceScope.launch {
            _wakeWordEvents.emit(
                WakeWordTriggerPayload(
                    matchedPhrase = matchedPhrase,
                    remainderCommand = cleanRemainder,
                    fullText = fullText
                )
            )
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_WAKE_TRIGGERED", true)
            putExtra("EXTRA_MATCHED_PHRASE", matchedPhrase)
            putExtra("EXTRA_REMAINDER_COMMAND", cleanRemainder)
            putExtra("EXTRA_SPOKEN_TEXT", fullText)
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
        } catch (_: Exception) {
        }
    }

    private fun startListening() {
        if (isPaused || isCurrentlyListening || GeminiLiveAnnouncementAdapter.isInActiveCall.value ||
            JarvisEmergencyStopCoordinator.isEmergencyStopActive.value
        ) return
        if (!MicrophoneCoordinator.tryAcquire(MicOwner.WAKE_WORD_SERVICE, preempt = false)) {
            Log.d(TAG, "Wake-word recognizer deferred: mic owned by ${MicrophoneCoordinator.currentOwner.value}")
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
            MicrophoneCoordinator.release(MicOwner.WAKE_WORD_SERVICE)
            setupSpeechRecognizer()
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        isCurrentlyListening = false
        MicrophoneCoordinator.release(MicOwner.WAKE_WORD_SERVICE)
    }

    override fun onDestroy() {
        _isRunning.value = false
        _isPausedState.value = false
        isPaused = true
        mainHandler.removeCallbacksAndMessages(null)
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
