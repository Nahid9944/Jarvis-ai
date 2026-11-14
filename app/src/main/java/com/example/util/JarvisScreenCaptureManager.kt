package com.example.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.ai.AiSettingsRepository
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenVisionState {
    STOPPED,
    ACTIVE,
    PAUSED
}

data class ScreenSharingTelemetry(
    val accessibilityUiAccess: Boolean = false,
    val accessibilityScreenshotAccess: Boolean = false,
    val mediaProjectionConsent: Boolean = false,
    val isProjectionRunning: Boolean = false,
    val visionState: ScreenVisionState = ScreenVisionState.STOPPED,
    val geminiVisionStatus: String = "NOT_CONFIGURED" // ONLINE, NOT_CONFIGURED, OFFLINE, ERROR
)

object JarvisScreenCaptureManager {

    private val _hasMediaProjectionConsent = MutableStateFlow(false)
    val hasMediaProjectionConsent: StateFlow<Boolean> = _hasMediaProjectionConsent.asStateFlow()

    private val _visionState = MutableStateFlow(ScreenVisionState.STOPPED)
    val visionState: StateFlow<ScreenVisionState> = _visionState.asStateFlow()

    private val _telemetry = MutableStateFlow(ScreenSharingTelemetry())
    val telemetry: StateFlow<ScreenSharingTelemetry> = _telemetry.asStateFlow()

    fun getScreenCaptureIntent(context: Context): Intent {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpManager.createScreenCaptureIntent()
    }

    fun onMediaProjectionResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            _hasMediaProjectionConsent.value = true
            _visionState.value = ScreenVisionState.ACTIVE

            val serviceIntent = Intent(context, JarvisScreenCaptureService::class.java).apply {
                action = JarvisScreenCaptureService.ACTION_START_CAPTURE
                putExtra(JarvisScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(JarvisScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            refreshTelemetry(context)
        } else {
            _hasMediaProjectionConsent.value = false
            _visionState.value = ScreenVisionState.STOPPED
            refreshTelemetry(context)
        }
    }

    fun pauseScreenVision(context: Context) {
        if (_visionState.value == ScreenVisionState.ACTIVE) {
            _visionState.value = ScreenVisionState.PAUSED
            val serviceIntent = Intent(context, JarvisScreenCaptureService::class.java).apply {
                action = JarvisScreenCaptureService.ACTION_PAUSE_CAPTURE
            }
            context.startService(serviceIntent)
            refreshTelemetry(context)
        }
    }

    fun resumeScreenVision(context: Context) {
        if (_visionState.value == ScreenVisionState.PAUSED && JarvisScreenCaptureService.isRunning()) {
            _visionState.value = ScreenVisionState.ACTIVE
            val serviceIntent = Intent(context, JarvisScreenCaptureService::class.java).apply {
                action = JarvisScreenCaptureService.ACTION_RESUME_CAPTURE
            }
            context.startService(serviceIntent)
            refreshTelemetry(context)
        }
    }

    fun stopScreenCaptureService(context: Context) {
        val serviceIntent = Intent(context, JarvisScreenCaptureService::class.java).apply {
            action = JarvisScreenCaptureService.ACTION_STOP_CAPTURE
        }
        context.stopService(serviceIntent)
        _hasMediaProjectionConsent.value = false
        _visionState.value = ScreenVisionState.STOPPED
        refreshTelemetry(context)
    }

    fun onProjectionStopped() {
        _hasMediaProjectionConsent.value = false
        _visionState.value = ScreenVisionState.STOPPED
    }

    fun refreshTelemetry(context: Context) {
        val hasA11y = JarvisAccessibilityService.isServiceActive.value
        val hasA11yScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && JarvisAccessibilityService.instance != null
        val projectionConsent = _hasMediaProjectionConsent.value
        val isServiceRunning = JarvisScreenCaptureService.isRunning()
        val currentVision = if (!isServiceRunning && _visionState.value != ScreenVisionState.PAUSED) {
            ScreenVisionState.STOPPED
        } else {
            _visionState.value
        }

        val geminiKey = try {
            AiSettingsRepository(context).settingsState.value.apiKey.trim()
        } catch (_: Exception) { "" }

        val geminiStatus = when {
            geminiKey.isBlank() || geminiKey == "MY_GEMINI_API_KEY" -> "NOT_CONFIGURED"
            else -> "ONLINE"
        }

        _telemetry.value = ScreenSharingTelemetry(
            accessibilityUiAccess = hasA11y,
            accessibilityScreenshotAccess = hasA11yScreenshot,
            mediaProjectionConsent = projectionConsent,
            isProjectionRunning = isServiceRunning,
            visionState = currentVision,
            geminiVisionStatus = geminiStatus
        )
    }

    fun captureScreen(context: Context, onResult: (Bitmap?) -> Unit) {
        if (_visionState.value == ScreenVisionState.PAUSED) {
            onResult(null)
            return
        }

        if (JarvisScreenCaptureService.isRunning()) {
            JarvisScreenCaptureService.captureCurrentScreen(onResult)
        } else {
            val accessibility = JarvisAccessibilityService.instance
            if (accessibility != null) {
                accessibility.captureCurrentScreenBitmap(onResult)
            } else {
                onResult(null)
            }
        }
    }
}
