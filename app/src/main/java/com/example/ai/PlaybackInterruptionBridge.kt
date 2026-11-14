package com.example.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-speed atomic bridge for instant audio playback interruption and barge-in.
 * Ensures zero lag when clearing the AudioTrack queue upon server interruption
 * or direct user voice barge-in.
 */
object PlaybackInterruptionBridge {

    private var sessionManagerRef: GeminiLiveSessionManager? = null
    private val isInterruptedFlag = AtomicBoolean(false)

    private val _lastInterruptionReason = MutableStateFlow<String?>(null)
    val lastInterruptionReason: StateFlow<String?> = _lastInterruptionReason.asStateFlow()

    private val _isUserBargeInActive = MutableStateFlow(false)
    val isUserBargeInActive: StateFlow<Boolean> = _isUserBargeInActive.asStateFlow()

    fun registerSessionManager(manager: GeminiLiveSessionManager) {
        sessionManagerRef = manager
    }

    fun unregisterSessionManager() {
        sessionManagerRef = null
    }

    /**
     * Instantly halts audio output playback, flushes audio buffers, and transitions
     * session state back to LISTENING without breaking WebSocket or session lifecycle.
     */
    fun triggerInstantBargeIn(reason: String = "user_voice_barge_in"): Boolean {
        isInterruptedFlag.set(true)
        _lastInterruptionReason.value = reason
        _isUserBargeInActive.value = true

        val manager = sessionManagerRef
        if (manager != null) {
            manager.interruptPlayback()
            LiveInterruptionCoordinator.onBargeInTriggered(reason)
            return true
        }
        return false
    }

    /**
     * Called when Gemini Live server sends serverContent.interrupted = true
     */
    fun onServerInterrupted() {
        isInterruptedFlag.set(true)
        _lastInterruptionReason.value = "server_interrupted"
        _isUserBargeInActive.value = true
        LiveInterruptionCoordinator.onBargeInTriggered("server_interrupted")
    }

    fun clearBargeInFlag() {
        isInterruptedFlag.set(false)
        _isUserBargeInActive.value = false
    }
}
