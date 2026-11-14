package com.example.ai

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Coordinates conversational speech interruptions and active task cancellations.
 */
object LiveInterruptionCoordinator {

    private val _interruptionEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val interruptionEvents: SharedFlow<String> = _interruptionEvents.asSharedFlow()

    fun onBargeInTriggered(reason: String) {
        _interruptionEvents.tryEmit(reason)
        VoiceTurnManager.setTurnState(TurnState.USER_INTERRUPTED)

        // Check if an active autonomous task or tool is executing and notify the task interruption manager
        ActiveTaskInterruptionManager.onSpeechInterrupted(reason)
    }

    /**
     * Handles incoming natural language during active tasks
     */
    fun handleInterruptionUtterance(utterance: String): InterruptionHandlingResult {
        return ActiveTaskInterruptionManager.handleInterruptionCommand(utterance)
    }
}
