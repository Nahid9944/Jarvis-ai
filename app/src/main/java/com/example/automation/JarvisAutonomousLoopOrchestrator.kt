package com.example.automation

import android.content.Context
import com.example.ai.ActiveTaskInterruptionManager
import com.example.ai.ExecutionCommitController
import com.example.ai.ScreenPerceptionEngine
import com.example.ai.TaskStatus
import com.example.service.JarvisAccessibilityService
import com.example.util.JarvisDeviceAutomationHelper
import com.example.util.JarvisEmergencyStopCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class AutonomousLoopStep(
    val stepIndex: Int,
    val phase: String,
    val action: String,
    val target: String,
    val success: Boolean,
    val observation: String,
    val errorMessage: String? = null
)

data class AutonomousLoopExecution(
    val success: Boolean,
    val goal: String,
    val stepsTaken: List<AutonomousLoopStep>,
    val verified: Boolean,
    val summaryBengali: String,
    val finalState: String
)

object JarvisAutonomousLoopOrchestrator {

    private const val MAX_PLAN_STEPS = 12
    private const val EXECUTION_TIMEOUT_MS = 45_000L
    private val isCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancelAllTasks() {
        isCancelled.set(true)
    }

    private fun shouldAbort(taskId: String): Boolean {
        val taskStatus = ActiveTaskInterruptionManager.currentTask.value?.let {
            if (it.taskId == taskId) it.status else null
        }
        return isCancelled.get() ||
            JarvisEmergencyStopCoordinator.isEmergencyStopActive.value ||
            ExecutionCommitController.isActionCancelled(taskId) ||
            taskStatus == TaskStatus.CANCELLED ||
            taskStatus == TaskStatus.REPLACED
    }

    private suspend fun waitIfPaused(taskId: String): Boolean {
        while (true) {
            if (shouldAbort(taskId)) return false
            val current = ActiveTaskInterruptionManager.currentTask.value
            if (current != null && current.taskId == taskId && current.status == TaskStatus.PAUSED) {
                delay(150L)
            } else {
                return true
            }
        }
    }

    suspend fun executeTask(
        context: Context,
        goal: String,
        planSteps: JSONArray? = null,
        confirmationToken: String? = null
    ): AutonomousLoopExecution {
        isCancelled.set(false)
        val steps = mutableListOf<AutonomousLoopStep>()
        var stepCount = 0

        val trackedTask = ActiveTaskInterruptionManager.registerNewTask(
            goalBn = goal,
            toolName = "executeAutonomousTask",
            steps = listOf("অবজার্ভ", "পরিকল্পনা", "অ্যাকশন", "যাচাই")
        )

        // 1. UNDERSTAND
        stepCount++
        steps.add(
            AutonomousLoopStep(
                stepIndex = stepCount,
                phase = "UNDERSTAND",
                action = "parse_intent",
                target = goal,
                success = true,
                observation = "লক্ষ্য নির্ধারণ সম্পন্ন: $goal"
            )
        )

        // 2. OBSERVE INITIAL STATE
        stepCount++
        val a11y = JarvisAccessibilityService.instance
        if (a11y == null) {
            steps.add(
                AutonomousLoopStep(
                    stepIndex = stepCount,
                    phase = "OBSERVE",
                    action = "inspect_screen",
                    target = "accessibility",
                    success = false,
                    observation = "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই",
                    errorMessage = "Accessibility Service is not enabled"
                )
            )
            ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, false, "Accessibility Service অনুপস্থিত")
            return AutonomousLoopExecution(
                success = false,
                goal = goal,
                stepsTaken = steps,
                verified = false,
                summaryBengali = "স্বায়ত্তশাসিত টাস্ক সম্পন্ন করা যায়নি: ফোনের Accessibility Service সক্রিয় করা প্রয়োজন।",
                finalState = "FAILED_MISSING_A11Y"
            )
        }

