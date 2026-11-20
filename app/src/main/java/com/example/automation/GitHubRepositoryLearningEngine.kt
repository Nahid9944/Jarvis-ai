package com.example.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.data.repository.JarvisSkillRepository
import com.example.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class HttpFetchResult(
    val statusCode: Int,
    val body: String = "",
    val error: String? = null
)

/**
 * Full GitHub Project Intelligence & Capability Learning Engine for NAHID JARVIS AI.
 * - Inspects real GitHub REST API metadata, file tree, manifests, build configs, LICENSE, and key source files.
 * - Performs Google Play & security audit (flags DCL, unsafe eval/exec, broad storage perms, hardcoded secrets).
 * - Compares discovered repository capabilities against NAHID AI's ToolRegistry.
 * - Produces both Safe Declarative Skill Imports AND Developer Feature Integration Proposals.
 * - Never dynamically executes downloaded code (.dex/.jar/.so) and never fabricates repo contents.
 */
object GitHubRepositoryLearningEngine {

    private val GITHUB_REPO_REGEX = Regex(
        "^https?:\\/\\/github\\.com\\/([a-zA-Z0-9_.-]+)\\/([a-zA-Z0-9_.-]+)(?:\\/.*)?$",
        RegexOption.IGNORE_CASE
    )

    internal var authTokenProvider: (Context?) -> String = { ctx ->
        if (ctx == null) {
            ""
        } else {
            try {
                com.example.ai.AiSettingsRepository(ctx.applicationContext).getGitHubToken()
            } catch (_: Exception) {
                ""
            }
        }
    }

    internal var httpFetcherWithHeaders: ((String, Map<String, String>) -> HttpFetchResult)? = null

    internal var httpFetcher: (String) -> HttpFetchResult = { urlString ->
        fetchUrlReal(urlString, emptyMap())
    }

    internal var networkAvailabilityCheck: (Context?) -> Boolean = { ctx ->
        if (ctx == null) {
            true
        } else {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val net = cm?.activeNetwork
                val caps = if (net != null) cm.getNetworkCapabilities(net) else null
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (_: Exception) {
                true
            }
        }
    }

    private fun performFetch(urlString: String, token: String): HttpFetchResult {
        val headers = if (token.isNotBlank() && urlString.startsWith("https://api.github.com")) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
        httpFetcherWithHeaders?.let { return it(urlString, headers) }
        return if (headers.isEmpty()) httpFetcher(urlString) else fetchUrlReal(urlString, headers)
    }

    private fun fetchUrlReal(urlString: String, extraHeaders: Map<String, String> = emptyMap()): HttpFetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Nahid-Jarvis-AI-Android")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
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

    fun isValidGitHubUrl(url: String): Boolean = GITHUB_REPO_REGEX.matches(url.trim())

    fun extractOwnerAndRepo(url: String): Pair<String, String>? {
        val match = GITHUB_REPO_REGEX.find(url.trim()) ?: return null
        val owner = match.groupValues.getOrNull(1) ?: return null
        val repo = match.groupValues.getOrNull(2)?.removeSuffix(".git") ?: return null
        return Pair(owner, repo)
    }

    internal data class SecurityAuditReport(
        val riskLevel: String,
        val blockedByPlayOrSecurityPolicy: Boolean,
        val findings: List<String>
    )

