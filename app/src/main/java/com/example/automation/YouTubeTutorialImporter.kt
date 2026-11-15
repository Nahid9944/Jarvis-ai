package com.example.automation

import android.content.Context
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.data.repository.JarvisSkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Real YouTube Tutorial Importer & Skill Synthesis Engine for NAHID JARVIS AI.
 * Fetches public YouTube metadata via oEmbed and caption tracks when available.
 * Never fabricates hardcoded steps when transcript/instructions are missing or unrelated to Android.
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

    internal var httpFetcher: (String) -> HttpFetchResult = { urlString ->
        fetchPublicPage(urlString)
    }

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

    suspend fun importFromTutorial(
        context: Context? = null,
        url: String,
        userProvidedTitle: String? = null,
        userProvidedTranscript: String? = null,
        skillRepo: JarvisSkillRepository? = null
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
        var resolvedTitle = userProvidedTitle?.trim()?.takeIf { it.isNotBlank() }
        var transcriptText = userProvidedTranscript?.trim()?.takeIf { it.isNotBlank() }

        // 1. Fetch public oEmbed metadata if title missing
        if (resolvedTitle == null) {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val oembedResp = httpFetcher(oembedUrl)
            if (oembedResp.statusCode in 200..299 && oembedResp.body.isNotBlank()) {
                try {
                    val json = JSONObject(oembedResp.body)
                    resolvedTitle = json.optString("title", "").takeIf { it.isNotBlank() }
                } catch (_: Exception) {}
            }
        }
        val finalTitle = resolvedTitle ?: "YouTube Video $videoId"

        // 2. Fetch public caption track if user did not supply transcript
        if (transcriptText == null) {
            transcriptText = tryFetchPublicTranscript(videoId)
        }

        val combinedLower = "${finalTitle.lowercase(Locale.ROOT)} ${transcriptText?.lowercase(Locale.ROOT) ?: ""}"
        val hasExplicitAndroidSignal = combinedLower.contains("android") || combinedLower.contains("অ্যান্ড্রয়েড") ||
            combinedLower.contains("settings") || combinedLower.contains("সেটিংস") ||
            combinedLower.contains("whatsapp") || combinedLower.contains("app") || combinedLower.contains("অ্যাপ")

        if (!hasExplicitAndroidSignal && NON_ANDROID_TOPICS.any { combinedLower.contains(it) }) {
            result.put("success", false)
            result.put("status", "UNSUPPORTED")
            result.put("videoId", videoId)
            result.put("videoTitle", finalTitle)
            result.put("error", "UNSUPPORTED_TOPIC")
            result.put(
                "summaryBengali",
                "'$finalTitle' ভিডিওটি অ্যান্ড্রয়েড অটোমেশন বিষয়ক নয়। তাই এটি থেকে অ্যান্ড্রয়েড স্কিল তৈরি করা সম্ভব নয় (UNSUPPORTED)।"
            )
            return@withContext result
        }

        // 3. Truthfulness gate: If transcript/instructions unavailable, NEVER fabricate hardcoded steps!
        if (transcriptText.isNullOrBlank()) {
            result.put("success", false)
            result.put("status", "TRANSCRIPT_REQUIRED")
            result.put("videoId", videoId)
            result.put("videoTitle", finalTitle)
            result.put("error", "TRANSCRIPT_REQUIRED")
            result.put(
                "summaryBengali",
                "'$finalTitle' ভিডিওটির পাবলিক ট্রান্সক্রিপ্ট বা ক্যাপশন পাওয়া যায়নি। অসত্য ধাপ অনুমান না করে, দয়া করে ভিডিওটির ধাপগুলো লিখে বা ট্রান্সক্রিপ্ট পেস্ট করে দিন।"
            )
            return@withContext result
        }

        // 4. Parse genuine executable steps from transcript lines
        val extractedSteps = extractActionStepsFromText(transcriptText)
        if (extractedSteps.isEmpty()) {
            result.put("success", false)
            result.put("status", "TRANSCRIPT_REQUIRED")
            result.put("videoId", videoId)
            result.put("videoTitle", finalTitle)
            result.put("error", "NO_ACTIONABLE_STEPS_FOUND")
            result.put(
                "summaryBengali",
                "প্রদত্ত ট্রান্সক্রিপ্ট থেকে সুনির্দিষ্ট অ্যান্ড্রয়েড অ্যাকশন ধাপ (যেমন Open / Click / Type / Scroll) সনাক্ত করা যায়নি। অনুগ্রহ করে প্রতিটি ধাপ স্পষ্টভাবে লিখে দিন।"
            )
            return@withContext result
        }

        val (targetPkg, targetAppName) = detectTargetApp(combinedLower)
        val skillId = "yt_${videoId}_${System.currentTimeMillis() % 10000}"

        val finalSteps = mutableListOf<JarvisSkillStepEntity>()
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
        }
        extractedSteps.forEach { step ->
            finalSteps.add(step.copy(skillId = skillId, stepIndex = finalSteps.size + 1))
        }

        val stepsJsonArray = JSONArray().apply {
            finalSteps.forEach { step ->
                put(JSONObject().apply {
                    put("stepIndex", step.stepIndex)
                    put("actionType", step.actionType)
                    put("targetText", step.targetText ?: "")
                    put("targetViewId", step.targetViewId ?: "")
                    put("inputPayload", step.inputPayload ?: "")
                    put("descriptionBn", step.descriptionBengali)
                    put("delayAfterMs", step.delayAfterMs)
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
            description = "Imported from YouTube Tutorial ($trimmedUrl)",
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
            status = "ACTIVE",
            parametersSchemaJson = JSONArray(paramsSchema).toString()
        )

        if (skillRepo != null) {
            skillRepo.saveSkillWithSteps(entity, finalSteps)
        }

        result.put("success", true)
        result.put("status", "SUCCESS")
        result.put("skillId", skillId)
        result.put("videoId", videoId)
        result.put("skillName", finalTitle)
        result.put("skillNameBengali", finalTitle)
        result.put("stepCount", finalSteps.size)
        result.put("parameters", JSONArray(paramsSchema))
        result.put("steps", stepsJsonArray)
        result.put(
            "summaryBengali",
            "ইউটিউব টিউটোরিয়াল '$finalTitle' থেকে ${finalSteps.size}টি প্রকৃত ধাপ বিশ্লেষণ করে সফলভাবে স্কিল সংরক্ষণ করা হয়েছে।"
        )

        result
    }

    private fun tryFetchPublicTranscript(videoId: String): String? {
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

        val textChunks = Regex("""<text[^>]*>([^<]+)</text>""").findAll(capResp.body)
            .map {
                it.groupValues[1]
                    .replace("&amp;", "&")
                    .replace("&#39;", "'")
                    .replace("&quot;", "\"")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&#10;", "\n")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .toList()
        return if (textChunks.isNotEmpty()) textChunks.joinToString("\n") else null
    }

    fun extractActionStepsFromText(text: String): List<JarvisSkillStepEntity> {
        val steps = mutableListOf<JarvisSkillStepEntity>()
        val rawLines = text.split('\n', ';', '।')
            .map { it.replace(Regex("""^\s*(?:\d+[.)০-৯]*|[-*•])\s*"""), "").trim() }
            .filter { it.length >= 4 }

        for (line in rawLines) {
            val step = inferStepFromLine(line, steps.size + 1)
            if (step != null) {
                steps.add(step)
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
