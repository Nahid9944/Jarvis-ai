package com.example.automation

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.local.JarvisLearningSessionEntity
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.data.repository.JarvisSkillRepository
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SkillRecorderState {
    IDLE,
    RECORDING,
    PAUSED,
    PROCESSING,
    COMPLETED
}

data class CapturedStep(
    val stepIndex: Int,
    val actionType: String, // CLICK, INPUT_TEXT, SCROLL_FORWARD, OPEN_APP, WAIT_FOR_ELEMENT
    val targetViewId: String? = null,
    val targetText: String? = null,
    val targetContentDescription: String? = null,
    val targetClassName: String? = null,
    val bounds: Rect? = null,
    val inputPayload: String? = null,
    val descriptionBengali: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toStepEntity(skillId: String): JarvisSkillStepEntity {
        return JarvisSkillStepEntity(
            skillId = skillId,
            stepIndex = stepIndex,
            actionType = actionType,
            targetViewId = targetViewId,
            targetText = targetText,
            targetContentDescription = targetContentDescription,
            targetClassName = targetClassName,
            boundsInScreen = bounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" },
            inputPayload = inputPayload,
            descriptionBengali = descriptionBengali,
            delayAfterMs = 600L,
            createdAt = timestamp
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("stepIndex", stepIndex)
            put("actionType", actionType)
            put("targetViewId", targetViewId ?: "")
            put("targetText", targetText ?: "")
            put("targetContentDescription", targetContentDescription ?: "")
            put("targetClassName", targetClassName ?: "")
            put("bounds", bounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "")
            put("inputPayload", inputPayload ?: "")
            put("descriptionBengali", descriptionBengali)
        }
    }
}

/**
 * JarvisSkillRecorder captures user interactions via JarvisAccessibilityService
 * and compiles them into structured, reusable workflow steps for demonstration learning.
 */
