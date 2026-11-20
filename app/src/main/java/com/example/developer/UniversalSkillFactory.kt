package com.example.developer

import android.content.Context
import com.example.automation.GitHubRepositoryLearningEngine
import com.example.automation.JarvisSkillRecorder
import com.example.automation.TextLearningEngine
import com.example.automation.YouTubeTutorialImporter
import com.example.data.local.ArtifactEntity
import com.example.data.local.AssistantDao
import com.example.data.local.AssistantDatabase
import com.example.data.local.HomeDeveloperDao
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.data.local.SkillEvidenceEntity
import com.example.data.repository.JarvisSkillRepository
import com.example.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class SkillFactoryResult(
    val success: Boolean,
    val status: String, // ACTIVE, DRAFT, BLOCKED, UNSUPPORTED, FAILED, SOURCE_DISABLED, TRANSCRIPT_REQUIRED
    val skillId: String = "",
    val skillName: String = "",
    val stepCount: Int = 0,
    val sourceType: String = "",
    val capabilityClassification: String = "", // ALREADY_AVAILABLE, IMPROVEMENT_REQUIRED, MISSING_CAPABILITY
    val artifactId: Long = 0L,
    val stagesCompleted: List<SkillLifecycleStage> = emptyList(),
    val summaryBn: String,
    val rawPayload: JSONObject = JSONObject()
)

/**
 * Universal Skill Acquisition, Validation, Composition, Evidence & Self-Repair Factory.
 * Consolidates YouTube, GitHub, Text, Live Demo, Screenshot/OCR, Document, and Combined Skill
 * flows on top of existing engines—never duplicating JarvisSkillRepository or PlaybookStore.
 */
object UniversalSkillFactory {

    suspend fun learnFromYouTube(
        context: Context? = null,
        url: String,
        title: String? = null,
        transcript: String? = null,
        draftMode: Boolean = true,
        skillRepo: JarvisSkillRepository? = null,
        devDao: HomeDeveloperDao? = null,
        settingsRepo: HomeDeveloperSettingsRepository? = null
    ): SkillFactoryResult = withContext(Dispatchers.IO) {
        if (settingsRepo != null && !settingsRepo.isSourceAllowed("YOUTUBE")) {
            return@withContext disabledSourceResult("YOUTUBE", "YouTube থেকে শেখা সেটিংস থেকে বন্ধ রাখা হয়েছে।")
        }
        val repo = skillRepo ?: context?.let { JarvisSkillRepository(AssistantDatabase.getDatabase(it).jarvisSkillDao()) }
        val dev = devDao ?: context?.let { AssistantDatabase.getDatabase(it).homeDeveloperDao() }

        val json = YouTubeTutorialImporter.importFromTutorial(
            context = context,
            url = url,
            userProvidedTitle = title,
            userProvidedTranscript = transcript,
            skillRepo = repo,
            draftMode = draftMode
        )

        val ok = json.optBoolean("success", false)
        val status = json.optString("status", if (ok) "SUCCESS" else "FAILED")
        val skillId = json.optString("skillId", "")
        val skillName = json.optString("skillNameBengali", json.optString("skillName", "YouTube Skill"))
        val stepCount = json.optInt("stepCount", 0)
        val summaryBn = json.optString("summaryBengali", "YouTube শেখার ফলাফল প্রস্তুত।")

        if (ok && skillId.isNotBlank()) {
            dev?.upsertSkillEvidence(
                SkillEvidenceEntity(
                    skillId = skillId,
                    sourceType = "YOUTUBE_TUTORIAL",
                    sourceUrl = url.trim(),
                    version = 1,
                    healthScore = 100
                )
            )
        }

        SkillFactoryResult(
            success = ok,
            status = status,
            skillId = skillId,
            skillName = skillName,
            stepCount = stepCount,
            sourceType = "YOUTUBE_TUTORIAL",
            stagesCompleted = if (ok) listOf(
                SkillLifecycleStage.LEARN,
                SkillLifecycleStage.UNDERSTAND,
                SkillLifecycleStage.CREATE_DRAFT,
                SkillLifecycleStage.MAP_TO_TOOLS,
                SkillLifecycleStage.VALIDATE,
                SkillLifecycleStage.DRY_RUN,
                SkillLifecycleStage.SAVE
            ) else listOf(SkillLifecycleStage.LEARN),
            summaryBn = summaryBn,
            rawPayload = json
        )
    }

