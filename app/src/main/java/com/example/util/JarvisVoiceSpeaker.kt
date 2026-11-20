package com.example.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.service.AnnouncementDispatchOutcome
import com.example.service.GeminiLiveAnnouncementAdapter
import com.example.service.QueuedAnnouncement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

enum class OfflineTtsStatus {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    SPEAKING,
    UNAVAILABLE_NO_ENGINE,
    MUTED
}

/**
 * Dual-Path Voice Dispatcher for NAHID AI:
 * 1. Primary path: Gemini Live Audio Output via GeminiLiveAnnouncementAdapter when live session is connected.
 * 2. Offline fallback path: Native Android TextToSpeech (bn-BD -> bn -> en-US) when Gemini Live is disconnected/offline.
 * 3. Safety & Privacy: Always enforces OTP digit redaction via GeminiLiveAnnouncementAdapter.sanitizeForVoice,
 *    defers audio playback during active telephone calls, and respects user silentMode / speechRate settings.
 */
object JarvisVoiceSpeaker {

    private const val TAG = "JarvisVoiceSpeaker"

    private val _latestAnnouncement = MutableStateFlow("")
    val latestAnnouncement: StateFlow<String> = _latestAnnouncement.asStateFlow()

    private val _queuedAnnouncements = MutableStateFlow<List<String>>(emptyList())
    val queuedAnnouncements: StateFlow<List<String>> = _queuedAnnouncements.asStateFlow()

    private val _offlineTtsStatus = MutableStateFlow(OfflineTtsStatus.UNINITIALIZED)
    val offlineTtsStatus: StateFlow<OfflineTtsStatus> = _offlineTtsStatus.asStateFlow()

    private val _lastSpeakPath = MutableStateFlow("")
    val lastSpeakPath: StateFlow<String> = _lastSpeakPath.asStateFlow()

    private var liveAudioDispatcher: ((String) -> Unit)? = null
    private var ttsEngine: TextToSpeech? = null
    private var appContext: Context? = null

    @Volatile
    var offlineTtsEnabled: Boolean = true

    @Volatile
    var silentMode: Boolean = false

    @Volatile
    var speechRate: Float = 1.0f

    @Volatile
    var voicePitch: Float = 1.0f

    @Volatile
    var preferredLanguageCode: String = "bn"

    /**
     * Optional test hook allowing deterministic JVM/Robolectric verification of offline fallback
     * without requiring a bound native Android TTS engine service.
     */
    @Volatile
    var offlineSpeakerOverride: ((text: String, rate: Float) -> Boolean)? = null

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (ttsEngine != null || _offlineTtsStatus.value == OfflineTtsStatus.INITIALIZING) return

