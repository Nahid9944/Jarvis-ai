package com.example.automation

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisNotificationListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.coroutines.resume

object PlaybookRunner {

    suspend fun execute(
        context: Context,
        playbook: Playbook,
        params: Map<String, String> = emptyMap()
    ): PlaybookRunResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val stepRecords = mutableListOf<StepExecutionRecord>()

        // 1. Rate Limit Check
        val (canRun, rateReason) = RateLimiter.canExecute(playbook.appPackage)
        if (!canRun) {
            val res = PlaybookRunResult(
                playbookId = playbook.id,
                success = false,
                code = "RATE_LIMITED",
                messageBn = rateReason ?: "রেট লিমিটের কারণে প্লেবুক কার্যকর করা সম্ভব হয়নি।",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
            PlaybookStore.logRun(playbook.id, params, res)
            return@withContext res
        }

        // 2. Idempotency Check for external comm
        if (playbook.risk == RiskLevel.EXTERNAL_COMM) {
            val textParam = params["text"] ?: ""
            val recipientParam = params["phone"] ?: params["from"] ?: params["contactName"] ?: ""
            if (textParam.isNotBlank() && !Idempotency.canSend(playbook.appPackage, recipientParam, textParam)) {
                val res = PlaybookRunResult(
                    playbookId = playbook.id,
                    success = false,
                    code = "DUPLICATE_SUPPRESSED",
                    messageBn = "একই বার্তা ৬০ সেকেন্ডের মধ্যে পুনরায় পাঠানোর অনুরোধ ব্লক করা হয়েছে।",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
                PlaybookStore.logRun(playbook.id, params, res)
                return@withContext res
            }
        }

        var currentHash = NodeSnapshot.computeScreenHash(getRootNode(), playbook.appPackage)
        val initialHash = currentHash
        var lastReplySuccess = false
        var lastMediaTitle: String? = null

        // 3. Step Loop
        for ((idx, step) in playbook.steps.withIndex()) {
            val stepStart = System.currentTimeMillis()
            var stepSuccess = false
            var stepError: String? = null
            var stepDesc = step.javaClass.simpleName

            try {
                when (step) {
                    is Step.Launch -> {
                        val resolvedUri = substituteParams(step.uri, params)
                        stepDesc = "Launch($resolvedUri)"
                        val launched = launchUri(context, resolvedUri, playbook.appPackage)
                        if (launched) {
                            stepSuccess = true
                            delay(600) // allow activity launch settle
                        } else {
                            stepError = "ইনটেন্ট বা ইউআরআই ওপেন করা যায়নি: $resolvedUri"
                        }
                    }

                    is Step.WaitFor -> {
                        stepDesc = "WaitFor(${step.selector.value})"
                        val resolvedSel = substituteSelector(step.selector, params)
                        val node = pollForNode(resolvedSel, step.timeoutMs)
                        if (node != null) {
                            stepSuccess = true
                        } else {
                            stepError = "প্রত্যাশিত ইন্টারফেস নোড (${resolvedSel.value}) পাওয়া যায়নি।"
                        }
                    }

                    is Step.Tap -> {
                        stepDesc = "Tap(${step.selector.value})"
                        val resolvedSel = substituteSelector(step.selector, params)

                        val pipelineApprovalId = params["_pipelineApprovalId"]
                        val executionId = params["_executionId"]
                        val alreadyPipelineApproved = com.example.permissions.ActionApprovalRegistry.isApprovalInFlight(pipelineApprovalId)
                        val alreadyExecutionConsented = com.example.permissions.ConsentManager.hasExecutionConsent(
                            executionId = executionId,
                            toolName = "runPlaybook"
                        )

                        // Check consent only if not already approved by centralized pipeline / ConsentManager
                        if (!alreadyPipelineApproved && !alreadyExecutionConsented &&
                            (step.dangerous || playbook.risk == RiskLevel.EXTERNAL_COMM || playbook.risk == RiskLevel.DESTRUCTIVE)
                        ) {
                            val actionType = when (playbook.id) {
                                "wa.send_text", "sms.send" -> "SEND_MESSAGE"
                                "fb.post_text" -> "POST_PUBLIC"
                                else -> "ACTION"
                            }
                            val target = params["contactName"] ?: params["phone"] ?: "প্রাপক"
                            val content = params["text"] ?: ""

                            val approved = com.example.permissions.ConsentManager.requestConsentIfNeeded(
                                executionId = executionId,
                                pipelineApprovalId = pipelineApprovalId,
                                toolName = "runPlaybook",
                                actionType = actionType,
                                target = target,
                                content = content
                            )
                            if (!approved) {
                                val res = PlaybookRunResult(
                                    playbookId = playbook.id,
                                    success = false,
                                    code = "CONFIRMATION_DECLINED",
                                    messageBn = "ব্যবহারকারী সম্মতি না দেওয়ায় কাজটি বাতিল করা হয়েছে।",
                                    stepRecords = stepRecords,
                                    executionTimeMs = System.currentTimeMillis() - startTime
                                )
                                PlaybookStore.logRun(playbook.id, params, res)
                                return@withContext res
                            }
                        }

                        val node = pollForNode(resolvedSel, timeoutMs = 3000)
                        if (node != null) {
                            val tapped = performNodeClick(node)
                            if (tapped) {
                                stepSuccess = true
                                delay(400) // settle
                            } else {
                                stepError = "নোডে ক্লিক একশন কার্যকর হয়নি।"
                            }
                        } else {
                            stepError = "ক্লিক করার জন্য নোড (${resolvedSel.value}) পাওয়া যায়নি।"
                        }
                    }

                    is Step.LongPress -> {
                        stepDesc = "LongPress(${step.selector.value})"
                        val resolvedSel = substituteSelector(step.selector, params)
                        val node = pollForNode(resolvedSel, timeoutMs = 3000)
                        if (node != null) {
                            val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                            stepSuccess = ok
                            if (!ok) stepError = "লং-প্রেস একশন সফল হয়নি।"
                        } else {
                            stepError = "লং-প্রেসের জন্য নোড পাওয়া যায়নি।"
                        }
                    }

                    is Step.SetText -> {
                        val textToSet = substituteParams(step.text, params)
                        stepDesc = "SetText($textToSet)"
                        val resolvedSel = substituteSelector(step.selector, params)
                        val node = pollForNode(resolvedSel, timeoutMs = 3000)
                        if (node != null) {
                            val bundle = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet)
                            }
                            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                            stepSuccess = ok
                            if (!ok) stepError = "টেক্সট ইনপুট করা যায়নি।"
                        } else {
                            stepError = "ইনপুট ফিল্ড নোড পাওয়া যায়নি।"
                        }
                    }

                    is Step.PressKey -> {
                        stepDesc = "PressKey(${step.key})"
                        val a11y = JarvisAccessibilityService.instance
                        if (a11y != null) {
                            when (step.key) {
                                Key.BACK -> a11y.performGlobalAction(AccessibilityServiceCompat.GLOBAL_ACTION_BACK)
                                Key.HOME -> a11y.performGlobalAction(AccessibilityServiceCompat.GLOBAL_ACTION_HOME)
                                Key.ENTER -> {
                                    val focused = getRootNode()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                    focused?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                }
                                else -> {}
                            }
                            stepSuccess = true
                            delay(300)
                        } else {
                            stepError = "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।"
                        }
                    }

                    is Step.Scroll -> {
                        stepDesc = "Scroll(${step.direction})"
                        val a11y = JarvisAccessibilityService.instance
                        if (a11y != null) {
                            val dirStr = when (step.direction) {
                                Dir.DOWN -> "scroll_down"
                                Dir.UP -> "scroll_up"
                                Dir.LEFT -> "left"
                                Dir.RIGHT -> "right"
                            }
                            var foundUntil = false
                            for (s in 0 until step.maxSwipes) {
                                if (step.until != null) {
                                    val resolvedUntil = substituteSelector(step.until, params)
                                    if (NodeSnapshot.findNode(getRootNode(), resolvedUntil) != null) {
                                        foundUntil = true
                                        break
                                    }
                                }
                                a11y.swipeDirection(dirStr)
                                delay(500)
                            }
                            stepSuccess = if (step.until != null) foundUntil else true
                            if (!stepSuccess) stepError = "স্ক্রল করার পরও নির্দিষ্ট এলিমেন্ট পাওয়া যায়নি।"
                        } else {
                            stepError = "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।"
                        }
                    }

                    is Step.Assert -> {
                        stepDesc = "Assert(${step.selector.value}, ${step.state})"
                        val resolvedSel = substituteSelector(step.selector, params)
                        val node = NodeSnapshot.findNode(getRootNode(), resolvedSel)
                        when (step.state) {
                            NodeState.EXISTS -> {
                                stepSuccess = (node != null)
                                if (!stepSuccess) stepError = "Assert ব্যর্থ: নোড উপস্থিত নেই।"
                            }
                            NodeState.GONE -> {
                                stepSuccess = (node == null)
                                if (!stepSuccess) stepError = "Assert ব্যর্থ: নোড এখনও উপস্থিত রয়েছে।"
                            }
                            NodeState.ENABLED -> {
                                stepSuccess = (node?.isEnabled == true)
                                if (!stepSuccess) stepError = "Assert ব্যর্থ: নোড সক্রিয় নয়।"
                            }
                            else -> stepSuccess = true
                        }
                    }

                    is Step.Sleep -> {
                        stepDesc = "Sleep(${step.ms}ms)"
                        delay(step.ms)
                        stepSuccess = true
                    }

                    is Step.NotificationReply -> {
                        val replyText = substituteParams(step.text, params)
                        val sender = if (!step.sender.isNullOrBlank()) substituteParams(step.sender, params) else null
                        stepDesc = "NotificationReply($replyText)"
                        val ok = JarvisNotificationListenerService.sendDirectReply(
                            context = context,
                            packageFilter = step.packageFilter,
                            sender = sender,
                            replyText = replyText
                        )
                        stepSuccess = ok
                        lastReplySuccess = ok
                        if (!ok) stepError = "নোটিফিকেশন থেকে ডিরেক্ট রিপ্লাই পাঠানো সম্ভব হয়নি।"
                    }

                    is Step.MediaAction -> {
                        stepDesc = "MediaAction(${step.action})"
                        val (ok, title) = JarvisNotificationListenerService.sendMediaAction(
                            context = context,
                            action = step.action,
                            param = step.param
                        )
                        stepSuccess = ok
                        lastMediaTitle = title
                        if (!ok) stepError = "মিডিয়া সেশন একশন চালানো যায়নি।"
                    }

                    is Step.AskUser -> {
                        stepDesc = "AskUser(${step.questionKey})"
                        stepSuccess = true
                    }

                    is Step.VisionCheck -> {
                        stepDesc = "VisionCheck(${step.question})"
                        stepSuccess = true
                    }

                    is Step.ReadText -> {
                        stepDesc = "ReadText(${step.selector.value})"
                        val resolvedSel = substituteSelector(step.selector, params)
                        val node = NodeSnapshot.findNode(getRootNode(), resolvedSel)
                        if (node != null) {
                            stepSuccess = true
                        } else {
                            stepError = "টেক্সট পড়ার জন্য নোড পাওয়া যায়নি।"
                        }
                    }
                }
            } catch (e: Exception) {
                stepSuccess = false
                stepError = e.localizedMessage
            }

            val stepTime = System.currentTimeMillis() - stepStart
            stepRecords.add(
                StepExecutionRecord(
                    stepIndex = idx,
                    stepDescription = stepDesc,
                    success = stepSuccess,
                    errorMessage = stepError,
                    executionTimeMs = stepTime
                )
            )

            // Update screen hash after step settle
            currentHash = NodeSnapshot.computeScreenHash(getRootNode(), playbook.appPackage)

            if (!stepSuccess) {
                // Execute onFail cleanup steps
                executeCleanupSteps(context, playbook.onFail, params)

                val res = PlaybookRunResult(
                    playbookId = playbook.id,
                    success = false,
                    code = "STEP_FAILED",
                    messageBn = "প্লেবুকের ${idx + 1}-তম ধাপ (${stepDesc}) ব্যর্থ হয়েছে: ${stepError ?: "অজানা ত্রুটি"}",
                    stepRecords = stepRecords,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
                PlaybookStore.logRun(playbook.id, params, res)
                return@withContext res
            }
        }

        // 4. Verification Step
        var isMediaPlaying = false
        if (playbook.verify.type == VerifyType.MEDIA_PLAYING || playbook.verify.type == VerifyType.PLAYBACK_TITLE_CONTAINS) {
            val (playing, _, liveTitle) = JarvisNotificationListenerService.verifyMediaPlaybackState(
                context = context,
                packageHint = playbook.appPackage
            )
            isMediaPlaying = playing
            if (!liveTitle.isNullOrBlank()) {
                lastMediaTitle = liveTitle
            }
        }
        val (verified, verifyMsg) = ActionVerifier.verify(
            spec = playbook.verify,
            root = getRootNode(),
            initialScreenHash = initialHash,
            currentScreenHash = currentHash,
            lastReplySuccess = lastReplySuccess,
            mediaTitle = lastMediaTitle,
            params = params,
            isMediaPlaying = isMediaPlaying || !lastMediaTitle.isNullOrBlank()
        )

        // Record Rate Limiter & Idempotency on success
        if (verified) {
            RateLimiter.recordExecution(playbook.appPackage)
            val recipientParam = params["phone"] ?: params["from"] ?: params["contactName"] ?: ""
            val textParam = params["text"] ?: ""
            if (recipientParam.isNotBlank() && textParam.isNotBlank()) {
                Idempotency.recordSent(playbook.appPackage, recipientParam, textParam)
            }
        }

        val finalResult = PlaybookRunResult(
            playbookId = playbook.id,
            success = verified,
            code = if (verified) "SUCCESS" else "VERIFY_FAILED",
            messageBn = if (verified) "${playbook.titleBn} সফলভাবে সম্পন্ন ও নিশ্চিত করা হয়েছে।" else "ধাপগুলো সম্পন্ন হয়েছে কিন্তু চূড়ান্ত যাচাই অসম্পূর্ণ: $verifyMsg",
            stepRecords = stepRecords,
            verified = verified,
            executionTimeMs = System.currentTimeMillis() - startTime
        )

        PlaybookStore.logRun(playbook.id, params, finalResult)
        return@withContext finalResult
    }

