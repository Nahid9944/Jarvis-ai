package com.example.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.ai.AiSettingsRepository
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.data.repository.JarvisSkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

data class YouTubeMultimodalAnalysis(
    val success: Boolean,
    val videoSummaryBn: String = "",
    val topicRelevantToAndroid: Boolean = true,
    val steps: List<YouTubeAnalyzedStep> = emptyList(),
    val error: String? = null
)

data class YouTubeAnalyzedStep(
    val timestampLabel: String,
    val actionType: String,
    val targetText: String = "",
    val targetViewId: String = "",
    val inputPayload: String = "",
    val descriptionBn: String = "",
    val uiTargetHint: String = "",
    val precondition: String = "",
    val expectedOutcome: String = ""
)

/**
 * Real YouTube Video Learning & Multimodal Tutorial Importer for NAHID JARVIS AI.
 * - Fetches public YouTube oEmbed metadata + timed XML caption tracks with [MM:SS] timestamps.
 * - Supports Gemini Multimodal Video Understanding (file_data.file_uri) when an API key is configured.
 * - Never fabricates hardcoded steps or timestamps when video content cannot be observed.
 */
object YouTubeTutorialImporter {

    private val YOUTUBE_URL_REGEX = Regex(
        "^(?:https?:\\/\\/)?(?:www\\.|m\\.)?(?:youtube\\.com\\/(?:watch\\?v=|embed\\/|v\\/|shorts\\/)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})(?:[?&].*)?$",
        RegexOption.IGNORE_CASE
    )

    private val NON_ANDROID_TOPICS = listOf(
        "recipe", "রান্না", "biryani", "pasta", "cake recipe",
        "iphone", "ios ", "macos", "xcode", "apple watch",
        "gameplay", "minecraft", "pubg", "free fire", "music video", "official video", "song lyrics"
    )

    private val geminiHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    internal var httpFetcher: (String) -> HttpFetchResult = { urlString ->
        fetchPublicPage(urlString)
    }

    internal var apiKeyProvider: (Context?) -> String = { ctx ->
        if (ctx == null) {
            ""
        } else {
            try {
                AiSettingsRepository(ctx.applicationContext).settingsState.value.apiKey.trim()
            } catch (_: Exception) {
                ""
            }
        }
    }

    /**
     * Testable multimodal Gemini video analyzer hook.
     * By default invokes Gemini REST `generateContent` with `file_data: { file_uri: watchUrl }`.
     */
    internal var videoMultimodalAnalyzer: ((videoUrl: String, videoTitle: String, apiKey: String) -> YouTubeMultimodalAnalysis)? = null