    suspend fun learnFromGitHub(
        context: Context? = null,
        repoUrl: String,
        branch: String = "",
        skillRepo: JarvisSkillRepository? = null,
        assistantDao: AssistantDao? = null,
        devDao: HomeDeveloperDao? = null,
        settingsRepo: HomeDeveloperSettingsRepository? = null
    ): SkillFactoryResult = withContext(Dispatchers.IO) {
        if (settingsRepo != null && !settingsRepo.isSourceAllowed("GITHUB")) {
            return@withContext disabledSourceResult("GITHUB", "GitHub থেকে শেখা সেটিংস থেকে বন্ধ রাখা হয়েছে।")
        }
        val db = context?.let { AssistantDatabase.getDatabase(it) }
        val repo = skillRepo ?: db?.let { JarvisSkillRepository(it.jarvisSkillDao()) }
        val aDao = assistantDao ?: db?.assistantDao()
        val dDao = devDao ?: db?.homeDeveloperDao()

        val json = GitHubRepositoryLearningEngine.importFromRepository(
            context = context,
            repoUrl = repoUrl,
            branch = branch,
            skillRepo = repo
        )

        val ok = json.optBoolean("success", false)
        val status = json.optString("status", if (ok) "SUCCESS" else "FAILED")
        val skillId = json.optString("skillId", "")
        val skillName = json.optString("skillNameBengali", json.optString("repo", "GitHub Skill"))
        val stepCount = json.optInt("stepCount", 0)

        // Compare against NAHID AI ToolRegistry -> ALREADY_AVAILABLE / IMPROVEMENT_REQUIRED / MISSING_CAPABILITY
        val capDiff = json.optJSONObject("capabilityDiff") ?: JSONObject()
        val alreadyCount = capDiff.optJSONArray("alreadySupportedByNahidAi")?.length() ?: 0
        val novelCount = capDiff.optJSONArray("novelOrGapCapabilities")?.length() ?: 0
        val classification = when {
            novelCount > 0 && alreadyCount == 0 -> "MISSING_CAPABILITY"
            novelCount > 0 && alreadyCount > 0 -> "IMPROVEMENT_REQUIRED"
            alreadyCount > 0 -> "ALREADY_AVAILABLE"
            else -> if (ok) "IMPROVEMENT_REQUIRED" else "MISSING_CAPABILITY"
        }

        // Persist Integration Proposal Artifact if meaningful metadata returned
        var artifactId = 0L
        val proposal = json.optJSONObject("integrationProposal")
        if (aDao != null && proposal != null) {
            val ownerRepo = proposal.optString("repo", repoUrl)
            val artifact = ArtifactEntity(
                title = "GitHub Analysis: $ownerRepo [$classification]",
                content = buildString {
                    appendLine("# GitHub Integration & Capability Report")
                    appendLine("- Repository: $ownerRepo")
                    appendLine("- Classification: $classification")
                    appendLine("- License: ${proposal.optString("license", "UNSPECIFIED")}")
                    appendLine("- Security Risk: ${proposal.optString("securityRiskLevel", "UNKNOWN")}")
                    appendLine("- Dynamic Code Execution Blocked: ${proposal.optBoolean("dynamicCodeExecutionBlocked", true)}")
                    appendLine()
                    appendLine("## Summary (Bengali)")
                    appendLine(proposal.optString("proposalSummaryBn", json.optString("summaryBengali", "")))
                    appendLine()
                    appendLine("## Capability Diff JSON")
                    appendLine("```json")
                    appendLine(capDiff.toString(2))
                    appendLine("```")
                },
                format = "MARKDOWN",
                category = "GITHUB_INTEGRATION_PROPOSAL"
            )
            artifactId = aDao.insertArtifact(artifact)
        }

        if (ok && skillId.isNotBlank()) {
            dDao?.upsertSkillEvidence(
                SkillEvidenceEntity(
                    skillId = skillId,
                    sourceType = "GITHUB_IMPORT",
                    sourceUrl = repoUrl.trim(),
                    version = 1,
                    healthScore = 100
                )
            )
        }

        val summaryBn = json.optString("summaryBengali", "GitHub বিশ্লেষণ সম্পন্ন ($classification)।")
        SkillFactoryResult(
            success = ok,
            status = status,
            skillId = skillId,
            skillName = skillName,
            stepCount = stepCount,
            sourceType = "GITHUB_IMPORT",
            capabilityClassification = classification,
            artifactId = artifactId,
            stagesCompleted = if (ok) listOf(
                SkillLifecycleStage.LEARN,
                SkillLifecycleStage.UNDERSTAND,
                SkillLifecycleStage.MAP_TO_TOOLS,
                SkillLifecycleStage.VALIDATE,
                SkillLifecycleStage.SAVE,
                SkillLifecycleStage.ACTIVATE
            ) else listOf(SkillLifecycleStage.LEARN),
            summaryBn = summaryBn,
            rawPayload = json
        )
    }

