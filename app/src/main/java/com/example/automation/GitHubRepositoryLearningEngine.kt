package com.example.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

data class HttpFetchResult(
    val statusCode: Int,
    val body: String = "",
    val error: String? = null
)

/**
 * Real GitHub Repository Learning Engine for NAHID JARVIS AI.
 * Inspects real GitHub REST API metadata, file tree, manifests, declarative playbooks, and README.md.
 * Never fabricates repository playbooks from repo names when unreachable or incompatible.
 */
object GitHubRepositoryLearningEngine {

    private val GITHUB_REPO_REGEX = Regex(
        "^https?:\\/\\/github\\.com\\/([a-zA-Z0-9_.-]+)\\/([a-zA-Z0-9_.-]+)(?:\\/.*)?$",
        RegexOption.IGNORE_CASE
    )

    internal var httpFetcher: (String) -> HttpFetchResult = { urlString ->
        fetchUrlReal(urlString)
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

    private fun fetchUrlReal(urlString: String): HttpFetchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 6000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Nahid-Jarvis-AI-Android")
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
        val repoApiUrl = "https://api.github.com/repos/$owner/$repo"
        val metaResp = httpFetcher(repoApiUrl)

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

        // 2. Inspect actual repository file tree
        val treeResp = httpFetcher("https://api.github.com/repos/$owner/$repo/git/trees/$defaultBranch?recursive=1")
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

        // 4. Fetch declarative playbook or README.md
        var readmeContent = ""
        val readmePath = treePaths.firstOrNull { it.equals("README.md", ignoreCase = true) } ?: "README.md"
        val readmeResp = httpFetcher("https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$readmePath")
        if (readmeResp.statusCode in 200..299) {
            readmeContent = readmeResp.body.take(8000)
        }

        val parsedSteps = mutableListOf<JarvisSkillStepEntity>()
        var targetPackage = ""
        var skillName = "$repo Skill"
        var skillNameBn = "$repo অটোমেশন স্কিল"

        if (playbookFile != null) {
            val pbResp = httpFetcher("https://raw.githubusercontent.com/$owner/$repo/$defaultBranch/$playbookFile")
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

        // If no declarative JSON playbook, extract real imperative steps from README if Android-relevant
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
            result.put("fileCount", treePaths.size)
            result.put("hasReadme", readmeContent.isNotBlank())
            result.put("error", if (isAndroidCompatible) "NO_EXECUTABLE_STEPS" else "UNSUPPORTED_RUNTIME")
            result.put(
                "summaryBengali",
                if (!isAndroidCompatible) {
                    "'$owner/$repo' রিপোজিটরিতে কোনো অ্যান্ড্রয়েড ম্যানিফেস্ট, Gradle বা কার্যকর অটোমেশন প্লেবুক পাওয়া যায়নি।"
                } else {
                    "'$owner/$repo' ($primaryLang) রিপোজিটরি বিশ্লেষণ করা হয়েছে (${treePaths.size} ফাইল), তবে সরাসরি চালানোর মতো প্লেবুক বা ধাপ (jarvis-skill.json / playbook.json) পাওয়া যায়নি।"
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
            riskLevel = "MEDIUM",
            requiresConfirmation = false,
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
        result.put("fileCount", treePaths.size)
        result.put("skillName", skillName)
        result.put("skillNameBengali", skillNameBn)
        result.put("stepCount", finalSteps.size)
        result.put("parameters", JSONArray(paramsSchema))
        result.put("steps", stepsJsonArr)
        result.put("hasReadme", readmeContent.isNotBlank())
        result.put(
            "summaryBengali",
            "গিটহাব রিপোজিটরি ($owner/$repo, ব্রাঞ্চ: $defaultBranch, ${treePaths.size} ফাইল) যাচাই করে ‘$skillNameBn’ স্কিল ইমপোর্ট সম্পন্ন (${finalSteps.size} ধাপ)।"
        )

        result
    }
}