        _offlineTtsStatus.value = OfflineTtsStatus.INITIALIZING
        try {
            val holder = arrayOfNulls<TextToSpeech>(1)
            holder[0] = TextToSpeech(ctx) { status ->
                val engine = holder[0]
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    applyLocaleAndRate(engine)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _offlineTtsStatus.value = OfflineTtsStatus.SPEAKING
                        }

                        override fun onDone(utteranceId: String?) {
                            if (_offlineTtsStatus.value == OfflineTtsStatus.SPEAKING) {
                                _offlineTtsStatus.value = OfflineTtsStatus.READY
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _offlineTtsStatus.value = OfflineTtsStatus.READY
                        }
                    })
                    ttsEngine = engine
                    _offlineTtsStatus.value = if (silentMode) OfflineTtsStatus.MUTED else OfflineTtsStatus.READY
                    Log.i(TAG, "JarvisVoiceSpeaker initialized: Gemini Live + Android Offline TTS Ready")
                } else {
                    _offlineTtsStatus.value = OfflineTtsStatus.UNAVAILABLE_NO_ENGINE
                    Log.w(TAG, "Android TextToSpeech engine unavailable (status=$status)")
                }
            }
        } catch (e: Exception) {
            _offlineTtsStatus.value = OfflineTtsStatus.UNAVAILABLE_NO_ENGINE
            Log.w(TAG, "Offline TextToSpeech init skipped/failed: ${e.localizedMessage}")
        }
    }

    private fun applyLocaleAndRate(engine: TextToSpeech) {
        try {
            val candidates = if (preferredLanguageCode == "en") {
                listOf(Locale.US, Locale("bn", "BD"))
            } else {
                listOf(Locale("bn", "BD"), Locale("bn"), Locale.US)
            }
            for (loc in candidates) {
                val res = engine.setLanguage(loc)
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    break
                }
            }
            engine.setSpeechRate(speechRate.coerceIn(0.6f, 1.8f))
            engine.setPitch(voicePitch.coerceIn(0.7f, 1.4f))
        } catch (_: Exception) {
        }
    }

    fun configureVoiceSettings(
        offlineEnabled: Boolean = this.offlineTtsEnabled,
        speechRate: Float = this.speechRate,
        voicePitch: Float = this.voicePitch,
        silentMode: Boolean = this.silentMode,
        preferredLanguage: String = this.preferredLanguageCode
    ) {
        this.offlineTtsEnabled = offlineEnabled
        this.speechRate = speechRate.coerceIn(0.6f, 1.8f)
        this.voicePitch = voicePitch.coerceIn(0.7f, 1.4f)
        this.silentMode = silentMode
        this.preferredLanguageCode = preferredLanguage
        if (silentMode) {
            ttsEngine?.stop()
            if (_offlineTtsStatus.value == OfflineTtsStatus.READY || _offlineTtsStatus.value == OfflineTtsStatus.SPEAKING) {
                _offlineTtsStatus.value = OfflineTtsStatus.MUTED
            }
        } else if (_offlineTtsStatus.value == OfflineTtsStatus.MUTED) {
            _offlineTtsStatus.value = if (ttsEngine != null) OfflineTtsStatus.READY else OfflineTtsStatus.UNINITIALIZED
        }
        ttsEngine?.let { applyLocaleAndRate(it) }
    }

    /**
     * Registers a live audio session receiver (such as Gemini Live) and attaches it to GeminiLiveAnnouncementAdapter.
     */
    fun registerLiveAudioDispatcher(dispatcher: (String) -> Unit) {
        this.liveAudioDispatcher = dispatcher
        GeminiLiveAnnouncementAdapter.attachLiveSession(dispatcher)
    }

    fun unregisterLiveAudioDispatcher() {
        this.liveAudioDispatcher = null
        GeminiLiveAnnouncementAdapter.detachLiveSession()
    }

    /**
     * Updates the UI-visible announcement log without re-triggering live dispatch.
     */
    fun recordUiAnnouncement(text: String) {
        if (text.isBlank()) return
        _latestAnnouncement.value = text
        val currentList = _queuedAnnouncements.value.toMutableList()
        currentList.add(text)
        if (currentList.size > 20) {
            currentList.removeAt(0)
        }
        _queuedAnnouncements.value = currentList
    }

    /**
     * Speaks an announcement via Gemini Live when connected, or falls back to local Android TTS when offline.
     */
    fun speak(text: String, queueMode: Int = 0) {
        if (text.isBlank()) return
        val sanitized = GeminiLiveAnnouncementAdapter.sanitizeForVoice(text, isSensitiveOtp = false)
        if (sanitized.isBlank()) return

        if (silentMode) {
            recordUiAnnouncement(sanitized)
            _lastSpeakPath.value = "MUTED_SILENT_MODE"
            return
        }

        val eventKey = "speaker_${sanitized.hashCode()}_${System.currentTimeMillis() / 1000L}"
        val outcome = GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
            eventKey = eventKey,
            rawText = text,
            category = QueuedAnnouncement.CATEGORY_SYSTEM_ALERT,
            priority = if (queueMode > 0) QueuedAnnouncement.PRIORITY_HIGH else QueuedAnnouncement.PRIORITY_NORMAL
        )

        when (outcome) {
            AnnouncementDispatchOutcome.DISPATCHED_LIVE -> {
                _lastSpeakPath.value = "GEMINI_LIVE"
            }
            AnnouncementDispatchOutcome.DEFERRED_IN_CALL -> {
                _lastSpeakPath.value = "MUTED_OR_IN_CALL"
            }
            AnnouncementDispatchOutcome.QUEUED_OFFLINE -> {
                if (!offlineTtsEnabled) {
                    _lastSpeakPath.value = "OFFLINE_TTS_DISABLED"
                    return
                }
                val spokeOffline = dispatchOfflineSpeech(sanitized, queueMode)
                if (spokeOffline) {
                    GeminiLiveAnnouncementAdapter.removePendingByEventKey(eventKey)
                    _lastSpeakPath.value = "ANDROID_OFFLINE_TTS"
                } else {
                    _lastSpeakPath.value = "QUEUED_NO_ENGINE"
                }
            }
            AnnouncementDispatchOutcome.SKIPPED_DUPLICATE,
            AnnouncementDispatchOutcome.SKIPPED_BLANK -> Unit
        }
    }

    private fun dispatchOfflineSpeech(sanitizedText: String, queueMode: Int): Boolean {
        val override = offlineSpeakerOverride
        if (override != null) {
            return override.invoke(sanitizedText, speechRate)
        }

        val engine = ttsEngine ?: return false
        return try {
            applyLocaleAndRate(engine)
            val mode = if (queueMode > 0) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val res = engine.speak(sanitizedText, mode, params, "jarvis_tts_${UUID.randomUUID()}")
            res == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "Offline TextToSpeech speak error: ${e.localizedMessage}")
            false
        }
    }

    fun stop() {
        _latestAnnouncement.value = ""
        try {
            ttsEngine?.stop()
        } catch (_: Exception) {
        }
        if (_offlineTtsStatus.value == OfflineTtsStatus.SPEAKING) {
            _offlineTtsStatus.value = OfflineTtsStatus.READY
        }
    }

    fun clearQueue() {
        _queuedAnnouncements.value = emptyList()
        _latestAnnouncement.value = ""
        GeminiLiveAnnouncementAdapter.clearQueue()
        try {
            ttsEngine?.stop()
        } catch (_: Exception) {
        }
    }

    fun isSpeaking(): Boolean {
        val nativeSpeaking = try {
            ttsEngine?.isSpeaking == true
        } catch (_: Exception) {
            false
        }
        return _offlineTtsStatus.value == OfflineTtsStatus.SPEAKING || nativeSpeaking || _latestAnnouncement.value.isNotBlank()
    }

    fun shutdown() {
        clearQueue()
        unregisterLiveAudioDispatcher()
        try {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        } catch (_: Exception) {
        }
        ttsEngine = null
        _offlineTtsStatus.value = OfflineTtsStatus.UNINITIALIZED
    }
}