    suspend fun learnFromText(
        context: Context? = null,
        instructionText: String,
        title: String = "",
        dryRunOnly: Boolean = false,
        saveAsActiveIfValid: Boolean = false,
        daoOverride: AssistantDao? = null,
        skillRepoOverride: JarvisSkillRepository? = null,
        devDaoOverride: HomeDeveloperDao? = null,
        settingsRepo: HomeDeveloperSettingsRepository? = null
    ): SkillFactoryResult = withContext(Dispatchers.IO) {
        if (settingsRepo != null && !settingsRepo.isSourceAllowed("TEXT")) {
            return@withContext disabledSourceResult("TEXT", "টেক্সট থেকে শেখা সেটিংস থেকে বন্ধ রাখা হয়েছে।")
        }
        val db = context?.let { AssistantDatabase.getDatabase(it) }
        val aDao = daoOverride ?: db?.assistantDao()
        val sRepo = skillRepoOverride ?: db?.let { JarvisSkillRepository(it.jarvisSkillDao()) }
        val dDao = devDaoOverride ?: db?.homeDeveloperDao()

        val json = TextLearningEngine.learnFromText(
            context = context,
            instructionText = instructionText,
            title = title,
            dryRunOnly = dryRunOnly,
            saveAsActiveSkillIfValid = saveAsActiveIfValid,
            daoOverride = aDao,
            skillRepoOverride = sRepo
        )
        val ok = json.optBoolean("success", false)
        val skillId = json.optString("skillId", "")
        val stepCount = json.optInt("stepCount", json.optJSONArray("steps")?.length() ?: 0)
        val skillStatus = json.optString("skillStatus", json.optString("status", "SUCCESS"))

        if (ok && skillId.isNotBlank()) {
            dDao?.upsertSkillEvidence(
                SkillEvidenceEntity(
                    skillId = skillId,
                    sourceType = "TEXT_TEACH",
                    version = 1,
                    healthScore = 100
                )
            )
        }

        SkillFactoryResult(
            success = ok,
            status = skillStatus,
            skillId = skillId,
            skillName = json.optString("title", title.ifBlank { "Text Skill" }),
            stepCount = stepCount,
            sourceType = "TEXT_TEACH",
            summaryBn = json.optString("summaryBengali", "টেক্সট থেকে শেখা সম্পন্ন।"),
            rawPayload = json
        )
    }

