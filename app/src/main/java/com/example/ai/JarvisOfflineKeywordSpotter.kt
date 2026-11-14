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
    private val onWakeWordTriggered: (keyword: String) -> Unit
) {

    private val isSpottingActive = AtomicBoolean(false)
    private val isPausedForLiveMic = AtomicBoolean(false)
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

        if (isPausedForLiveMic.get() || GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
            Log.d(TAG, "Deferring wake-word start because live mic or phone call holds audio focus.")
            isSpottingActive.set(true)
            return
        }

        if (isSpottingActive.compareAndSet(false, true)) {
            _isListening.value = true
            Log.i(TAG, "J.A.R.V.I.S. Offline Keyword Spotter started.")
            startOfflineSpeechDetection()
        } else if (!_isListening.value) {
            _isListening.value = true
            startOfflineSpeechDetection()
        }
    }

    /**
     * Yields hardware microphone when Gemini Live AudioInputStreamer or an active phone call takes over.
     */
    fun pauseForLiveMic(reason: String = "live_session") {
        isPausedForLiveMic.set(true)
        _isListening.value = false
        _audioRmsEnergy.value = 0f
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizerOnMain()
        }
        Log.d(TAG, "Paused offline keyword spotter ($reason) to prevent microphone contention.")
    }

    /**
     * Resumes wake-word spotting only if user wants spotting active and no active call holds the mic.
     */
    fun resumeAfterLiveMic(reason: String = "live_session_ended") {
        if (!isPausedForLiveMic.compareAndSet(true, false)) return
        if (isSpottingActive.get() && !GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
            _isListening.value = true
            mainHandler.postDelayed({
                if (isSpottingActive.get() && !isPausedForLiveMic.get()) {
                    startOfflineSpeechDetection()
                }
            }, 350)
            Log.d(TAG, "Resumed offline keyword spotter ($reason).")
        }
    }

    fun stopListening() {
        isSpottingActive.set(false)
        isPausedForLiveMic.set(false)
        _isListening.value = false
        _audioRmsEnergy.value = 0f
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
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

    private fun startOfflineSpeechDetection() {
        mainHandler.post {
            if (!isSpottingActive.get() || isPausedForLiveMic.get() || GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
                _isListening.value = false
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "SpeechRecognizer unavailable on this device.")
                _isListening.value = false
                return@post
            }

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        _audioRmsEnergy.value = ((rmsdB + 2f) * 8f).coerceIn(0f, 100f)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        if (isSpottingActive.get() && !isPausedForLiveMic.get() && !GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
                            mainHandler.postDelayed({
                                if (isSpottingActive.get() && !isPausedForLiveMic.get()) {
                                    startOfflineSpeechDetection()
                                }
                            }, 500)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.forEach { candidate ->
                            checkAndMatchCandidate(candidate, confidence = 0.95f)
                        }

                        if (isSpottingActive.get() && !isPausedForLiveMic.get() && !GeminiLiveAnnouncementAdapter.isInActiveCall.value) {
                            mainHandler.postDelayed({
                                if (isSpottingActive.get() && !isPausedForLiveMic.get()) startOfflineSpeechDetection()
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

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "SpeechRecognizer initialization failure: ${e.message}")
            }
        }
    }

    private fun checkAndMatchCandidate(candidate: String, confidence: Float) {
        val lower = candidate.lowercase(Locale.ROOT).trim()
        val matchedKeyword = KEYWORD_PATTERNS.find { pattern -> lower.contains(pattern) }
        if (matchedKeyword != null) {
            triggerWakeEvent(matchedKeyword, confidence)
        }
    }

    private fun triggerWakeEvent(keyword: String, confidence: Float) {
        Log.i(TAG, "Wake word detected: '$keyword' (Confidence: $confidence)")
        _lastDetectedKeyword.value = keyword
        _detectionConfidence.value = confidence

        provideHapticPulse()

        mainHandler.post {
            onWakeWordTriggered(keyword)
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
