package com.example.automation

import android.content.Context
import com.example.data.local.AiMemoryEntity
import com.example.data.local.AiTaskEntity
import com.example.data.local.AssistantDao
import com.example.data.local.AssistantDatabase
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.data.repository.JarvisSkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class TextLearningCategory {
    MEMORY,
    ROUTINE,
    EXECUTABLE_SKILL_DRAFT
}

data class TextLearningClassification(
    val category: TextLearningCategory,
    val title: String,
    val extractedSteps: List<JarvisSkillStepEntity> = emptyList(),
    val validation: JarvisSkillRecorder.Companion.SkillValidationResult? = null,
    val triggerPhrases: List<String> = emptyList(),
    val scheduleHint: String = "",
    val memoryKey: String = ""
)

/**
 * Text-Based Learning Workspace Engine (Priority 3).
 * Parses natural Bengali, Banglish, or English instructions into:
 * - MEMORY (user fact, rule, preference)
 * - ROUTINE (recurring/scheduled protocol)
 * - EXECUTABLE_SKILL_DRAFT (validated multi-step UI/App workflow)
 * Supports explicit Dry Run validation before persistence into Room repositories.
 */
object TextLearningEngine {

    fun classifyInstruction(
        instructionText: String,
        preferredTitle: String = ""
    ): TextLearningClassification {
        val clean = YouTubeTutorialImporter.sanitizeUntrustedTranscript(instructionText).trim()
        val lower = clean.lowercase(Locale.ROOT)
        val steps = YouTubeTutorialImporter.extractActionStepsFromText(clean)

        val routineSignals = listOf(
            "প্রতিদিন", "সকালে", "রাতে", "বিকালে", "every day", "daily", "weekly",
            "সাপ্তাহিক", "রুটিন", "routine", "রিমাইন্ডার", "মনে করিয়ে", "remind me",
            "at 8", "at 9", "টায়", "টার সময়"
        )
        val memorySignals = listOf(
            "মনে রাখো", "মনে রেখো", "remember", "আমাকে ", "আমার নাম", "পছন্দ",
            "prefer", "call me", "ডাকবে", "সবসময়", "কখনোই", "alias", "ডাকনাম", "সতর্কতা"
        )

        val hasActionSteps = steps.size >= 2 || (steps.size == 1 && (lower.contains("open ") || lower.contains("খোলো") || lower.contains("ক্লিক")))
        val isRoutine = routineSignals.any { lower.contains(it) }
        val isMemory = memorySignals.any { lower.contains(it) }

        val category = when {
            hasActionSteps && !isRoutine -> TextLearningCategory.EXECUTABLE_SKILL_DRAFT
            isRoutine -> TextLearningCategory.ROUTINE
            hasActionSteps -> TextLearningCategory.EXECUTABLE_SKILL_DRAFT
            isMemory || clean.isNotBlank() -> TextLearningCategory.MEMORY
            else -> TextLearningCategory.MEMORY
        }

        val resolvedTitle = preferredTitle.trim().ifBlank {
            clean.lines().firstOrNull { it.isNotBlank() }?.take(48)?.trim() ?: "Custom Learning"
        }

        return when (category) {
            TextLearningCategory.EXECUTABLE_SKILL_DRAFT -> {
                val validation = JarvisSkillRecorder.validateSkillPlaybook(steps)
                TextLearningClassification(
                    category = category,
                    title = resolvedTitle,
                    extractedSteps = steps,
                    validation = validation,
                    triggerPhrases = listOf(resolvedTitle)
                )
            }
            TextLearningCategory.ROUTINE -> {
                val scheduleHint = extractScheduleHint(lower)
                TextLearningClassification(
                    category = category,
                    title = resolvedTitle,
                    extractedSteps = steps,
                    scheduleHint = scheduleHint,
                    triggerPhrases = listOf(resolvedTitle)
                )
            }
            TextLearningCategory.MEMORY -> {
                val key = resolvedTitle.take(36)
                TextLearningClassification(
                    category = category,
                    title = resolvedTitle,
                    memoryKey = key
                )
            }
        }
    }

