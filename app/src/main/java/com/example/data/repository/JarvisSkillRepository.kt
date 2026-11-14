package com.example.data.repository

import com.example.data.local.JarvisLearningSessionEntity
import com.example.data.local.JarvisSkillDao
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

class JarvisSkillRepository(
    private val skillDao: JarvisSkillDao
) {
    val allSkills: Flow<List<JarvisSkillEntity>> = skillDao.getAllSkills()
    val activeSkills: Flow<List<JarvisSkillEntity>> = skillDao.getAllActiveSkills()
    val activeLearningSession: Flow<JarvisLearningSessionEntity?> = skillDao.getActiveRecordingSession()

    suspend fun getSkillById(skillId: String): JarvisSkillEntity? = withContext(Dispatchers.IO) {
        skillDao.getSkillByIdSync(skillId)
    }

    fun getSkillFlow(skillId: String): Flow<JarvisSkillEntity?> {
        return skillDao.getSkillById(skillId)
    }

    suspend fun getStepsForSkill(skillId: String): List<JarvisSkillStepEntity> = withContext(Dispatchers.IO) {
        skillDao.getStepsForSkillSync(skillId)
    }

    fun getStepsFlow(skillId: String): Flow<List<JarvisSkillStepEntity>> {
        return skillDao.getStepsForSkill(skillId)
    }

    suspend fun saveSkillWithSteps(
        skill: JarvisSkillEntity,
        steps: List<JarvisSkillStepEntity>
    ) = withContext(Dispatchers.IO) {
        skillDao.insertSkill(skill)
        skillDao.deleteStepsForSkill(skill.skillId)
        if (steps.isNotEmpty()) {
            skillDao.insertSteps(steps)
        }
    }

    suspend fun deleteSkill(skillId: String) = withContext(Dispatchers.IO) {
        skillDao.deleteStepsForSkill(skillId)
        skillDao.deleteSkillById(skillId)
    }

    suspend fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val existing = skillDao.getSkillByIdSync(skillId) ?: return@withContext false
        val updated = existing.copy(
            status = if (enabled) "ACTIVE" else "DISABLED",
            updatedAt = System.currentTimeMillis()
        )
        skillDao.updateSkill(updated)
        true
    }

    suspend fun updateSkillWithVersioning(
        skillId: String,
        newName: String? = null,
        newNameBengali: String? = null,
        newDescription: String? = null,
        newTriggerPhrases: List<String>? = null,
        newSteps: List<JarvisSkillStepEntity>? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val existing = skillDao.getSkillByIdSync(skillId)
            ?: return@withContext Pair(false, "স্কিল খুঁজে পাওয়া যায়নি।")

        val finalSteps = newSteps ?: skillDao.getStepsForSkillSync(skillId)
        val stepsJsonArr = JSONArray().apply {
            finalSteps.forEachIndexed { idx, s ->
                put(org.json.JSONObject().apply {
                    put("stepIndex", idx + 1)
                    put("actionType", s.actionType)
                    put("targetText", s.targetText ?: "")
                    put("targetViewId", s.targetViewId ?: "")
                    put("inputPayload", s.inputPayload ?: "")
                    put("descriptionBn", s.descriptionBengali)
                    put("delayAfterMs", s.delayAfterMs)
                })
            }
        }
        val paramNames = com.example.automation.JarvisSkillRecorder.extractParameterNames(finalSteps)
        val updatedTriggers = if (newTriggerPhrases != null) {
            JSONArray(newTriggerPhrases.filter { it.isNotBlank() }).toString()
        } else {
            existing.triggerPhrasesJson
        }

        val updatedEntity = existing.copy(
            name = newName?.ifBlank { existing.name } ?: existing.name,
            nameBengali = newNameBengali?.ifBlank { existing.nameBengali } ?: existing.nameBengali,
            description = newDescription?.ifBlank { existing.description } ?: existing.description,
            triggerPhrasesJson = updatedTriggers,
            previousStepsJson = existing.stepsJson,
            previousVersion = existing.version,
            stepsJson = stepsJsonArr.toString(),
            stepCount = finalSteps.size,
            version = existing.version + 1,
            parametersSchemaJson = JSONArray(paramNames).toString(),
            updatedAt = System.currentTimeMillis()
        )
        val normalizedSteps = finalSteps.mapIndexed { idx, step ->
            step.copy(id = 0, skillId = skillId, stepIndex = idx + 1)
        }
        saveSkillWithSteps(updatedEntity, normalizedSteps)
        Pair(true, "'${updatedEntity.nameBengali}' v${updatedEntity.version}-এ আপডেট করা হয়েছে (পূর্ববর্তী সংস্করণ v${existing.version} সংরক্ষিত)।")
    }

    suspend fun rollbackSkillToPreviousVersion(skillId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val existing = skillDao.getSkillByIdSync(skillId)
            ?: return@withContext Pair(false, "স্কিল খুঁজে পাওয়া যায়নি।")

        if (existing.previousVersion <= 0 || existing.previousStepsJson.isBlank() || existing.previousStepsJson == "[]") {
            return@withContext Pair(false, "'${existing.nameBengali}'-এর কোনো পূর্ববর্তী সংস্করণ রোলব্যাকের জন্য সংরক্ষিত নেই।")
        }

        val restoredSteps = mutableListOf<JarvisSkillStepEntity>()
        try {
            val arr = JSONArray(existing.previousStepsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                restoredSteps.add(
                    JarvisSkillStepEntity(
                        skillId = skillId,
                        stepIndex = i + 1,
                        actionType = obj.optString("actionType", "CLICK"),
                        targetText = obj.optString("targetText", "").ifBlank { null },
                        targetViewId = obj.optString("targetViewId", "").ifBlank { null },
                        inputPayload = obj.optString("inputPayload", "").ifBlank { null },
                        descriptionBengali = obj.optString("descriptionBn", obj.optString("descriptionBengali", "")),
                        delayAfterMs = obj.optLong("delayAfterMs", 600L)
                    )
                )
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "পূর্ববর্তী সংস্করণ পার্স করতে ত্রুটি: ${e.message}")
        }

        val paramNames = com.example.automation.JarvisSkillRecorder.extractParameterNames(restoredSteps)
        val rolledBack = existing.copy(
            stepsJson = existing.previousStepsJson,
            previousStepsJson = existing.stepsJson,
            version = existing.version + 1,
            previousVersion = existing.version,
            stepCount = restoredSteps.size,
            parametersSchemaJson = JSONArray(paramNames).toString(),
            updatedAt = System.currentTimeMillis()
        )
        saveSkillWithSteps(rolledBack, restoredSteps)
        Pair(true, "'${rolledBack.nameBengali}' পূর্ববর্তী স্টেপে রোলব্যাক সম্পন্ন হয়েছে (নতুন সংস্করণ v${rolledBack.version})।")
    }

    suspend fun recordExecution(skillId: String, success: Boolean) = withContext(Dispatchers.IO) {
        if (success) {
            skillDao.recordSkillSuccess(skillId)
        } else {
            skillDao.recordSkillFailure(skillId)
        }
    }

    /**
     * Resolves user natural language command against stored trigger phrases across Bengali, English, and Banglish.
     */
    suspend fun findMatchingSkill(userPrompt: String): JarvisSkillEntity? = withContext(Dispatchers.IO) {
        val cleanPrompt = userPrompt.trim().lowercase(Locale.ROOT)
        if (cleanPrompt.isBlank()) return@withContext null

        val activeList = skillDao.getAllActiveSkillsSync()
        for (skill in activeList) {
            try {
                val triggers = JSONArray(skill.triggerPhrasesJson)
                for (i in 0 until triggers.length()) {
                    val trigger = triggers.optString(i, "").trim().lowercase(Locale.ROOT)
                    if (trigger.isNotBlank() && (cleanPrompt.contains(trigger) || trigger.contains(cleanPrompt))) {
                        return@withContext skill
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors on triggers
            }

            // Fallback match on skill name
            if (cleanPrompt.contains(skill.name.lowercase(Locale.ROOT)) ||
                cleanPrompt.contains(skill.nameBengali.lowercase(Locale.ROOT))
            ) {
                return@withContext skill
            }
        }
        null
    }

    // =========================================================================
    // LEARNING SESSION MANAGEMENT
    // =========================================================================

    suspend fun startLearningSession(session: JarvisLearningSessionEntity) = withContext(Dispatchers.IO) {
        skillDao.insertLearningSession(session)
    }

    suspend fun completeLearningSession(
        sessionId: String,
        status: String = "COMPLETED",
        recordedStepCount: Int
    ) = withContext(Dispatchers.IO) {
        skillDao.updateSessionStatus(
            sessionId = sessionId,
            status = status,
            completedAt = System.currentTimeMillis(),
            stepCount = recordedStepCount
        )
    }

    suspend fun cancelLearningSession(sessionId: String) = withContext(Dispatchers.IO) {
        skillDao.updateSessionStatus(
            sessionId = sessionId,
            status = "CANCELLED",
            completedAt = System.currentTimeMillis(),
            stepCount = 0
        )
    }
}
