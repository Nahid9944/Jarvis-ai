package com.example.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TurnState(val labelEnglish: String, val labelBengali: String) {
    IDLE("Idle", "প্রস্তুত"),
    LISTENING_TO_USER("Listening to User", "আমি শুনছি..."),
    USER_SPEAKING("User Speaking", "আপনি কথা বলছেন..."),
    ASSISTANT_THINKING("Assistant Thinking", "ভাবছি..."),
    ASSISTANT_SPEAKING("Assistant Speaking", "JARVIS IS SPEAKING"),
    USER_INTERRUPTED("User Interrupted", "USER INTERRUPTED"),
    TASK_IN_PROGRESS("Task In Progress", "TASK IN PROGRESS"),
    TASK_PAUSED("Task Paused", "TASK PAUSED"),
    TASK_CANCELLED("Task Cancelled", "TASK CANCELLED"),
    TASK_REPLACED("Task Replaced", "TASK REPLACED")
}

/**
 * Manages conversational turn-taking, continuous listening, full-duplex speech
 * and acoustic threshold detection for instant barge-in.
 */
object VoiceTurnManager {

    private val _turnState = MutableStateFlow(TurnState.IDLE)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()

    // Energy threshold to detect confident user speech above acoustic room baseline
    private const val BARGE_IN_RMS_THRESHOLD = 0.085f

    private var consecutiveHighChunks = 0

    fun onAssistantStartedSpeaking() {
        if (_turnState.value != TurnState.USER_INTERRUPTED) {
            _turnState.value = TurnState.ASSISTANT_SPEAKING
            consecutiveHighChunks = 0
        }
    }

    fun onAssistantFinishedSpeaking() {
        if (_turnState.value == TurnState.ASSISTANT_SPEAKING) {
            _turnState.value = TurnState.LISTENING_TO_USER
        }
        consecutiveHighChunks = 0
    }

    /**
     * Inspects microphone audio energy during assistant playback.
     * When user speech is detected above threshold, instantly initiates full-duplex barge-in.
     */
    fun processMicrophoneChunk(micLevel: Float): Boolean {
        if (_turnState.value == TurnState.ASSISTANT_SPEAKING) {
            if (micLevel >= BARGE_IN_RMS_THRESHOLD) {
                consecutiveHighChunks++
                if (consecutiveHighChunks >= 2) { // ~200ms of sustained speech confirms user intention
                    _turnState.value = TurnState.USER_INTERRUPTED
                    PlaybackInterruptionBridge.triggerInstantBargeIn("user_voice_energy_threshold")
                    consecutiveHighChunks = 0
                    return true
                }
            } else {
                consecutiveHighChunks = 0
            }
        } else if (_turnState.value == TurnState.LISTENING_TO_USER && micLevel >= 0.04f) {
            _turnState.value = TurnState.USER_SPEAKING
        }
        return false
    }

    fun setTurnState(newState: TurnState) {
        _turnState.value = newState
    }
}