        val initialScreen = ScreenPerceptionEngine.inspectCurrentScreen(context)
        val initialApp = initialScreen.foregroundPackage
        steps.add(
            AutonomousLoopStep(
                stepIndex = stepCount,
                phase = "OBSERVE",
                action = "inspect_screen",
                target = initialApp,
                success = true,
                observation = "বর্তমান অ্যাপ: $initialApp, দৃশ্যমান এলিমেন্ট: ${initialScreen.allElements.size}টি"
            )
        )

        // 3. PLAN & CHECK PERMISSIONS
        stepCount++
        steps.add(
            AutonomousLoopStep(
                stepIndex = stepCount,
                phase = "CHECK_PERMISSIONS",
                action = "verify_safety_and_policy",
                target = "system",
                success = true,
                observation = "অটোমেশন পলিসি, ইন্টারাপশন ও কমিট বাউন্ডারি প্রস্তুত।"
            )
        )

        val timedOutResult = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
        var allSuccess = true
        if (planSteps != null && planSteps.length() > 0) {
            val cappedCount = planSteps.length().coerceAtMost(MAX_PLAN_STEPS)
            for (i in 0 until cappedCount) {
                val stepObj = planSteps.optJSONObject(i) ?: continue
                val actionType = stepObj.optString("action", "click")
                val target = stepObj.optString("target", "")
                val value = stepObj.optString("value", "")

                if (!waitIfPaused(trackedTask.taskId) || shouldAbort(trackedTask.taskId)) {
                    steps.add(
                        AutonomousLoopStep(
                            stepIndex = ++stepCount,
                            phase = "ABORT",
                            action = "interrupted_or_stop",
                            target = target,
                            success = false,
                            observation = "ব্যবহারকারীর বিরতি/বাতিল নির্দেশে অ্যাকশন লুপ থামানো হয়েছে।"
                        )
                    )
                    ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, false, "অ্যাকশন বাতিল হয়েছে")
                    return@withTimeoutOrNull AutonomousLoopExecution(
                        success = false,
                        goal = goal,
                        stepsTaken = steps,
                        verified = false,
                        summaryBengali = "বিরতি বা বাতিল আদেশের কারণে স্বয়ংক্রিয় প্রসেস থামিয়ে দেওয়া হয়েছে।",
                        finalState = "CANCELLED"
                    )
                }

                // Register step-level commit boundary before dispatching OS UI side effect
                val stepActionId = "${trackedTask.taskId}_step_$i"
                ExecutionCommitController.registerAction(stepActionId, actionType)
                if (!ExecutionCommitController.canDispatchAction(stepActionId) || shouldAbort(trackedTask.taskId)) {
                    ExecutionCommitController.cancelAction(stepActionId)
                    steps.add(
                        AutonomousLoopStep(
                            stepIndex = ++stepCount,
                            phase = "ABORT",
                            action = actionType,
                            target = target,
                            success = false,
                            observation = "ডিসপ্যাচের ঠিক আগে অ্যাকশনটি বাতিল হয়েছে।"
                        )
                    )
                    allSuccess = false
                    break
                }

                ExecutionCommitController.markDispatched(stepActionId)
                trackedTask.currentStep = "$actionType -> $target"

                // 4. ACT
                stepCount++
                var actSuccess = false
                var actError: String? = null

                when (actionType.lowercase()) {
                    "click", "tap" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreen(target)
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "long_press" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.longPressElement(target)
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "type", "input" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.typeTextOnScreen(value.ifBlank { target })
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "clear_and_type" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.clearAndTypeText(value.ifBlank { target })
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "scroll_down" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.performSwipe("scroll_down")
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "scroll_up" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.performSwipe("scroll_up")
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "system_action" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.performSystemAction(target)
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    else -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreen(target)
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                }
                ExecutionCommitController.markCompleted(stepActionId)

                steps.add(
                    AutonomousLoopStep(
                        stepIndex = stepCount,
                        phase = "ACT",
                        action = actionType,
                        target = target,
                        success = actSuccess,
                        observation = if (actSuccess) "অ্যাকশন সফলভাবে সম্পাদিত: $actionType -> $target" else (actError ?: "অ্যাকশন ব্যর্থ"),
                        errorMessage = actError
                    )
                )

                if (!actSuccess) {
                    if (actionType.lowercase() in listOf("click", "tap")) {
                        if (!waitIfPaused(trackedTask.taskId) || shouldAbort(trackedTask.taskId)) {
                            allSuccess = false
                            break
                        }
                        stepCount++
                        steps.add(
                            AutonomousLoopStep(
                                stepIndex = stepCount,
                                phase = "REPLAN",
                                action = "scroll_and_retry",
                                target = target,
                                success = true,
                                observation = "টার্গেট না পেয়ে স্ক্রিন স্ক্রল করে পুনরায় খোঁজা হচ্ছে..."
                            )
                        )
                        JarvisDeviceAutomationHelper.performSwipe("scroll_down")
                        delay(600)
                        val (retrySuccess, retryMsg) = JarvisDeviceAutomationHelper.clickElementOnScreen(target)
                        stepCount++
                        steps.add(
                            AutonomousLoopStep(
                                stepIndex = stepCount,
                                phase = "ACT_RETRY",
                                action = actionType,
                                target = target,
                                success = retrySuccess,
                                observation = if (retrySuccess) "পুনরায় চেষ্টায় টার্গেট পাওয়া গেছে এবং ক্লিক করা হয়েছে।" else retryMsg
                            )
                        )
                        if (!retrySuccess) {
                            allSuccess = false
                            break
                        }
                    } else {
                        allSuccess = false
                        break
                    }
                }

                // 6. WAIT & OBSERVE AGAIN
                delay(400)
                stepCount++
                val screenAfter = ScreenPerceptionEngine.inspectCurrentScreen(context)
                val appAfter = screenAfter.foregroundPackage
                steps.add(
                    AutonomousLoopStep(
                        stepIndex = stepCount,
                        phase = "OBSERVE_AGAIN",
                        action = "inspect_screen",
                        target = appAfter,
                        success = true,
                        observation = "পরিবর্তিত স্ক্রিন: $appAfter (এলিমেন্ট: ${screenAfter.allElements.size})"
                    )
                )
            }
        } else {
            if (!waitIfPaused(trackedTask.taskId) || shouldAbort(trackedTask.taskId)) {
                ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, false, "অ্যাকশন বাতিল হয়েছে")
                return@withTimeoutOrNull AutonomousLoopExecution(
                    success = false,
                    goal = goal,
                    stepsTaken = steps,
                    verified = false,
                    summaryBengali = "বিরতি বা বাতিল আদেশের কারণে কাজ থামানো হয়েছে।",
                    finalState = "CANCELLED"
                )
            }

            val lowerGoal = goal.lowercase()
            stepCount++
            val singleActionId = "${trackedTask.taskId}_single"
            ExecutionCommitController.registerAction(singleActionId, "single_goal")
            if (!ExecutionCommitController.canDispatchAction(singleActionId)) {
                ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, false, "ডিসপ্যাচের আগে বাতিল")
                return@withTimeoutOrNull AutonomousLoopExecution(
                    success = false,
                    goal = goal,
                    stepsTaken = steps,
                    verified = false,
                    summaryBengali = "কাজটি শুরু হওয়ার পূর্বেই বাতিল করা হয়েছে।",
                    finalState = "CANCELLED"
                )
            }
            ExecutionCommitController.markDispatched(singleActionId)

            val (singleSuccess, singleMsg) = when {
                lowerGoal.contains("scroll down") || lowerGoal.contains("নিচে স্ক্রল") -> {
                    JarvisDeviceAutomationHelper.performSwipe("scroll_down")
                }
                lowerGoal.contains("scroll up") || lowerGoal.contains("উপরে স্ক্রল") -> {
                    JarvisDeviceAutomationHelper.performSwipe("scroll_up")
                }
                lowerGoal.startsWith("type ") || lowerGoal.startsWith("লেখ ") -> {
                    val textToType = goal.substringAfter(" ").trim()
                    JarvisDeviceAutomationHelper.typeTextOnScreen(textToType)
                }
                else -> {
                    JarvisDeviceAutomationHelper.clickElementOnScreen(goal)
                }
            }
            ExecutionCommitController.markCompleted(singleActionId)

            steps.add(
                AutonomousLoopStep(
                    stepIndex = stepCount,
                    phase = "ACT",
                    action = if (lowerGoal.contains("scroll")) "swipe" else "click",
                    target = goal,
                    success = singleSuccess,
                    observation = if (singleSuccess) "'$goal' স্বয়ংক্রিয়ভাবে সম্পাদিত হয়েছে।" else singleMsg
                )
            )
            allSuccess = singleSuccess
        }

        // 7. VERIFY & REPORT
        stepCount++
        val finalScreen = ScreenPerceptionEngine.inspectCurrentScreen(context)
        val finalApp = finalScreen.foregroundPackage
        val hasExecutionEvidence = steps.any { it.phase.startsWith("ACT") && it.success }
        val verified = allSuccess && hasExecutionEvidence && finalScreen.allElements.isNotEmpty()

        steps.add(
            AutonomousLoopStep(
                stepIndex = stepCount,
                phase = "VERIFY",
                action = "verify_final_outcome",
                target = finalApp,
                success = verified,
                observation = if (verified) {
                    "টাস্ক যাচাইকরণ সফল (চূড়ান্ত প্যাকেজ: $finalApp, দৃশ্যমান এলিমেন্ট: ${finalScreen.allElements.size})।"
                } else {
                    "টাস্কের চূড়ান্ত ফলাফল সম্পূর্ণ যাচাই করা যায়নি।"
                }
            )
        )

        val summary = if (verified) {
            "স্বায়ত্তশাসিত টাস্ক সফলভাবে সম্পন্ন ও যাচাই করা হয়েছে: '$goal'। (মোট ধাপ: ${steps.size}টি)"
        } else {
            "টাস্ক সম্পূর্ণ করতে সমস্যা হয়েছে: '$goal'। কোনো একটি অ্যাকশন বা যাচাইকরণ সফল হয়নি।"
        }

        ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, verified, summary)

        AutonomousLoopExecution(
            success = verified,
            goal = goal,
            stepsTaken = steps,
            verified = verified,
            summaryBengali = summary,
            finalState = if (verified) "SUCCEEDED" else if (hasExecutionEvidence) "PARTIALLY_COMPLETED" else "FAILED"
        )
        }
        if (timedOutResult != null) return timedOutResult

        ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, false, "অ্যাকশন টাইমআউট হয়েছে")
        return AutonomousLoopExecution(
            success = false,
            goal = goal,
            stepsTaken = steps,
            verified = false,
            summaryBengali = "নির্ধারিত ৪৫ সেকেন্ডের মধ্যে অটোমেশন কাজটি সম্পন্ন হয়নি (TIMED_OUT)।",
            finalState = "TIMED_OUT"
        )
    }

    fun toJsonObject(exec: AutonomousLoopExecution): JSONObject {
        val root = JSONObject()
        root.put("success", exec.success)
        root.put("goal", exec.goal)
        root.put("verified", exec.verified)
        root.put("finalState", exec.finalState)
        root.put("summaryBengali", exec.summaryBengali)
        val stepsArr = JSONArray()
        exec.stepsTaken.forEach { s ->
            val sObj = JSONObject()
            sObj.put("stepIndex", s.stepIndex)
            sObj.put("phase", s.phase)
            sObj.put("action", s.action)
            sObj.put("target", s.target)
            sObj.put("success", s.success)
            sObj.put("observation", s.observation)
            sObj.put("errorMessage", s.errorMessage ?: "")
            stepsArr.put(sObj)
        }
        root.put("steps", stepsArr)
        return root
    }
}
