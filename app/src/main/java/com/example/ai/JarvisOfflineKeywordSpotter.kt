package com.example.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.service.GeminiLiveAnnouncementAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline Keyword Spotter for NAHID JARVIS AI.
 * Coordinates microphone ownership cleanly with GeminiLiveSessionManager and active phone calls
 * (deriving RMS energy from SpeechRecognizer callbacks instead of opening a second colliding AudioRecord).
 */
class JarvisOfflineKeywordSpotter(
    private val context: Context,
    private val onWakeWordTriggered: (matchedPhrase: String, remainderCommand: String) -> Unit
) {

    private val isSpottingActive = AtomicBoolean(false)
    private val isPausedForLiveMic = AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastDetectedKeyword = MutableStateFlow("")
    val lastDetectedKeyword: StateFlow<String> = _lastDetectedKeyword.asStateFlow()

    private val _detectionConfidence = MutableStateFlow(0f)
    val detectionConfidence: StateFlow<Float> = _detectionConfidence.asStateFlow()

    private val _audioRmsEnergy = MutableStateFlow(0f)
    val audioRmsEnergy: StateFlow<Float> = _audioRmsEnergy.asStateFlow()

    companion object {
        private const val TAG = "OfflineKeywordSpotter"

        val KEYWORD_PATTERNS = listOf(
            "jarvis",
            "hey jarvis",
            "ok jarvis",
            "hi jarvis",
            "hello jarvis",
            "jarvis ai",
            "জারভিস",
            "এই জারভিস",
            "শোনো জারভিস",
            "হ্যালো জারভিস",
            "ওহে জারভিস",
            "হে জারভিস",
            "jarv",
            "javis",
            "charvis"
        )
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start offline keyword spotter: RECORD_AUDIO permission missing.")
            return
        }

        if (com.example.service.JarvisWakeWordService.isRunning.value) {
            Log.d(TAG, "JarvisWakeWordService already handles wake listening; skipping duplicate OfflineKeywordSpotter.")
            return
        }

        if (isPausedForLiveMic.get() || GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
            Log.d(TAG, "Deferring wake-word start because live mic or phone call holds audio focus.")
            isSpottingActive.set(true)
            return
        }

        if (isSpottingActive.compareAndSet(false, true)) {
            _isListening.value = true
            Log.i(TAG, "J.A.R.V.I.S. Offline Keyword Spotter started.")
            startOfflineSpeechDetection(generation.incrementAndGet())
        } else if (!_isListening.value) {
            _isListening.value = true
            startOfflineSpeechDetection(generation.incrementAndGet())
        }
    }

    /**
     * Yields hardware microphone when Gemini Live AudioInputStreamer or an active phone call takes over.
     */
    fun pauseForLiveMic(reason: String = "live_session") {
        generation.incrementAndGet()
        isPausedForLiveMic.set(true)
        _isListening.value = false
        _audioRmsEnergy.value = 0f
        com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.OFFLINE_SPOTTER)
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            destroyRecognizerOnMain()
        }
        Log.d(TAG, "Paused offline keyword spotter ($reason) to prevent microphone contention.")
    }

    /**
     * Resumes wake-word spotting only if user wants spotting active and no active call or foreground wake service holds the mic.
     */
    fun resumeAfterLiveMic(reason: String = "live_session_ended") {
        if (!isPausedForLiveMic.compareAndSet(true, false)) return
        if (com.example.service.JarvisWakeWordService.isRunning.value) {
            Log.d(TAG, "Resume skipped: JarvisWakeWordService manages wake listening.")
            return
        }
        if (isSpottingActive.get() && !GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
            val token = generation.incrementAndGet()
            _isListening.value = true
            mainHandler.postDelayed({
                if (token == generation.get() && isSpottingActive.get() && !isPausedForLiveMic.get() &&
                    !com.example.service.JarvisWakeWordService.isRunning.value
                ) {
                    startOfflineSpeechDetection(token)
                }
            }, 350)
            Log.d(TAG, "Resumed offline keyword spotter ($reason).")
        }
    }

    fun stopListening() {
        generation.incrementAndGet()
        isSpottingActive.set(false)
        isPausedForLiveMic.set(false)
        _isListening.value = false
        _audioRmsEnergy.value = 0f
        com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.OFFLINE_SPOTTER)
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            destroyRecognizerOnMain()
        }
        Log.i(TAG, "J.A.R.V.I.S. Offline Keyword Spotter stopped.")
    }

    private fun destroyRecognizerOnMain() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer: ${e.message}")
        } finally {
            speechRecognizer = null
        }
    }

    private fun startOfflineSpeechDetection(expectedGeneration: Long = generation.get()) {
        mainHandler.post {
            if (expectedGeneration != generation.get() ||
                !isSpottingActive.get() ||
                isPausedForLiveMic.get() ||
                GeminiLiveAnnouncementAdapter.isInActiveCall.value ||
                com.example.service.JarvisWakeWordService.isRunning.value
            ) {
                _isListening.value = false
                com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.OFFLINE_SPOTTER)
                return@post
            }

            if (!com.example.audio.MicrophoneCoordinator.tryAcquire(com.example.audio.MicOwner.OFFLINE_SPOTTER, preempt = false)) {
                _isListening.value = false
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.OFFLINE_SPOTTER)
                Log.w(TAG, "SpeechRecognizer unavailable on this device.")
                _isListening.value = false
                return@post
            }

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }

                val prefLang = try {
                    AiSettingsRepository(context).settingsState.value.preferredLanguage
                } catch (_: Exception) {
                    "bn"
                }
                val recognitionLocale = when (prefLang) {
                    "en" -> "en-US"
                    "bn" -> "bn-BD"
                    else -> Locale.getDefault().toLanguageTag()
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        _audioRmsEnergy.value = ((rmsdB + 2f) * 8f).coerceIn(0f, 100f)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _isListening.value = false
                        _audioRmsEnergy.value = 0f
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        _audioRmsEnergy.value = 0f
                        com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.OFFLINE_SPOTTER)
                        if (expectedGeneration == generation.get() &&
                            isSpottingActive.get() &&
                            !isPausedForLiveMic.get() &&
                            !GeminiLiveAnnouncementAdapter.isInActiveCall.value &&
                            !com.example.service.JarvisWakeWordService.isRunning.value
                        ) {
                            mainHandler.postDelayed({
                                if (expectedGeneration == generation.get() &&
                                    isSpottingActive.get() &&
                                    !isPausedForLiveMic.get() &&
                                    !com.example.service.JarvisWakeWordService.isRunning.value
                                ) {
                                    _isListening.value = true
                                    startOfflineSpeechDetection(expectedGeneration)
                                }
                            }, 500)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.OFFLINE_SPOTTER)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.forEach { candidate ->
                            checkAndMatchCandidate(candidate, confidence = 0.95f)
                        }

                        if (expectedGeneration == generation.get() &&
                            isSpottingActive.get() &&
                            !isPausedForLiveMic.get() &&
                            !GeminiLiveAnnouncementAdapter.isInActiveCall.value &&
                            !com.example.service.JarvisWakeWordService.isRunning.value
                        ) {
                            mainHandler.postDelayed({
                                if (expectedGeneration == generation.get() &&
                                    isSpottingActive.get() &&
                                    !isPausedForLiveMic.get() &&
                                    !com.example.service.JarvisWakeWordService.isRunning.value
                                ) {
                                    _isListening.value = true
                                    startOfflineSpeechDetection(expectedGeneration)
                                }
                            }, 350)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        partials?.forEach { candidate ->
                            checkAndMatchCandidate(candidate, confidence = 0.85f)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                _isListening.value = true
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "SpeechRecognizer initialization failure: ${e.message}")
                _isListening.value = false
                com.example.audio.MicrophoneCoordinator.release(com.example.audio.MicOwner.OFFLINE_SPOTTER)
            }
        }
    }

    private fun checkAndMatchCandidate(candidate: String, confidence: Float) {
        val cooldown = com.example.audio.WakeWordModelManager.getCooldownMs(context)
        if (!com.example.audio.OfflineWakeWordEngine.canTriggerNow(cooldownMs = cooldown)) return

        val phrases = com.example.audio.WakeWordModelManager.getCustomWakePhrases(context)
        val match = com.example.audio.OfflineWakeWordEngine.matchWakePhrase(
            spokenText = candidate,
            configuredPhrases = phrases,
            sensitivity = com.example.service.JarvisWakeWordService.getSensitivity(context),
            isPartial = confidence < 0.90f
        )
        if (match.matched) {
            com.example.audio.OfflineWakeWordEngine.markTriggered()
            triggerWakeEvent(match.matchedPhrase, match.remainderCommand, match.confidence)
        }
    }

    private fun triggerWakeEvent(matchedPhrase: String, remainderCommand: String, confidence: Float) {
        Log.i(TAG, "Wake word detected: '$matchedPhrase' (Confidence: $confidence)")
        _lastDetectedKeyword.value = matchedPhrase
        _detectionConfidence.value = confidence

        provideHapticPulse()

        mainHandler.post {
            onWakeWordTriggered(matchedPhrase, remainderCommand.trim())
        }
    }

    private fun provideHapticPulse() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(140)
            }
        } catch (_: Exception) {
        }
    }
}