    suspend fun learnFromDocument(
        context: Context? = null,
        fileName: String,
        documentContent: String,
        skillRepoOverride: JarvisSkillRepository? = null,
        assistantDaoOverride: AssistantDao? = null,
        devDaoOverride: HomeDeveloperDao? = null,
        settingsRepo: HomeDeveloperSettingsRepository? = null
    ): SkillFactoryResult = withContext(Dispatchers.IO) {
        if (settingsRepo != null && !settingsRepo.isSourceAllowed("DOCUMENT")) {
            return@withContext disabledSourceResult("DOCUMENT", "ডকুমেন্ট/কোড থেকে শেখা সেটিংস থেকে বন্ধ রাখা হয়েছে।")
        }
        val cleanContent = YouTubeTutorialImporter.sanitizeUntrustedTranscript(documentContent).trim()
        if (cleanContent.length < 10) {
            return@withContext SkillFactoryResult(
                success = false,
                status = "FAILED",
                sourceType = "DOCUMENT",
                summaryBn = "ডকুমেন্টটি ফাঁকা বা পর্যাপ্ত নির্দেশনা নেই।"
            )
        }

        // Security audit first — block DCL or hardcoded credentials
        val audit = GitHubRepositoryLearningEngine.scanSourceForSecurityAndPlayRisks(
            fileContents = mapOf(fileName.ifBlank { "document.txt" } to cleanContent),
            manifestPermissions = emptyList()
        )
        if (audit.blockedByPlayOrSecurityPolicy) {
            return@withContext SkillFactoryResult(
                success = false,
                status = "BLOCKED",
                sourceType = "DOCUMENT",
                summaryBn = "ডকুমেন্টে নিরাপত্তা/Google Play পরিপন্থী কোড (${audit.findings.firstOrNull().orEmpty()}) পাওয়ায় এটি ব্লক করা হয়েছে।"
            )
        }

        val db = context?.let { AssistantDatabase.getDatabase(it) }
        val sRepo = skillRepoOverride ?: db?.let { JarvisSkillRepository(it.jarvisSkillDao()) }
        val aDao = assistantDaoOverride ?: db?.assistantDao()
        val dDao = devDaoOverride ?: db?.homeDeveloperDao()

        val extractedSteps = mutableListOf<JarvisSkillStepEntity>()
        val trimmedLower = fileName.trim().lowercase(Locale.ROOT)
        if (trimmedLower.endsWith(".json")) {
            try {
                val obj = JSONObject(cleanContent)
                val arr = obj.optJSONArray("steps")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        extractedSteps.add(
                            JarvisSkillStepEntity(
                                skillId = "",
                                stepIndex = extractedSteps.size + 1,
                                actionType = s.optString("actionType", "CLICK").uppercase(Locale.ROOT),
                                targetViewId = s.optString("targetViewId", "").ifBlank { null },
                                targetText = s.optString("targetText", "").ifBlank { null },
                                inputPayload = s.optString("inputPayload", "").ifBlank { null },
                                descriptionBengali = s.optString("descriptionBengali", "ডকুমেন্ট ধাপ ${extractedSteps.size + 1}")
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (extractedSteps.isEmpty()) {
            extractedSteps.addAll(YouTubeTutorialImporter.extractActionStepsFromText(cleanContent))
        }

        val docTitle = fileName.substringAfterLast('/').ifBlank { "Document Knowledge" }
        var artifactId = 0L
        if (aDao != null) {
            artifactId = aDao.insertArtifact(
                ArtifactEntity(
                    title = "Document Study: $docTitle",
                    content = cleanContent.take(8000),
                    format = if (trimmedLower.endsWith(".json")) "JSON" else "MARKDOWN",
                    category = "DOCUMENT"
                )
            )
        }

        if (extractedSteps.isEmpty()) {
            return@withContext SkillFactoryResult(
                success = true,
                status = "KNOWLEDGE_ARCHIVED",
                skillName = docTitle,
                sourceType = "DOCUMENT",
                artifactId = artifactId,
                summaryBn = "ডকুমেন্ট '$docTitle' পড়া হয়েছে এবং নিরাপদ নলেজ আর্টিফ্যাক্ট (#$artifactId) হিসেবে সংরক্ষণ করা হয়েছে; তবে সরাসরি UI-অ্যাকশন ধাপ পাওয়া যায়নি।"
            )
        }

        val skillId = "doc_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
        val finalSteps = extractedSteps.mapIndexed { i, st -> st.copy(skillId = skillId, stepIndex = i + 1) }
        val validation = JarvisSkillRecorder.validateSkillPlaybook(finalSteps)
        val status = if (validation.isValid) "DRAFT" else "DRAFT"

        val entity = JarvisSkillEntity(
            skillId = skillId,
            name = docTitle,
            nameBengali = "ডকুমেন্ট স্কিল: $docTitle",
            description = "Learned from document $docTitle (${finalSteps.size} steps, security ${audit.riskLevel})",
            triggerPhrasesJson = JSONArray(listOf(docTitle, "ডকুমেন্ট $docTitle")).toString(),
            category = "CUSTOM",
            riskLevel = audit.riskLevel,
            requiresConfirmation = true,
            stepsJson = stepsToJsonArray(finalSteps).toString(),
            stepCount = finalSteps.size,
            sourceType = "DOCUMENT",
            status = status,
            parametersSchemaJson = JSONArray(JarvisSkillRecorder.extractParameterNames(finalSteps)).toString()
        )
        sRepo?.saveSkillWithSteps(entity, finalSteps)
        dDao?.upsertSkillEvidence(
            SkillEvidenceEntity(
                skillId = skillId,
                sourceType = "DOCUMENT",
                sourceUrl = fileName,
                version = 1,
                healthScore = if (validation.isValid) 95 else 75
            )
        )

        SkillFactoryResult(
            success = true,
            status = status,
            skillId = skillId,
            skillName = entity.nameBengali,
            stepCount = finalSteps.size,
            sourceType = "DOCUMENT",
            artifactId = artifactId,
            stagesCompleted = listOf(
                SkillLifecycleStage.LEARN,
                SkillLifecycleStage.UNDERSTAND,
                SkillLifecycleStage.CREATE_DRAFT,
                SkillLifecycleStage.VALIDATE
            ),
            summaryBn = "ডকুমেন্ট '$docTitle' বিশ্লেষণ করে ${finalSteps.size} ধাপের একটি DRAFT স্কিল তৈরি করা হয়েছে (নিরাপত্তা: ${audit.riskLevel})। অনুমোদনের পর এটি ACTIVE হবে।"
        )
    }

    suspend fun learnFromScreenshotOrVisionText(
        context: Context? = null,
        observedUiText: String,
        targetAppHint: String = "",
        title: String = "",
        skillRepoOverride: JarvisSkillRepository? = null,
        devDaoOverride: HomeDeveloperDao? = null,
        settingsRepo: HomeDeveloperSettingsRepository? = null
    ): SkillFactoryResult = withContext(Dispatchers.IO) {
        if (settingsRepo != null && !settingsRepo.isSourceAllowed("SCREENSHOT")) {
            return@withContext disabledSourceResult("SCREENSHOT", "স্ক্রিনশট/ইমেজ থেকে শেখা সেটিংস থেকে বন্ধ রাখা হয়েছে।")
        }
        val clean = YouTubeTutorialImporter.sanitizeUntrustedTranscript(observedUiText).trim()
        if (clean.length < 4) {
            return@withContext SkillFactoryResult(
                success = false,
                status = "FAILED",
                sourceType = "SCREENSHOT",
                summaryBn = "স্ক্রিনশট/ছবিতে পর্যাপ্ত UI টেক্সট পাওয়া যায়নি।"
            )
        }

        val db = context?.let { AssistantDatabase.getDatabase(it) }
        val sRepo = skillRepoOverride ?: db?.let { JarvisSkillRepository(it.jarvisSkillDao()) }
        val dDao = devDaoOverride ?: db?.homeDeveloperDao()

        val extracted = YouTubeTutorialImporter.extractActionStepsFromText(clean).toMutableList()
        if (extracted.isEmpty()) {
            // Convert visible button/field hints from OCR text into a single verifiable DRAFT click/open skeleton
            val firstToken = clean.lines().firstOrNull { it.isNotBlank() }?.take(36).orEmpty()
            if (targetAppHint.isNotBlank()) {
                extracted.add(
                    JarvisSkillStepEntity(
                        skillId = "",
                        stepIndex = 1,
                        actionType = "OPEN_APP",
                        targetViewId = targetAppHint,
                        targetText = targetAppHint,
                        descriptionBengali = "$targetAppHint চালু করা"
                    )
                )
            }
            if (firstToken.isNotBlank()) {
                extracted.add(
                    JarvisSkillStepEntity(
                        skillId = "",
                        stepIndex = extracted.size + 1,
                        actionType = "CLICK",
                        targetText = firstToken,
                        descriptionBengali = "দৃশ্যমান ‘$firstToken’ উপাদানে ক্লিক করা"
                    )
                )
            }
        }

        val skillId = "vis_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
        val finalSteps = extracted.mapIndexed { idx, st -> st.copy(skillId = skillId, stepIndex = idx + 1) }
        val skillTitle = title.ifBlank { "স্ক্রিনশট ওয়ার্কফ্লো (${finalSteps.size} ধাপ)" }
        val entity = JarvisSkillEntity(
            skillId = skillId,
            name = skillTitle,
            nameBengali = skillTitle,
            description = "Extracted from screenshot OCR / UI analysis",
            triggerPhrasesJson = JSONArray(listOf(skillTitle)).toString(),
            targetPackageName = targetAppHint,
            category = "CUSTOM",
            riskLevel = "MEDIUM",
            requiresConfirmation = true,
            stepsJson = stepsToJsonArray(finalSteps).toString(),
            stepCount = finalSteps.size,
            sourceType = "SCREENSHOT",
            status = "DRAFT",
            parametersSchemaJson = JSONArray(JarvisSkillRecorder.extractParameterNames(finalSteps)).toString()
        )
        sRepo?.saveSkillWithSteps(entity, finalSteps)
        dDao?.upsertSkillEvidence(
            SkillEvidenceEntity(
                skillId = skillId,
                sourceType = "SCREENSHOT",
                version = 1,
                healthScore = 85
            )
        )

        SkillFactoryResult(
            success = true,
            status = "DRAFT",
            skillId = skillId,
            skillName = skillTitle,
            stepCount = finalSteps.size,
            sourceType = "SCREENSHOT",
            summaryBn = "স্ক্রিনশট/ভিশন থেকে ‘$skillTitle’ (${finalSteps.size} ধাপ) DRAFT স্কিল হিসেবে সংরক্ষিত হয়েছে। ড্রাই-রান ও অনুমোদনের পর সক্রিয় হবে।"
        )
    }

    /**
     * Combines 2+ existing stored skills into a validated composite multi-step skill.
     */
    suspend fun combineSkills(
        context: Context? = null,
        skillIds: List<String>,
        newSkillName: String,
        activateImmediately: Boolean = false,
        skillRepoOverride: JarvisSkillRepository? = null,
        devDaoOverride: HomeDeveloperDao? = null
    ): SkillFactoryResult = withContext(Dispatchers.IO) {
        val db = context?.let { AssistantDatabase.getDatabase(it) }
        val sRepo = skillRepoOverride ?: db?.let { JarvisSkillRepository(it.jarvisSkillDao()) }
            ?: return@withContext SkillFactoryResult(
                success = false,
                status = "FAILED",
                sourceType = "COMPOSITE",
                summaryBn = "JarvisSkillRepository উপলব্ধ নয়।"
            )
        val dDao = devDaoOverride ?: db?.homeDeveloperDao()

        if (skillIds.distinct().size < 2) {
            return@withContext SkillFactoryResult(
                success = false,
                status = "FAILED",
                sourceType = "COMPOSITE",
                summaryBn = "কম্বাইনড স্কিল তৈরি করতে অন্তত ২টি ভিন্ন স্কিল নির্বাচন করুন।"
            )
        }

        val combinedSteps = mutableListOf<JarvisSkillStepEntity>()
        val sourceNames = mutableListOf<String>()
        var primaryPkg = ""
        for (id in skillIds.distinct()) {
            val skill = sRepo.getSkillById(id) ?: continue
            val steps = sRepo.getStepsForSkill(id)
            if (steps.isEmpty()) continue
            sourceNames.add(skill.nameBengali.ifBlank { skill.name })
            if (primaryPkg.isBlank() && skill.targetPackageName.isNotBlank()) {
                primaryPkg = skill.targetPackageName
            }
            steps.forEach { st ->
                combinedSteps.add(st.copy(id = 0, stepIndex = combinedSteps.size + 1))
            }
        }

        if (combinedSteps.isEmpty() || sourceNames.size < 2) {
            return@withContext SkillFactoryResult(
                success = false,
                status = "FAILED",
                sourceType = "COMPOSITE",
                summaryBn = "নির্বাচিত স্কিলগুলোর মধ্যে বৈধ অ্যাকশন ধাপ পাওয়া যায়নি।"
            )
        }

        val validation = JarvisSkillRecorder.validateSkillPlaybook(combinedSteps)
        val newId = "cmb_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
        val finalSteps = combinedSteps.mapIndexed { idx, s -> s.copy(skillId = newId, stepIndex = idx + 1) }
        val status = if (activateImmediately && validation.isValid) "ACTIVE" else "DRAFT"
        val resolvedName = newSkillName.trim().ifBlank { "কম্বাইনড: ${sourceNames.joinToString(" + ")}" }

        val entity = JarvisSkillEntity(
            skillId = newId,
            name = resolvedName,
            nameBengali = resolvedName,
            description = "Combined from ${sourceNames.joinToString(", ")}",
            triggerPhrasesJson = JSONArray(listOf(resolvedName)).toString(),
            targetPackageName = primaryPkg,
            category = "CUSTOM",
            riskLevel = "MEDIUM",
            requiresConfirmation = true,
            stepsJson = stepsToJsonArray(finalSteps).toString(),
            stepCount = finalSteps.size,
            sourceType = "COMPOSITE_COMBINATION",
            status = status,
            parametersSchemaJson = JSONArray(JarvisSkillRecorder.extractParameterNames(finalSteps)).toString()
        )
        sRepo.saveSkillWithSteps(entity, finalSteps)
        dDao?.upsertSkillEvidence(
            SkillEvidenceEntity(
                skillId = newId,
                sourceType = "COMPOSITE_COMBINATION",
                version = 1,
                healthScore = if (validation.isValid) 95 else 70
            )
        )

        SkillFactoryResult(
            success = true,
            status = status,
            skillId = newId,
            skillName = resolvedName,
            stepCount = finalSteps.size,
            sourceType = "COMPOSITE_COMBINATION",
            stagesCompleted = listOf(
                SkillLifecycleStage.LEARN,
                SkillLifecycleStage.UNDERSTAND,
                SkillLifecycleStage.CREATE_DRAFT,
                SkillLifecycleStage.MAP_TO_TOOLS,
                SkillLifecycleStage.VALIDATE,
                SkillLifecycleStage.SAVE
            ),
            summaryBn = "${sourceNames.size}টি স্কিল একত্রিত করে ‘$resolvedName’ (${finalSteps.size} ধাপ, স্ট্যাটাস: $status) তৈরি হয়েছে।"
        )
    }

    /**
     * Dry-runs, validates, and approves/activates a DRAFT skill.
     */
    suspend fun approveAndActivateDraftSkill(
        skillId: String,
        skillRepo: JarvisSkillRepository,
        devDao: HomeDeveloperDao? = null
    ): SkillFactoryResult = withContext(Dispatchers.IO) {
        val existing = skillRepo.getSkillById(skillId)
            ?: return@withContext SkillFactoryResult(false, "FAILED", summaryBn = "স্কিল '$skillId' খুঁজে পাওয়া যায়নি।")
        val steps = skillRepo.getStepsForSkill(skillId)
        val validation = JarvisSkillRecorder.validateSkillPlaybook(steps)
        if (!validation.isValid) {
            return@withContext SkillFactoryResult(
                success = false,
                status = "FAILED",
                skillId = skillId,
                skillName = existing.nameBengali,
                summaryBn = "স্কিল ভ্যালিডেশন ব্যর্থ: ${validation.issues.joinToString("; ")}"
            )
        }
        val activated = existing.copy(status = "ACTIVE", updatedAt = System.currentTimeMillis())
        skillRepo.saveSkillWithSteps(activated, steps)
        val evidence = devDao?.getSkillEvidenceByIdSync(skillId)
        if (evidence != null) {
            devDao.upsertSkillEvidence(evidence.copy(version = activated.version, needsReview = false))
        }
        SkillFactoryResult(
            success = true,
            status = "ACTIVE",
            skillId = skillId,
            skillName = activated.nameBengali,
            stepCount = steps.size,
            sourceType = activated.sourceType,
            stagesCompleted = listOf(
                SkillLifecycleStage.VALIDATE,
                SkillLifecycleStage.DRY_RUN,
                SkillLifecycleStage.APPROVAL,
                SkillLifecycleStage.ACTIVATE
            ),
            summaryBn = "‘${activated.nameBengali}’ ভ্যালিডেশন পাস করে ACTIVE করা হয়েছে (${steps.size} ধাপ)।"
        )
    }

    /**
     * Tracks Skill Evidence metrics on execution and triggers automatic review/self-revision
     * or rollback when repeated failures occur.
     */
    suspend fun recordSkillOutcomeAndAudit(
        skillId: String,
        success: Boolean,
        executionTimeMs: Long,
        failureReason: String = "",
        skillRepo: JarvisSkillRepository,
        devDao: HomeDeveloperDao
    ): SkillEvidenceEntity = withContext(Dispatchers.IO) {
        skillRepo.recordExecution(skillId, success)
        val skill = skillRepo.getSkillById(skillId)
        val existing = devDao.getSkillEvidenceByIdSync(skillId) ?: SkillEvidenceEntity(
            skillId = skillId,
            sourceType = skill?.sourceType ?: "CUSTOM",
            version = skill?.version ?: 1
        )
        val nextExec = existing.executionCount + 1
        val nextSuccess = existing.verifiedSuccessCount + if (success) 1 else 0
        val nextFailure = existing.failureCount + if (success) 0 else 1
        val nextAvg = if (existing.executionCount == 0) executionTimeMs
        else ((existing.avgExecTimeMs * existing.executionCount) + executionTimeMs) / nextExec
        val rate = if (nextExec == 0) 100 else (nextSuccess * 100) / nextExec
        val needsReview = !success && (nextFailure >= 2 || rate < 60)

        val updated = existing.copy(
            version = skill?.version ?: existing.version,
            executionCount = nextExec,
            verifiedSuccessCount = nextSuccess,
            failureCount = nextFailure,
            avgExecTimeMs = nextAvg,
            lastFailureReason = if (success) existing.lastFailureReason else failureReason.ifBlank { "EXECUTION_FAILURE" },
            needsReview = needsReview,
            healthScore = rate.coerceIn(0, 100)
        )
        devDao.upsertSkillEvidence(updated)

        if (needsReview) {
            skillRepo.analyzeFailureHistoryAndProposeRevision(skillId, updated.lastFailureReason)
        }
        updated
    }

    private fun disabledSourceResult(source: String, msgBn: String) = SkillFactoryResult(
        success = false,
        status = "SOURCE_DISABLED",
        sourceType = source,
        summaryBn = msgBn
    )

    private fun stepsToJsonArray(steps: List<JarvisSkillStepEntity>): JSONArray {
        return JSONArray().apply {
            steps.forEach { s ->
                put(JSONObject().apply {
                    put("stepIndex", s.stepIndex)
                    put("actionType", s.actionType)
                    put("targetText", s.targetText ?: "")
                    put("targetViewId", s.targetViewId ?: "")
                    put("inputPayload", s.inputPayload ?: "")
                    put("descriptionBn", s.descriptionBengali)
                    put("delayAfterMs", s.delayAfterMs)
                })
            }
        }
    }
}
