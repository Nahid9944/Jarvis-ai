package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ai.ActiveTaskInterruptionManager
import com.example.ai.JarvisCameraVisionCoordinator
import com.example.ai.PlaybackInterruptionBridge
import com.example.automation.JarvisAutonomousLoopOrchestrator
import com.example.automation.JarvisSkillRecorder
import com.example.service.GeminiLiveAnnouncementAdapter
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisWakeWordService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class EmergencyStopReport(
    val success: Boolean,
    val screenCaptureStopped: Boolean,
    val wakeWordStopped: Boolean,
    val autonomousLoopHalted: Boolean,
    val cameraVisionReset: Boolean,
    val skillRecorderHalted: Boolean,
    val livePlaybackInterrupted: Boolean = false,
    val announcementsCleared: Boolean = false,
    val activeTaskCancelled: Boolean = false,
    val offlineSpotterHalted: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val summaryBengali: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("screenCaptureStopped", screenCaptureStopped)
            put("wakeWordStopped", wakeWordStopped)
            put("autonomousLoopHalted", autonomousLoopHalted)
            put("cameraVisionReset", cameraVisionReset)
            put("skillRecorderHalted", skillRecorderHalted)
            put("livePlaybackInterrupted", livePlaybackInterrupted)
            put("announcementsCleared", announcementsCleared)
            put("activeTaskCancelled", activeTaskCancelled)
            put("offlineSpotterHalted", offlineSpotterHalted)
            put("timestampMs", timestampMs)
            put("summaryBengali", summaryBengali)
        }
    }
}

object JarvisEmergencyStopCoordinator {
    private const val TAG = "JarvisEmergencyStop"

    private val _isEmergencyStopActive = MutableStateFlow(false)
    val isEmergencyStopActive: StateFlow<Boolean> = _isEmergencyStopActive.asStateFlow()

    private val _lastReport = MutableStateFlow<EmergencyStopReport?>(null)
    val lastReport: StateFlow<EmergencyStopReport?> = _lastReport.asStateFlow()

    @Volatile
    private var stopLiveSessionHook: (() -> Unit)? = null

    @Volatile
    private var stopOfflineSpotterHook: (() -> Unit)? = null

    fun registerRuntimeHooks(
        onStopLiveSession: () -> Unit,
        onStopOfflineSpotter: () -> Unit
    ) {
        stopLiveSessionHook = onStopLiveSession
        stopOfflineSpotterHook = onStopOfflineSpotter
    }

    fun resetEmergencyStop() {
        _isEmergencyStopActive.value = false
    }

    fun triggerEmergencyStop(context: Context?): EmergencyStopReport {
        Log.w(TAG, "EMERGENCY STOP TRIGGERED: Halting all active AI, voice, vision, and automation subsystems.")
        _isEmergencyStopActive.value = true

        var screenCapOk = false
        var wakeWordOk = false
        var loopHalted = false
        var cameraReset = false
        var skillHalted = false
        var liveInterrupted = false
        var announcementsCleared = false
        var activeTaskCancelled = false
        var spotterHalted = false

        // 1. Interrupt active task & execution commit controller
        try {
            ActiveTaskInterruptionManager.handleInterruptionCommand("emergency stop")
            activeTaskCancelled = true
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling active tracked task", e)
        }

        // 2. Halt Autonomous Loop Orchestrator
        try {
            JarvisAutonomousLoopOrchestrator.cancelAllTasks()
            loopHalted = true
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling autonomous loop", e)
        }

        // 3. Interrupt Gemini Live playback / runtime session hook
        try {
            PlaybackInterruptionBridge.triggerInstantBargeIn("emergency_stop")
            stopLiveSessionHook?.invoke()
            liveInterrupted = true
        } catch (e: Exception) {
            Log.e(TAG, "Error interrupting live session playback", e)
        }

        // 4. Clear pending announcements and speaker queue
        try {
            GeminiLiveAnnouncementAdapter.clearQueue()
            JarvisVoiceSpeaker.clearQueue()
            announcementsCleared = true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing announcement queues", e)
        }

        // 5. Stop Screen Capture Service & MediaProjection
        if (context != null) {
            try {
                JarvisScreenCaptureManager.stopScreenCaptureService(context)
                screenCapOk = true
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping screen capture", e)
            }
        }

        // 6. Stop Wake Word Listening Service & Offline Keyword Spotter
        if (context != null) {
            try {
                val stopIntent = Intent(context, JarvisWakeWordService::class.java).apply {
                    action = JarvisWakeWordService.ACTION_STOP
                }
                context.startService(stopIntent)
                wakeWordOk = true
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping wake word service", e)
            }
        }
        try {
            stopOfflineSpotterHook?.invoke()
            spotterHalted = true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping offline keyword spotter", e)
        }

        // 7. Stop Continuous CameraX Vision Stream & Hardware Controls
        try {
            JarvisCameraVisionCoordinator.stopVisionStream()
            JarvisCameraManager.setZoom(1.0f)
            JarvisCameraManager.activeCameraControl?.enableTorch(false)
            JarvisCameraManager.activeCameraControl = null
            JarvisCameraManager.activeImageCapture = null
            cameraReset = true
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting camera manager & vision stream", e)
        }

        // 8. Discard active Skill Recorder & detach Accessibility event listener
        try {
            JarvisSkillRecorder.getInstance().discardRecording(null)
            JarvisAccessibilityService.eventListener = null
            skillHalted = true
        } catch (e: Exception) {
            Log.e(TAG, "Error halting skill recorder / accessibility listener", e)
        }

        // 9. Pause HOME DEVELOPER work cleanly at checkpoint
        try {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.example.developer.HomeDeveloperOrchestrator.pauseOrStopCleanly(
                    reason = "EMERGENCY_STOP",
                    context = context
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting Home Developer pause", e)
        }

        val summary = "জরুরি বিরতি সক্রিয়: চলমান টাস্ক, স্বায়ত্তশাসিত অ্যাকশন লুপ, লাইভ অডিও/বক্তব্য, অ্যানাউন্সমেন্ট কিউ, স্ক্রিন ক্যাপচার, ওয়েক-ওয়ার্ড ও ক্যামেরা ভিশন অবিলম্বে বন্ধ করা হয়েছে।"

        val report = EmergencyStopReport(
            success = true,
            screenCaptureStopped = screenCapOk,
            wakeWordStopped = wakeWordOk,
            autonomousLoopHalted = loopHalted,
            cameraVisionReset = cameraReset,
            skillRecorderHalted = skillHalted,
            livePlaybackInterrupted = liveInterrupted,
            announcementsCleared = announcementsCleared,
            activeTaskCancelled = activeTaskCancelled,
            offlineSpotterHalted = spotterHalted,
            summaryBengali = summary
        )
        _lastReport.value = report
        return report
    }
}
