package com.example.automation

import android.content.Context
import com.example.ai.ActiveTaskInterruptionManager
import com.example.ai.AiSettingsRepository
import com.example.ai.ExecutionCommitController
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.ScreenActionSuggestion
import com.example.ai.ScreenPerceptionEngine
import com.example.ai.ScreenVisualAnalysis
import com.example.ai.ScreenVisualAnalyzer
import com.example.ai.TaskStatus
import com.example.service.JarvisAccessibilityService
import com.example.util.JarvisDeviceAutomationHelper
import com.example.util.JarvisEmergencyStopCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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
    private const val VISION_CACHE_FRESHNESS_MS = 12_000L
    private val isCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    internal var visualAnalyzerRunner: (suspend (Context, String) -> ScreenVisualAnalysis?)? = null

    fun resetForTest() {
        isCancelled.set(false)
        visualAnalyzerRunner = null
    }

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

    private suspend fun resolveVisionAnalysis(
        context: Context,
        query: String,
        forceCapture: Boolean = false
    ): ScreenVisualAnalysis? {
        val now = System.currentTimeMillis()
        val cached = JarvisMasterOrchestrator.latestVisionAnalysis.value
        if (!forceCapture &&
            cached != null &&
            cached.suggestions.isNotEmpty() &&
            (now - cached.timestamp) <= VISION_CACHE_FRESHNESS_MS
        ) {
            return cached
        }

        val customRunner = visualAnalyzerRunner
        val fresh = if (customRunner != null) {
            customRunner.invoke(context, query)
        } else {
            val apiKey = try {
                AiSettingsRepository(context.applicationContext).settingsState.value.apiKey
            } catch (_: Exception) {
                ""
            }
            ScreenVisualAnalyzer.captureAndAnalyze(context, apiKey, query).second
        }
        if (fresh != null) {
            JarvisMasterOrchestrator.recordVisionAnalysisContext(fresh)
        }
        return fresh
    }

    internal fun selectBestVisualSuggestion(
        analysis: ScreenVisualAnalysis,
        targetHint: String,
        expectedAction: String = "click"
    ): ScreenActionSuggestion? {
        val cleanHint = targetHint.trim().lowercase(Locale.ROOT)
        val normalizedAction = when (expectedAction.lowercase(Locale.ROOT)) {
            "tap", "vision_click", "vision_discover", "visual_suggestion" -> "click"
            else -> expectedAction.lowercase(Locale.ROOT)
        }
        val candidates = analysis.suggestions.filter { s ->
            s.actionType.equals(normalizedAction, ignoreCase = true) ||
                (normalizedAction == "click" && s.clickable)
        }
        if (candidates.isEmpty()) return null
        if (cleanHint.isBlank()) {
            return candidates.maxByOrNull { it.confidence }
        }

        val hintTokens = cleanHint.split(Regex("""[\s,।?!.:;]+""")).filter { it.length >= 2 }

        fun scoreSuggestion(s: ScreenActionSuggestion): Float {
            val fields = listOfNotNull(s.targetTextOrId, s.elementName, s.title, s.viewId, s.description)
                .map { it.lowercase(Locale.ROOT) }
            val exactBonus = if (fields.any { it == cleanHint }) 2.0f else 0f
            val containsBonus = if (fields.any { it.contains(cleanHint) || cleanHint.contains(it) }) 1.2f else 0f
            val tokenMatches = hintTokens.count { tok -> fields.any { f -> f.contains(tok) } } * 0.35f
            return exactBonus + containsBonus + tokenMatches + s.confidence
        }

        val best = candidates.maxByOrNull { scoreSuggestion(it) } ?: return null
        val fields = listOfNotNull(best.targetTextOrId, best.elementName, best.title, best.viewId, best.description)
            .map { it.lowercase(Locale.ROOT) }
        val hasTextOverlap = fields.any { it.contains(cleanHint) || cleanHint.contains(it) } ||
            hintTokens.any { tok -> fields.any { f -> f.contains(tok) } }
        return if (hasTextOverlap || best.confidence >= 0.45f) best else null
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
        val initialHash = ScreenPerceptionEngine.computeScreenHash(initialScreen)
        var lastHash = initialHash
        var anyScreenDelta = false
        var replanAttempts = 0

        steps.add(
            AutonomousLoopStep(
                stepIndex = stepCount,
                phase = "OBSERVE",
                action = "inspect_screen",
                target = initialApp,
                success = true,
                observation = "বর্তমান অ্যাপ: $initialApp, দৃশ্যমান এলিমেন্ট: ${initialScreen.allElements.size}টি (Hash: ${initialHash.take(8)})"
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
                var stepScreenChanged = false

                when (actionType.lowercase(Locale.ROOT)) {
                    "click", "tap" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreenAwait(target)
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "vision_click", "visual_suggestion", "vision_discover" -> {
                        val hasExplicitCoords = stepObj.optDouble("centerNormX", -1.0) in 0.0..1.0 &&
                            stepObj.optDouble("centerNormY", -1.0) in 0.0..1.0
                        val hasExplicitViewId = stepObj.optString("viewId", "").isNotBlank()
                        val suggestion = if ((hasExplicitCoords || hasExplicitViewId) && actionType.lowercase(Locale.ROOT) != "vision_discover") {
                            ScreenActionSuggestion(
                                actionType = "click",
                                title = target,
                                description = "Autonomous visual click",
                                targetTextOrId = target.ifBlank { null },
                                viewId = stepObj.optString("viewId", "").ifBlank { null },
                                centerNormX = stepObj.optDouble("centerNormX", -1.0).takeIf { it in 0.0..1.0 }?.toFloat(),
                                centerNormY = stepObj.optDouble("centerNormY", -1.0).takeIf { it in 0.0..1.0 }?.toFloat(),
                                confidence = stepObj.optDouble("confidence", 0.7).toFloat(),
                                screenshotTimestamp = System.currentTimeMillis()
                            )
                        } else {
                            val analysis = resolveVisionAnalysis(context, target.ifBlank { goal })
                            steps.add(
                                AutonomousLoopStep(
                                    stepIndex = stepCount++,
                                    phase = "OBSERVE_VISION",
                                    action = "screen_visual_analyze",
                                    target = target.ifBlank { goal },
                                    success = analysis != null && analysis.suggestions.isNotEmpty(),
                                    observation = if (analysis != null) {
                                        "ভিশন বিশ্লেষণ (${analysis.detectedApp}): ${analysis.suggestions.size}টি উপাদান শনাক্ত হয়েছে।"
                                    } else {
                                        "ভিশন বিশ্লেষণ কোনো উপাদান রিটার্ন করেনি।"
                                    }
                                )
                            )
                            analysis?.let { selectBestVisualSuggestion(it, target.ifBlank { goal }, "click") }
                        }

                        if (suggestion != null) {
                            val vRes = ScreenVisualAnalyzer.executeVisualSuggestion(context, suggestion)
                            actSuccess = vRes.success
                            stepScreenChanged = vRes.screenChanged
                            if (vRes.screenChanged) anyScreenDelta = true
                            if (!actSuccess) actError = vRes.summaryBengali
                        } else {
                            actSuccess = false
                            actError = "ভিশন স্ক্রিন বিশ্লেষণে '$target' উপাদানটি পাওয়া যায়নি।"
                        }
                    }
                    "long_press" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.longPressElementAwait(target)
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
                        val (ok, msg) = JarvisDeviceAutomationHelper.performSwipeAwait("scroll_down")
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "scroll_up" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.performSwipeAwait("scroll_up")
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    "system_action" -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.performSystemAction(target)
                        actSuccess = ok
                        if (!actSuccess) actError = msg
                    }
                    else -> {
                        val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreenAwait(target)
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
                        observation = if (actSuccess) {
                            "অ্যাকশন সফলভাবে সম্পাদিত: $actionType -> $target" +
                                if (stepScreenChanged) " (ভিশন-ভেরিফায়েড স্ক্রিন পরিবর্তন নিশ্চিত)" else ""
                        } else {
                            actError ?: "অ্যাকশন ব্যর্থ"
                        },
                        errorMessage = actError
                    )
                )

                // 5. DYNAMIC REPLANNING IF STEP FAILED
                if (!actSuccess) {
                    if (!waitIfPaused(trackedTask.taskId) || shouldAbort(trackedTask.taskId)) {
                        allSuccess = false
                        break
                    }
                    replanAttempts++
                    val replanScreen = ScreenPerceptionEngine.inspectCurrentScreen(context)
                    var recovered = false

                    // Strategy A: Check Accessibility tree for fuzzy / partial match & resourceId / coordinates
                    val matchedEl = replanScreen.allElements.firstOrNull { el ->
                        (target.isNotBlank() && (
                            el.text.contains(target, ignoreCase = true) ||
                                el.contentDescription.contains(target, ignoreCase = true) ||
                                el.resourceId.contains(target, ignoreCase = true)
                            ))
                    }
                    if (matchedEl != null && actionType.lowercase(Locale.ROOT) in listOf("click", "tap", "vision_click", "vision_discover")) {
                        val fuzzySuggestion = ScreenActionSuggestion(
                            actionType = "click",
                            title = target,
                            description = "Dynamic UI-tree replan",
                            targetTextOrId = matchedEl.text.ifBlank { matchedEl.contentDescription }.ifBlank { null },
                            viewId = matchedEl.resourceId.ifBlank { null },
                            centerNormX = matchedEl.centerNormX,
                            centerNormY = matchedEl.centerNormY,
                            confidence = 0.85f,
                            screenshotTimestamp = System.currentTimeMillis()
                        )
                        val vOutcome = ScreenVisualAnalyzer.executeVisualSuggestion(context, fuzzySuggestion)
                        recovered = vOutcome.success
                        if (vOutcome.screenChanged) anyScreenDelta = true
                        steps.add(
                            AutonomousLoopStep(
                                stepIndex = ++stepCount,
                                phase = "REPLAN",
                                action = "semantic_and_coord_recovery",
                                target = target,
                                success = recovered,
                                observation = "UI ট্রি রিপ্ল্যান (${vOutcome.strategyUsed}): ${vOutcome.summaryBengali}"
                            )
                        )
                    }

                    // Strategy A.5: Direct ScreenVisualAnalyzer vision-based element detection + Accessibility click + state verification
                    if (!recovered && actionType.lowercase(Locale.ROOT) in listOf("click", "tap", "vision_click", "vision_discover")) {
                        val visionAnalysis = resolveVisionAnalysis(context, target.ifBlank { goal }, forceCapture = true)
                        val visualSuggestion = visionAnalysis?.let {
                            selectBestVisualSuggestion(it, target.ifBlank { goal }, "click")
                        }
                        if (visualSuggestion != null) {
                            val vOutcome = ScreenVisualAnalyzer.executeVisualSuggestion(context, visualSuggestion)
                            recovered = vOutcome.success
                            if (vOutcome.screenChanged) anyScreenDelta = true
                            steps.add(
                                AutonomousLoopStep(
                                    stepIndex = ++stepCount,
                                    phase = "REPLAN_VISION",
                                    action = "vision_execute_suggestion",
                                    target = visualSuggestion.targetTextOrId ?: visualSuggestion.elementName ?: visualSuggestion.title,
                                    success = recovered,
                                    observation = "ভিশন রিপ্ল্যান (${vOutcome.strategyUsed}, screenChanged=${vOutcome.screenChanged}): ${vOutcome.summaryBengali}",
                                    errorMessage = if (!recovered) vOutcome.summaryBengali else null
                                )
                            )
                        } else {
                            steps.add(
                                AutonomousLoopStep(
                                    stepIndex = ++stepCount,
                                    phase = "REPLAN_VISION",
                                    action = "vision_element_lookup",
                                    target = target,
                                    success = false,
                                    observation = "ভিশন এনালাইসিসে '$target' এর জন্য ক্লিকযোগ্য উপাদান মেলেনি।"
                                )
                            )
                        }
                    }

                    // Strategy B: Safe scroll_down recovery for clickable items
                    if (!recovered && actionType.lowercase(Locale.ROOT) in listOf("click", "tap", "vision_click", "vision_discover")) {
                        steps.add(
                            AutonomousLoopStep(
                                stepIndex = ++stepCount,
                                phase = "REPLAN",
                                action = "scroll_and_retry",
                                target = target,
                                success = true,
                                observation = "টার্গেট না পেয়ে স্ক্রিন স্ক্রল করে ও পুনরায় পর্যবেক্ষণ করে খোঁজা হচ্ছে..."
                            )
                        )
                        JarvisDeviceAutomationHelper.performSwipeAwait("scroll_down")
                        delay(500)
                        val (retrySuccess, retryMsg) = JarvisDeviceAutomationHelper.clickElementOnScreenAwait(target)
                        recovered = retrySuccess
                        steps.add(
                            AutonomousLoopStep(
                                stepIndex = ++stepCount,
                                phase = "ACT_RETRY",
                                action = actionType,
                                target = target,
                                success = retrySuccess,
                                observation = if (retrySuccess) "পুনরায় চেষ্টায় টার্গেট পাওয়া গেছে এবং ক্লিক করা হয়েছে।" else retryMsg
                            )
                        )
                    }

                    if (!recovered) {
                        allSuccess = false
                        break
                    }
                }

                // 6. WAIT & OBSERVE AGAIN + TRACK STATE DELTA
                delay(350)
                stepCount++
                val screenAfter = ScreenPerceptionEngine.inspectCurrentScreen(context)
                val appAfter = screenAfter.foregroundPackage
                val hashAfter = ScreenPerceptionEngine.computeScreenHash(screenAfter)
                val stepDelta = hashAfter != lastHash || appAfter != initialApp
                if (stepDelta) anyScreenDelta = true
                lastHash = hashAfter

                steps.add(
                    AutonomousLoopStep(
                        stepIndex = stepCount,
                        phase = "OBSERVE_AGAIN",
                        action = "inspect_screen",
                        target = appAfter,
                        success = true,
                        observation = "পরিবর্তিত স্ক্রিন: $appAfter (এলিমেন্ট: ${screenAfter.allElements.size}, পরিবর্তন শনাক্ত: $stepDelta)"
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

            val lowerGoal = goal.lowercase(Locale.ROOT)
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

            var (singleSuccess, singleMsg) = when {
                lowerGoal.contains("scroll down") || lowerGoal.contains("নিচে স্ক্রল") -> {
                    JarvisDeviceAutomationHelper.performSwipeAwait("scroll_down")
                }
                lowerGoal.contains("scroll up") || lowerGoal.contains("উপরে স্ক্রল") -> {
                    JarvisDeviceAutomationHelper.performSwipeAwait("scroll_up")
                }
                lowerGoal.startsWith("type ") || lowerGoal.startsWith("লেখ ") -> {
                    val textToType = goal.substringAfter(" ").trim()
                    JarvisDeviceAutomationHelper.typeTextOnScreen(textToType)
                }
                else -> {
                    JarvisDeviceAutomationHelper.clickElementOnScreenAwait(goal)
                }
            }

            var usedVisionAction = false
            if (!singleSuccess && !lowerGoal.contains("scroll")) {
                val visionAnalysis = resolveVisionAnalysis(context, goal, forceCapture = true)
                val visualSuggestion = visionAnalysis?.let { selectBestVisualSuggestion(it, goal, "click") }
                if (visualSuggestion != null) {
                    val vOutcome = ScreenVisualAnalyzer.executeVisualSuggestion(context, visualSuggestion)
                    usedVisionAction = true
                    singleSuccess = vOutcome.success
                    singleMsg = "ভিশন-ভিত্তিক ক্লিক (${vOutcome.strategyUsed}): ${vOutcome.summaryBengali}"
                    if (vOutcome.screenChanged) anyScreenDelta = true
                    steps.add(
                        AutonomousLoopStep(
                            stepIndex = stepCount++,
                            phase = "OBSERVE_VISION",
                            action = "vision_element_lookup",
                            target = visualSuggestion.targetTextOrId ?: visualSuggestion.title,
                            success = true,
                            observation = "ভিশন বিশ্লেষণে '${visualSuggestion.title}' শনাক্ত হয়েছে (confidence=${visualSuggestion.confidence})।"
                        )
                    )
                }
            }
            ExecutionCommitController.markCompleted(singleActionId)

            steps.add(
                AutonomousLoopStep(
                    stepIndex = stepCount,
                    phase = if (usedVisionAction) "ACT_VISION" else "ACT",
                    action = when {
                        usedVisionAction -> "vision_execute_suggestion"
                        lowerGoal.contains("scroll") -> "swipe"
                        else -> "click"
                    },
                    target = goal,
                    success = singleSuccess,
                    observation = if (singleSuccess) {
                        if (usedVisionAction) singleMsg else "'$goal' স্বয়ংক্রিয়ভাবে সম্পাদিত হয়েছে।"
                    } else {
                        singleMsg
                    },
                    errorMessage = if (!singleSuccess) singleMsg else null
                )
            )
            allSuccess = singleSuccess
        }

        // 7. VERIFY & REPORT WITH GENUINE UI DELTA CHECK
        stepCount++
        val finalScreen = ScreenPerceptionEngine.inspectCurrentScreen(context)
        val finalApp = finalScreen.foregroundPackage
        val finalHash = ScreenPerceptionEngine.computeScreenHash(finalScreen)
        val finalDelta = anyScreenDelta || (finalHash != initialHash) || (finalApp != initialApp)
        val hasExecutionEvidence = steps.any { (it.phase.startsWith("ACT") || it.phase.startsWith("REPLAN")) && it.success }
        val verified = allSuccess && hasExecutionEvidence && finalScreen.allElements.isNotEmpty() && finalDelta

        steps.add(
            AutonomousLoopStep(
                stepIndex = stepCount,
                phase = "VERIFY",
                action = "verify_final_outcome",
                target = finalApp,
                success = verified,
                observation = when {
                    verified -> "টাস্ক যাচাইকরণ সফল (চূড়ান্ত অ্যাপ: $finalApp, UI পরিবর্তন নিশ্চিত, রিপ্ল্যান চেষ্টা: $replanAttempts)।"
                    allSuccess && hasExecutionEvidence && !finalDelta -> "অ্যাকশন প্রেরিত হয়েছে তবে স্ক্রিনের অবস্থায় কোনো পরিবর্তন শনাক্ত হয়নি (UNVERIFIED)।"
                    else -> "টাস্কের চূড়ান্ত ফলাফল সম্পূর্ণ যাচাই করা যায়নি।"
                }
            )
        )

        val summary = when {
            verified -> "স্বায়ত্তশাসিত টাস্ক সফলভাবে সম্পন্ন ও যাচাই করা হয়েছে: '$goal'। (মোট ধাপ: ${steps.size}টি)"
            allSuccess && hasExecutionEvidence && !finalDelta -> "অ্যাকশন চালানো হয়েছে কিন্তু স্ক্রিনে প্রত্যাশিত পরিবর্তন যাচাই হয়নি: '$goal'।"
            else -> "টাস্ক সম্পূর্ণ করতে সমস্যা হয়েছে: '$goal'। কোনো একটি অ্যাকশন বা যাচাইকরণ সফল হয়নি।"
        }

        ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, verified, summary)

        AutonomousLoopExecution(
            success = verified,
            goal = goal,
            stepsTaken = steps,
            verified = verified,
            summaryBengali = summary,
            finalState = when {
                verified -> "SUCCEEDED"
                hasExecutionEvidence -> "PARTIALLY_COMPLETED"
                else -> "FAILED"
            }
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
