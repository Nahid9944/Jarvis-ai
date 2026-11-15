package com.example.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicOwner(val priority: Int) {
    NONE(0),
    WAKE_WORD_SERVICE(1),
    OFFLINE_SPOTTER(2),
    GEMINI_LIVE_STREAM(3)
}

/**
 * Centralized Microphone Ownership Coordinator for NAHID AI.
 * Prevents AudioRecord (Gemini Live) and SpeechRecognizer (Wake-Word / Offline Spotter)
 * from colliding over the hardware microphone concurrently.
 */
object MicrophoneCoordinator {
    private const val TAG = "MicrophoneCoordinator"
    private val lock = Any()

    private val _currentOwner = MutableStateFlow(MicOwner.NONE)
    val currentOwner: StateFlow<MicOwner> = _currentOwner.asStateFlow()

    fun tryAcquire(owner: MicOwner, preempt: Boolean = false): Boolean {
        if (owner == MicOwner.NONE) return false
        synchronized(lock) {
            val existing = _currentOwner.value
            if (existing == MicOwner.NONE || existing == owner) {
                _currentOwner.value = owner
                return true
            }
            if (preempt && owner.priority > existing.priority) {
                Log.d(TAG, "Preempting microphone from $existing to $owner")
                _currentOwner.value = owner
                return true
            }
            Log.d(TAG, "Microphone acquire denied for $owner; currently owned by $existing")
            return false
        }
    }

    fun release(owner: MicOwner) {
        synchronized(lock) {
            if (_currentOwner.value == owner) {
                _currentOwner.value = MicOwner.NONE
            }
        }
    }

    fun forceReleaseAll() {
        synchronized(lock) {
            _currentOwner.value = MicOwner.NONE
        }
    }
}
