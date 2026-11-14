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
    val targetTextOrId: String? = null
)

data class ScreenVisualAnalysis(
    val summaryBengali: String,
    val detectedApp: String,
    val screenDescription: String,
    val suggestions: List<ScreenActionSuggestion>,
    val rawAiResponse: String,
    val timestamp: Long = System.currentTimeMillis()
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
                        targetTextOrId = "accessibility"
                    )
                ),
                rawAiResponse = "No bitmap captured"
            )
        }
        return Pair(bitmap, analysis)
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
                rawAiResponse = "API key not configured"
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
                append("Carefully inspect this mobile phone screenshot. ")
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
                append("       \"description\": \"What will happen when Jarvis executes this action in Bengali\",\n")
                append("       \"targetTextOrId\": \"The exact visible button text or element to click/type\"\n")
                append("     }\n")
                append("  ]\n")
                append("}\n")
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
                        return@withContext parseGeminiResponse(responseBody)
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
                rawAiResponse = e.stackTraceToString()
            )
        }
    }

    private fun parseGeminiResponse(responseJsonStr: String): ScreenVisualAnalysis {
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
                    suggestionsList.add(
                        ScreenActionSuggestion(
                            actionType = item.optString("actionType", "click"),
                            title = item.optString("title", "অ্যাকশন ${i + 1}"),
                            description = item.optString("description", ""),
                            targetTextOrId = item.optString("targetTextOrId", null)
                        )
                    )
                }
            }

            ScreenVisualAnalysis(
                summaryBengali = summaryBn,
                detectedApp = detectedApp,
                screenDescription = screenDesc,
                suggestions = suggestionsList,
                rawAiResponse = rawText
            )
        } catch (e: Exception) {
            ScreenVisualAnalysis(
                summaryBengali = "স্ক্রিন পরিদর্শন সফল হয়েছে।",
                detectedApp = "বর্তমান অ্যাপ",
                screenDescription = responseJsonStr.take(300),
                suggestions = emptyList(),
                rawAiResponse = responseJsonStr
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
