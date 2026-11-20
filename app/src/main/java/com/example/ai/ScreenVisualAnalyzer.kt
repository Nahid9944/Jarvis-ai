package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.util.JarvisScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class ScreenActionSuggestion(
    val actionType: String, // "click", "type", "scroll", "open_app", "system"
    val title: String,
    val description: String,
    val targetTextOrId: String? = null,
    val viewId: String? = null,
    val elementName: String? = null,
    val elementType: String = "Button",
    val clickable: Boolean = true,
    val inputPayload: String? = null,
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val centerNormX: Float? = null,
    val centerNormY: Float? = null,
    val confidence: Float = 0.7f,
    val screenshotTimestamp: Long = System.currentTimeMillis(),
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0
)

data class VisionActionExecutionOutcome(
    val success: Boolean,
    val status: String,
    val verified: Boolean,
    val strategyUsed: String,
    val screenChanged: Boolean,
    val summaryBengali: String
)

data class ScreenVisualAnalysis(
    val summaryBengali: String,
    val detectedApp: String,
    val screenDescription: String,
    val suggestions: List<ScreenActionSuggestion>,
    val rawAiResponse: String,
    val timestamp: Long = System.currentTimeMillis(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

data class ExpressionAndGestureResult(
    val available: Boolean,
    val faceDetected: Boolean,
    val expressionLabel: String, // HAPPY, SAD, TIRED, STRESSED, NEUTRAL, UNKNOWN
    val expressionBn: String,
    val gestureLabel: String,    // WAVE, THUMBS_UP, OPEN_PALM_STOP, POINTING, NONE, UNKNOWN
    val gestureBn: String,
    val confidence: Float,
    val summaryBn: String,
    val requiresUserConfirmationForAction: Boolean = true
)

object ScreenVisualAnalyzer {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun captureCurrentScreen(context: Context): Bitmap? = suspendCancellableCoroutine { cont ->
        JarvisScreenCaptureManager.captureScreen(context) { bitmap ->
            if (cont.isActive) {
                cont.resume(bitmap)
            }
        }
    }

    suspend fun captureAndAnalyze(
        context: Context,
        apiKey: String,
        userQuery: String = ""
    ): Pair<Bitmap?, ScreenVisualAnalysis> {
        val bitmap = captureCurrentScreen(context)
        val analysis = if (bitmap != null) {
            analyzeBitmap(bitmap, apiKey, userQuery)
        } else {
            ScreenVisualAnalysis(
                summaryBengali = "স্ক্রিনশট নেওয়া সম্ভব হয়নি। দয়া করে স্ক্রিন ক্যাপচার অথবা অ্যাক্সেসিবিলিটি পারমিশন নিশ্চিত করুন।",
                detectedApp = "অজানা",
                screenDescription = "Screen capture failed: No frame captured.",
                suggestions = listOf(
                    ScreenActionSuggestion(
                        actionType = "system",
                        title = "পারমিশন ওপেন করুন",
                        description = "অ্যাক্সেসিবিলিটি অথবা স্ক্রিন পারমিশন সেটিংস দেখুন",
                        targetTextOrId = "accessibility",
                        confidence = 1.0f
                    )
                ),
                rawAiResponse = "No bitmap captured"
            )
        }
        return Pair(bitmap, analysis)
    }

    /**
     * Vision-to-Action Execution Bridge:
     * Validates frame freshness, orientation alignment, and confidence before executing semantic-first
     * (viewId / text) or normalized-coordinate fallback actions, then verifies UI delta.
     */
    suspend fun executeVisualSuggestion(
        context: Context,
        suggestion: ScreenActionSuggestion,
        allowCoordinateFallback: Boolean = true,
        maxFrameAgeMs: Long = 15_000L
    ): VisionActionExecutionOutcome = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        if (suggestion.screenshotTimestamp > 0L && (now - suggestion.screenshotTimestamp) > maxFrameAgeMs) {
            return@withContext VisionActionExecutionOutcome(
                success = false,
                status = "STALE_FRAME",
                verified = false,
                strategyUsed = "NONE",
                screenChanged = false,
                summaryBengali = "ভিজ্যুয়াল স্ক্রিন ফ্রেমটি পুরোনো হয়ে গেছে — নিরাপদ অ্যাকশনের জন্য স্ক্রিন নতুন করে বিশ্লেষণ করুন।"
            )
        }

        val action = suggestion.actionType.trim().lowercase(java.util.Locale.ROOT)
        if (action == "click" && !suggestion.clickable && suggestion.viewId.isNullOrBlank() && suggestion.targetTextOrId.isNullOrBlank()) {
            return@withContext VisionActionExecutionOutcome(
                success = false,
                status = "NON_CLICKABLE_ELEMENT",
                verified = false,
                strategyUsed = "NONE",
                screenChanged = false,
                summaryBengali = "নির্বাচিত উপাদানটি ক্লিকযোগ্য নয় বা এর কনফিডেন্স কম।"
            )
        }

        val dm = context.resources.displayMetrics
        val curW = dm.widthPixels.coerceAtLeast(1)
        val curH = dm.heightPixels.coerceAtLeast(1)
        if (suggestion.screenshotWidth > 0 && suggestion.screenshotHeight > 0) {
            val frameLandscape = suggestion.screenshotWidth > suggestion.screenshotHeight
            val deviceLandscape = curW > curH
            if (frameLandscape != deviceLandscape) {
                return@withContext VisionActionExecutionOutcome(
                    success = false,
                    status = "ORIENTATION_MISMATCH",
                    verified = false,
                    strategyUsed = "NONE",
                    screenChanged = false,
                    summaryBengali = "স্ক্রিন রোটেশন পরিবর্তিত হওয়ায় পুরোনো কোঅর্ডিনেটে ক্লিক বাতিল করা হয়েছে।"
                )
            }
        }

        if (action == "open_app") {
            val appTarget = suggestion.targetTextOrId?.trim().orEmpty().ifBlank { suggestion.title }
            val (ok, msg) = com.example.util.JarvisDeviceAutomationHelper.openApp(context, appTarget)
            return@withContext VisionActionExecutionOutcome(
                success = ok,
                status = if (ok) "APP_LAUNCH_DISPATCHED" else "APP_NOT_FOUND",
                verified = false,
                strategyUsed = "INTENT_APP_LAUNCH",
                screenChanged = ok,
                summaryBengali = msg
            )
        }

        if (action == "system") {
            val target = suggestion.targetTextOrId?.trim().orEmpty()
            val (ok, msg) = if (target == "accessibility" || target == "screen_capture") {
                com.example.util.JarvisDeviceAutomationHelper.openApp(context, "settings")
            } else {
                com.example.util.JarvisDeviceAutomationHelper.performSystemAction(target.ifBlank { "home" })
            }
            return@withContext VisionActionExecutionOutcome(
                success = ok,
                status = if (ok) "SYSTEM_ACTION_EXECUTED" else "PERMISSION_REQUIRED",
                verified = ok,
                strategyUsed = "GLOBAL_ACTION",
                screenChanged = ok,
                summaryBengali = msg
            )
        }

        val a11y = com.example.service.JarvisAccessibilityService.instance
            ?: return@withContext VisionActionExecutionOutcome(
                success = false,
                status = "PERMISSION_REQUIRED",
                verified = false,
                strategyUsed = "NONE",
                screenChanged = false,
                summaryBengali = "ভিজ্যুয়াল অ্যাকশন সম্পাদনের জন্য Accessibility Service সক্রিয় থাকা প্রয়োজন।"
            )

        val beforeScreen = ScreenPerceptionEngine.inspectCurrentScreen(context, sanitize = true)
        val beforeHash = ScreenPerceptionEngine.computeScreenHash(beforeScreen)

        var dispatched = false
        var strategyUsed = "NONE"
        var detailMsg = ""

        when (action) {
            "scroll" -> {
                val dir = if (suggestion.targetTextOrId?.contains("up", true) == true) "scroll_up" else "scroll_down"
                val (ok, msg) = com.example.util.JarvisDeviceAutomationHelper.performSwipeAwait(dir)
                dispatched = ok
                strategyUsed = "SEMANTIC_SCROLL"
                detailMsg = msg
            }
            "type" -> {
                val focusTarget = suggestion.viewId?.takeIf { it.isNotBlank() } ?: suggestion.targetTextOrId?.takeIf { it.isNotBlank() }
                if (!focusTarget.isNullOrBlank()) {
                    if (suggestion.viewId?.isNotBlank() == true) a11y.clickElementByIdAwait(suggestion.viewId)
                    else a11y.clickElementByTextAwait(focusTarget)
                    kotlinx.coroutines.delay(200)
                }
                val textToType = suggestion.inputPayload?.ifBlank { null } ?: suggestion.targetTextOrId.orEmpty()
                val (ok, msg) = com.example.util.JarvisDeviceAutomationHelper.typeTextOnScreen(textToType)
                dispatched = ok
                strategyUsed = "ACCESSIBILITY_INPUT"
                detailMsg = msg
            }
            else -> {
                // 1. Semantic ViewId lookup first
                val viewId = suggestion.viewId?.trim().orEmpty()
                if (viewId.isNotBlank() && a11y.clickElementByIdAwait(viewId)) {
                    dispatched = true
                    strategyUsed = "SEMANTIC_VIEW_ID"
                    detailMsg = "'$viewId' উপাদানে ক্লিক করা হয়েছে।"
                }
                // 2. Semantic Text / ContentDescription lookup (targetTextOrId then elementName)
                val textTarget = suggestion.targetTextOrId?.trim().orEmpty()
                if (!dispatched && textTarget.isNotBlank() && a11y.clickElementByTextAwait(textTarget)) {
                    dispatched = true
                    strategyUsed = "SEMANTIC_TEXT"
                    detailMsg = "'$textTarget' উপাদানে ক্লিক করা হয়েছে।"
                }
                val elemName = suggestion.elementName?.trim().orEmpty()
                if (!dispatched && elemName.isNotBlank() && elemName != textTarget && a11y.clickElementByTextAwait(elemName)) {
                    dispatched = true
                    strategyUsed = "SEMANTIC_ELEMENT_NAME"
                    detailMsg = "'$elemName' উপাদানে ক্লিক করা হয়েছে।"
                }
                // 3. Scaled normalized coordinate fallback if confidence is acceptable
                if (!dispatched && allowCoordinateFallback) {
                    val normX = suggestion.centerNormX ?: computeNormalizedX(suggestion)
                    val normY = suggestion.centerNormY ?: computeNormalizedY(suggestion)
                    if (normX != null && normY != null && suggestion.confidence >= 0.45f) {
                        val pxX = (normX.coerceIn(0f, 1f) * curW).coerceIn(1f, (curW - 1).toFloat())
                        val pxY = (normY.coerceIn(0f, 1f) * curH).coerceIn(1f, (curH - 1).toFloat())
                        dispatched = a11y.clickCoordinatesAwait(pxX, pxY)
                        if (dispatched) {
                            strategyUsed = "SCALED_COORDINATE_FALLBACK"
                            detailMsg = "দৃশ্যমান অবস্থানে (${String.format(java.util.Locale.US, "%.2f", normX)}, ${String.format(java.util.Locale.US, "%.2f", normY)}) ক্লিক করা হয়েছে।"
                        } else {
                            detailMsg = "সমন্বয়কৃত অবস্থানে জেসচার ক্লিক ব্যর্থ হয়েছে।"
                        }
                    } else {
                        detailMsg = if (suggestion.confidence < 0.45f) {
                            "ভিজ্যুয়াল লক্ষ্য '${suggestion.title}'-এর কনফিডেন্স (${(suggestion.confidence * 100).toInt()}%) কম হওয়ায় নিরাপদ ক্লিক স্থগিত।"
                        } else {
                            "স্ক্রিনে '${suggestion.targetTextOrId ?: suggestion.title}' উপাদানের অবস্থান নিশ্চিত করা যায়নি।"
                        }
                    }
                }
            }
        }

        if (!dispatched) {
            return@withContext VisionActionExecutionOutcome(
                success = false,
                status = "TARGET_NOT_ACTED",
                verified = false,
                strategyUsed = strategyUsed,
                screenChanged = false,
                summaryBengali = detailMsg.ifBlank { "ভিজ্যুয়াল অ্যাকশন সম্পাদন করা যায়নি।" }
            )
        }

        kotlinx.coroutines.delay(350)
        val afterScreen = ScreenPerceptionEngine.inspectCurrentScreen(context, sanitize = true)
        val afterHash = ScreenPerceptionEngine.computeScreenHash(afterScreen)
        val changed = beforeHash != afterHash || beforeScreen.foregroundPackage != afterScreen.foregroundPackage

        VisionActionExecutionOutcome(
            success = true,
            status = if (changed) "VERIFIED_UI_CHANGED" else "DISPATCHED_NO_HASH_DELTA",
            verified = changed,
            strategyUsed = strategyUsed,
            screenChanged = changed,
            summaryBengali = if (changed) {
                "$detailMsg (স্ক্রিনের অবস্থা পরিবর্তন যাচাইকৃত)"
            } else {
                "$detailMsg (ক্লিক পাঠানো হয়েছে, স্ক্রিন পরিবর্তন পরবর্তী ধাপে যাচাইযোগ্য)"
            }
        )
    }

    private fun computeNormalizedX(s: ScreenActionSuggestion): Float? {
        if (s.screenshotWidth <= 0 || s.boundsRight <= s.boundsLeft) return null
        return ((s.boundsLeft + s.boundsRight) / 2f) / s.screenshotWidth.toFloat()
    }

    private fun computeNormalizedY(s: ScreenActionSuggestion): Float? {
        if (s.screenshotHeight <= 0 || s.boundsBottom <= s.boundsTop) return null
        return ((s.boundsTop + s.boundsBottom) / 2f) / s.screenshotHeight.toFloat()
    }

    suspend fun analyzeBitmap(
        bitmap: Bitmap,
        apiKey: String,
        userQuery: String = ""
    ): ScreenVisualAnalysis = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank() || cleanKey == "MY_GEMINI_API_KEY") {
            return@withContext ScreenVisualAnalysis(
                summaryBengali = "Gemini API Key সেটআপ করা নেই। Settings থেকে API Key যুক্ত করুন।",
                detectedApp = "অজানা",
                screenDescription = "API key missing.",
                suggestions = emptyList(),
                rawAiResponse = "API key not configured",
                frameWidth = bitmap.width,
                frameHeight = bitmap.height
            )
        }

        try {
            // Compress bitmap to JPEG
            val outputStream = ByteArrayOutputStream()
            // Scale down if image is huge to speed up upload
            val maxDimension = 1280
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = (bitmap.width.toFloat() / bitmap.height.toFloat())
                val targetW = if (ratio > 1f) maxDimension else (maxDimension * ratio).toInt()
                val targetH = if (ratio > 1f) (maxDimension / ratio).toInt() else maxDimension
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val promptText = buildString {
                append("You are J.A.R.V.I.S., the ultimate Iron Man AI operating system assistant on Android. ")
                append("Carefully inspect this mobile phone screenshot (${bitmap.width}x${bitmap.height}). ")
                if (userQuery.isNotBlank()) {
                    append("User's specific question or request: \"$userQuery\". ")
                }
                append("Provide a response in JSON format matching this exact schema:\n")
                append("{\n")
                append("  \"detectedApp\": \"Name of the app or system screen\",\n")
                append("  \"screenDescription\": \"Detailed explanation of all important information, elements, cards, and state visible on screen\",\n")
                append("  \"summaryBengali\": \"Polite, smart, and crystal-clear summary in Bengali for the user (as Jarvis speaking to Sir)\",\n")
                append("  \"suggestions\": [\n")
                append("     {\n")
                append("       \"actionType\": \"click|type|scroll|open_app|system\",\n")
                append("       \"title\": \"Short button label in English or Bengali\",\n")
                append("       \"elementName\": \"Semantic UI element role/name (e.g. Search Input, Submit Button)\",\n")
                append("       \"description\": \"What will happen when Jarvis executes this action in Bengali\",\n")
                append("       \"targetTextOrId\": \"The exact visible button text or element to click/type\",\n")
                append("       \"viewId\": \"Android resource view ID if visible or null\",\n")
                append("       \"elementType\": \"Button|TextField|Icon|Toggle|ListItem\",\n")
                append("       \"clickable\": true,\n")
                append("       \"centerNorm\": [0.5, 0.5],\n")
                append("       \"bounds\": [0, 0, 100, 100],\n")
                append("       \"confidence\": 0.9\n")
                append("     }\n")
                append("  ]\n")
                append("}\n")
                append("Use normalized [0.0..1.0] coordinates in centerNorm ([x, y]) and calibrated confidence [0.0..1.0]. ")
                append("Ensure the JSON is strictly valid with no markdown backticks surrounding it.")
            }

            // Construct Gemini REST JSON Payload
            val root = JSONObject()
            val contents = JSONArray()
            val contentObj = JSONObject()
            val parts = JSONArray()

            // 1. Text part
            val textPart = JSONObject()
            textPart.put("text", promptText)
            parts.put(textPart)

            // 2. Inline data image part
            val imagePart = JSONObject()
            val inlineData = JSONObject()
            inlineData.put("mimeType", "image/jpeg")
            inlineData.put("data", base64Image)
            imagePart.put("inlineData", inlineData)
            parts.put(imagePart)

            contentObj.put("parts", parts)
            contents.put(contentObj)
            root.put("contents", contents)

            // Generation config
            val genConfig = JSONObject()
            genConfig.put("responseMimeType", "application/json")
            root.put("generationConfig", genConfig)

            // Call gemini-2.5-flash or gemini-1.5-flash
            val modelsToTry = listOf("gemini-2.5-flash", "gemini-1.5-flash", "gemini-2.0-flash")
            var lastError: Exception? = null

            for (model in modelsToTry) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                    val requestBody = root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url(url).post(requestBody).build()

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        return@withContext parseGeminiResponse(responseBody, bitmap.width, bitmap.height)
                    } else {
                        lastError = RuntimeException("Model $model returned HTTP ${response.code}: $responseBody")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }

            throw (lastError ?: RuntimeException("Gemini Vision API request failed"))
        } catch (e: Exception) {
            ScreenVisualAnalysis(
                summaryBengali = "স্ক্রিন বিশ্লেষণে সমস্যা হয়েছে: ${e.localizedMessage ?: "নেটওয়ার্ক বিচ্ছিন্ন"}",
                detectedApp = "অজানা",
                screenDescription = "Error during analysis: ${e.message}",
                suggestions = emptyList(),
                rawAiResponse = e.stackTraceToString(),
                frameWidth = bitmap.width,
                frameHeight = bitmap.height
            )
        }
    }

    internal fun parseGeminiResponse(
        responseJsonStr: String,
        frameWidth: Int = 0,
        frameHeight: Int = 0
    ): ScreenVisualAnalysis {
        val captureTime = System.currentTimeMillis()
        return try {
            val root = JSONObject(responseJsonStr)
            val candidates = root.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val rawText = parts?.optJSONObject(0)?.optString("text", "") ?: ""

            // Clean json markdown if model added it
            val cleanJson = rawText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val parsed = JSONObject(cleanJson)
            val detectedApp = parsed.optString("detectedApp", "অ্যান্ড্রয়েড সিস্টেম")
            val screenDesc = parsed.optString("screenDescription", "স্ক্রিন উপাদানসমূহ সনাক্ত হয়েছে।")
            val summaryBn = parsed.optString("summaryBengali", "স্যার, বর্তমান স্ক্রিনের তথ্য পর্যালোচনা করা হয়েছে।")

            val suggestionsList = mutableListOf<ScreenActionSuggestion>()
            val suggArray = parsed.optJSONArray("suggestions")
            if (suggArray != null) {
                for (i in 0 until suggArray.length()) {
                    val item = suggArray.optJSONObject(i) ?: continue
                    val centerArr = item.optJSONArray("centerNorm")
                    val boundsArr = item.optJSONArray("bounds")
                    val cX = centerArr?.optDouble(0)?.takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
                    val cY = centerArr?.optDouble(1)?.takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0f, 1f)
                    val bLeft = boundsArr?.optInt(0, 0) ?: 0
                    val bTop = boundsArr?.optInt(1, 0) ?: 0
                    val bRight = boundsArr?.optInt(2, 0) ?: 0
                    val bBottom = boundsArr?.optInt(3, 0) ?: 0

                    suggestionsList.add(
                        ScreenActionSuggestion(
                            actionType = item.optString("actionType", "click"),
                            title = item.optString("title", "অ্যাকশন ${i + 1}"),
                            description = item.optString("description", ""),
                            targetTextOrId = item.optString("targetTextOrId", "").ifBlank { null },
                            viewId = item.optString("viewId", "").ifBlank { null },
                            elementName = item.optString("elementName", "").ifBlank { null },
                            elementType = item.optString("elementType", "Button"),
                            clickable = item.optBoolean("clickable", true),
                            inputPayload = item.optString("inputPayload", "").ifBlank { null },
                            boundsLeft = bLeft,
                            boundsTop = bTop,
                            boundsRight = bRight,
                            boundsBottom = bBottom,
                            centerNormX = cX,
                            centerNormY = cY,
                            confidence = item.optDouble("confidence", 0.75).toFloat().coerceIn(0f, 1f),
                            screenshotTimestamp = captureTime,
                            screenshotWidth = frameWidth,
                            screenshotHeight = frameHeight
                        )
                    )
                }
            }

            ScreenVisualAnalysis(
                summaryBengali = summaryBn,
                detectedApp = detectedApp,
                screenDescription = screenDesc,
                suggestions = suggestionsList,
                rawAiResponse = rawText,
                timestamp = captureTime,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        } catch (e: Exception) {
            ScreenVisualAnalysis(
                summaryBengali = "স্ক্রিন পরিদর্শন সফল হয়েছে।",
                detectedApp = "বর্তমান অ্যাপ",
                screenDescription = responseJsonStr.take(300),
                suggestions = emptyList(),
                rawAiResponse = responseJsonStr,
                timestamp = captureTime,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        }
    }

    /**
     * Truthfully inspects facial expression & hand gesture using Gemini Vision API.
     * Returns available=false if API key is missing or network fails — never fabricates detections.
     */
    suspend fun analyzeExpressionAndGesture(
        bitmap: Bitmap,
        apiKey: String
    ): ExpressionAndGestureResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank() || cleanKey == "MY_GEMINI_API_KEY") {
            return@withContext ExpressionAndGestureResult(
                available = false,
                faceDetected = false,
                expressionLabel = "UNKNOWN",
                expressionBn = "অফলাইন / API কী অনুপস্থিত",
                gestureLabel = "NONE",
                gestureBn = "শনাক্ত করা যায়নি",
                confidence = 0f,
                summaryBn = "ভিশন এক্সপ্রেশন বিশ্লেষণের জন্য সক্রিয় Gemini API কী প্রয়োজন।"
            )
        }

        try {
            val outputStream = ByteArrayOutputStream()
            val maxDim = 768
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val w = if (ratio > 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
                val h = if (ratio > 1f) (maxDim / ratio).toInt().coerceAtLeast(1) else maxDim
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else bitmap
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val prompt = """
You are inspecting a live camera frame for an empathetic personal AI companion.
Respond strictly in valid JSON (no markdown) with this schema:
{
  "faceDetected": true/false,
  "expressionLabel": "HAPPY|SAD|TIRED|STRESSED|ANGRY|CONFUSED|EXCITED|CALM|NEUTRAL|UNKNOWN",
  "expressionBn": "concise Bengali expression description",
  "gestureLabel": "WAVE|THUMBS_UP|OPEN_PALM_STOP|POINTING|NONE",
  "gestureBn": "concise Bengali hand gesture description",
  "confidence": 0.0 to 1.0,
  "summaryBn": "One natural honest Bengali sentence describing visible expression/gesture, or state clearly if no face/gesture is visible."
}
Never invent a face or gesture if unclear.
""".trimIndent()

            val root = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        }))
                    })
                }))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }

            for (model in listOf("gemini-2.5-flash", "gemini-2.0-flash")) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                    val body = root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val resp = httpClient.newCall(Request.Builder().url(url).post(body).build()).execute()
                    val respBody = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        return@withContext parseExpressionGestureResponse(respBody)
                    }
                } catch (_: Exception) {
                }
            }

            ExpressionAndGestureResult(
                available = false,
                faceDetected = false,
                expressionLabel = "UNKNOWN",
                expressionBn = "বিশ্লেষণ ব্যর্থ",
                gestureLabel = "NONE",
                gestureBn = "শনাক্ত করা যায়নি",
                confidence = 0f,
                summaryBn = "নেটওয়ার্ক বা সার্ভার সমস্যার কারণে মুখভঙ্গি বিশ্লেষণ করা যায়নি।"
            )
        } catch (e: Exception) {
            ExpressionAndGestureResult(
                available = false,
                faceDetected = false,
                expressionLabel = "UNKNOWN",
                expressionBn = "বিশ্লেষণ ব্যর্থ",
                gestureLabel = "NONE",
                gestureBn = "শনাক্ত করা যায়নি",
                confidence = 0f,
                summaryBn = "ভিশন ত্রুটি: ${e.localizedMessage ?: "অজানা সমস্যা"}"
            )
        }
    }

    private fun parseExpressionGestureResponse(responseJsonStr: String): ExpressionAndGestureResult {
        return try {
            val rawText = JSONObject(responseJsonStr)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text", "") ?: ""
            val clean = rawText.replace("```json", "").replace("```", "").trim()
            val parsed = JSONObject(clean)
            ExpressionAndGestureResult(
                available = true,
                faceDetected = parsed.optBoolean("faceDetected", false),
                expressionLabel = parsed.optString("expressionLabel", "UNKNOWN"),
                expressionBn = parsed.optString("expressionBn", "অনির্ধারিত"),
                gestureLabel = parsed.optString("gestureLabel", "NONE"),
                gestureBn = parsed.optString("gestureBn", "কোনো ইশারা নেই"),
                confidence = parsed.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                summaryBn = parsed.optString("summaryBn", "ক্যামেরা ফ্রেম পর্যালোচনা করা হয়েছে।")
            )
        } catch (_: Exception) {
            ExpressionAndGestureResult(
                available = false,
                faceDetected = false,
                expressionLabel = "UNKNOWN",
                expressionBn = "অনির্ধারিত",
                gestureLabel = "NONE",
                gestureBn = "কোনো ইশারা নেই",
                confidence = 0f,
                summaryBn = "ভিশন উত্তর পার্স করা যায়নি।"
            )
        }
    }
}
