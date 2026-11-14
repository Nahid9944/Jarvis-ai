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

data class PlannedSubTask(
    val stepIndex: Int,
    val rawCommand: String,
    val toolName: String?,
    val args: JSONObject = JSONObject(),
    val assignedAgent: AiAgent,
    val status: String = "PENDING", // PENDING, SUCCEEDED, FAILED, SKIPPED, CANCELLED
    val summaryBengali: String = ""
)

data class MasterExecutionPlan(
    val planId: String = UUID.randomUUID().toString(),
    val goal: String,
    val primaryAgent: AiAgent,
    val subTasks: List<PlannedSubTask>,
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean,
    val personalityMode: JarvisPersonalityMode,
    val detectedEmotion: UserEmotion
)

data class MasterOrchestrationResult(
    val success: Boolean,
    val finalState: String, // SUCCEEDED, PARTIALLY_COMPLETED, FAILED, CANCELLED, PAUSED, INTERRUPTED, PERSONALITY_SWITCHED, CONVERSATIONAL
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

    private val _activePlan = MutableStateFlow<MasterExecutionPlan?>(null)
    val activePlan: StateFlow<MasterExecutionPlan?> = _activePlan.asStateFlow()

    private val _selectedSpecialist = MutableStateFlow(AiAgentRegistry.JARVIS_MASTER)
    val selectedSpecialist: StateFlow<AiAgent> = _selectedSpecialist.asStateFlow()

    private val _lastOrchestrationResult = MutableStateFlow<MasterOrchestrationResult?>(null)
    val lastOrchestrationResult: StateFlow<MasterOrchestrationResult?> = _lastOrchestrationResult.asStateFlow()

    /**
     * Selects the appropriate domain specialist agent based on user utterance or tool name.
     */
    fun selectSpecialistAgent(utterance: String, toolName: String? = null): AiAgent {
        val lower = utterance.lowercase(Locale.ROOT)
        val t = toolName?.lowercase(Locale.ROOT).orEmpty()

        return when {
            t.contains("notification") || t == "suggestsmartreplies" ||
                lower.contains("নোটিফিকেশন") || lower.contains("notification") || lower.contains("otp") || lower.contains("ওটিপি") ->
                AiAgentRegistry.NOTIFICATION_AGENT

            t.contains("call") || t.contains("sms") || t.contains("whatsapp") || t.contains("contact") ||
                lower.contains("কল") || lower.contains("ফোন") || lower.contains("sms") || lower.contains("এসএমএস") ||
                lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("হোয়াটসঅ্যাপ") ||
                lower.contains("মেসেজ") || lower.contains("বার্তা") ->
                AiAgentRegistry.COMMUNICATION_AGENT

            t.contains("screen") || t.contains("click") || t.contains("scroll") || t.contains("swipe") ||
                t.contains("type") || t.contains("autonomous") || t.contains("accessibility") ||
                lower.contains("স্ক্রল") || lower.contains("ক্লিক") || lower.contains("স্ক্রিন") || lower.contains("টাইপ") ->
                AiAgentRegistry.AUTOMATION_AGENT

            t.contains("camera") || t.contains("vision") ||
                lower.contains("ক্যামেরা") || lower.contains("ছবি") || lower.contains("দেখো") ->
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

            t.contains("github") || t.contains("youtube") || t.contains("wikipedia") || t.contains("news") ||
                lower.contains("উইকিপিডিয়া") || lower.contains("সার্চ") || lower.contains("github") ||
                lower.contains("গিটহাব") || lower.contains("youtube") || lower.contains("ইউটিউব") ->
                AiAgentRegistry.HOMER_AGENT

            t.contains("battery") || t.contains("flashlight") || t.contains("torch") || t.contains("gps") ||
                t.contains("location") || t.contains("settings") || t.contains("launch") || t.contains("volume") ||
                lower.contains("ব্যাটারি") || lower.contains("ফ্ল্যাশলাইট") || lower.contains("টর্চ") ||
                lower.contains("ব্রাইটনেস") || lower.contains("ভলিউম") || lower.contains("লোকেশন") ->
                AiAgentRegistry.EDITH_AGENT

            else -> AiAgentRegistry.JARVIS_MASTER
        }
    }

    /**
     * Splits compound multi-step commands ("আগে X করো তারপর Y করো" / "and then") into bounded subtasks.
     */
    fun decomposeGoal(utterance: String): List<String> {
        val cleaned = utterance.trim()
        if (cleaned.isBlank()) return emptyList()

        val splitRegex = Regex("""\s*(?:,|;|তারপর|এরপর|এবং তারপর|আগে\b.*?\bতারপর|and then|then)\s*""", RegexOption.IGNORE_CASE)
        val rawParts = cleaned
            .replace(Regex("""^(আগে|প্রথমে)\s+""", RegexOption.IGNORE_CASE), "")
            .split(splitRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return if (rawParts.size > 1) {
            rawParts.take(MAX_SUBTASKS)
        } else {
            listOf(cleaned)
        }
    }

    fun buildPlan(
        utterance: String,
        toolNameOverride: String? = null,
        toolArgsOverride: JSONObject? = null
    ): MasterExecutionPlan {
        val emotionEval = EmotionContextEngine.analyzeText(utterance)
        val personality = JarvisPersonalityManager.currentMode.value
        val parts = if (toolNameOverride != null) listOf(utterance) else decomposeGoal(utterance)

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

        val primary = subTasks.firstOrNull()?.assignedAgent ?: AiAgentRegistry.JARVIS_MASTER
        val plan = MasterExecutionPlan(
            goal = utterance.trim(),
            primaryAgent = primary,
            subTasks = subTasks,
            riskLevel = highestRisk,
            requiresConfirmation = needsConf,
            personalityMode = personality,
            detectedEmotion = emotionEval.emotion
        )
        _activePlan.value = plan
        _selectedSpecialist.value = primary
        return plan
    }

    /**
     * Orchestrates complete execution of a natural-language user goal or explicit tool call:
     * emotion sensing -> personality switching -> interruption handling -> planning ->
     * specialist tool execution -> partial-failure & verification aggregation.
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
                        val summary = jsonRes.optString("summaryBengali", if (ok) "অনুমোদিত কাজ সফলভাবে সম্পন্ন হয়েছে।" else "কাজটি সম্পন্ন করা যায়নি।")
                        val step = PlannedSubTask(
                            stepIndex = 1,
                            rawCommand = cleanInput,
                            toolName = approved.toolName,
                            args = approvedArgs,
                            assignedAgent = selectSpecialistAgent(cleanInput, approved.toolName),
                            status = if (ok) "SUCCEEDED" else "FAILED",
                            summaryBengali = summary
                        )
                        val finalState = when {
                            statusStr == "PERMISSION_REQUIRED" -> "REQUIRES_PERMISSION"
                            ok -> "SUCCEEDED"
                            else -> "FAILED"
                        }
                        val res = MasterOrchestrationResult(
                            success = ok,
                            finalState = finalState,
                            plan = plan.copy(subTasks = listOf(step)),
                            executedSteps = listOf(step),
                            summaryBengali = summary,
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

        // 3. No actionable tools found -> report as conversational / guidance needed
        if (plan.subTasks.none { it.toolName != null }) {
            val res = MasterOrchestrationResult(
                success = false,
                finalState = "CONVERSATIONAL",
                plan = plan,
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
                plan = plan,
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

        for (step in plan.subTasks) {
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

            when (statusStr) {
                "CONFIRMATION_REQUIRED" -> anyNeedsConfirmation = true
                "PERMISSION_REQUIRED" -> anyNeedsPermission = true
            }

            val stepStatus = when {
                statusStr == "CANCELLED_BEFORE_DISPATCH" || statusStr == "CANCELLED" -> "CANCELLED"
                ok -> "SUCCEEDED"
                else -> "FAILED"
            }

            ActiveTaskInterruptionManager.completeTask(activeExecTask.taskId, ok, summary)
            completedSubTasks.add(step.copy(status = stepStatus, summaryBengali = summary))
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
            plan = plan,
            executedSteps = completedSubTasks,
            summaryBengali = combinedSummary,
            needsConfirmation = anyNeedsConfirmation,
            needsPermission = anyNeedsPermission
        )
        _lastOrchestrationResult.value = finalResult
        return finalResult
    }
}
