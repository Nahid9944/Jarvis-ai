package com.example.ai

import android.content.Context
import com.example.automation.RiskLevel
import com.example.tools.ConfirmationLevel
import com.example.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlannedSubTask(
    val stepIndex: Int,
    val rawCommand: String,
    val toolName: String?,
    val args: JSONObject = JSONObject(),
    val assignedAgent: AiAgent,
    val status: String = "PENDING", // PENDING, SUCCEEDED, FAILED, SKIPPED, CANCELLED, PERMISSION_REQUIRED, CONFIRMATION_REQUIRED
    val summaryBengali: String = "",
    val permissionRequired: String = ""
)

data class PendingSlotRequest(
    val intentType: String, // CALL, WHATSAPP, SMS
    val partialTool: String,
    val partialArgs: JSONObject,
    val missingSlot: String,
    val promptBn: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class AgentSelectionResult(
    val controllerAgent: AiAgent = AiAgentRegistry.JARVIS_MASTER,
    val primarySpecialistAgent: AiAgent,
    val supportingAgents: List<AiAgent> = emptyList(),
    val intentType: CanonicalIntent = CanonicalIntent.CONVERSATIONAL,
    val rationaleBn: String = ""
)

data class MasterExecutionPlan(
    val planId: String = UUID.randomUUID().toString(),
    val goal: String,
    val primaryAgent: AiAgent,
    val subTasks: List<PlannedSubTask>,
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean,
    val personalityMode: JarvisPersonalityMode,
    val detectedEmotion: UserEmotion,
    val controllerAgent: AiAgent = AiAgentRegistry.JARVIS_MASTER,
    val primarySpecialistAgent: AiAgent = primaryAgent,
    val supportingAgents: List<AiAgent> = emptyList(),
    val intentType: CanonicalIntent = CanonicalIntent.CONVERSATIONAL,
    val executionMode: String = "STANDARD",
    val dependencies: List<String> = emptyList(),
    val retryPolicy: String = "BOUNDED_2",
    val timeoutPolicy: String = "45S"
)

data class MasterOrchestrationResult(
    val success: Boolean,
    val finalState: String, // SUCCEEDED, PARTIALLY_COMPLETED, FAILED, CANCELLED, PAUSED, INTERRUPTED, PERSONALITY_SWITCHED, CONVERSATIONAL, WAITING_FOR_USER, REQUIRES_PERMISSION, REQUIRES_CONFIRMATION
    val plan: MasterExecutionPlan,
    val executedSteps: List<PlannedSubTask>,
    val summaryBengali: String,
    val needsConfirmation: Boolean = false,
    val needsPermission: Boolean = false
)

/**
 * Unified Master Agent Orchestrator (One Brain Architecture):
 * Coordinates specialist agents from AiAgentRegistry, EmotionContextEngine,
 * JarvisPersonalityManager, ActiveTaskInterruptionManager, TaskReplacementPlanner,
 * ExecutionCommitController, and AssistantToolExecutor without fake success.
 */
object JarvisMasterOrchestrator {

    private const val MAX_SUBTASKS = 6
    private const val COMMAND_DEDUP_WINDOW_MS = 1800L
    private const val TOGGLE_DEDUP_WINDOW_MS = 500L

    private val inFlightKeys = ConcurrentHashMap<String, Boolean>()
    private val recentExecutionTimestamps = ConcurrentHashMap<String, Long>()

    private val _activePlan = MutableStateFlow<MasterExecutionPlan?>(null)
    val activePlan: StateFlow<MasterExecutionPlan?> = _activePlan.asStateFlow()

    private val _selectedSpecialist = MutableStateFlow(AiAgentRegistry.JARVIS_MASTER)
    val selectedSpecialist: StateFlow<AiAgent> = _selectedSpecialist.asStateFlow()

    private val _lastOrchestrationResult = MutableStateFlow<MasterOrchestrationResult?>(null)
    val lastOrchestrationResult: StateFlow<MasterOrchestrationResult?> = _lastOrchestrationResult.asStateFlow()

    private val _pendingSlotRequest = MutableStateFlow<PendingSlotRequest?>(null)
    val pendingSlotRequest: StateFlow<PendingSlotRequest?> = _pendingSlotRequest.asStateFlow()

    private val _preferredUserAgent = MutableStateFlow<AiAgent?>(null)

    private val _latestVisionAnalysis = MutableStateFlow<ScreenVisualAnalysis?>(null)
    val latestVisionAnalysis: StateFlow<ScreenVisualAnalysis?> = _latestVisionAnalysis.asStateFlow()

    fun recordVisionAnalysisContext(analysis: ScreenVisualAnalysis) {
        _latestVisionAnalysis.value = analysis
    }

    fun setUserSelectedAgent(agent: AiAgent) {
        _preferredUserAgent.value = agent
        _selectedSpecialist.value = agent
    }

    fun clearPendingSlotRequest() {
        _pendingSlotRequest.value = null
    }

    fun resetForTest() {
        _activePlan.value = null
        _lastOrchestrationResult.value = null
        _pendingSlotRequest.value = null
        _latestVisionAnalysis.value = null
        _preferredUserAgent.value = null
        _selectedSpecialist.value = AiAgentRegistry.JARVIS_MASTER
        inFlightKeys.clear()
        recentExecutionTimestamps.clear()
    }

    private fun acquireCommandSlot(dedupKey: String, cooldownMs: Long, bypass: Boolean): String? {
        if (bypass) return null
        if (inFlightKeys.putIfAbsent(dedupKey, true) != null) {
            return "IN_FLIGHT"
        }
        val now = System.currentTimeMillis()
        val prev = recentExecutionTimestamps[dedupKey]
        if (prev != null && (now - prev) < cooldownMs) {
            inFlightKeys.remove(dedupKey)
            return "COOLDOWN"
        }
        recentExecutionTimestamps[dedupKey] = now
        return null
    }

    private fun releaseCommandSlot(dedupKey: String, bypass: Boolean, clearCooldown: Boolean = false) {
        if (!bypass) {
            inFlightKeys.remove(dedupKey)
            if (clearCooldown) {
                recentExecutionTimestamps.remove(dedupKey)
            }
        }
    }

    /**
     * Selects the appropriate domain specialist agent under JARVIS_MASTER controller governance.
     */
    fun selectSpecialistAgent(utterance: String, toolName: String? = null): AiAgent {
        val lower = java.text.Normalizer.normalize(utterance, java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val t = toolName?.lowercase(Locale.ROOT).orEmpty()
        val isAppQuestion = UnifiedBrainLanguageEngine.isQuestionOrAnalysisAboutApp(utterance)

        return when {
            t.contains("notification") || t == "suggestsmartreplies" ||
                lower.contains("নোটিফিকেশন") || lower.contains("notification") || lower.contains("otp") || lower.contains("ওটিপি") ->
                AiAgentRegistry.NOTIFICATION_AGENT

            t.contains("call") || t.contains("sms") || t.contains("whatsapp") || t.contains("contact") ||
                (!isAppQuestion && (
                    lower.contains("কল") || lower.contains("ফোন") || lower.contains("sms") || lower.contains("এসএমএস") ||
                    lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") ||
                    lower.contains("মেসেজ") || lower.contains("বার্তা")
                )) ->
                AiAgentRegistry.COMMUNICATION_AGENT

            t.contains("screen") || t.contains("click") || t.contains("scroll") || t.contains("swipe") ||
                t.contains("type") || t.contains("autonomous") || t.contains("accessibility") ||
                lower.contains("স্ক্রল") || lower.contains("ক্লিক") || lower.contains("স্ক্রিন") || lower.contains("টাইপ") ->
                AiAgentRegistry.AUTOMATION_AGENT

            t.contains("camera") || t.contains("vision") ||
                lower.contains("ক্যামেরা") || lower.contains("ছবি") ||
                lower.contains("ছবিটা") || lower.contains("স্ক্রিন দেখো") ->
                AiAgentRegistry.VISION_AGENT

            t.contains("memory") || t.contains("note") ||
                lower.contains("মনে রাখ") || lower.contains("নোট") || lower.contains("মেমোরি") ->
                AiAgentRegistry.MEMORY_AGENT

            t.contains("task") || t.contains("alarm") || t.contains("routine") || t.contains("briefing") ||
                lower.contains("রিমাইন্ডার") || lower.contains("অ্যালার্ম") || lower.contains("রুটিন") || lower.contains("ব্রিফিং") ->
                AiAgentRegistry.TASK_AGENT

            t.contains("permission") || t.contains("emergency") || t.contains("security") ||
                lower.contains("পারমিশন") || lower.contains("সিকিউরিটি") || lower.contains("স্টপ") ->
                AiAgentRegistry.SECURITY_AGENT

            t.contains("intelligent") || t == "intelligentknowledgequery" || t == "deepresearchweb" ||
                t == "searchexternalknowledge" || t == "getliveweather" || t == "getlivenews" ||
                t == "searchwikipedia" || t == "getknowledgesystemstatus" || t == "canceldeepresearch" ||
                lower.contains("গভীর গবেষণা") || lower.contains("ডিপ রিসার্চ") || lower.contains("deep research") ||
                lower.contains("ক্রস চেক") || lower.contains("ক্রস-চেক") || lower.contains("cross check") ||
                lower.contains("আবহাওয়া") || lower.contains("weather") || lower.contains("খবর") ||
                lower.contains("সংবাদ") || lower.contains("news") || lower.contains("উইকিপিডিয়া") ||
                lower.contains("wikipedia") || lower.contains("সার্চ") ||
                ((lower.contains("search") || lower.contains("research") || lower.contains("circular") || lower.contains("policy")) &&
                    !lower.contains("youtube") && !lower.contains("ইউটিউব") && !lower.contains("github") && !lower.contains("গিটহাব")) ||
                lower.contains("নীতিমালা") || lower.contains("সরকারি") ||
                lower.contains("গবেষণা") || UnifiedBrainLanguageEngine.isQuestionOrAnalysisAboutApp(utterance) ->
                AiAgentRegistry.INTELLIGENT_AGENT

            t.contains("github") || t.contains("youtube") ||
                lower.contains("github") || lower.contains("গিটহাব") || lower.contains("youtube") || lower.contains("ইউটিউব") ->
                AiAgentRegistry.HOMER_AGENT

            t.contains("battery") || t.contains("flashlight") || t.contains("torch") || t.contains("gps") ||
                t.contains("location") || t.contains("settings") || t.contains("launch") || t.contains("volume") ||
                lower.contains("ব্যাটারি") || lower.contains("ফ্ল্যাশলাইট") || lower.contains("টর্চ") ||
                lower.contains("ব্রাইটনেস") || lower.contains("ভলিউম") || lower.contains("লোকেশন") ->
                AiAgentRegistry.EDITH_AGENT

            t.contains("homedev") || t.contains("selfrepair") || t.contains("selfdevelopment") ||
                t == "createcombinedskill" || t == "learndocument" || t == "learnfromdocument" ||
                t == "learnfromscreenshot" || t == "generatedeveloperartifact" ||
                lower.contains("home developer") || lower.contains("হোম ডেভেলপার") ||
                lower.contains("সেল্ফ রিপেয়ার") || lower.contains("সেল্ফ ডেভেলপমেন্ট") ||
                lower.contains("স্কিল কম্বাইন") || lower.contains("কোড প্যাচ") ||
                lower.contains("ওয়েব অ্যাপ") ->
                AiAgentRegistry.HOME_DEVELOPER

            else -> AiAgentRegistry.JARVIS_MASTER
        }
    }

    /**
     * Explicit Master-Specialist hierarchy selector used by Brain Planning & Live Voice sessions.
     */
    fun selectAgentsWithMasterHierarchy(utterance: String, toolName: String? = null): AgentSelectionResult {
        val structured = UnifiedBrainLanguageEngine.analyze(utterance, updateReferenceContext = false)
        val firstSegment = structured.compoundSegments.firstOrNull().orEmpty().ifBlank { structured.resolvedQuery }
        val primarySpecialist = selectSpecialistAgent(firstSegment, toolName)
        val supporting = mutableListOf<AiAgent>()
        if (structured.primaryIntent == CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH && primarySpecialist.id != AiAgentRegistry.INTELLIGENT_AGENT.id) {
            supporting.add(AiAgentRegistry.INTELLIGENT_AGENT)
        }
        if (structured.compoundSegments.size > 1) {
            structured.compoundSegments.drop(1).forEach { seg ->
                val segAgent = selectSpecialistAgent(seg)
                if (segAgent.id != primarySpecialist.id && segAgent.id != AiAgentRegistry.JARVIS_MASTER.id && segAgent !in supporting) {
                    supporting.add(segAgent)
                }
            }
        }
        if (structured.subIntents.size > 1) {
            structured.subIntents.drop(1).forEach { subIntent ->
                val subAgent = when (subIntent) {
                    CanonicalIntent.KNOWLEDGE_FAST, CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH -> AiAgentRegistry.INTELLIGENT_AGENT
                    CanonicalIntent.COMMUNICATION_CALL, CanonicalIntent.COMMUNICATION_SMS, CanonicalIntent.COMMUNICATION_WHATSAPP -> AiAgentRegistry.COMMUNICATION_AGENT
                    CanonicalIntent.AUTOMATION_UI -> AiAgentRegistry.AUTOMATION_AGENT
                    CanonicalIntent.SCREEN_VISION -> AiAgentRegistry.VISION_AGENT
                    CanonicalIntent.NOTIFICATION_INTEL -> AiAgentRegistry.NOTIFICATION_AGENT
                    CanonicalIntent.MEMORY_MANAGE -> AiAgentRegistry.MEMORY_AGENT
                    CanonicalIntent.TASK_SCHEDULE -> AiAgentRegistry.TASK_AGENT
                    CanonicalIntent.DEVICE_CONTROL, CanonicalIntent.APP_LAUNCH, CanonicalIntent.NAVIGATION_ROUTE -> AiAgentRegistry.EDITH_AGENT
                    CanonicalIntent.HOME_DEV -> AiAgentRegistry.HOME_DEVELOPER
                    else -> null
                }
                if (subAgent != null && subAgent.id != primarySpecialist.id && subAgent.id != AiAgentRegistry.JARVIS_MASTER.id && subAgent !in supporting) {
                    supporting.add(subAgent)
                }
            }
        }
        return AgentSelectionResult(
            controllerAgent = AiAgentRegistry.JARVIS_MASTER,
            primarySpecialistAgent = primarySpecialist,
            supportingAgents = supporting,
            intentType = structured.primaryIntent,
            rationaleBn = "সেন্ট্রাল কন্ট্রোলার JARVIS_MASTER '${primarySpecialist.titleBengali}'-কে প্রাইমারি স্পেশালিস্ট হিসেবে নির্বাচিত করেছে।"
        )
    }

    /**
     * Splits compound multi-step commands ("আগে X করো তারপর Y করো" / "and then") into bounded subtasks.
     */
    fun decomposeGoal(utterance: String): List<String> {
        return UnifiedBrainLanguageEngine.splitCompoundCommands(utterance).take(MAX_SUBTASKS)
    }

    fun buildPlan(
        utterance: String,
        toolNameOverride: String? = null,
        toolArgsOverride: JSONObject? = null
    ): MasterExecutionPlan {
        val emotionEval = EmotionContextEngine.analyzeText(utterance)
        val personality = JarvisPersonalityManager.currentMode.value
        val structured = UnifiedBrainLanguageEngine.analyze(utterance, updateReferenceContext = false)
        val parts = if (toolNameOverride != null) listOf(utterance) else decomposeGoal(structured.resolvedQuery)

        val subTasks = parts.mapIndexed { idx, part ->
            val resolved = if (toolNameOverride != null) {
                Pair(toolNameOverride, toolArgsOverride ?: JSONObject())
            } else {
                VoiceCommandUnderstandingEngine.resolveToolMapping(part)
            }
            val toolName = resolved?.first
            val args = resolved?.second ?: JSONObject()
            val specialist = selectSpecialistAgent(part, toolName)
            PlannedSubTask(
                stepIndex = idx + 1,
                rawCommand = part,
                toolName = toolName,
                args = args,
                assignedAgent = specialist
            )
        }

        val highestRisk: RiskLevel = subTasks.mapNotNull { st ->
            st.toolName?.let { ToolRegistry.get(it)?.riskScore }?.let { score ->
                when {
                    score >= 85 -> RiskLevel.DESTRUCTIVE
                    score >= 60 -> RiskLevel.EXTERNAL_COMM
                    score >= 30 -> RiskLevel.INTERACTION
                    score >= 15 -> RiskLevel.SAFE_NAVIGATION
                    else -> RiskLevel.READ_ONLY
                }
            }
        }.maxByOrNull { it.ordinal } ?: RiskLevel.READ_ONLY

        val needsConf = subTasks.any { st ->
            val spec = st.toolName?.let { ToolRegistry.get(it) }
            spec != null && spec.confirmationLevel != ConfirmationLevel.NONE
        }

        val selection = selectAgentsWithMasterHierarchy(utterance, subTasks.firstOrNull()?.toolName ?: toolNameOverride)
        val primary = subTasks.firstOrNull()?.assignedAgent ?: selection.primarySpecialistAgent
        val execMode = when (structured.primaryIntent) {
            CanonicalIntent.KNOWLEDGE_FAST -> "FAST_KNOWLEDGE"
            CanonicalIntent.KNOWLEDGE_DEEP_RESEARCH -> "DEEP_RESEARCH"
            else -> if (subTasks.size > 1) "MULTI_STEP_SERIAL" else "STANDARD"
        }
        val dependencies = if (subTasks.size > 1) {
            subTasks.zipWithNext().map { (a, b) -> "step_${a.stepIndex}->step_${b.stepIndex}" }
        } else emptyList()

        val plan = MasterExecutionPlan(
            goal = utterance.trim(),
            primaryAgent = primary,
            subTasks = subTasks,
            riskLevel = highestRisk,
            requiresConfirmation = needsConf,
            personalityMode = personality,
            detectedEmotion = emotionEval.emotion,
            controllerAgent = AiAgentRegistry.JARVIS_MASTER,
            primarySpecialistAgent = primary,
            supportingAgents = selection.supportingAgents,
            intentType = structured.primaryIntent,
            executionMode = execMode,
            dependencies = dependencies,
            retryPolicy = if (highestRisk.ordinal >= RiskLevel.EXTERNAL_COMM.ordinal) "NO_BLIND_RETRY" else "BOUNDED_2",
            timeoutPolicy = if (execMode == "DEEP_RESEARCH") "12S_BOUNDED_PARALLEL" else "45S"
        )
        _activePlan.value = plan
        _selectedSpecialist.value = primary
        return plan
    }

    /**
     * Centralized structured tool execution entry point used by Gemini Live voice tool calls,
     * Archer HUD quick shortcuts (LIVE CAMERA, FRONT/BACK, SHARE SCREEN), and automated triggers.
     * Integrates agent selection, MasterExecutionPlan tracking, redundant execution prevention,
     * and ToolExecutionPipeline / JarvisPermissionManager enforcement.
     */
    suspend fun orchestrateToolCall(
        context: Context?,
        toolName: String,
        args: JSONObject = JSONObject(),
        caller: String = "gemini_live_voice",
        toolExecutor: AssistantToolExecutor?
    ): JSONObject {
        val cleanTool = toolName.trim()
        if (toolExecutor == null) {
            return JSONObject().apply {
                put("success", false)
                put("status", "FAILED")
                put("summaryBengali", "টুল এক্সিকিউটর অনুপলব্ধ থাকায় '$cleanTool' চালানো যায়নি।")
            }
        }

        val canonicalArgs = JSONObject(args.toString()).apply {
            remove("_approvalId")
            remove("_executionId")
            remove("_confirmationToken")
        }
        val dedupKey = "tool:$cleanTool:${canonicalArgs.toString().lowercase(Locale.ROOT)}"
        val isToggleOrCamera = cleanTool in setOf(
            "switchCameraLens", "startCameraVision", "stopCameraVision",
            "startScreenSharing", "stopScreenSharing"
        )
        val cooldown = if (isToggleOrCamera) TOGGLE_DEDUP_WINDOW_MS else COMMAND_DEDUP_WINDOW_MS
        val bypassDedup = cleanTool == "emergencyStopAllWork" ||
            caller == "auto_resume" ||
            args.has("_approvalId") ||
            args.has("_confirmationToken")

        val blockedReason = acquireCommandSlot(dedupKey, cooldown, bypassDedup)
        if (blockedReason != null) {
            return JSONObject().apply {
                put("success", false)
                put("status", "DUPLICATE_IGNORED")
                put("reason", blockedReason)
                put("toolName", cleanTool)
                put("summaryBengali", "'$cleanTool' কমান্ডটি ইতিমধ্যে প্রক্রিয়াকরণ করা হচ্ছে।")
            }
        }

        try {
            val plan = buildPlan(
                utterance = cleanTool,
                toolNameOverride = cleanTool,
                toolArgsOverride = args
            )
            val specialist = plan.primaryAgent
            _selectedSpecialist.value = specialist

            val activeTask = if (cleanTool != "emergencyStopAllWork") {
                ActiveTaskInterruptionManager.registerNewTask(
                    goalBn = cleanTool,
                    toolName = cleanTool,
                    steps = listOf(specialist.titleBengali, cleanTool)
                )
            } else null

            val jsonRes = toolExecutor.executeToolWithCaller(cleanTool, args, caller)
            val statusStr = jsonRes.optString("status", "")
            val ok = jsonRes.optBoolean("success", false)
            val summary = jsonRes.optString("summaryBengali", if (ok) "সম্পন্ন" else "ব্যর্থ")
            val permReq = jsonRes.optString("permissionRequired", "")

            if (activeTask != null) {
                ActiveTaskInterruptionManager.completeTask(activeTask.taskId, ok, summary)
            }

            val stepStatus = when {
                statusStr == "PERMISSION_REQUIRED" -> "PERMISSION_REQUIRED"
                statusStr == "CONFIRMATION_REQUIRED" -> "CONFIRMATION_REQUIRED"
                statusStr == "CANCELLED_BEFORE_DISPATCH" || statusStr == "CANCELLED" -> "CANCELLED"
                ok -> "SUCCEEDED"
                else -> "FAILED"
            }
            val updatedStep = plan.subTasks.first().copy(
                status = stepStatus,
                summaryBengali = summary,
                permissionRequired = permReq
            )
            val finalState = when {
                statusStr == "PERMISSION_REQUIRED" -> "REQUIRES_PERMISSION"
                statusStr == "CONFIRMATION_REQUIRED" -> "REQUIRES_CONFIRMATION"
                statusStr == "CANCELLED_BEFORE_DISPATCH" || statusStr == "CANCELLED" -> "CANCELLED"
                ok -> "SUCCEEDED"
                else -> "FAILED"
            }
            _lastOrchestrationResult.value = MasterOrchestrationResult(
                success = ok,
                finalState = finalState,
                plan = plan.copy(subTasks = listOf(updatedStep)),
                executedSteps = listOf(updatedStep),
                summaryBengali = summary,
                needsConfirmation = statusStr == "CONFIRMATION_REQUIRED",
                needsPermission = statusStr == "PERMISSION_REQUIRED"
            )
            if (finalState in setOf("REQUIRES_PERMISSION", "REQUIRES_CONFIRMATION", "CANCELLED")) {
                recentExecutionTimestamps.remove(dedupKey)
            }
            return jsonRes
        } finally {
            releaseCommandSlot(dedupKey, bypassDedup)
        }
    }

    /**
     * Orchestrates complete execution of a natural-language user goal or explicit tool call:
     * deduplication -> emotion sensing -> personality switching -> interruption handling ->
     * planning -> specialist tool execution -> partial-failure & verification aggregation.
     */
    suspend fun orchestrate(
        context: Context?,
        utterance: String,
        toolExecutor: AssistantToolExecutor?,
        caller: String = "master_orchestrator",
        confirmationToken: String? = null
    ): MasterOrchestrationResult {
        val cleanInput = utterance.trim()
        val plan = buildPlan(cleanInput)

        val hasPendingGate = com.example.permissions.JarvisPermissionManager.pendingUserApproval.value != null ||
            _pendingSlotRequest.value != null
        val isStopCommand = ActiveTaskInterruptionManager.classifyInterruptionIntent(cleanInput) == InterruptionIntent.EMERGENCY_STOP
        val bypassDedup = cleanInput.isBlank() ||
            hasPendingGate ||
            isStopCommand ||
            toolExecutor == null ||
            !confirmationToken.isNullOrBlank()
        val utteranceKey = "utterance:${cleanInput.lowercase(Locale.ROOT)}"
        val blockedReason = acquireCommandSlot(utteranceKey, COMMAND_DEDUP_WINDOW_MS, bypassDedup)
        if (blockedReason != null) {
            return MasterOrchestrationResult(
                success = false,
                finalState = "DUPLICATE_IGNORED",
                plan = plan,
                executedSteps = emptyList(),
                summaryBengali = "কমান্ডটি ইতিমধ্যে প্রক্রিয়াকরণ করা হচ্ছে।"
            )
        }

        try {

        // 0. Pending One-Time Confirmation Intercept ("হ্যাঁ", "হ্যাঁ করো", "অনুমতি দিলাম", "না", etc.)
        val pendingApproval = com.example.permissions.JarvisPermissionManager.pendingUserApproval.value
        if (pendingApproval != null && toolExecutor != null) {
            when (com.example.permissions.ActionApprovalRegistry.classifyApprovalReply(cleanInput)) {
                com.example.permissions.ApprovalReplyDecision.APPROVE -> {
                    val approved = com.example.permissions.JarvisPermissionManager.approveUserAction(
                        source = caller,
                        approvalId = pendingApproval.approvalId
                    )
                    if (approved != null) {
                        val approvedArgs = try {
                            JSONObject(approved.argsJson)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        approvedArgs.put("_approvalId", approved.approvalId)
                        approvedArgs.put("_executionId", approved.executionId)
                        val jsonRes = toolExecutor.executeToolWithCaller(approved.toolName, approvedArgs, caller)
                        val statusStr = jsonRes.optString("status", "")
                        val ok = jsonRes.optBoolean("success", false)
                        val permReq = jsonRes.optString("permissionRequired", "")
                        val summary = jsonRes.optString("summaryBengali", if (ok) "অনুমোদিত কাজ সফলভাবে সম্পন্ন হয়েছে।" else "কাজটি সম্পন্ন করা যায়নি।")
                        val step = PlannedSubTask(
                            stepIndex = 1,
                            rawCommand = cleanInput,
                            toolName = approved.toolName,
                            args = approvedArgs,
                            assignedAgent = selectSpecialistAgent(cleanInput, approved.toolName),
                            status = when {
                                statusStr == "PERMISSION_REQUIRED" -> "PERMISSION_REQUIRED"
                                statusStr == "CONFIRMATION_REQUIRED" -> "CONFIRMATION_REQUIRED"
                                ok -> "SUCCEEDED"
                                else -> "FAILED"
                            },
                            summaryBengali = summary,
                            permissionRequired = permReq
                        )
                        val finalState = when {
                            statusStr == "PERMISSION_REQUIRED" -> "REQUIRES_PERMISSION"
                            statusStr == "CONFIRMATION_REQUIRED" -> "REQUIRES_CONFIRMATION"
                            ok -> "SUCCEEDED"
                            else -> "FAILED"
                        }
                        val res = MasterOrchestrationResult(
                            success = ok,
                            finalState = finalState,
                            plan = plan.copy(subTasks = listOf(step)),
                            executedSteps = listOf(step),
                            summaryBengali = summary,
                            needsConfirmation = statusStr == "CONFIRMATION_REQUIRED",
                            needsPermission = statusStr == "PERMISSION_REQUIRED"
                        )
                        _lastOrchestrationResult.value = res
                        return res
                    }
                }
                com.example.permissions.ApprovalReplyDecision.REJECT -> {
                    com.example.permissions.JarvisPermissionManager.rejectUserAction(source = caller)
                    val res = MasterOrchestrationResult(
                        success = true,
                        finalState = "CANCELLED",
                        plan = plan,
                        executedSteps = emptyList(),
                        summaryBengali = "ঠিক আছে, '${pendingApproval.reasonBn}' কাজটি বাতিল করা হয়েছে।"
                    )
                    _lastOrchestrationResult.value = res
                    return res
                }
                com.example.permissions.ApprovalReplyDecision.CORRECTION -> {
                    com.example.permissions.JarvisPermissionManager.rejectUserAction(
                        source = "user_correction",
                        reason = "ব্যবহারকারী নির্দেশ সংশোধন করেছেন"
                    )
                }
                com.example.permissions.ApprovalReplyDecision.UNRELATED -> Unit
            }
        }

        // 1. Personality switching by voice / text
        val switchedPersonality = JarvisPersonalityManager.parseVoicePersonalitySwitch(cleanInput)
        if (switchedPersonality != null) {
            JarvisPersonalityManager.setPersonality(switchedPersonality)
            val result = MasterOrchestrationResult(
                success = true,
                finalState = "PERSONALITY_SWITCHED",
                plan = plan.copy(personalityMode = switchedPersonality),
                executedSteps = emptyList(),
                summaryBengali = "পার্সোনালিটি মোড পরিবর্তন করা হয়েছে: ${switchedPersonality.titleBengali}। (${switchedPersonality.shortDescriptionBn})"
            )
            _lastOrchestrationResult.value = result
            return result
        }

        // 2. Interruption & Active Task Control check
        val activeTask = ActiveTaskInterruptionManager.currentTask.value
        val interruptionIntent = ActiveTaskInterruptionManager.classifyInterruptionIntent(cleanInput)
        val shouldHandleInterruption = interruptionIntent == InterruptionIntent.EMERGENCY_STOP ||
            interruptionIntent == InterruptionIntent.RESUME_TASK ||
            (activeTask != null && interruptionIntent in setOf(
                InterruptionIntent.STOP_SPEECH_ONLY,
                InterruptionIntent.PAUSE_CURRENT_TASK,
                InterruptionIntent.CANCEL_CURRENT_TASK,
                InterruptionIntent.REPLACE_CURRENT_TASK,
                InterruptionIntent.MODIFY_TASK_PARAMETER
            ))

        if (shouldHandleInterruption) {
            val handled = LiveInterruptionCoordinator.handleInterruptionUtterance(cleanInput)
            if (interruptionIntent == InterruptionIntent.EMERGENCY_STOP && toolExecutor != null) {
                toolExecutor.executeToolWithCaller("emergencyStopAllWork", JSONObject(), caller)
            }
            if (interruptionIntent == InterruptionIntent.REPLACE_CURRENT_TASK && handled.replacementTask != null && toolExecutor != null) {
                val rep = handled.replacementTask
                val repArgs = JSONObject(rep.params.mapValues { it.value ?: "" })
                if (!confirmationToken.isNullOrBlank()) repArgs.put("_confirmationToken", confirmationToken)
                val execResult = toolExecutor.executeToolWithCaller(rep.toolName, repArgs, caller)
                val ok = execResult.optBoolean("success", false)
                ActiveTaskInterruptionManager.completeTask(rep.taskId, ok, execResult.optString("summaryBengali", handled.messageBengali))
                val repStep = PlannedSubTask(
                    stepIndex = 1,
                    rawCommand = cleanInput,
                    toolName = rep.toolName,
                    args = repArgs,
                    assignedAgent = selectSpecialistAgent(cleanInput, rep.toolName),
                    status = if (ok) "SUCCEEDED" else "FAILED",
                    summaryBengali = execResult.optString("summaryBengali", handled.messageBengali)
                )
                val res = MasterOrchestrationResult(
                    success = ok,
                    finalState = if (ok) "SUCCEEDED" else "FAILED",
                    plan = plan,
                    executedSteps = listOf(repStep),
                    summaryBengali = execResult.optString("summaryBengali", handled.messageBengali)
                )
                _lastOrchestrationResult.value = res
                return res
            }

            val finalState = when (interruptionIntent) {
                InterruptionIntent.PAUSE_CURRENT_TASK -> "PAUSED"
                InterruptionIntent.CANCEL_CURRENT_TASK, InterruptionIntent.EMERGENCY_STOP -> "CANCELLED"
                else -> "INTERRUPTED"
            }
            val res = MasterOrchestrationResult(
                success = handled.handled,
                finalState = finalState,
                plan = plan,
                executedSteps = emptyList(),
                summaryBengali = handled.messageBengali
            )
            _lastOrchestrationResult.value = res
            return res
        }

        // 2.5 Multi-Turn Slot-Filling Continuation (e.g., user answers "আব্বু" or "আব্বুকে বলো আমি আসছি")
        var workingPlan = plan
        val activeSlot = _pendingSlotRequest.value
        val slotExpired = activeSlot != null && (System.currentTimeMillis() - activeSlot.createdAt) > 180_000L
        if (slotExpired) _pendingSlotRequest.value = null
        if (activeSlot != null && !slotExpired && workingPlan.subTasks.none { it.toolName != null }) {
            val lowerReply = cleanInput.lowercase(Locale.ROOT)
            if (lowerReply in listOf("বাতিল", "বাদ দাও", "cancel", "না", "no")) {
                _pendingSlotRequest.value = null
                val res = MasterOrchestrationResult(
                    success = true,
                    finalState = "CANCELLED",
                    plan = workingPlan,
                    executedSteps = emptyList(),
                    summaryBengali = "অসম্পূর্ণ নির্দেশটি বাতিল করা হয়েছে।"
                )
                _lastOrchestrationResult.value = res
                return res
            }
            val mergedArgs = JSONObject(activeSlot.partialArgs.toString())
            when (activeSlot.intentType) {
                "CALL" -> {
                    mergedArgs.put("contactName", cleanInput)
                    mergedArgs.put("phoneNumber", cleanInput)
                }
                "WHATSAPP", "SMS" -> {
                    val splitParts = cleanInput.split(Regex("""\s+(?:কে|বলো|লিখে দাও|লিখো|to|:|-)\s+""", RegexOption.IGNORE_CASE), limit = 2)
                    if (splitParts.size == 2 && splitParts[0].isNotBlank() && splitParts[1].isNotBlank()) {
                        if (activeSlot.intentType == "SMS") {
                            mergedArgs.put("phoneNumber", splitParts[0].trim())
                            mergedArgs.put("contactName", splitParts[0].trim())
                        } else {
                            mergedArgs.put("contact", splitParts[0].trim())
                        }
                        mergedArgs.put("message", splitParts[1].trim())
                    } else if (!mergedArgs.has("contact") && !mergedArgs.has("phoneNumber")) {
                        if (activeSlot.intentType == "SMS") {
                            mergedArgs.put("phoneNumber", cleanInput)
                            mergedArgs.put("contactName", cleanInput)
                        } else {
                            mergedArgs.put("contact", cleanInput)
                        }
                        mergedArgs.put("message", "")
                    } else {
                        mergedArgs.put("message", cleanInput)
                    }
                }
            }
            _pendingSlotRequest.value = null
            val resolvedStep = PlannedSubTask(
                stepIndex = 1,
                rawCommand = cleanInput,
                toolName = activeSlot.partialTool,
                args = mergedArgs,
                assignedAgent = selectSpecialistAgent(cleanInput, activeSlot.partialTool)
            )
            workingPlan = workingPlan.copy(
                primaryAgent = resolvedStep.assignedAgent,
                subTasks = listOf(resolvedStep)
            )
        }

        // 2.75 Learned Skill Check & Incomplete Communication Slot Detection
        if (workingPlan.subTasks.none { it.toolName != null }) {
            if (context != null) {
                val matchedSkill = try {
                    com.example.data.repository.JarvisSkillRepository(
                        com.example.data.local.AssistantDatabase.getDatabase(context).jarvisSkillDao()
                    ).findMatchingSkill(cleanInput)
                } catch (_: Exception) {
                    null
                }
                if (matchedSkill != null && matchedSkill.status.equals("ACTIVE", ignoreCase = true) && matchedSkill.stepCount > 0) {
                    val skillStep = PlannedSubTask(
                        stepIndex = 1,
                        rawCommand = cleanInput,
                        toolName = "executeLearnedSkill",
                        args = JSONObject().put("skillNameOrTrigger", cleanInput),
                        assignedAgent = AiAgentRegistry.AUTOMATION_AGENT
                    )
                    workingPlan = workingPlan.copy(
                        primaryAgent = AiAgentRegistry.AUTOMATION_AGENT,
                        subTasks = listOf(skillStep)
                    )
                }
            }

            if (workingPlan.subTasks.none { it.toolName != null }) {
                val slotPrompt = detectMissingCommunicationSlot(cleanInput)
                if (slotPrompt != null) {
                    _pendingSlotRequest.value = slotPrompt
                    val res = MasterOrchestrationResult(
                        success = false,
                        finalState = "WAITING_FOR_USER",
                        plan = workingPlan,
                        executedSteps = emptyList(),
                        summaryBengali = slotPrompt.promptBn
                    )
                    _lastOrchestrationResult.value = res
                    return res
                }
            }
        }

        // 3. No actionable tools found -> report as conversational / guidance needed
        if (workingPlan.subTasks.none { it.toolName != null }) {
            val res = MasterOrchestrationResult(
                success = false,
                finalState = "CONVERSATIONAL",
                plan = workingPlan,
                executedSteps = emptyList(),
                summaryBengali = "এই নির্দেশের জন্য সরাসরি কোনো ডিভাইস টুল ম্যাচ হয়নি; সাধারণ কথোপকথন বা Gemini Live সেশনে উত্তর দিন।"
            )
            _lastOrchestrationResult.value = res
            return res
        }

        if (toolExecutor == null) {
            val res = MasterOrchestrationResult(
                success = false,
                finalState = "FAILED",
                plan = workingPlan,
                executedSteps = emptyList(),
                summaryBengali = "টুল এক্সিকিউটর অনুপস্থিত থাকায় কাজ চালানো সম্ভব হয়নি।"
            )
            _lastOrchestrationResult.value = res
            return res
        }

        // 4. Execute each planned subtask sequentially through verified pipeline
        val completedSubTasks = mutableListOf<PlannedSubTask>()
        var anyNeedsConfirmation = false
        var anyNeedsPermission = false

        for (step in workingPlan.subTasks) {
            val toolName = step.toolName
            if (toolName == null) {
                completedSubTasks.add(
                    step.copy(status = "SKIPPED", summaryBengali = "অ্যাকশন চিহ্নিত করা যায়নি: ${step.rawCommand}")
                )
                continue
            }

            _selectedSpecialist.value = step.assignedAgent
            val args = JSONObject(step.args.toString())
            if (!confirmationToken.isNullOrBlank() && !args.has("_confirmationToken")) {
                args.put("_confirmationToken", confirmationToken)
            }

            val activeExecTask = ActiveTaskInterruptionManager.registerNewTask(
                goalBn = step.rawCommand,
                toolName = toolName,
                steps = listOf(step.assignedAgent.titleBengali, toolName)
            )
            val jsonRes = toolExecutor.executeToolWithCaller(toolName, args, caller)
            val statusStr = jsonRes.optString("status", "")
            val ok = jsonRes.optBoolean("success", false)
            val summary = jsonRes.optString("summaryBengali", if (ok) "সম্পন্ন" else "ব্যর্থ")
            val permReq = jsonRes.optString("permissionRequired", "")

            when (statusStr) {
                "CONFIRMATION_REQUIRED" -> anyNeedsConfirmation = true
                "PERMISSION_REQUIRED" -> anyNeedsPermission = true
            }

            val stepStatus = when {
                statusStr == "PERMISSION_REQUIRED" -> "PERMISSION_REQUIRED"
                statusStr == "CONFIRMATION_REQUIRED" -> "CONFIRMATION_REQUIRED"
                statusStr == "CANCELLED_BEFORE_DISPATCH" || statusStr == "CANCELLED" -> "CANCELLED"
                ok -> "SUCCEEDED"
                else -> "FAILED"
            }

            ActiveTaskInterruptionManager.completeTask(activeExecTask.taskId, ok, summary)
            completedSubTasks.add(step.copy(status = stepStatus, summaryBengali = summary, permissionRequired = permReq))
        }

        val succeededCount = completedSubTasks.count { it.status == "SUCCEEDED" }
        val actionableCount = completedSubTasks.count { it.toolName != null }

        val finalState = when {
            anyNeedsPermission -> "REQUIRES_PERMISSION"
            anyNeedsConfirmation -> "REQUIRES_CONFIRMATION"
            actionableCount > 0 && succeededCount == actionableCount -> "SUCCEEDED"
            succeededCount > 0 -> "PARTIALLY_COMPLETED"
            completedSubTasks.any { it.status == "CANCELLED" } -> "CANCELLED"
            else -> "FAILED"
        }

        val combinedSummary = if (completedSubTasks.size == 1) {
            completedSubTasks.first().summaryBengali
        } else {
            val header = when (finalState) {
                "SUCCEEDED" -> "সবগুলো ধাপ ($succeededCount/$actionableCount) সফলভাবে সম্পন্ন হয়েছে:"
                "PARTIALLY_COMPLETED" -> "আংশিক সম্পন্ন ($succeededCount/$actionableCount টি ধাপ সফল):"
                "REQUIRES_CONFIRMATION" -> "ব্যবহারকারীর অনুমোদন প্রয়োজন:"
                "REQUIRES_PERMISSION" -> "অ্যান্ড্রয়েড পারমিশন প্রয়োজন:"
                else -> "টাস্ক সম্পন্ন করা যায়নি ($succeededCount/$actionableCount):"
            }
            header + "\n" + completedSubTasks.joinToString("\n") {
                "${it.stepIndex}. [${it.status}] ${it.summaryBengali}"
            }
        }

        val finalResult = MasterOrchestrationResult(
            success = finalState == "SUCCEEDED",
            finalState = finalState,
            plan = workingPlan,
            executedSteps = completedSubTasks,
            summaryBengali = combinedSummary,
            needsConfirmation = anyNeedsConfirmation,
            needsPermission = anyNeedsPermission
        )
        _lastOrchestrationResult.value = finalResult
        if (finalState in setOf("REQUIRES_PERMISSION", "REQUIRES_CONFIRMATION", "WAITING_FOR_USER", "CONVERSATIONAL", "CANCELLED")) {
            recentExecutionTimestamps.remove(utteranceKey)
        }
        return finalResult
        } finally {
            releaseCommandSlot(utteranceKey, bypassDedup)
        }
    }

    private fun detectMissingCommunicationSlot(cleanInput: String): PendingSlotRequest? {
        val lower = cleanInput.lowercase(Locale.ROOT).trim()
        val isGenericCall = lower in listOf(
            "কল দাও", "ফোন করো", "কল করো", "কাউকে কল দাও", "make a call", "call someone", "call"
        )
        if (isGenericCall) {
            return PendingSlotRequest(
                intentType = "CALL",
                partialTool = "makePhoneCall",
                partialArgs = JSONObject(),
                missingSlot = "contactName",
                promptBn = "স্যার, কার কাছে কল করতে চান? কন্টাক্টের নাম বা নম্বর বলুন।"
            )
        }
        val isGenericWhatsApp = lower in listOf(
            "হোয়াটসঅ্যাপ মেসেজ পাঠাও", "হোয়াটসঅ্যাপ মেসেজ পাঠাও", "হোয়াটসঅ্যাপে মেসেজ দাও",
            "whatsapp message", "send whatsapp message", "send a whatsapp message"
        )
        if (isGenericWhatsApp) {
            return PendingSlotRequest(
                intentType = "WHATSAPP",
                partialTool = "controlWhatsApp",
                partialArgs = JSONObject(),
                missingSlot = "contact_and_message",
                promptBn = "স্যার, হোয়াটসঅ্যাপে কাকে এবং কী বার্তা পাঠাতে চান?"
            )
        }
        val isGenericSms = lower in listOf(
            "এসএমএস পাঠাও", "মেসেজ পাঠাও", "sms দাও", "send sms", "send a message"
        )
        if (isGenericSms) {
            return PendingSlotRequest(
                intentType = "SMS",
                partialTool = "sendSmsMessage",
                partialArgs = JSONObject(),
                missingSlot = "phone_and_message",
                promptBn = "স্যার, কাকে এবং কী এসএমএস পাঠাতে চান?"
            )
        }
        return null
    }
}
