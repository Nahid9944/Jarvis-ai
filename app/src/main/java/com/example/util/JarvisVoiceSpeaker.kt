package com.example.util

import android.content.Context
import android.util.Log
import com.example.service.GeminiLiveAnnouncementAdapter
import com.example.service.QueuedAnnouncement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Announcement Coordinator & Voice Dispatcher for NAHID JARVIS AI.
 * Strictly adheres to the project mandate: Zero Android TextToSpeech dependencies.
 * Spoken voice is exclusively driven by Gemini Live Audio Output via GeminiLiveAnnouncementAdapter.
 * When Gemini Live is disconnected or busy in a phone call, announcements are queued and shown in UI.
 */
object JarvisVoiceSpeaker {

    private const val TAG = "JarvisVoiceSpeaker"

    private val _latestAnnouncement = MutableStateFlow("")
    val latestAnnouncement: StateFlow<String> = _latestAnnouncement.asStateFlow()

    private val _queuedAnnouncements = MutableStateFlow<List<String>>(emptyList())
    val queuedAnnouncements: StateFlow<List<String>> = _queuedAnnouncements.asStateFlow()

    private var liveAudioDispatcher: ((String) -> Unit)? = null

    fun init(context: Context) {
        Log.i(TAG, "JarvisVoiceSpeaker initialized (Gemini Live Audio Queue Mode, Zero Android TTS)")
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
     * Speaks or queues an announcement through GeminiLiveAnnouncementAdapter.
     * Never invokes Android TextToSpeech.
     */
    fun speak(text: String, queueMode: Int = 0) {
        if (text.isBlank()) return
        Log.i(TAG, "Announcement requested: $text")
        GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
            eventKey = "speaker_${text.hashCode()}",
            rawText = text,
            category = QueuedAnnouncement.CATEGORY_SYSTEM_ALERT,
            priority = if (queueMode > 0) QueuedAnnouncement.PRIORITY_HIGH else QueuedAnnouncement.PRIORITY_NORMAL
        )
    }

    fun stop() {
        _latestAnnouncement.value = ""
    }

    fun clearQueue() {
        _queuedAnnouncements.value = emptyList()
        _latestAnnouncement.value = ""
        GeminiLiveAnnouncementAdapter.clearQueue()
    }

    fun isSpeaking(): Boolean {
        return _latestAnnouncement.value.isNotBlank()
    }

    fun shutdown() {
        clearQueue()
        unregisterLiveAudioDispatcher()
    }
}
