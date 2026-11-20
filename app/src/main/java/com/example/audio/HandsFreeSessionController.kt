package com.example.audio

import android.content.Context
import android.util.Log
import com.example.ai.LiveSessionState
import com.example.service.JarvisWakeWordService
import com.example.util.JarvisEmergencyStopCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Truthful assistant HUD & voice pipeline state displayed in UI and telemetry.
 */
enum class AssistantVoiceHudState(val labelBn: String) {
    IDLE("প্রস্তুত (Idle)"),
    WAKE_WORD_ARMED("ওয়েক-ওয়ার্ড সক্রিয় (Armed)"),
    LISTENING("শুনছি (Listening)"),
    THINKING("চিন্তা ও পরিকল্পনা (Thinking)"),
    TOOL_EXECUTING("টুল কার্যকর হচ্ছে (Executing)"),
    VERIFYING("ফলাফল যাচাই হচ্ছে (Verifying)"),
    SPEAKING("উত্তর দিচ্ছি (Speaking)"),
    WAITING_FOR_PERMISSION("পারমিশন অপেক্ষমাণ"),
    WAITING_FOR_CONFIRMATION("সম্মতি অপেক্ষমাণ"),
    PAUSED("সাময়িক বিরতি (Paused)"),
    OFFLINE("অফলাইন (Offline)"),
    ERROR("ত্রুটি (Error)")
}

/**
 * Coordinates seamless hands-free transitions:
 * WakeWord detected -> MicrophoneCoordinator preempt to GEMINI_LIVE_STREAM ->
 * Gemini Live session active -> on turn/session complete, release mic & resume WakeWord if armed.
 */
object HandsFreeSessionController {

    private const val TAG = "HandsFreeController"

    private val _hudState = MutableStateFlow(AssistantVoiceHudState.IDLE)
    val hudState: StateFlow<AssistantVoiceHudState> = _hudState.asStateFlow()

    private val _lastWakeTriggerAtMs = MutableStateFlow(0L)
    val lastWakeTriggerAtMs: StateFlow<Long> = _lastWakeTriggerAtMs.asStateFlow()

    private val _pendingInlineCommand = MutableStateFlow("")
    val pendingInlineCommand: StateFlow<String> = _pendingInlineCommand.asStateFlow()

    fun updateHudState(state: AssistantVoiceHudState) {
        _hudState.value = state
    }

    fun syncFromLiveSession(
        liveState: LiveSessionState,
        isWakeWordRunning: Boolean,
        isPaused: Boolean,
        hasPermissionPending: Boolean,
        hasApprovalPending: Boolean,
        isOnline: Boolean
    ) {
        val next = when {
            hasPermissionPending -> AssistantVoiceHudState.WAITING_FOR_PERMISSION
            hasApprovalPending -> AssistantVoiceHudState.WAITING_FOR_CONFIRMATION
            isPaused -> AssistantVoiceHudState.PAUSED
            liveState == LiveSessionState.TOOL_EXECUTING -> AssistantVoiceHudState.TOOL_EXECUTING
            liveState == LiveSessionState.THINKING -> AssistantVoiceHudState.THINKING
            liveState == LiveSessionState.LISTENING -> AssistantVoiceHudState.LISTENING
            liveState == LiveSessionState.SPEAKING -> AssistantVoiceHudState.SPEAKING
            liveState == LiveSessionState.ERROR -> AssistantVoiceHudState.ERROR
            !isOnline && liveState == LiveSessionState.DISCONNECTED -> AssistantVoiceHudState.OFFLINE
            liveState == LiveSessionState.CONNECTED || liveState == LiveSessionState.CONNECTING -> AssistantVoiceHudState.LISTENING
            isWakeWordRunning -> AssistantVoiceHudState.WAKE_WORD_ARMED
            else -> AssistantVoiceHudState.IDLE
        }
        _hudState.value = next
    }

    private var lastTriggerPhrase: String = ""
    private var lastTriggerRemainder: String = ""
    private var pauseSpotterHook: (() -> Unit)? = null
    private var resumeSpotterHook: (() -> Unit)? = null

    fun registerSpotterHooks(
        onPauseSpotter: (() -> Unit)?,
        onResumeSpotter: (() -> Unit)?
    ) {
        pauseSpotterHook = onPauseSpotter
        resumeSpotterHook = onResumeSpotter
    }

    fun onWakeWordDetected(
        context: Context,
        matchedPhrase: String,
        remainderCommand: String,
        onActivateLiveSession: (initialCommand: String) -> Unit
    ): Boolean {
        if (JarvisEmergencyStopCoordinator.isEmergencyStopActive.value) {
            Log.w(TAG, "Wake trigger ignored: Emergency Stop is active.")
            return false
        }

        val now = System.currentTimeMillis()
        val cleanRemainder = remainderCommand.trim()
        if ((now - _lastWakeTriggerAtMs.value) in 0..1200 &&
            lastTriggerPhrase == matchedPhrase &&
            lastTriggerRemainder == cleanRemainder
        ) {
            Log.d(TAG, "Duplicate wake trigger suppressed within 1.2s window.")
            return false
        }

        // Preempt microphone cleanly for Gemini Live stream
        MicrophoneCoordinator.release(MicOwner.WAKE_WORD_SERVICE)
        MicrophoneCoordinator.release(MicOwner.OFFLINE_SPOTTER)
        try {
            pauseSpotterHook?.invoke()
        } catch (_: Throwable) {
        }
        val acquired = MicrophoneCoordinator.tryAcquire(MicOwner.GEMINI_LIVE_STREAM, preempt = true)
        if (!acquired) {
            Log.w(TAG, "Failed to preempt microphone for Live session.")
            return false
        }

        _lastWakeTriggerAtMs.value = now
        lastTriggerPhrase = matchedPhrase
        lastTriggerRemainder = cleanRemainder
        _pendingInlineCommand.value = cleanRemainder
        _hudState.value = AssistantVoiceHudState.LISTENING

        try {
            if (JarvisWakeWordService.isRunning.value) {
                JarvisWakeWordService.pause(context)
            }
        } catch (_: Throwable) {
        }

        onActivateLiveSession(remainderCommand.trim())
        return true
    }

    fun onLiveSessionEnded(context: Context) {
        MicrophoneCoordinator.release(MicOwner.GEMINI_LIVE_STREAM)
        _pendingInlineCommand.value = ""

        val optIn = try {
            WakeWordModelManager.isOptInEnabled(context) &&
                !JarvisEmergencyStopCoordinator.isEmergencyStopActive.value
        } catch (_: Throwable) {
            false
        }
        val serviceRunning = try {
            JarvisWakeWordService.isRunning.value
        } catch (_: Throwable) {
            false
        }

        if (optIn && serviceRunning) {
            _hudState.value = AssistantVoiceHudState.WAKE_WORD_ARMED
            try {
                JarvisWakeWordService.resume(context)
            } catch (_: Throwable) {
            }
        } else if (optIn && resumeSpotterHook != null) {
            _hudState.value = AssistantVoiceHudState.WAKE_WORD_ARMED
            try {
                resumeSpotterHook?.invoke()
            } catch (_: Throwable) {
            }
        } else {
            _hudState.value = AssistantVoiceHudState.IDLE
        }
    }

    fun consumePendingInlineCommand(): String {
        val cmd = _pendingInlineCommand.value
        _pendingInlineCommand.value = ""
        return cmd
    }
}
