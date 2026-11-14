package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.util.JarvisCameraManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

data class VisualObservationState(
    val isStreaming: Boolean = false,
    val isPaused: Boolean = false,
    val samplingIntervalMs: Long = 3000L,
    val detectedExpressionBn: String = "অনির্ধারিত",
    val detectedEmotion: UserEmotion = UserEmotion.UNKNOWN,
    val detectedGestureBn: String = "কোনো ইশারা শনাক্ত হয়নি",
    val gestureCode: String = "NONE",
    val confidence: Float = 0f,
    val privacyIndicatorActive: Boolean = false,
    val statusNoteBn: String = "ক্যামেরা প্রস্তুত"
)

/**
 * Coordinates real-time camera vision with CameraX, truthful face/expression & gesture understanding,
 * privacy status indicators, and safe streaming to Gemini Live Multimodal WebSocket.
 */
object JarvisCameraVisionCoordinator {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamingJob: Job? = null

    private val _isCameraStreaming = MutableStateFlow(false)
    val isCameraStreaming: StateFlow<Boolean> = _isCameraStreaming.asStateFlow()

    private val _lastVisualSummaryBn = MutableStateFlow("ক্যামেরা প্রস্তুত")
    val lastVisualSummaryBn: StateFlow<String> = _lastVisualSummaryBn.asStateFlow()

    private val _observationState = MutableStateFlow(VisualObservationState())
    val observationState: StateFlow<VisualObservationState> = _observationState.asStateFlow()

    private var sessionManagerRef: GeminiLiveSessionManager? = null

    fun registerSessionManager(manager: GeminiLiveSessionManager) {
        sessionManagerRef = manager
    }

    fun setSamplingInterval(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(1000L, 10000L)
        _observationState.value = _observationState.value.copy(samplingIntervalMs = clamped)
    }

    fun setPaused(paused: Boolean) {
        _observationState.value = _observationState.value.copy(
            isPaused = paused,
            privacyIndicatorActive = !paused && _isCameraStreaming.value,
            statusNoteBn = if (paused) "ক্যামেরা ভিশন সাময়িক বিরতিতে" else _observationState.value.statusNoteBn
        )
    }

    /**
     * Captures a single camera frame, streams it if Live is connected,
     * and optionally runs truthful Gemini expression/gesture observation (zero fake face/emotion guesses).
     */
    suspend fun captureAndStreamVisualContext(
        context: Context,
        apiKey: String = ""
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!JarvisCameraManager.hasCameraPermission(context)) {
            _observationState.value = _observationState.value.copy(
                privacyIndicatorActive = false,
                statusNoteBn = "ক্যামেরা পারমিশন (CAMERA) অনুমোদিত নয়"
            )
            return@withContext Pair(false, "ক্যামেরা পারমিশন (CAMERA) প্রয়োজন।")
        }

        val bitmap = JarvisCameraManager.captureFrame(context) ?: JarvisCameraManager.lastCapturedBitmap
        if (bitmap == null) {
            return@withContext Pair(false, "ক্যামেরা ফ্রেম গ্রহণ করা সম্ভব হয়নি। ক্যামেরা প্রিভিউ চালু আছে কি না পরীক্ষা করুন।")
        }

        // Convert to Base64 JPEG and stream only while live session exists
        val base64Jpeg = bitmapToBase64Jpeg(bitmap)
        sessionManagerRef?.sendImageFrame(base64Jpeg)

        val resolvedKey = apiKey.ifBlank {
            try {
                AiSettingsRepository(context).settingsState.value.apiKey
            } catch (_: Exception) {
                ""
            }
        }