class JarvisSkillRecorder(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "JarvisSkillRecorder"
        private const val DEBOUNCE_CLICK_MS = 350L
        private const val DEBOUNCE_TEXT_MS = 600L

        @Volatile
        private var instance: JarvisSkillRecorder? = null

        fun getInstance(): JarvisSkillRecorder {
            return instance ?: synchronized(this) {
                instance ?: JarvisSkillRecorder().also { instance = it }
            }
        }

        fun startRecording(context: Context, skillName: String): Boolean {
            val recorder = getInstance()
            val repo = com.example.data.repository.JarvisSkillRepository(
                com.example.data.local.AssistantDatabase.getDatabase(context).jarvisSkillDao()
            )
            val sessionId = recorder.startRecording(
                skillName = skillName,
                skillNameBengali = skillName,
                triggerPhrase = skillName,
                repository = repo
            )
            return sessionId.isNotBlank()
        }

        suspend fun stopAndSaveSkill(context: Context): JarvisSkillEntity? {
            val recorder = getInstance()
            val repo = com.example.data.repository.JarvisSkillRepository(
                com.example.data.local.AssistantDatabase.getDatabase(context).jarvisSkillDao()
            )
            return try {
                if (recorder.capturedSteps.value.isEmpty()) {
                    null
                } else {
                    recorder.stopAndSaveSkill(
                        description = "ব্যবহারকারী কর্তৃক রেকর্ডকৃত স্কিল",
                        category = "CUSTOM",
                        repository = repo
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving skill", e)
                null
            }
        }

        private val PARAM_REGEX = Regex("""\{([a-zA-Z0-9_]+)\}""")
        private val SYSTEM_PARAMS = setOf("date", "time")

        fun extractParameterNames(steps: List<JarvisSkillStepEntity>): List<String> {
            val found = linkedSetOf<String>()
            for (step in steps) {
                listOfNotNull(step.inputPayload, step.targetText, step.targetViewId).forEach { field ->
                    PARAM_REGEX.findAll(field).forEach { m ->
                        val paramName = m.groupValues[1]
                        if (paramName.isNotBlank()) found.add(paramName)
                    }
                }
            }
            return found.toList()
        }

        fun extractUserRequiredParameters(steps: List<JarvisSkillStepEntity>): List<String> {
            return extractParameterNames(steps).filterNot { it.lowercase() in SYSTEM_PARAMS }
        }

        /**
         * Voice / Natural-Language Teach Mode for non-programmers.
         * Converts Bengali/Banglish/English step instructions into a persisted,
         * parameterized JarvisSkillEntity + steps without touching code or accessibility recording.
         */
        suspend fun teachSkillFromNaturalLanguage(
            skillName: String,
            skillNameBengali: String = skillName,
            triggerPhrases: List<String> = emptyList(),
            stepsInstructions: String,
            targetPackageName: String = "",
            repository: JarvisSkillRepository
        ): JarvisSkillEntity = withContext(Dispatchers.IO) {
            val parsedSteps = YouTubeTutorialImporter.extractActionStepsFromText(stepsInstructions)
            val skillId = "teach_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val stepEntities = parsedSteps.mapIndexed { idx, s ->
                s.copy(skillId = skillId, stepIndex = idx + 1)
            }
            val inferredPkg = targetPackageName.ifBlank {
                stepEntities.firstOrNull { it.actionType == "OPEN_APP" }?.targetViewId.orEmpty()
            }
            val paramNames = extractParameterNames(stepEntities)
            val stepsJsonArray = JSONArray().apply {
                stepEntities.forEach { s ->
                    put(JSONObject().apply {
                        put("stepIndex", s.stepIndex)
                        put("actionType", s.actionType)
                        put("targetText", s.targetText ?: "")
                        put("targetViewId", s.targetViewId ?: "")
                        put("inputPayload", s.inputPayload ?: "")
                        put("descriptionBn", s.descriptionBengali)
                    })
                }
            }
            val triggers = JSONArray().apply {
                if (skillNameBengali.isNotBlank()) put(skillNameBengali)
                if (skillName.isNotBlank() && skillName != skillNameBengali) put(skillName)
                triggerPhrases.distinct().filter { it.isNotBlank() }.forEach { put(it) }
            }

            val entity = JarvisSkillEntity(
                skillId = skillId,
                name = skillName.ifBlank { "Taught Skill" },
                nameBengali = skillNameBengali.ifBlank { skillName.ifBlank { "শেখানো কাজ" } },
                description = "ভয়েস বা সাধারণ নির্দেশনার মাধ্যমে শেখানো দক্ষতা",
                triggerPhrasesJson = triggers.toString(),
                targetPackageName = inferredPkg,
                targetAppName = inferredPkg.substringAfterLast('.'),
                category = "CUSTOM",
                riskLevel = "MEDIUM",
                requiresConfirmation = false,
                requiredPermissionsJson = "[\"ACCESSIBILITY_SERVICE\"]",
                stepsJson = stepsJsonArray.toString(),
                stepCount = stepEntities.size,
                sourceType = "VOICE_TEACHING",
                status = "ACTIVE",
                version = 1,
                parametersSchemaJson = JSONArray(paramNames).toString()
            )
            repository.saveSkillWithSteps(entity, stepEntities)
            entity
        }
    }

    private val _recorderState = MutableStateFlow(SkillRecorderState.IDLE)
    val recorderState: StateFlow<SkillRecorderState> = _recorderState.asStateFlow()

    private val _capturedSteps = MutableStateFlow<List<CapturedStep>>(emptyList())
    val capturedSteps: StateFlow<List<CapturedStep>> = _capturedSteps.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private var currentSkillName: String = ""
    private var currentSkillNameBengali: String = ""
    private var currentTriggerPhrase: String = ""
    private var currentTargetPackage: String = ""
    private var currentTargetAppName: String = ""

    private var lastClickTime = 0L
    private var lastClickedViewId: String? = null
    private var pendingTextJob: Job? = null
    private var lastInputText: String? = null

    /**
     * Start a new Demonstration Learning session.
     */
    fun startRecording(
        skillName: String,
        skillNameBengali: String,
        triggerPhrase: String,
        targetAppPackage: String = "",
        targetAppName: String = "",
        repository: JarvisSkillRepository? = null
    ): String {
        val sessionId = "session_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        currentSkillName = skillName.ifBlank { "New Learned Skill" }
        currentSkillNameBengali = skillNameBengali.ifBlank { "নতুন শেখা কাজ" }
        currentTriggerPhrase = triggerPhrase.ifBlank { skillNameBengali }
        currentTargetPackage = targetAppPackage
        currentTargetAppName = targetAppName.ifBlank { targetAppPackage }

        _activeSessionId.value = sessionId
        _capturedSteps.value = emptyList()
        _recorderState.value = SkillRecorderState.RECORDING

        // If target app is specified, record the initial OPEN_APP step
        if (targetAppPackage.isNotBlank()) {
            val openStep = CapturedStep(
                stepIndex = 0,
                actionType = "OPEN_APP",
                targetViewId = targetAppPackage,
                targetText = currentTargetAppName,
                descriptionBengali = "অ্যাপ্লিকেশন চালু: $currentTargetAppName ($targetAppPackage)"
            )
            _capturedSteps.value = listOf(openStep)
        }

        // Attach accessibility listener
        JarvisAccessibilityService.eventListener = { event ->
            handleAccessibilityEvent(event)
        }

        // Optionally record to repository
        if (repository != null) {
            scope.launch {
                repository.startLearningSession(
                    JarvisLearningSessionEntity(
                        sessionId = sessionId,
                        skillName = currentSkillName,
                        skillNameBengali = currentSkillNameBengali,
                        triggerPhrase = currentTriggerPhrase,
                        targetAppPackage = currentTargetPackage,
                        targetAppName = currentTargetAppName,
                        mode = "DEMONSTRATION",
                        status = "RECORDING"
                    )
                )
            }
        }

        Log.i(TAG, "Demonstration learning recording started: $sessionId for $skillName")
        return sessionId
    }

    fun pauseRecording() {
        if (_recorderState.value == SkillRecorderState.RECORDING) {
            _recorderState.value = SkillRecorderState.PAUSED
        }
    }

    fun resumeRecording() {
        if (_recorderState.value == SkillRecorderState.PAUSED) {
            _recorderState.value = SkillRecorderState.RECORDING
        }
    }

    fun discardRecording(repository: JarvisSkillRepository? = null) {
        val sessionId = _activeSessionId.value
        JarvisAccessibilityService.eventListener = null
        _recorderState.value = SkillRecorderState.IDLE
        _capturedSteps.value = emptyList()
        _activeSessionId.value = null

        if (repository != null && sessionId != null) {
            scope.launch {
                repository.cancelLearningSession(sessionId)
            }
        }
    }

    /**
     * Completes demonstration recording and persists the compiled skill and steps into Room Database.
     */
    suspend fun stopAndSaveSkill(
        description: String = "",
        category: String = "CUSTOM",
        repository: JarvisSkillRepository
    ): JarvisSkillEntity = withContext(Dispatchers.IO) {
        _recorderState.value = SkillRecorderState.PROCESSING
        JarvisAccessibilityService.eventListener = null

        val sessionId = _activeSessionId.value ?: UUID.randomUUID().toString()
        val skillId = "skill_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val currentSteps = _capturedSteps.value

        val triggerPhrases = JSONArray().apply {
            if (currentTriggerPhrase.isNotBlank()) put(currentTriggerPhrase)
            if (currentSkillNameBengali.isNotBlank() && currentSkillNameBengali != currentTriggerPhrase) {
                put(currentSkillNameBengali)
            }
            if (currentSkillName.isNotBlank() && currentSkillName != currentSkillNameBengali) {
                put(currentSkillName)
            }
        }

        val stepsJsonArray = JSONArray()
        currentSteps.forEach { stepsJsonArray.put(it.toJson()) }

        val stepEntities = currentSteps.map { it.toStepEntity(skillId) }
        val paramNames = extractParameterNames(stepEntities)

        val skillEntity = JarvisSkillEntity(
            skillId = skillId,
            name = currentSkillName,
            nameBengali = currentSkillNameBengali,
            description = description.ifBlank { "ব্যবহারকারীর প্রদর্শনী মাধ্যমে শেখা ওয়ার্কফ্লো: $currentSkillNameBengali" },
            triggerPhrasesJson = triggerPhrases.toString(),
            targetPackageName = currentTargetPackage,
            targetAppName = currentTargetAppName,
            category = category,
            riskLevel = if (currentSteps.any { it.actionType == "CLICK" && (it.targetText?.contains("Send") == true || it.targetText?.contains("পাঠান") == true || it.targetText?.contains("Pay") == true) }) "HIGH" else "MEDIUM",
            requiresConfirmation = currentSteps.any { it.actionType == "CLICK" && (it.targetText?.contains("Pay") == true || it.targetText?.contains("টাকা") == true) },
            requiredPermissionsJson = "[\"ACCESSIBILITY_SERVICE\"]",
            stepsJson = stepsJsonArray.toString(),
            stepCount = currentSteps.size,
            sourceType = "DEMONSTRATION",
            status = "ACTIVE",
            version = 1,
            parametersSchemaJson = JSONArray(paramNames).toString()
        )

        // Persist to Room Database
        repository.saveSkillWithSteps(skillEntity, stepEntities)
        repository.completeLearningSession(sessionId, "COMPLETED", currentSteps.size)

        _recorderState.value = SkillRecorderState.COMPLETED
        _activeSessionId.value = null

        Log.i(TAG, "Skill successfully saved: $skillId with ${currentSteps.size} steps")
        skillEntity
    }

    /**
     * Manually add an explicit workflow step (e.g. BACK, HOME, WAIT)
     */
    fun addManualStep(
        actionType: String,
        targetId: String? = null,
        targetText: String? = null,
        payload: String? = null,
        descriptionBn: String = ""
    ) {
        val currentList = _capturedSteps.value.toMutableList()
        val nextIndex = currentList.size
        val desc = descriptionBn.ifBlank {
            when (actionType) {
                "BACK" -> "পেছনে ফেরা (Back Button)"
                "HOME" -> "হোম স্ক্রিনে ফেরা"
                "WAIT" -> "অপেক্ষা করা"
                else -> "$actionType একশন সম্পাদন"
            }
        }

        val newStep = CapturedStep(
            stepIndex = nextIndex,
            actionType = actionType,
            targetViewId = targetId,
            targetText = targetText,
            inputPayload = payload,
            descriptionBengali = desc
        )
        currentList.add(newStep)
        _capturedSteps.value = currentList
    }

    // =========================================================================
    // ACCESSIBILITY EVENT PARSING & WORKFLOW INFERENCE
    // =========================================================================

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        if (_recorderState.value != SkillRecorderState.RECORDING) return

        val eventPkg = event.packageName?.toString() ?: ""
        // Skip JARVIS OS internal events
        if (eventPkg.contains("com.example") || eventPkg.contains("jarvis")) return

        if (currentTargetPackage.isBlank() && eventPkg.isNotBlank()) {
            currentTargetPackage = eventPkg
            currentTargetAppName = eventPkg.substringAfterLast('.')
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleViewClicked(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleTextChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScrollEvent(event)
            }
        }
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        val sourceNode = event.source ?: return
        val viewId = sourceNode.viewIdResourceName
        val text = sourceNode.text?.toString()?.trim() ?: event.text.joinToString(" ").trim()
        val contentDesc = sourceNode.contentDescription?.toString()?.trim()
        val className = sourceNode.className?.toString()

        val bounds = Rect()
        sourceNode.getBoundsInScreen(bounds)

        // Debounce rapid duplicate taps on same element
        if (now - lastClickTime < DEBOUNCE_CLICK_MS && viewId == lastClickedViewId && viewId != null) {
            return
        }
        lastClickTime = now
        lastClickedViewId = viewId

        // Build descriptive Bengali label
        val label = when {
            text.isNotBlank() -> "‘$text’"
            !contentDesc.isNullOrBlank() -> "‘$contentDesc’"
            !viewId.isNullOrBlank() -> "‘${viewId.substringAfterLast('/')}’ উপাদান"
            else -> "উপাদান"
        }
        val desc = "$label-এ ক্লিক করা হয়েছে"

        val currentList = _capturedSteps.value.toMutableList()
        val step = CapturedStep(
            stepIndex = currentList.size,
            actionType = "CLICK",
            targetViewId = viewId,
            targetText = text.takeIf { it.isNotBlank() },
            targetContentDescription = contentDesc.takeIf { !it.isNullOrBlank() },
            targetClassName = className,
            bounds = bounds,
            descriptionBengali = desc
        )
        currentList.add(step)
        _capturedSteps.value = currentList
        Log.d(TAG, "Captured Click Step: $desc")
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val sourceNode = event.source ?: return
        val viewId = sourceNode.viewIdResourceName
        val text = sourceNode.text?.toString()?.trim() ?: event.text.joinToString(" ").trim()
        if (text.isBlank() || text == lastInputText) return

        val className = sourceNode.className?.toString()
        val bounds = Rect()
        sourceNode.getBoundsInScreen(bounds)

        val isPasswordNode = sourceNode.isPassword
        val viewIdLower = viewId?.lowercase() ?: ""
        val isSensitiveField = isPasswordNode ||
            viewIdLower.contains("password") ||
            viewIdLower.contains("pin") ||
            viewIdLower.contains("otp") ||
            viewIdLower.contains("cvv") ||
            viewIdLower.contains("secret") ||
            viewIdLower.contains("security")

        val sanitized = if (isSensitiveField) {
            "[PROTECTED_CREDENTIAL]"
        } else {
            com.example.ai.ScreenPerceptionEngine.sanitizeText(text)
        }
        val isActuallySensitive = isSensitiveField || (sanitized != text)
        val safePayload = if (isActuallySensitive) "[PROTECTED_CREDENTIAL]" else text
        val safeDesc = if (isActuallySensitive) {
            "সুরক্ষিত সংবেদনশীল ইনপুট ফিল্ড (পাসওয়ার্ড/পিন রেড্যাক্ট করা হয়েছে)"
        } else {
            "ইনপুট ফিল্ডে ‘$text’ লেখা হয়েছে"
        }

        // Coalesce rapid keystrokes on the same input box
        pendingTextJob?.cancel()
        pendingTextJob = scope.launch {
            delay(DEBOUNCE_TEXT_MS)
            lastInputText = text

            val currentList = _capturedSteps.value.toMutableList()
            // If the last step was also an INPUT_TEXT on the same viewId, replace it
            val lastStep = currentList.lastOrNull()

            if (lastStep != null && lastStep.actionType == "INPUT_TEXT" && lastStep.targetViewId == viewId && viewId != null) {
                currentList[currentList.size - 1] = lastStep.copy(
                    inputPayload = safePayload,
                    descriptionBengali = safeDesc
                )
            } else {
                val step = CapturedStep(
                    stepIndex = currentList.size,
                    actionType = "INPUT_TEXT",
                    targetViewId = viewId,
                    targetText = null,
                    targetClassName = className,
                    bounds = bounds,
                    inputPayload = safePayload,
                    descriptionBengali = safeDesc
                )
                currentList.add(step)
            }
            _capturedSteps.value = currentList
            Log.d(TAG, "Captured Input Text Step: $safeDesc")
        }
    }

    private fun handleScrollEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 800L) return // Debounce scroll following a click

        val currentList = _capturedSteps.value.toMutableList()
        val lastStep = currentList.lastOrNull()
        if (lastStep?.actionType == "SCROLL_FORWARD" && (now - lastStep.timestamp < 1500L)) {
            return
        }

        val step = CapturedStep(
            stepIndex = currentList.size,
            actionType = "SCROLL_FORWARD",
            descriptionBengali = "স্ক্রল নিচে নেওয়া হয়েছে"
        )
        currentList.add(step)
        _capturedSteps.value = currentList
    }
}