    private suspend fun pollForNode(selector: Selector, timeoutMs: Long): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start <= timeoutMs) {
            val root = getRootNode()
            val node = NodeSnapshot.findNode(root, selector)
            if (node != null) return node
            delay(100) // 100ms polling
        }
        return null
    }

    private fun performNodeClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
            current = current.parent
        }
        // Fallback: click exact center coordinates via gesture
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val a11y = JarvisAccessibilityService.instance
            if (a11y != null) {
                a11y.clickCoordinates(bounds.exactCenterX(), bounds.exactCenterY())
                return true
            }
        }
        return false
    }

    private fun getRootNode(): AccessibilityNodeInfo? {
        return JarvisAccessibilityService.instance?.rootInActiveWindow
    }

    private fun launchUri(context: Context, uriStr: String, defaultPackage: String): Boolean {
        return try {
            val uri = Uri.parse(uriStr)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                if (defaultPackage.isNotBlank()) {
                    setPackage(defaultPackage)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Fallback launch intent for package
            try {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(defaultPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    true
                } else false
            } catch (e2: Exception) {
                false
            }
        }
    }

    private suspend fun requestGateConsent(actionType: String, target: String, content: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            HumanGate.requestConfirmation(actionType, target, content) { approved ->
                if (cont.isActive) {
                    cont.resume(approved)
                }
            }
        }
    }

    private fun substituteParams(input: String, params: Map<String, String>): String {
        var result = input
        for ((key, value) in params) {
            val encodedVal = try { URLEncoder.encode(value, "UTF-8") } catch (e: Exception) { value }
            result = result.replace("{$key}", encodedVal)
        }
        return result
    }

    private fun substituteSelector(selector: Selector, params: Map<String, String>): Selector {
        var resVal = selector.value
        for ((key, value) in params) {
            resVal = resVal.replace("{$key}", value)
        }
        val resAlts = selector.alts.map { alt ->
            var a = alt
            for ((key, value) in params) {
                a = a.replace("{$key}", value)
            }
            a
        }
        return selector.copy(value = resVal, alts = resAlts)
    }

    private suspend fun executeCleanupSteps(context: Context, onFailSteps: List<Step>, params: Map<String, String>) {
        for (step in onFailSteps) {
            try {
                if (step is Step.PressKey) {
                    val a11y = JarvisAccessibilityService.instance
                    if (step.key == Key.BACK) {
                        a11y?.performGlobalAction(AccessibilityServiceCompat.GLOBAL_ACTION_BACK)
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}

private object AccessibilityServiceCompat {
    const val GLOBAL_ACTION_BACK = 1
    const val GLOBAL_ACTION_HOME = 2
}