    internal fun scanSourceForSecurityAndPlayRisks(
        fileContents: Map<String, String>,
        manifestPermissions: List<String>
    ): SecurityAuditReport {
        val findings = mutableListOf<String>()
        var blocker = false

        val criticalChecks = listOf(
            Regex("""\bDexClassLoader\b|\bPathClassLoader\b|\bdalvik\.system\b""") to
                "CRITICAL: Dynamic Code Loading (DCL / DexClassLoader) detected — prohibited by Google Play Policy.",
            Regex("""Runtime\.getRuntime\(\)\.exec\(""") to
                "HIGH: Shell process execution detected (Runtime.exec).",
            Regex("""\beval\s*\(|\bnew\s+Function\s*\(""") to
                "HIGH: Unsafe dynamic script evaluation (eval/Function) detected.",
            Regex("""AIza[0-9A-Za-z_-]{35}|ghp_[0-9A-Za-z]{20,}|sk-[0-9A-Za-z]{20,}""") to
                "HIGH: Potential hardcoded API key or token detected in source."
        )

        for ((path, content) in fileContents) {
            for ((regex, msg) in criticalChecks) {
                if (regex.containsMatchIn(content)) {
                    findings.add("$path -> $msg")
                    if (msg.startsWith("CRITICAL")) blocker = true
                }
            }
        }

        val restrictedPerms = setOf("MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "QUERY_ALL_PACKAGES")
        manifestPermissions.filter { it in restrictedPerms }.forEach { perm ->
            findings.add("AndroidManifest.xml -> Restricted/sensitive Google Play permission: $perm")
        }

        val riskLevel = when {
            blocker -> "CRITICAL"
            findings.isNotEmpty() -> "HIGH"
            else -> "LOW"
        }
        return SecurityAuditReport(riskLevel, blocker, findings)
    }

    internal fun detectCapabilitiesFromSources(
        fileContents: Map<String, String>,
        treePaths: List<String>,
        readmeContent: String,
        repoDescription: String
    ): List<String> {
        val combined = buildString {
            append(repoDescription).append('\n')
            append(readmeContent.take(4000)).append('\n')
            treePaths.take(60).forEach { append(it).append('\n') }
            fileContents.values.forEach { append(it.take(3000)).append('\n') }
        }.lowercase(Locale.ROOT)

        val signatures = linkedMapOf(
            "voice_recognition" to listOf("speechrecognizer", "whisper", "vosk", "stt", "wakeword", "porcupine"),
            "accessibility_automation" to listOf("accessibilityservice", "accessibilitynodeinfo", "uiautomator"),
            "screen_vision_ocr" to listOf("mediaprojection", "textrecognizer", "mlkit", "tesseract", "opencv"),
            "web_scraping_research" to listOf("jsoup", "playwright", "puppeteer", "selenium", "tavily", "beautifulsoup"),
            "camera_multimodal" to listOf("camerax", "camera2", "imageanalysis", "videocapture"),
            "maps_geolocation" to listOf("fusedlocation", "haversine", "googlemaps", "osmdroid", "geocoder"),
            "notification_intelligence" to listOf("notificationlistenerservice", "remoteinput", "statusbarevent"),
            "background_scheduling" to listOf("workmanager", "alarmmanager", "foregroundservice"),
            "local_database_rag" to listOf("room", "sqlite", "faiss", "embedding", "vector")
        )

        return signatures.entries.filter { (_, kws) ->
            kws.any { combined.contains(it) }
        }.map { it.key }
    }

    internal fun compareCapabilitiesWithToolRegistry(discoveredCapabilities: List<String>): JSONObject {
        val allTools = ToolRegistry.getAll()
        val toolKeywords = allTools.map { "${it.name} ${it.description}".lowercase(Locale.ROOT) }

        val covered = mutableListOf<String>()
        val gapsOrEnhancements = mutableListOf<String>()

        for (cap in discoveredCapabilities) {
            val probeWords = when (cap) {
                "voice_recognition" -> listOf("voice", "speech", "wake")
                "accessibility_automation" -> listOf("accessibility", "clickui", "playbook")
                "screen_vision_ocr" -> listOf("screen", "ocr", "vision")
                "web_scraping_research" -> listOf("search", "web", "knowledge")
                "camera_multimodal" -> listOf("camera", "vision")
                "maps_geolocation" -> listOf("location", "gps", "distance", "route")
                "notification_intelligence" -> listOf("notification")
                "background_scheduling" -> listOf("alarm", "schedule", "task")
                "local_database_rag" -> listOf("memory", "note")
                else -> listOf(cap)
            }
            val matchCount = toolKeywords.count { desc -> probeWords.any { desc.contains(it) } }
            if (matchCount >= 2) {
                covered.add("$cap ($matchCount matching NAHID AI tools)")
            } else {
                gapsOrEnhancements.add(cap)
            }
        }

        return JSONObject().apply {
            put("discoveredCapabilities", JSONArray(discoveredCapabilities))
            put("alreadySupportedByNahidAi", JSONArray(covered))
            put("novelOrGapCapabilities", JSONArray(gapsOrEnhancements))
            put("nahidToolCountCompared", allTools.size)
        }
    }

    suspend fun importFromRepository(
        context: Context? = null,
        repoUrl: String,
        branch: String = "",
        skillRepo: JarvisSkillRepository? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        val trimmed = repoUrl.trim()

        if (!isValidGitHubUrl(trimmed)) {
            result.put("success", false)
            result.put("status", "FAILED")
            result.put("error", "INVALID_GITHUB_URL")
            result.put(
                "summaryBengali",
                "প্রদত্ত লিঙ্কটি সঠিক গিটহাব রিপোজিটরি লিঙ্ক নয়। (উদাহরণ: https://github.com/owner/repo)"
            )
            return@withContext result
        }

        if (!networkAvailabilityCheck(context)) {
            result.put("success", false)
            result.put("status", "OFFLINE_LIMITED")
            result.put("error", "NO_INTERNET")
            result.put(
                "summaryBengali",
                "ইন্টারনেট সংযোগ না থাকায় GitHub রিপোজিটরি যাচাই ও ইমপোর্ট করা সম্ভব হয়নি।"
            )
            return@withContext result
        }

        val (owner, repo) = extractOwnerAndRepo(trimmed)!!
        val authToken = authTokenProvider(context).trim()
        val repoApiUrl = "https://api.github.com/repos/$owner/$repo"
        val metaResp = performFetch(repoApiUrl, authToken)

        when (metaResp.statusCode) {
            -1 -> {
                result.put("success", false)
                result.put("status", "FAILED")
                result.put("error", "NETWORK_ERROR")
                result.put(
                    "summaryBengali",
                    "GitHub API-তে সংযোগ ব্যর্থ হয়েছে (${metaResp.error ?: "Network unreachable"})।"
                )
                return@withContext result
            }
            404 -> {
                result.put("success", false)
                result.put("status", "FAILED")
                result.put("error", "REPO_NOT_FOUND")
                result.put(
                    "summaryBengali",
                    "GitHub-এ '$owner/$repo' রিপোজিটরি পাওয়া যায়নি (404 Not Found) বা এটি প্রাইভেট।"
                )
                return@withContext result
            }
            403, 429 -> {
                result.put("success", false)
                result.put("status", "FAILED")
                result.put("error", "RATE_LIMITED")
                result.put(
                    "summaryBengali",
                    "GitHub API রেট-লিমিট বা অ্যাক্সেস সীমা অতিক্রম করেছে (${metaResp.statusCode})। কিছুক্ষণ পর আবার চেষ্টা করুন।"
                )
                return@withContext result
            }
            !in 200..299 -> {
                result.put("success", false)
                result.put("status", "FAILED")
                result.put("error", "HTTP_${metaResp.statusCode}")
                result.put(
                    "summaryBengali",
                    "GitHub API থেকে ত্রুটি পাওয়া গেছে (HTTP ${metaResp.statusCode})।"
                )
                return@withContext result
            }
        }

        val metaJson = try {
            JSONObject(metaResp.body)
        } catch (e: Exception) {
            result.put("success", false)
            result.put("status", "FAILED")
            result.put("error", "INVALID_API_RESPONSE")
            result.put("summaryBengali", "GitHub API রেসপন্স পার্স করা যায়নি।")
            return@withContext result
        }

        val defaultBranch = branch.ifBlank { metaJson.optString("default_branch", "main").ifBlank { "main" } }
        val repoDescription = metaJson.optString("description", "").takeIf { it != "null" }.orEmpty()
        val primaryLang = metaJson.optString("language", "").takeIf { it != "null" }.orEmpty()
        val licenseSpdx = metaJson.optJSONObject("license")?.optString("spdx_id", "")?.takeIf { it.isNotBlank() && it != "NOASSERTION" }
            ?: ""

        // 2. Inspect actual repository file tree
        val treeResp = performFetch("https://api.github.com/repos/$owner/$repo/git/trees/$defaultBranch?recursive=1", authToken)
        val treePaths = mutableListOf<String>()
        if (treeResp.statusCode in 200..299) {
            try {
                val treeArr = JSONObject(treeResp.body).optJSONArray("tree")
                if (treeArr != null) {
                    for (i in 0 until treeArr.length()) {
                        val p = treeArr.optJSONObject(i)?.optString("path", "").orEmpty()
                        if (p.isNotBlank()) treePaths.add(p)
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Classify project compatibility cleanly from real files
        val hasManifest = treePaths.any { it.endsWith("AndroidManifest.xml", ignoreCase = true) }
        val hasGradle = treePaths.any { it.endsWith("build.gradle", true) || it.endsWith("build.gradle.kts", true) }
        val playbookFile = treePaths.firstOrNull {
            val l = it.lowercase(Locale.ROOT)
            l == "jarvis-skill.json" || l == "playbook.json" || l.endsWith("/jarvis-skill.json") || l.endsWith("/playbook.json")
        }
        val hasKotlinOrJava = primaryLang.equals("Kotlin", true) || primaryLang.equals("Java", true) ||
            treePaths.any { it.endsWith(".kt", true) || it.endsWith(".java", true) }
        val isIosOrDesktopOnly = (primaryLang.equals("Swift", true) || primaryLang.equals("Objective-C", true) ||
            treePaths.any { it.endsWith(".xcodeproj") || it.endsWith("Package.swift") || it.endsWith(".vcxproj") }) &&
            !hasManifest && !hasGradle && playbookFile == null

        if (isIosOrDesktopOnly) {
            result.put("success", false)
            result.put("status", "UNSUPPORTED")
            result.put("runtimeCompatible", false)
            result.put("owner", owner)
            result.put("repo", repo)
            result.put("branch", defaultBranch)
            result.put("language", primaryLang)
            result.put(
                "summaryBengali",
                "'$owner/$repo' রিপোজিটরিটি $primaryLang / ডেস্কটপ বা iOS ভিত্তিক, যা Android JARVIS রানটাইমে সরাসরি চালানো সম্ভব নয় (UNSUPPORTED)।"
            )
            return@withContext result
        }

        // 4. Deep inspection: README, AndroidManifest, LICENSE, build configs, and top source files
        var readmeContent = ""
        val readmePath = treePaths.firstOrNull { it.equals("README.md", ignoreCase = true) } ?: "README.md"
        val readmeResp = performFetch("https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$readmePath", "")
        if (readmeResp.statusCode in 200..299) {
            readmeContent = YouTubeTutorialImporter.sanitizeUntrustedTranscript(readmeResp.body.take(8000))
        }

        val manifestPath = treePaths.firstOrNull { it.endsWith("AndroidManifest.xml", ignoreCase = true) }
        val manifestPermissions = mutableListOf<String>()
        if (manifestPath != null) {
            val manifestResp = performFetch("https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$manifestPath", "")
            if (manifestResp.statusCode in 200..299 && manifestResp.body.isNotBlank()) {
                Regex("""android\.permission\.([A-Z0-9_]+)""").findAll(manifestResp.body).forEach { m ->
                    val perm = m.groupValues.getOrNull(1).orEmpty()
                    if (perm.isNotBlank() && perm !in manifestPermissions) manifestPermissions.add(perm)
                }
            }
        }

        // Fetch LICENSE file if not reported in API metadata
        var resolvedLicense = licenseSpdx
        val licensePath = treePaths.firstOrNull { it.substringAfterLast('/').startsWith("LICENSE", ignoreCase = true) }
        if (resolvedLicense.isBlank() && licensePath != null) {
            val licResp = performFetch("https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$licensePath", "")
            if (licResp.statusCode in 200..299 && licResp.body.isNotBlank()) {
                val head = licResp.body.take(300)
                resolvedLicense = when {
                    head.contains("MIT License", true) -> "MIT"
                    head.contains("Apache License", true) -> "Apache-2.0"
                    head.contains("GNU GENERAL PUBLIC LICENSE", true) -> "GPL"
                    else -> "CUSTOM_OR_OTHER"
                }
            }
        }

        // Fetch up to 5 key build / source files (.kt, .java, .py, .js, .ts, build.gradle*, package.json)
        val candidateSourceFiles = treePaths.filter { path ->
            val l = path.lowercase(Locale.ROOT)
            l.endsWith(".kt") || l.endsWith(".java") || l.endsWith(".py") ||
                l.endsWith(".js") || l.endsWith(".ts") ||
                l.endsWith("build.gradle") || l.endsWith("build.gradle.kts") || l.endsWith("package.json")
        }.take(5)

        val inspectedSourceContents = linkedMapOf<String, String>()
        for (srcPath in candidateSourceFiles) {
            val srcResp = performFetch("https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$srcPath", "")
            if (srcResp.statusCode in 200..299 && srcResp.body.isNotBlank()) {
                inspectedSourceContents[srcPath] = srcResp.body.take(6000)
            }
        }

        val securityAudit = scanSourceForSecurityAndPlayRisks(inspectedSourceContents, manifestPermissions)
        val discoveredCapabilities = detectCapabilitiesFromSources(
            fileContents = inspectedSourceContents,
            treePaths = treePaths,
            readmeContent = readmeContent,
            repoDescription = repoDescription
        )
        val capabilityDiff = compareCapabilitiesWithToolRegistry(discoveredCapabilities)
        val buildDepsDetected = extractBuildDependencies(inspectedSourceContents)

        val integrationProposal = JSONObject().apply {
            put("repo", "$owner/$repo")
            put("license", resolvedLicense.ifBlank { "UNSPECIFIED" })
            put("securityRiskLevel", securityAudit.riskLevel)
            put("securityFindings", JSONArray(securityAudit.findings))
            put("detectedDependencies", JSONArray(buildDepsDetected))
            put("inspectedFiles", JSONArray(inspectedSourceContents.keys.toList()))
            put("capabilityComparison", capabilityDiff)
            put("dynamicCodeExecutionBlocked", true)
            put(
                "proposalSummaryBn",
                if (securityAudit.blockedByPlayOrSecurityPolicy) {
                    "সতর্কতা: এই রিপোজিটরিতে Dynamic Code Loading বা উচ্চ ঝুঁকিপূর্ণ কোড পাওয়া গেছে; নিরাপদ ডিভাইস এক্সিকিউশনের জন্য স্বয়ংক্রিয় ইমপোর্ট ব্লক করা হয়েছে।"
                } else {
                    "রিপোজিটরি '$owner/$repo' ($primaryLang, লাইসেন্স: ${resolvedLicense.ifBlank { "অনির্দিষ্ট" }}) থেকে ${discoveredCapabilities.size}টি সক্ষমতা ও ${buildDepsDetected.size}টি ডিপেনডেন্সি শনাক্ত হয়েছে; NAHID AI মডিউলে নিরাপদ অ্যাডাপ্টার হিসেবে ইন্টিগ্রেশন প্রস্তাব প্রস্তুত।"
                }
            )
        }

        // Security blocker: Never import executable playbooks containing DCL
        if (securityAudit.blockedByPlayOrSecurityPolicy) {
            result.put("success", false)
            result.put("status", "BLOCKED")
            result.put("error", "SECURITY_POLICY_BLOCKED")
            result.put("owner", owner)
            result.put("repo", repo)
            result.put("license", resolvedLicense)
            result.put("securityAudit", JSONObject().apply {
                put("riskLevel", securityAudit.riskLevel)
                put("blocked", true)
                put("findings", JSONArray(securityAudit.findings))
            })
            result.put("integrationProposal", integrationProposal)
            result.put(
                "summaryBengali",
                "'$owner/$repo' রিপোজিটরিতে Google Play নীতিমালা ও নিরাপত্তার পরিপন্থী ডায়নামিক কোড লোডিং (DCL) শনাক্ত হওয়ায় ইমপোর্ট ব্লক করা হয়েছে।"
            )
            return@withContext result
        }

        val parsedSteps = mutableListOf<JarvisSkillStepEntity>()
        var targetPackage = ""
        var skillName = "$repo Skill"
        var skillNameBn = "$repo অটোমেশন স্কিল"

        if (playbookFile != null) {
            val pbResp = performFetch("https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$playbookFile", "")
            if (pbResp.statusCode in 200..299) {
                try {
                    val pbJson = JSONObject(pbResp.body)
                    skillName = pbJson.optString("name", skillName)
                    skillNameBn = pbJson.optString("nameBengali", skillNameBn)
                    targetPackage = pbJson.optString("targetPackageName", "")
                    val stepsArr = pbJson.optJSONArray("steps")
                    if (stepsArr != null) {
                        for (i in 0 until stepsArr.length()) {
                            val sObj = stepsArr.optJSONObject(i) ?: continue
                            val act = sObj.optString("actionType", "CLICK").uppercase(Locale.ROOT)
                            val targetTxt = sObj.optString("targetText", "").ifBlank { null }
                            val targetId = sObj.optString("targetViewId", "").ifBlank { null }
                            val payload = sObj.optString("inputPayload", "").ifBlank { null }
                            val desc = sObj.optString("descriptionBengali", "$act ধাপ").ifBlank { "$act ধাপ" }
                            parsedSteps.add(
                                JarvisSkillStepEntity(
                                    skillId = "",
                                    stepIndex = parsedSteps.size + 1,
                                    actionType = act,
                                    targetViewId = targetId,
                                    targetText = targetTxt,
                                    inputPayload = payload,
                                    descriptionBengali = desc,
                                    delayAfterMs = sObj.optLong("delayAfterMs", 600L)
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // If no declarative JSON playbook, extract real imperative steps from sanitized README if Android-relevant
        if (parsedSteps.isEmpty() && readmeContent.isNotBlank()) {
            val inferred = YouTubeTutorialImporter.extractActionStepsFromText(readmeContent)
            parsedSteps.addAll(inferred)
        }

        val isAndroidCompatible = hasManifest || hasGradle || hasKotlinOrJava || playbookFile != null || parsedSteps.isNotEmpty()
        if (!isAndroidCompatible || parsedSteps.isEmpty()) {
            result.put("success", false)
            result.put("status", if (isAndroidCompatible) "FAILED" else "UNSUPPORTED")
            result.put("runtimeCompatible", isAndroidCompatible)
            result.put("owner", owner)
            result.put("repo", repo)
            result.put("branch", defaultBranch)
            result.put("language", primaryLang)
            result.put("license", resolvedLicense)
            result.put("fileCount", treePaths.size)
            result.put("hasReadme", readmeContent.isNotBlank())
            result.put("inspectedFiles", JSONArray(inspectedSourceContents.keys.toList()))
            result.put("integrationProposal", integrationProposal)
            result.put("capabilityDiff", capabilityDiff)
            result.put("error", if (isAndroidCompatible) "NO_EXECUTABLE_STEPS" else "UNSUPPORTED_RUNTIME")
            result.put(
                "summaryBengali",
                if (!isAndroidCompatible) {
                    "'$owner/$repo' রিপোজিটরিতে কোনো অ্যান্ড্রয়েড ম্যানিফেস্ট, Gradle বা কার্যকর অটোমেশন প্লেবুক পাওয়া যায়নি।"
                } else {
                    "'$owner/$repo' ($primaryLang) রিপোজিটরি ও সোর্স ফাইল বিশ্লেষণ সম্পন্ন হয়েছে (${treePaths.size} ফাইল, ডেভেলপার ইন্টিগ্রেশন প্রস্তাব প্রস্তুত), তবে সরাসরি চালানোর মতো প্লেবুক ধাপ (jarvis-skill.json) পাওয়া যায়নি।"
                }
            )
            return@withContext result
        }

        val repoSkillId = "gh_${owner.lowercase(Locale.ROOT)}_${repo.lowercase(Locale.ROOT)}"
        val finalSteps = parsedSteps.mapIndexed { idx, step ->
            step.copy(skillId = repoSkillId, stepIndex = idx + 1)
        }
        val paramsSchema = JarvisSkillRecorder.extractParameterNames(finalSteps)
        val stepsJsonArr = JSONArray().apply {
            finalSteps.forEach { step ->
                put(JSONObject().apply {
                    put("stepIndex", step.stepIndex)
                    put("actionType", step.actionType)
                    put("targetText", step.targetText ?: "")
                    put("targetViewId", step.targetViewId ?: "")
                    put("inputPayload", step.inputPayload ?: "")
                    put("descriptionBn", step.descriptionBengali)
                })
            }
        }
        val triggers = JSONArray().apply {
            put(skillNameBn)
            put(skillName)
            put("run $repo")
            put("চালু করো $repo")
        }

        val entity = JarvisSkillEntity(
            skillId = repoSkillId,
            name = skillName,
            nameBengali = skillNameBn,
            description = repoDescription.ifBlank { "Imported from GitHub: $owner/$repo ($defaultBranch)" },
            triggerPhrasesJson = triggers.toString(),
            targetPackageName = targetPackage,
            targetAppName = targetPackage.substringAfterLast('.').ifBlank { repo },
            category = if (playbookFile != null) "AUTOMATION" else "CUSTOM",
            riskLevel = securityAudit.riskLevel,
            requiresConfirmation = securityAudit.riskLevel != "LOW",
            requiredPermissionsJson = "[\"ACCESSIBILITY_SERVICE\"]",
            stepsJson = stepsJsonArr.toString(),
            stepCount = finalSteps.size,
            sourceType = "GITHUB_IMPORT",
            status = "ACTIVE",
            parametersSchemaJson = JSONArray(paramsSchema).toString()
        )

        if (skillRepo != null) {
            skillRepo.saveSkillWithSteps(entity, finalSteps)
        }

        result.put("success", true)
        result.put("status", "SUCCESS")
        result.put("runtimeCompatible", true)
        result.put("skillId", repoSkillId)
        result.put("owner", owner)
        result.put("repo", repo)
        result.put("branch", defaultBranch)
        result.put("language", primaryLang)
        result.put("license", resolvedLicense)
        result.put("fileCount", treePaths.size)
        result.put("skillName", skillName)
        result.put("skillNameBengali", skillNameBn)
        result.put("stepCount", finalSteps.size)
        result.put("parameters", JSONArray(paramsSchema))
        result.put("steps", stepsJsonArr)
        result.put("hasReadme", readmeContent.isNotBlank())
        result.put("authenticatedRequest", authToken.isNotBlank())
        result.put("manifestPermissions", JSONArray(manifestPermissions))
        result.put("inspectedFiles", JSONArray(inspectedSourceContents.keys.toList()))
        result.put("capabilityDiff", capabilityDiff)
        result.put("securityAudit", JSONObject().apply {
            put("riskLevel", securityAudit.riskLevel)
            put("blocked", securityAudit.blockedByPlayOrSecurityPolicy)
            put("findings", JSONArray(securityAudit.findings))
        })
        result.put("integrationProposal", integrationProposal)
        result.put(
            "summaryBengali",
            "গিটহাব রিপোজিটরি ($owner/$repo, ব্রাঞ্চ: $defaultBranch, ${treePaths.size} ফাইল, লাইসেন্স: ${resolvedLicense.ifBlank { "অনির্দিষ্ট" }}) গভীরভাবে যাচাই করে ‘$skillNameBn’ স্কিল ইমপোর্ট ও ইন্টিগ্রেশন প্রস্তাব প্রস্তুত (${finalSteps.size} ধাপ)।"
        )

        result
    }

    private fun extractBuildDependencies(fileContents: Map<String, String>): List<String> {
        val deps = linkedSetOf<String>()
        for ((path, content) in fileContents) {
            if (path.endsWith("build.gradle", true) || path.endsWith("build.gradle.kts", true)) {
                Regex("""(?:implementation|api|ksp)\s*\(\s*["']([^"']+)["']\s*\)""").findAll(content).forEach {
                    deps.add(it.groupValues[1])
                }
            } else if (path.endsWith("package.json", true)) {
                Regex(""""([@a-zA-Z0-9_./-]+)"\s*:\s*"[\^~]?[0-9]""").findAll(content).forEach {
                    deps.add(it.groupValues[1])
                }
            }
        }
        return deps.take(15).toList()
    }
}