    private fun fetchPublicPage(urlString: String): HttpFetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 Nahid-Jarvis-AI")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpFetchResult(statusCode = code, body = body)
        } catch (e: Exception) {
            HttpFetchResult(statusCode = -1, error = e.localizedMessage ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    fun isValidYouTubeUrl(url: String): Boolean = YOUTUBE_URL_REGEX.matches(url.trim())

    fun extractVideoId(url: String): String? {
        val match = YOUTUBE_URL_REGEX.find(url.trim()) ?: return null
        return match.groupValues.getOrNull(1)
    }

    fun createPlaybackIntent(url: String): Intent? {
        val videoId = extractVideoId(url) ?: return null
        val appUri = Uri.parse("vnd.youtube:$videoId")
        return Intent(Intent.ACTION_VIEW, appUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun formatTimestampSeconds(secondsFloat: Double): String {
        val total = secondsFloat.toInt().coerceAtLeast(0)
        val mins = total / 60
        val secs = total % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    /**
     * Dry-run validation helper for Skill Draft -> ACTIVE lifecycle.
     */
    fun validateImportedSkillDraft(steps: List<JarvisSkillStepEntity>): JarvisSkillRecorder.Companion.SkillValidationResult {
        return JarvisSkillRecorder.validateSkillPlaybook(steps)
    }

    suspend fun importFromTutorial(
        context: Context? = null,
        url: String,
        userProvidedTitle: String? = null,
        userProvidedTranscript: String? = null,
        skillRepo: JarvisSkillRepository? = null,
        draftMode: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        val trimmedUrl = url.trim()

        if (!isValidYouTubeUrl(trimmedUrl)) {
            result.put("success", false)
            result.put("status", "FAILED")
            result.put("error", "INVALID_YOUTUBE_URL")
            result.put("summaryBengali", "প্রদত্ত লিঙ্কটি সঠিক ইউটিউব ভিডিও লিঙ্ক নয়। অনুগ্রহ করে সঠিক ইউটিউব লিঙ্ক দিন।")
            return@withContext result
        }

        val videoId = extractVideoId(trimmedUrl) ?: ""
        val canonicalWatchUrl = "https://www.youtube.com/watch?v=$videoId"
        var resolvedTitle = userProvidedTitle?.trim()?.takeIf { it.isNotBlank() }
        var transcriptText = userProvidedTranscript?.trim()?.takeIf { it.isNotBlank() }

        // 1. Fetch public oEmbed metadata if title missing
        if (resolvedTitle == null) {
            val oembedUrl = "https://www.youtube.com/oembed?url=$canonicalWatchUrl&format=json"
            val oembedResp = httpFetcher(oembedUrl)
            if (oembedResp.statusCode in 200..299 && oembedResp.body.isNotBlank()) {
                try {
                    val json = JSONObject(oembedResp.body)
                    resolvedTitle = json.optString("title", "").takeIf { it.isNotBlank() }
                } catch (_: Exception) {}
            }
        }
        val finalTitle = resolvedTitle ?: "YouTube Video $videoId"

        // 2. Fetch public timed caption track if user did not supply transcript
        var extractionMode = if (transcriptText != null) "USER_TRANSCRIPT" else "NONE"
        if (transcriptText == null) {
            val captions = tryFetchPublicTranscript(videoId)
            if (!captions.isNullOrBlank()) {
                transcriptText = captions
                extractionMode = "PUBLIC_CAPTIONS"
            }
        }

        // 3. Check obvious non-Android topics before synthesis
        val preCombinedLower = "${finalTitle.lowercase(Locale.ROOT)} ${transcriptText?.lowercase(Locale.ROOT) ?: ""}"
        val hasExplicitAndroidSignal = preCombinedLower.contains("android") || preCombinedLower.contains("অ্যান্ড্রয়েড") ||
            preCombinedLower.contains("settings") || preCombinedLower.contains("সেটিংস") ||
            preCombinedLower.contains("whatsapp") || preCombinedLower.contains("app") || preCombinedLower.contains("অ্যাপ")

        if (!hasExplicitAndroidSignal && NON_ANDROID_TOPICS.any { preCombinedLower.contains(it) }) {
            result.put("success", false)
            result.put("status", "UNSUPPORTED")
            result.put("videoId", videoId)
            result.put("videoTitle", finalTitle)
            result.put("playbackUri", "vnd.youtube:$videoId")
            result.put("error", "UNSUPPORTED_TOPIC")
            result.put(
                "summaryBengali",
                "'$finalTitle' ভিডিওটি অ্যান্ড্রয়েড অটোমেশন বিষয়ক নয়। তাই এটি থেকে অ্যান্ড্রয়েড স্কিল তৈরি করা সম্ভব নয় (UNSUPPORTED)।"
            )
            return@withContext result
        }

        // 4. Gemini Multimodal Video Understanding when captions/transcript missing OR to enrich video steps
        var multimodalUsed = false
        var multimodalSteps = emptyList<YouTubeAnalyzedStep>()
        var videoSummaryBn = ""

        if (transcriptText.isNullOrBlank()) {
            val apiKey = apiKeyProvider(context)
            val multimodalResult = analyzeVideoMultimodal(canonicalWatchUrl, finalTitle, apiKey)
            if (multimodalResult != null) {
                if (!multimodalResult.topicRelevantToAndroid) {
                    result.put("success", false)
                    result.put("status", "UNSUPPORTED")
                    result.put("videoId", videoId)
                    result.put("videoTitle", finalTitle)
                    result.put("playbackUri", "vnd.youtube:$videoId")
                    result.put("error", "UNSUPPORTED_TOPIC")
                    result.put(
                        "summaryBengali",
                        multimodalResult.videoSummaryBn.ifBlank {
                            "'$finalTitle' ভিডিওটি অ্যান্ড্রয়েড অটোমেশন বিষয়ক নয় (UNSUPPORTED)।"
                        }
                    )
                    return@withContext result
                }
                if (multimodalResult.success && multimodalResult.steps.isNotEmpty()) {
                    multimodalUsed = true
                    extractionMode = "GEMINI_MULTIMODAL_VIDEO"
                    multimodalSteps = multimodalResult.steps
                    videoSummaryBn = multimodalResult.videoSummaryBn
                }
            }
        }

        // 5. Truthfulness gate: If transcript/captions AND multimodal video analysis unavailable, NEVER fabricate hardcoded steps!
        if (transcriptText.isNullOrBlank() && !multimodalUsed) {
            result.put("success", false)
            result.put("status", "TRANSCRIPT_REQUIRED")
            result.put("videoId", videoId)
            result.put("videoTitle", finalTitle)
            result.put("playbackUri", "vnd.youtube:$videoId")
            result.put("error", "TRANSCRIPT_REQUIRED")
            result.put(
                "summaryBengali",
                "'$finalTitle' ভিডিওটির পাবলিক ট্রান্সক্রিপ্ট/ক্যাপশন পাওয়া যায়নি এবং মাল্টিমোডাল ভিডিও বিশ্লেষণ সক্রিয় নয়। অসত্য ধাপ অনুমান না করে, দয়া করে ভিডিওটির ধাপগুলো লিখে বা ট্রান্সক্রিপ্ট পেস্ট করে দিন।"
            )
            return@withContext result
        }

        val timestampMap = mutableMapOf<Int, String>()
        val extractedSteps = mutableListOf<JarvisSkillStepEntity>()
        val analyzedMeta = mutableMapOf<Int, YouTubeAnalyzedStep>()

        if (multimodalUsed) {
            multimodalSteps.take(12).forEachIndexed { idx, ms ->
                val stepNum = idx + 1
                val act = ms.actionType.uppercase(Locale.ROOT).takeIf {
                    it in setOf("OPEN_APP", "CLICK", "INPUT_TEXT", "SCROLL_FORWARD", "SCROLL_BACKWARD", "BACK", "HOME", "WAIT", "WAIT_FOR_ELEMENT", "VERIFY_TEXT")
                } ?: "CLICK"
                val stepEntity = JarvisSkillStepEntity(
                    skillId = "",
                    stepIndex = stepNum,
                    actionType = act,
                    targetViewId = ms.targetViewId.ifBlank { null },
                    targetText = ms.targetText.ifBlank { ms.uiTargetHint.ifBlank { null } },
                    inputPayload = ms.inputPayload.ifBlank { null },
                    descriptionBengali = ms.descriptionBn.ifBlank { "$act ধাপ (${ms.timestampLabel})" },
                    delayAfterMs = 800L
                )
                extractedSteps.add(stepEntity)
                timestampMap[stepNum] = ms.timestampLabel.ifBlank { "00:00" }
                analyzedMeta[stepNum] = ms
            }
        } else {
            val safeTranscript = sanitizeUntrustedTranscript(transcriptText.orEmpty())
            val timedSteps = extractTimedActionStepsFromText(safeTranscript)
            timedSteps.forEachIndexed { idx, (ts, step) ->
                val stepNum = idx + 1
                extractedSteps.add(step.copy(stepIndex = stepNum))
                if (ts.isNotBlank()) timestampMap[stepNum] = ts
            }
        }

        if (extractedSteps.isEmpty()) {
            result.put("success", false)
            result.put("status", "TRANSCRIPT_REQUIRED")
            result.put("videoId", videoId)
            result.put("videoTitle", finalTitle)
            result.put("playbackUri", "vnd.youtube:$videoId")
            result.put("error", "NO_ACTIONABLE_STEPS_FOUND")
            result.put(
                "summaryBengali",
                "প্রদত্ত ট্রান্সক্রিপ্ট থেকে সুনির্দিষ্ট অ্যান্ড্রয়েড অ্যাকশন ধাপ (যেমন Open / Click / Type / Scroll) সনাক্ত করা যায়নি। অনুগ্রহ করে প্রতিটি ধাপ স্পষ্টভাবে লিখে দিন।"
            )
            return@withContext result
        }

        val combinedTextForApp = "${finalTitle.lowercase(Locale.ROOT)} ${transcriptText?.lowercase(Locale.ROOT) ?: ""} ${multimodalSteps.joinToString(" ") { "${it.targetViewId} ${it.targetText} ${it.descriptionBn}" }.lowercase(Locale.ROOT)}"
        val (targetPkg, targetAppName) = detectTargetApp(combinedTextForApp)
        val skillId = "yt_${videoId}_${System.currentTimeMillis() % 10000}"

        val finalSteps = mutableListOf<JarvisSkillStepEntity>()
        val finalTimestamps = mutableMapOf<Int, String>()
        val finalMeta = mutableMapOf<Int, YouTubeAnalyzedStep>()

        if (targetPkg.isNotBlank() && extractedSteps.none { it.actionType == "OPEN_APP" }) {
            finalSteps.add(
                JarvisSkillStepEntity(
                    skillId = skillId,
                    stepIndex = 1,
                    actionType = "OPEN_APP",
                    targetViewId = targetPkg,
                    targetText = targetAppName,
                    descriptionBengali = "$targetAppName অ্যাপ চালু করা",
                    delayAfterMs = 1000L
                )
            )
            finalTimestamps[1] = timestampMap[1] ?: "00:00"
        }
        extractedSteps.forEach { step ->
            val oldIdx = step.stepIndex
            val newIdx = finalSteps.size + 1
            finalSteps.add(step.copy(skillId = skillId, stepIndex = newIdx))
            timestampMap[oldIdx]?.let { finalTimestamps[newIdx] = it }
            analyzedMeta[oldIdx]?.let { finalMeta[newIdx] = it }
        }

        val validation = validateImportedSkillDraft(finalSteps)
        val initialStatus = if (draftMode || !validation.isValid) "DRAFT" else validation.recommendedStatus

        val stepsJsonArray = JSONArray().apply {
            finalSteps.forEach { step ->
                val meta = finalMeta[step.stepIndex]
                val tsLabel = finalTimestamps[step.stepIndex].orEmpty()
                val targetLabel = step.targetText?.ifBlank { null } ?: step.targetViewId?.ifBlank { null } ?: "Target UI"
                val precondition = meta?.precondition?.ifBlank { null } ?: when (step.actionType) {
                    "OPEN_APP" -> "Target package installed on device"
                    "CLICK" -> "Element '$targetLabel' visible on active screen"
                    "INPUT_TEXT" -> "Focused editable input field available"
                    else -> "Active window responsive"
                }
                val expectedResult = meta?.expectedOutcome?.ifBlank { null } ?: when (step.actionType) {
                    "OPEN_APP" -> "$targetLabel foreground window opens"
                    "CLICK" -> "UI transitions or triggers action for '$targetLabel'"
                    "INPUT_TEXT" -> "Editable node reflects supplied text"
                    else -> "${step.actionType} completes without accessibility error"
                }
                val failureHandling = "Retry bounded once after 600ms; if still missing, abort step honestly and report UI mismatch."

                put(JSONObject().apply {
                    put("stepIndex", step.stepIndex)
                    put("actionType", step.actionType)
                    put("targetText", step.targetText ?: "")
                    put("targetViewId", step.targetViewId ?: "")
                    put("inputPayload", step.inputPayload ?: "")
                    put("descriptionBn", step.descriptionBengali)
                    put("delayAfterMs", step.delayAfterMs)
                    if (tsLabel.isNotBlank()) put("timestamp", tsLabel)
                    put("precondition", precondition)
                    put("expectedResult", expectedResult)
                    put("postcondition", expectedResult)
                    put("failureHandling", failureHandling)
                })
            }
        }
        val paramsSchema = JarvisSkillRecorder.extractParameterNames(finalSteps)
        val triggers = JSONArray().apply {
            put(finalTitle)
            put("YouTube $videoId")
        }

        val entity = JarvisSkillEntity(
            skillId = skillId,
            name = finalTitle,
            nameBengali = finalTitle,
            description = videoSummaryBn.ifBlank { "Imported from YouTube Tutorial ($trimmedUrl)" },
            triggerPhrasesJson = triggers.toString(),
            targetPackageName = targetPkg,
            targetAppName = targetAppName,
            category = inferCategory(targetPkg),
            riskLevel = "MEDIUM",
            requiresConfirmation = false,
            requiredPermissionsJson = "[\"ACCESSIBILITY_SERVICE\"]",
            stepsJson = stepsJsonArray.toString(),
            stepCount = finalSteps.size,
            sourceType = "YOUTUBE_TUTORIAL",
            status = initialStatus,
            parametersSchemaJson = JSONArray(paramsSchema).toString()
        )

        if (skillRepo != null) {
            skillRepo.saveSkillWithSteps(entity, finalSteps)
        }

        result.put("success", true)
        result.put("status", "SUCCESS")
        result.put("skillStatus", initialStatus)
        result.put("draftValidated", validation.isValid)
        result.put("validationIssues", JSONArray(validation.issues))
        result.put("extractionMode", extractionMode)
        result.put("skillId", skillId)
        result.put("videoId", videoId)
        result.put("playbackUri", "vnd.youtube:$videoId")
        result.put("watchUrl", canonicalWatchUrl)
        result.put("skillName", finalTitle)
        result.put("skillNameBengali", finalTitle)
        result.put("videoSummaryBn", videoSummaryBn)
        result.put("stepCount", finalSteps.size)
        result.put("parameters", JSONArray(paramsSchema))
        result.put("steps", stepsJsonArray)
        result.put(
            "summaryBengali",
            if (draftMode) {
                "ইউটিউব ভিডিও '$finalTitle' ($extractionMode) থেকে ${finalSteps.size}টি ধাপ স্কিল ড্রাফট হিসেবে সংরক্ষণ করা হয়েছে; প্লেব্যাকের আগে ভ্যালিডেশন প্রস্তুত।"
            } else {
                "ইউটিউব টিউটোরিয়াল '$finalTitle' ($extractionMode) থেকে ${finalSteps.size}টি প্রকৃত ধাপ বিশ্লেষণ করে সফলভাবে স্কিল সংরক্ষণ করা হয়েছে।"
            }
        )

        result
    }

    internal fun analyzeVideoMultimodal(
        videoUrl: String,
        videoTitle: String,
        apiKey: String
    ): YouTubeMultimodalAnalysis? {
        videoMultimodalAnalyzer?.let { return it(videoUrl, videoTitle, apiKey) }
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank() || cleanKey == "MY_GEMINI_API_KEY") return null

        return try {
            val prompt = """
You are an Android Skill Extraction AI analyzing a YouTube video tutorial at $videoUrl (Title: "$videoTitle").
Inspect both spoken explanations and visual screen UI transitions in the video.
Return ONLY valid JSON (no markdown) matching:
{
  "topicRelevantToAndroid": true/false,
  "videoSummaryBn": "Short honest Bengali summary of what the video actually demonstrates",
  "steps": [
    {
      "timestampLabel": "MM:SS",
      "actionType": "OPEN_APP|CLICK|INPUT_TEXT|SCROLL_FORWARD|SCROLL_BACKWARD|BACK|HOME|WAIT",
      "targetText": "exact visible button/label text if seen or spoken",
      "targetViewId": "package or view id if Android app (e.g. com.android.settings)",
      "inputPayload": "text typed if INPUT_TEXT",
      "descriptionBn": "short Bengali description of this step",
      "uiTargetHint": "visual target cue",
      "precondition": "screen state before step",
      "expectedOutcome": "expected screen state after step"
    }
  ]
}
Strictly do NOT invent steps or timestamps that do not appear in the video.
""".trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("file_data", JSONObject().apply {
                            put("file_uri", videoUrl)
                        }))
                    })
                }))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }

            for (model in listOf("gemini-2.5-flash", "gemini-2.0-flash")) {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val resp = geminiHttpClient.newCall(Request.Builder().url(endpoint).post(reqBody).build()).execute()
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    return parseGeminiMultimodalResponse(body)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseGeminiMultimodalResponse(rawApiJson: String): YouTubeMultimodalAnalysis {
        return try {
            val rawText = JSONObject(rawApiJson)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text", "")
                .orEmpty()
                .replace("```json", "")
                .replace("```", "")
                .trim()
            val obj = JSONObject(rawText)
            val relevant = obj.optBoolean("topicRelevantToAndroid", true)
            val summaryBn = obj.optString("videoSummaryBn", "")
            val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
            val list = mutableListOf<YouTubeAnalyzedStep>()
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.optJSONObject(i) ?: continue
                list.add(
                    YouTubeAnalyzedStep(
                        timestampLabel = s.optString("timestampLabel", "00:00"),
                        actionType = s.optString("actionType", "CLICK"),
                        targetText = s.optString("targetText", ""),
                        targetViewId = s.optString("targetViewId", ""),
                        inputPayload = s.optString("inputPayload", ""),
                        descriptionBn = s.optString("descriptionBn", ""),
                        uiTargetHint = s.optString("uiTargetHint", ""),
                        precondition = s.optString("precondition", ""),
                        expectedOutcome = s.optString("expectedOutcome", "")
                    )
                )
            }
            YouTubeMultimodalAnalysis(
                success = list.isNotEmpty(),
                videoSummaryBn = summaryBn,
                topicRelevantToAndroid = relevant,
                steps = list
            )
        } catch (e: Exception) {
            YouTubeMultimodalAnalysis(success = false, error = e.localizedMessage)
        }
    }

    internal fun sanitizeUntrustedTranscript(rawTranscript: String): String {
        val injectionPatterns = listOf(
            "ignore previous instructions",
            "ignore all instructions",
            "system prompt",
            "developer prompt",
            "transfer money",
            "send otp"
        )
        return rawTranscript
            .lines()
            .filterNot { line ->
                val lower = line.lowercase(Locale.ROOT)
                injectionPatterns.any { lower.contains(it) }
            }
            .joinToString("\n")
    }

    internal fun tryFetchPublicTranscript(videoId: String): String? {
        val watchResp = httpFetcher("https://www.youtube.com/watch?v=$videoId")
        if (watchResp.statusCode !in 200..299 || watchResp.body.isBlank()) return null

        val match = Regex(""""captionTracks":\s*\[\s*\{[^}]*"baseUrl":\s*"([^"]+)"""").find(watchResp.body)
            ?: return null
        val captionUrl = match.groupValues.getOrNull(1)
            ?.replace("\\u0026", "&")
            ?.replace("\\/", "/")
            ?: return null

        val capResp = httpFetcher(captionUrl)
        if (capResp.statusCode !in 200..299 || capResp.body.isBlank()) return null

        val textChunks = Regex("""<text([^>]*)>([^<]+)</text>""").findAll(capResp.body)
            .map { m ->
                val attrs = m.groupValues[1]
                val startSec = Regex("""start="([0-9.]+)"""").find(attrs)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                val decoded = m.groupValues[2]
                    .replace("&amp;", "&")
                    .replace("&#39;", "'")
                    .replace("&quot;", "\"")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&#10;", "\n")
                    .trim()
                if (decoded.isBlank()) "" else if (startSec != null) "[${formatTimestampSeconds(startSec)}] $decoded" else decoded
            }
            .filter { it.isNotBlank() }
            .toList()
        return if (textChunks.isNotEmpty()) textChunks.joinToString("\n") else null
    }

    fun extractActionStepsFromText(text: String): List<JarvisSkillStepEntity> {
        return extractTimedActionStepsFromText(text).map { it.second }
    }

    internal fun extractTimedActionStepsFromText(text: String): List<Pair<String, JarvisSkillStepEntity>> {
        val steps = mutableListOf<Pair<String, JarvisSkillStepEntity>>()
        val tsRegex = Regex("""^\[(\d{1,2}:\d{2}(?::\d{2})?)\]\s*(.*)$""")
        val rawLines = text.split('\n', ';', '।')
            .map { it.trim() }
            .filter { it.length >= 4 }

        for (rawLine in rawLines) {
            val tsMatch = tsRegex.find(rawLine)
            val timestamp = tsMatch?.groupValues?.getOrNull(1).orEmpty()
            val cleanLine = (tsMatch?.groupValues?.getOrNull(2) ?: rawLine)
                .replace(Regex("""^\s*(?:\d+[.)০-৯]*|[-*•])\s*"""), "")
                .trim()
            if (cleanLine.length < 4) continue
            val step = inferStepFromLine(cleanLine, steps.size + 1)
            if (step != null) {
                steps.add(timestamp to step)
                if (steps.size >= 12) break
            }
        }
        return steps
    }

    private fun inferStepFromLine(line: String, stepIndex: Int): JarvisSkillStepEntity? {
        val lower = line.lowercase(Locale.ROOT)
        return when {
            lower.contains("open ") || lower.contains("launch ") || lower.contains("খোলো") || lower.contains("খুলুন") || lower.contains("চালু করো") -> {
                val appTarget = line.replace(Regex("(?i)(open|launch|খোলো|খুলুন|চালু করো|অ্যাপ)"), "").trim().take(40)
                val (pkg, name) = detectTargetApp(appTarget.lowercase(Locale.ROOT))
                val resolvedPkg = if (pkg.isNotBlank()) pkg else appTarget
                if (resolvedPkg.isBlank()) null else JarvisSkillStepEntity(
                    skillId = "",
                    stepIndex = stepIndex,
                    actionType = "OPEN_APP",
                    targetViewId = resolvedPkg,
                    targetText = name.ifBlank { appTarget },
                    descriptionBengali = "${name.ifBlank { appTarget }} চালু করা",
                    delayAfterMs = 900L
                )
            }
            lower.contains("click") || lower.contains("tap") || lower.contains("press") || lower.contains("চাপুন") || lower.contains("চাপো") || lower.contains("ক্লিক") -> {
                val target = line.replace(Regex("(?i)(click|tap|press|on|বাটনে|বাটন|চাপুন|চাপো|ক্লিক করুন|ক্লিক করো|ক্লিক)"), "").trim().take(40)
                if (target.isBlank()) null else JarvisSkillStepEntity(
                    skillId = "",
                    stepIndex = stepIndex,
                    actionType = "CLICK",
                    targetText = target,
                    descriptionBengali = "‘$target’ বাটনে ক্লিক করা",
                    delayAfterMs = 600L
                )
            }
            lower.contains("type") || lower.contains("enter") || lower.contains("input") || lower.contains("write") ||
                lower.contains("লিখুন") || lower.contains("লেখো") || lower.contains("লিখো") -> {
                val payload = line.replace(Regex("(?i)(type|enter|input|write|ইনপুট ফিল্ডে|ফিল্ডে|লিখুন|লেখো|লিখো)"), "").trim().take(80)
                if (payload.isBlank()) null else JarvisSkillStepEntity(
                    skillId = "",
                    stepIndex = stepIndex,
                    actionType = "INPUT_TEXT",
                    inputPayload = payload,
                    descriptionBengali = "‘$payload’ লেখা",
                    delayAfterMs = 700L
                )
            }
            lower.contains("scroll up") || lower.contains("উপরে স্ক্রল") -> JarvisSkillStepEntity(
                skillId = "", stepIndex = stepIndex, actionType = "SCROLL_BACKWARD",
                descriptionBengali = "স্ক্রিন উপরে স্ক্রল করা", delayAfterMs = 600L
            )
            lower.contains("scroll") || lower.contains("swipe") || lower.contains("স্ক্রল") -> JarvisSkillStepEntity(
                skillId = "", stepIndex = stepIndex, actionType = "SCROLL_FORWARD",
                descriptionBengali = "স্ক্রিন নিচে স্ক্রল করা", delayAfterMs = 600L
            )
            lower.contains("back") || lower.contains("পেছনে") -> JarvisSkillStepEntity(
                skillId = "", stepIndex = stepIndex, actionType = "BACK",
                descriptionBengali = "পেছনে ফেরা", delayAfterMs = 500L
            )
            lower.contains("home") || lower.contains("হোম") -> JarvisSkillStepEntity(
                skillId = "", stepIndex = stepIndex, actionType = "HOME",
                descriptionBengali = "হোম স্ক্রিনে ফেরা", delayAfterMs = 500L
            )
            lower.contains("wait") || lower.contains("অপেক্ষা") -> JarvisSkillStepEntity(
                skillId = "", stepIndex = stepIndex, actionType = "WAIT",
                descriptionBengali = "কিছুক্ষণ অপেক্ষা করা", delayAfterMs = 1200L
            )
            else -> null
        }
    }

    private fun detectTargetApp(lowerContent: String): Pair<String, String> {
        return when {
            lowerContent.contains("whatsapp") || lowerContent.contains("হোয়াটসঅ্যাপ") -> Pair("com.whatsapp", "WhatsApp")
            lowerContent.contains("facebook") || lowerContent.contains("ফেসবুক") -> Pair("com.facebook.katana", "Facebook")
            lowerContent.contains("settings") || lowerContent.contains("সেটিংস") -> Pair("com.android.settings", "Settings")
            lowerContent.contains("chrome") || lowerContent.contains("browser") -> Pair("com.android.chrome", "Chrome")
            lowerContent.contains("youtube") || lowerContent.contains("ইউটিউব") -> Pair("com.google.android.youtube", "YouTube")
            else -> Pair("", "")
        }
    }

    private fun inferCategory(pkg: String): String {
        return when {
            pkg.contains("whatsapp") || pkg.contains("telegram") -> "COMMUNICATION"
            pkg.contains("settings") -> "SYSTEM_TOOL"
            pkg.contains("facebook") || pkg.contains("instagram") -> "SOCIAL"
            pkg.contains("youtube") -> "MEDIA"
            else -> "CUSTOM"
        }
    }
}