        if (resolvedKey.isNotBlank() && resolvedKey != "MY_GEMINI_API_KEY") {
            val expr = ScreenVisualAnalyzer.analyzeExpressionAndGesture(bitmap, resolvedKey)
            if (expr.available) {
                val mappedEmotion = mapExpressionToEmotion(expr.expressionLabel)
                _observationState.value = _observationState.value.copy(
                    detectedExpressionBn = expr.expressionBn,
                    detectedEmotion = mappedEmotion,
                    detectedGestureBn = expr.gestureBn,
                    gestureCode = expr.gestureLabel,
                    confidence = expr.confidence,
                    statusNoteBn = expr.summaryBn
                )
                _lastVisualSummaryBn.value = expr.summaryBn

                if (expr.faceDetected && mappedEmotion != UserEmotion.UNKNOWN) {
                    EmotionContextEngine.updateMultimodalEmotion(
                        textEval = null,
                        faceDetected = true,
                        visualEmotion = mappedEmotion,
                        visualConfidence = expr.confidence
                    )
                }

                LiveConversationContextBridge.enqueueContextUpdate(
                    dedupKey = "vision_${expr.expressionLabel}_${expr.gestureLabel}",
                    content = "ভিজ্যুয়াল পর্যবেক্ষণ: মুখভঙ্গি=${expr.expressionBn}, ইশারা=${expr.gestureBn} (${expr.summaryBn})"
                )

                // Safety Rule: gestures alone NEVER trigger sensitive external actions silently.
                if (expr.gestureLabel == "OPEN_PALM_STOP") {
                    ActiveTaskInterruptionManager.handleInterruptionCommand("একটু অপেক্ষা করো, পজ করো")
                }

                return@withContext Pair(true, expr.summaryBn)
            }
        }

        val streamNote = "ক্যামেরা ফ্রেম Gemini Live সেশনে পাঠানো হয়েছে (অফলাইন/কী ছাড়া আলাদা মুখভঙ্গি অনুমান করা হয়নি)।"
        _lastVisualSummaryBn.value = streamNote
        _observationState.value = _observationState.value.copy(statusNoteBn = streamNote)
        Pair(true, streamNote)
    }

    fun startContinuousVisionStream(context: Context, intervalMs: Long = _observationState.value.samplingIntervalMs) {
        if (streamingJob?.isActive == true) return
        val effectiveInterval = intervalMs.coerceIn(1000L, 10000L)
        _isCameraStreaming.value = true
        _observationState.value = _observationState.value.copy(
            isStreaming = true,
            isPaused = false,
            samplingIntervalMs = effectiveInterval,
            privacyIndicatorActive = true,
            statusNoteBn = "লাইভ ভিশন সক্রিয় (${effectiveInterval}ms ইন্টারভাল)"
        )

        streamingJob = scope.launch {
            while (isActive && _isCameraStreaming.value) {
                try {
                    if (!_observationState.value.isPaused) {
                        captureAndStreamVisualContext(context)
                    }
                } catch (_: Exception) {
                }
                delay(_observationState.value.samplingIntervalMs)
            }
        }
    }

    fun stopVisionStream() {
        _isCameraStreaming.value = false
        streamingJob?.cancel()
        streamingJob = null
        _observationState.value = _observationState.value.copy(
            isStreaming = false,
            privacyIndicatorActive = false,
            statusNoteBn = "ক্যামেরা ভিশন বন্ধ করা হয়েছে"
        )
    }

    fun resetForTest() {
        stopVisionStream()
        _lastVisualSummaryBn.value = "ক্যামেরা প্রস্তুত"
        _observationState.value = VisualObservationState()
    }

    private fun mapExpressionToEmotion(label: String): UserEmotion {
        return when (label.uppercase()) {
            "HAPPY", "SMILING" -> UserEmotion.HAPPY
            "SAD" -> UserEmotion.SAD
            "TIRED", "FATIGUED" -> UserEmotion.TIRED
            "STRESSED", "ANXIOUS", "WORRIED" -> UserEmotion.STRESSED
            "ANGRY", "FRUSTRATED" -> UserEmotion.ANGRY
            "CONFUSED" -> UserEmotion.CONFUSED
            "EXCITED", "SURPRISED" -> UserEmotion.EXCITED
            "CALM", "RELAXED" -> UserEmotion.CALM
            "NEUTRAL" -> UserEmotion.NEUTRAL
            else -> UserEmotion.UNKNOWN
        }
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