    suspend fun learnFromText(
        context: Context? = null,
        instructionText: String,
        title: String = "",
        dryRunOnly: Boolean = false,
        saveAsActiveSkillIfValid: Boolean = true,
        daoOverride: AssistantDao? = null,
        skillRepoOverride: JarvisSkillRepository? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        val sanitized = YouTubeTutorialImporter.sanitizeUntrustedTranscript(instructionText).trim()
        if (sanitized.length < 3) {
            result.put("success", false)
            result.put("status", "FAILED")
            result.put("error", "EMPTY_INSTRUCTION")
            result.put("summaryBengali", "শেখার জন্য অনুগ্রহ করে স্পষ্ট বাংলা, বাংলিশ বা ইংরেজি নির্দেশনা লিখুন।")
            return@withContext result
        }

        val classification = classifyInstruction(sanitized, title)
        result.put("classification", classification.category.name)
        result.put("title", classification.title)
        result.put("dryRunOnly", dryRunOnly)

        val stepsArr = JSONArray().apply {
            classification.extractedSteps.forEach { step ->
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
        result.put("steps", stepsArr)

        if (classification.validation != null) {
            result.put("validationPassed", classification.validation.isValid)
            result.put("validationIssues", JSONArray(classification.validation.issues))
            result.put("recommendedStatus", classification.validation.recommendedStatus)
        }

        if (dryRunOnly) {
            result.put("success", true)
            result.put("status", "DRY_RUN_READY")
            result.put(
                "summaryBengali",
                "ড্রাই-রান সম্পন্ন: নির্দেশনাটি '${classification.category.name}' হিসেবে শ্রেণীবদ্ধ হয়েছে" +
                    if (classification.extractedSteps.isNotEmpty()) " (${classification.extractedSteps.size}টি ধাপ)।" else "।"
            )
            return@withContext result
        }

        val db = if (context != null && (daoOverride == null || skillRepoOverride == null)) {
            AssistantDatabase.getDatabase(context)
        } else {
            null
        }
        val dao = daoOverride ?: db?.assistantDao()
        val skillRepo = skillRepoOverride ?: db?.let { JarvisSkillRepository(it.jarvisSkillDao()) }

        when (classification.category) {
            TextLearningCategory.MEMORY -> {
                val mem = AiMemoryEntity(
                    key = classification.memoryKey.ifBlank { classification.title },
                    content = sanitized,
                    category = "USER_PREFERENCE",
                    importance = 2,
                    source = "TEXT_WORKSPACE"
                )
                val id = dao?.insertMemory(mem) ?: -1L
                result.put("success", id > 0 || dao == null)
                result.put("status", "SUCCESS")
                result.put("memoryId", id)
                result.put("summaryBengali", "তথ্যটি দীর্ঘমেয়াদী মেমোরিতে সংরক্ষণ করা হয়েছে: '${mem.key}'।")
            }

            TextLearningCategory.ROUTINE -> {
                val task = AiTaskEntity(
                    title = classification.title,
                    description = sanitized,
                    scheduledTime = System.currentTimeMillis() + 60_000L,
                    scheduledTimeString = classification.scheduleHint.ifBlank { "রুটিন" },
                    status = "PENDING",
                    actionType = if (classification.extractedSteps.isNotEmpty()) "PLAYBOOK" else "REMINDER",
                    targetCommand = sanitized,
                    repeatInterval = if (sanitized.contains("প্রতিদিন") || sanitized.contains("daily", true)) "DAILY" else "NONE",
                    priority = "NORMAL"
                )
                val taskId = dao?.insertAiTask(task) ?: -1L
                result.put("success", taskId > 0 || dao == null)
                result.put("status", "SUCCESS")
                result.put("taskId", taskId)
                result.put("scheduleHint", classification.scheduleHint)
                result.put(
                    "summaryBengali",
                    "নতুন রুটিন '${classification.title}' (${classification.scheduleHint.ifBlank { "স্বয়ংক্রিয়" }}) সংরক্ষণ করা হয়েছে।"
                )
            }

            TextLearningCategory.EXECUTABLE_SKILL_DRAFT -> {
                val skillId = "txt_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
                val finalSteps = classification.extractedSteps.mapIndexed { idx, s ->
                    s.copy(skillId = skillId, stepIndex = idx + 1)
                }
                val validation = JarvisSkillRecorder.validateSkillPlaybook(finalSteps)
                val finalStatus = if (saveAsActiveSkillIfValid && validation.isValid) "ACTIVE" else "DRAFT"
                val pkg = finalSteps.firstOrNull { it.actionType == "OPEN_APP" }?.targetViewId.orEmpty()
                val params = JarvisSkillRecorder.extractParameterNames(finalSteps)

                val entity = JarvisSkillEntity(
                    skillId = skillId,
                    name = classification.title,
                    nameBengali = classification.title,
                    description = "Learned from Text Workspace",
                    triggerPhrasesJson = JSONArray(classification.triggerPhrases).toString(),
                    targetPackageName = pkg,
                    targetAppName = pkg.substringAfterLast('.'),
                    category = "CUSTOM",
                    riskLevel = "MEDIUM",
                    requiresConfirmation = false,
                    requiredPermissionsJson = "[\"ACCESSIBILITY_SERVICE\"]",
                    stepsJson = stepsArr.toString(),
                    stepCount = finalSteps.size,
                    sourceType = "TEXT_TEACH",
                    status = finalStatus,
                    parametersSchemaJson = JSONArray(params).toString()
                )
                skillRepo?.saveSkillWithSteps(entity, finalSteps)

                result.put("success", true)
                result.put("status", "SUCCESS")
                result.put("skillId", skillId)
                result.put("skillStatus", finalStatus)
                result.put("stepCount", finalSteps.size)
                result.put(
                    "summaryBengali",
                    "টেক্সট থেকে ‘${classification.title}’ স্কিল (${finalSteps.size} ধাপ, স্ট্যাটাস: $finalStatus) স্কিল লাইব্রেরিতে সংরক্ষণ করা হয়েছে।"
                )
            }
        }

        result
    }

    private fun extractScheduleHint(lower: String): String {
        val timeMatch = Regex("""(\d{1,2}(?::\d{2})?\s*(?:am|pm|টায়|টা)?)""").find(lower)?.groupValues?.getOrNull(1).orEmpty()
        return when {
            lower.contains("প্রতিদিন") || lower.contains("daily") || lower.contains("every day") ->
                if (timeMatch.isNotBlank()) "প্রতিদিন $timeMatch" else "প্রতিদিন"
            lower.contains("weekly") || lower.contains("সাপ্তাহিক") -> "সাপ্তাহিক"
            timeMatch.isNotBlank() -> timeMatch
            else -> "একবার"
        }
    }
}
