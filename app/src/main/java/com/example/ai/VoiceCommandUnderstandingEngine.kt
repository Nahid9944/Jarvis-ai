package com.example.ai

import android.content.Context
import android.util.Log
import com.example.tools.ToolExecutionPipeline
import com.example.tools.ToolRegistry
import com.example.util.JarvisDeviceAutomationHelper
import com.example.util.JarvisEmergencyStopCoordinator
import com.example.util.JarvisLiveLocationTracker
import com.example.util.JarvisSmsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class VoiceExecutionOutcome(
    val handled: Boolean,
    val success: Boolean,
    val feedbackMessage: String,
    val toolName: String? = null,
    val needsAccessibility: Boolean = false,
    val needsLocation: Boolean = false,
    val needsScreenSharing: Boolean = false,
    val rawResult: JSONObject? = null
)

/**
 * Universal Voice Command Engine for NAHID JARVIS AI.
 * Unifies Natural Language Command Parsing, Canonical Tool Routing,
 * Dynamic Tool Discovery, and Execution through a single centralized pipeline.
 * Adheres strictly to Zero Fake Success and Truthful Action Execution.
 */
object VoiceCommandUnderstandingEngine {

    private const val TAG = "VoiceCommandEngine"
    private var lastCommandText: String? = null
    private var lastCommandTimestamp: Long = 0L

    suspend fun processCommand(
        context: Context,
        rawCommand: String,
        onPromptAccessibility: (() -> Unit)? = null,
        toolExecutor: AssistantToolExecutor? = null,
        onNavigate: (String) -> Unit = {},
        caller: String = "voice"
    ): VoiceExecutionOutcome = withContext(Dispatchers.Default) {
        val clean = rawCommand.trim()
        if (clean.isBlank()) {
            return@withContext VoiceExecutionOutcome(
                handled = true,
                success = false,
                feedbackMessage = "কোনো ভয়েস কমান্ড পাওয়া যায়নি।"
            )
        }

        val now = System.currentTimeMillis()
        if (lastCommandText.equals(clean, ignoreCase = true) && (now - lastCommandTimestamp) < 2500L) {
            Log.d(TAG, "Deduplicating voice command: '$clean'")
            return@withContext VoiceExecutionOutcome(
                handled = true,
                success = false,
                feedbackMessage = "কমান্ডটি ইতিমধ্যে প্রক্রিয়াকরণ করা হচ্ছে (DUPLICATE_IGNORED)।",
                toolName = "duplicate_filter"
            )
        }
        lastCommandText = clean
        lastCommandTimestamp = now

        val executor = toolExecutor ?: AssistantToolExecutor(
            dao = com.example.data.local.AssistantDatabase.getDatabase(context).assistantDao(),
            onNavigate = onNavigate,
            context = context
        )

        // Step 0: Active Interruption, Barge-In & Task Replacement Handling
        val interruptionIntent = ActiveTaskInterruptionManager.classifyInterruptionIntent(clean)
        val hasActiveTask = ActiveTaskInterruptionManager.currentTask.value != null
        val isSpeakingNow = VoiceTurnManager.turnState.value == TurnState.ASSISTANT_SPEAKING

        if (interruptionIntent == InterruptionIntent.EMERGENCY_STOP) {
            val irResult = ActiveTaskInterruptionManager.handleInterruptionCommand(clean)
            val execResult = executor.executeToolWithCaller("emergencyStopAllWork", JSONObject(), caller)
            return@withContext VoiceExecutionOutcome(
                handled = true,
                success = execResult.optBoolean("success", irResult.handled),
                feedbackMessage = execResult.optString("summaryBengali", irResult.messageBengali),
                toolName = "emergencyStopAllWork",
                rawResult = execResult
            )
        }

        val shouldHandleAsInterruption = when (interruptionIntent) {
            InterruptionIntent.STOP_SPEECH_ONLY -> isSpeakingNow || hasActiveTask || clean.lowercase(Locale.ROOT).contains("কথা থামাও") || clean.lowercase(Locale.ROOT).contains("চুপ")
            InterruptionIntent.PAUSE_CURRENT_TASK,
            InterruptionIntent.CANCEL_CURRENT_TASK,
            InterruptionIntent.RESUME_TASK,
            InterruptionIntent.REPLACE_CURRENT_TASK,
            InterruptionIntent.MODIFY_TASK_PARAMETER -> hasActiveTask
            else -> false
        }

        if (shouldHandleAsInterruption) {
            val irResult = ActiveTaskInterruptionManager.handleInterruptionCommand(clean)
            val replacement = irResult.replacementTask
            if (replacement != null && replacement.toolName != "executeGenericReplacement") {
                val repArgs = JSONObject().apply {
                    replacement.params.forEach { (k, v) -> if (v != null) put(k, v) }
                }
                val repExec = executor.executeToolWithCaller(replacement.toolName, repArgs, caller)
                val repSummary = repExec.optString("summaryBengali", irResult.messageBengali)
                ActiveTaskInterruptionManager.completeTask(replacement.taskId, repExec.optBoolean("success", false), repSummary)
                return@withContext VoiceExecutionOutcome(
                    handled = true,
                    success = repExec.optBoolean("success", false),
                    feedbackMessage = "${irResult.messageBengali}\n$repSummary",
                    toolName = replacement.toolName,
                    needsLocation = repExec.optBoolean("needsLocationPermission", false),
                    needsScreenSharing = repExec.optBoolean("needsScreenSharingPrompt", false),
                    rawResult = repExec
                )
            }
            return@withContext VoiceExecutionOutcome(
                handled = true,
                success = irResult.handled,
                feedbackMessage = irResult.messageBengali,
                toolName = "interruption_${interruptionIntent.name.lowercase(Locale.ROOT)}"
            )
        }

        // Step 0.5: Pending One-Time Confirmation Intercept ("হ্যাঁ", "অনুমতি দিলাম", "না", etc.)
        val pendingApproval = com.example.permissions.JarvisPermissionManager.pendingUserApproval.value
        if (pendingApproval != null) {
            when (com.example.permissions.ActionApprovalRegistry.classifyApprovalReply(clean)) {
                com.example.permissions.ApprovalReplyDecision.APPROVE -> {
                    val approved = com.example.permissions.JarvisPermissionManager.approveUserAction(
                        source = "voice_or_text_yes",
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
                        val execResult = executor.executeToolWithCaller(approved.toolName, approvedArgs, caller)
                        val ok = execResult.optBoolean("success", false)
                        val feedback = execResult.optString(
                            "summaryBengali",
                            execResult.optString("message", execResult.optString("messageBn", "অনুমোদিত কাজ সম্পন্ন হয়েছে।"))
                        )
                        return@withContext VoiceExecutionOutcome(
                            handled = true,
                            success = ok,
                            feedbackMessage = feedback,
                            toolName = approved.toolName,
                            needsAccessibility = execResult.optString("permissionRequired") == "accessibility",
                            needsLocation = execResult.optBoolean("needsLocationPermission", false) ||
                                execResult.optString("permissionRequired") == "location_fine",
                            needsScreenSharing = execResult.optBoolean("needsScreenSharingPrompt", false),
                            rawResult = execResult
                        )
                    }
                }
                com.example.permissions.ApprovalReplyDecision.REJECT -> {
                    com.example.permissions.JarvisPermissionManager.rejectUserAction(source = caller)
                    return@withContext VoiceExecutionOutcome(
                        handled = true,
                        success = true,
                        feedbackMessage = "ঠিক আছে, '${pendingApproval.reasonBn}' কাজটি বাতিল করা হয়েছে।",
                        toolName = pendingApproval.toolName
                    )
                }
                com.example.permissions.ApprovalReplyDecision.CORRECTION -> {
                    com.example.permissions.JarvisPermissionManager.rejectUserAction(
                        source = "user_correction",
                        reason = "ব্যবহারকারী তথ্য সংশোধন করেছেন"
                    )
                }
                com.example.permissions.ApprovalReplyDecision.UNRELATED -> Unit
            }
        }

        // Step 1: Parse using typed NLP command parser -> canonical Tool Registry execution
        val parsed = JarvisNlpCommandParser.parse(clean)
        if (parsed !is JarvisParsedCommand.Unrecognized) {
            val (toolName, toolArgs) = mapParsedCommandToTool(parsed)
            if (toolName != null) {
                val trackedTask = ActiveTaskInterruptionManager.registerNewTask(
                    goalBn = clean,
                    toolName = toolName,
                    steps = listOf("অনুমতি ও ঝুঁকি যাচাই", "কার্যকর করা", "ফলাফল যাচাই")
                )
                val execResult = executor.executeToolWithCaller(toolName, toolArgs, caller)
                val isSuccess = execResult.optBoolean("success", false)
                val feedback = execResult.optString(
                    "summaryBengali",
                    execResult.optString("message", execResult.optString("messageBn", "কমান্ড সম্পন্ন হয়েছে।"))
                )
                ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, isSuccess, feedback)

                val needsA11y = execResult.optString("permissionRequired") == "accessibility"
                if (needsA11y) onPromptAccessibility?.invoke()

                return@withContext VoiceExecutionOutcome(
                    handled = true,
                    success = isSuccess,
                    feedbackMessage = feedback,
                    toolName = toolName,
                    needsAccessibility = needsA11y,
                    needsLocation = execResult.optBoolean("needsLocationPermission", false) ||
                            execResult.optString("requiredPermission") == "location" ||
                            execResult.optString("permissionRequired") == "location_fine",
                    needsScreenSharing = execResult.optBoolean("needsScreenSharingPrompt", false),
                    rawResult = execResult
                )
            }
        }

        // Step 2: Heuristic intent mapping -> canonical Tool Registry execution
        val (heuristicTool, heuristicArgs) = mapHeuristicIntent(clean)
        if (heuristicTool != null) {
            val trackedTask = ActiveTaskInterruptionManager.registerNewTask(
                goalBn = clean,
                toolName = heuristicTool
            )
            val execResult = executor.executeToolWithCaller(heuristicTool, heuristicArgs, caller)
            val isSuccess = execResult.optBoolean("success", false)
            val feedback = execResult.optString(
                "summaryBengali",
                execResult.optString("message", execResult.optString("messageBn", "কমান্ড সম্পন্ন হয়েছে।"))
            )
            ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, isSuccess, feedback)

            val needsA11y = execResult.optString("permissionRequired") == "accessibility"
            if (needsA11y) onPromptAccessibility?.invoke()

            return@withContext VoiceExecutionOutcome(
                handled = true,
                success = isSuccess,
                feedbackMessage = feedback,
                toolName = heuristicTool,
                needsAccessibility = needsA11y,
                needsLocation = execResult.optBoolean("needsLocationPermission", false) ||
                        execResult.optString("permissionRequired") == "location_fine",
                needsScreenSharing = execResult.optBoolean("needsScreenSharingPrompt", false),
                rawResult = execResult
            )
        }

        // Step 2.5: Active Learned Skills matching from local library
        try {
            val skillRepo = com.example.data.repository.JarvisSkillRepository(
                com.example.data.local.AssistantDatabase.getDatabase(context).jarvisSkillDao()
            )
            val matchedSkill = skillRepo.findMatchingSkill(clean)
            if (matchedSkill != null && matchedSkill.status.equals("ACTIVE", ignoreCase = true) && matchedSkill.stepCount > 0) {
                val skillArgs = JSONObject().apply {
                    put("skillNameOrTrigger", clean)
                }
                val trackedTask = ActiveTaskInterruptionManager.registerNewTask(
                    goalBn = clean,
                    toolName = "executeLearnedSkill",
                    steps = listOf("শেখা স্কিল ম্যাচিং", "ধাপসমূহ চালনা", "যাচাইকরণ")
                )
                val execResult = executor.executeToolWithCaller("executeLearnedSkill", skillArgs, caller)
                val isSuccess = execResult.optBoolean("success", false)
                val feedback = execResult.optString(
                    "summaryBengali",
                    execResult.optString("message", "স্কিল এক্সিকিউশন সম্পন্ন হয়েছে।")
                )
                ActiveTaskInterruptionManager.completeTask(trackedTask.taskId, isSuccess, feedback)
                val needsA11y = execResult.optString("permissionRequired") == "accessibility"
                if (needsA11y) onPromptAccessibility?.invoke()
                return@withContext VoiceExecutionOutcome(
                    handled = true,
                    success = isSuccess,
                    feedbackMessage = feedback,
                    toolName = "executeLearnedSkill",
                    needsAccessibility = needsA11y,
                    rawResult = execResult
                )
            }
        } catch (_: Exception) {
        }

        // Step 3: Not an OS automation command — route to Gemini Live Conversational Core
        VoiceExecutionOutcome(
            handled = false,
            success = true,
            feedbackMessage = clean
        )
    }

    /**
     * Resolves a single Bengali/English/Banglish utterance to a canonical ToolRegistry tool + args
     * without executing side effects, used by JarvisMasterOrchestrator during planning.
     */
    fun resolveToolMapping(rawCommand: String): Pair<String, JSONObject>? {
        val clean = rawCommand.trim()
        if (clean.isBlank()) return null

        val parsed = JarvisNlpCommandParser.parse(clean)
        if (parsed !is JarvisParsedCommand.Unrecognized) {
            val (toolName, toolArgs) = mapParsedCommandToTool(parsed)
            if (toolName != null) return Pair(toolName, toolArgs)
        }

        val (heuristicTool, heuristicArgs) = mapHeuristicIntent(clean)
        return if (heuristicTool != null) Pair(heuristicTool, heuristicArgs) else null
    }

    private fun mapParsedCommandToTool(cmd: JarvisParsedCommand): Pair<String?, JSONObject> {
        val args = JSONObject()
        val name = when (cmd) {
            is JarvisParsedCommand.Call -> {
                args.put("contactName", cmd.target)
                args.put("phoneNumber", cmd.target)
                "makePhoneCall"
            }
            is JarvisParsedCommand.OpenApp -> {
                args.put("appName", cmd.appKey)
                "openApp"
            }
            is JarvisParsedCommand.CloseApp -> "closeApp"
            is JarvisParsedCommand.PlayYouTube -> {
                args.put("query", cmd.query)
                "searchAndPlayYouTube"
            }
            is JarvisParsedCommand.SendWhatsApp -> {
                args.put("contact", cmd.contactOrPhone)
                args.put("message", cmd.message)
                "controlWhatsApp"
            }
            is JarvisParsedCommand.WhatsAppVoiceCall -> {
                args.put("contact", cmd.contactOrPhone)
                "startWhatsAppVoiceCall"
            }
            is JarvisParsedCommand.WhatsAppVideoCall -> {
                args.put("contact", cmd.contactOrPhone)
                "startWhatsAppVideoCall"
            }
            is JarvisParsedCommand.WhatsAppOpenChat -> {
                args.put("contact", cmd.contactOrPhone)
                "openWhatsAppConversation"
            }
            is JarvisParsedCommand.SendSms -> {
                args.put("phoneNumber", cmd.phoneNumber)
                args.put("message", cmd.message)
                "sendSmsMessage"
            }
            is JarvisParsedCommand.ReadLatestNotification -> "readNotificationAloud"
            is JarvisParsedCommand.AnswerCall -> "answerIncomingCall"
            is JarvisParsedCommand.RejectCall -> "rejectIncomingCall"
            is JarvisParsedCommand.SilenceCall -> "silenceIncomingCall"
            is JarvisParsedCommand.EndActiveCall -> "endActiveCall"
            is JarvisParsedCommand.IdentifyCaller -> "identifyIncomingCaller"
            is JarvisParsedCommand.PostFacebook -> {
                args.put("postText", cmd.content)
                "openFacebook"
            }
            is JarvisParsedCommand.ClickElement -> {
                args.put("targetText", cmd.text)
                args.put("x", cmd.x.toDouble())
                args.put("y", cmd.y.toDouble())
                "clickScreen"
            }
            is JarvisParsedCommand.TypeText -> {
                args.put("text", cmd.text)
                "typeOnScreen"
            }
            is JarvisParsedCommand.Swipe -> {
                args.put("direction", cmd.direction)
                "swipeScreen"
            }
            is JarvisParsedCommand.SystemNav -> {
                args.put("action", cmd.action)
                "performSystemAction"
            }
            is JarvisParsedCommand.InspectScreen -> "inspectScreen"
            is JarvisParsedCommand.StartScreenSharing -> "startScreenSharing"
            is JarvisParsedCommand.StopScreenSharing -> "stopScreenSharing"
            is JarvisParsedCommand.CurrentLocation -> "getCurrentGpsLocation"
            is JarvisParsedCommand.OpenSettings -> {
                args.put("permissionType", cmd.settingsType)
                "openPermissionSettings"
            }
            is JarvisParsedCommand.StartSkillRecording -> {
                args.put("skillName", cmd.skillName)
                "startSkillRecording"
            }
            is JarvisParsedCommand.StopSkillRecording -> "stopAndSaveSkill"
            is JarvisParsedCommand.RunLearnedSkill -> {
                args.put("skillNameOrTrigger", cmd.skillName)
                "executeLearnedSkill"
            }
            else -> null
        }
        return Pair(name, args)
    }

    private fun mapHeuristicIntent(input: String): Pair<String?, JSONObject> {
        val lower = input.lowercase(Locale.ROOT)
        val args = JSONObject()

        return when {
            // Flashlight
            lower.contains("টর্চ জ্বালাও") || lower.contains("ফ্ল্যাশলাইট অন") || lower.contains("flashlight on") || lower.contains("turn on torch") -> {
                args.put("enabled", true)
                Pair("toggleFlashlight", args)
            }
            lower.contains("টর্চ বন্ধ") || lower.contains("ফ্ল্যাশলাইট বন্ধ") || lower.contains("flashlight off") || lower.contains("turn off torch") -> {
                args.put("enabled", false)
                Pair("toggleFlashlight", args)
            }

            // Volume (Canonical name: setVolume)
            lower.contains("ভলিউম বাড়াও") || lower.contains("আওয়াজ বাড়াও") || lower.contains("volume up") -> {
                args.put("volumePercent", 80)
                Pair("setVolume", args)
            }
            lower.contains("ভলিউম কমাও") || lower.contains("আওয়াজ কমাও") || lower.contains("volume down") -> {
                args.put("volumePercent", 30)
                Pair("setVolume", args)
            }

            // Brightness (Canonical name: setBrightness)
            lower.contains("ব্রাইটনেস বাড়াও") || lower.contains("উজ্জ্বলতা বাড়াও") || lower.contains("brightness up") -> {
                args.put("percent", 80)
                Pair("setBrightness", args)
            }
            lower.contains("ব্রাইটনেস কমাও") || lower.contains("উজ্জ্বলতা কমাও") || lower.contains("brightness down") -> {
                args.put("percent", 25)
                Pair("setBrightness", args)
            }

            // Battery (Canonical name: getBatteryLevel)
            lower.contains("ব্যাটারি") || lower.contains("চার্জ কত") || lower.contains("battery status") || lower.contains("battery level") -> {
                Pair("getBatteryLevel", args)
            }

            // Weather (Canonical name: getLiveWeather)
            lower.contains("আবহাওয়া") || lower.contains("weather") || lower.contains("বৃষ্টি হবে") || lower.contains("তাপমাত্রা") -> {
                Pair("getLiveWeather", args)
            }

            // News (Canonical name: getLiveNews)
            lower.contains("খবর") || lower.contains("সংবাদ") || lower.contains("news") || lower.contains("আজকের তাজা খবর") -> {
                if (lower.contains("ai") || lower.contains("tech") || lower.contains("প্রযুক্তি") || lower.contains("এআই")) {
                    args.put("category", "প্রযুক্তি ও এআই")
                } else {
                    args.put("category", "সব")
                }
                Pair("getLiveNews", args)
            }

            // Daily / Evening Briefing (Canonical name: generateDailyBriefing)
            lower.contains("briefing") || lower.contains("ব্রিফিং") -> {
                val slot = when {
                    lower.contains("evening") || lower.contains("সন্ধ্যা") -> "Evening"
                    lower.contains("night") || lower.contains("রাত") -> "Night"
                    lower.contains("morning") || lower.contains("সকাল") -> "Morning"
                    else -> ""
                }
                if (slot.isNotBlank()) args.put("timeOfDay", slot)
                Pair("generateDailyBriefing", args)
            }

            // Notifications (list vs read aloud)
            lower.contains("কোনো notification") || lower.contains("নোটিফিকেশন আছে") || lower.contains("check notification") || lower.contains("notifications") -> {
                args.put("limit", 10)
                Pair("getRecentNotifications", args)
            }
            lower.contains("নোটিফিকেশন পড়ো") || lower.contains("নোটিফিকেশন শোনাও") || lower.contains("read notification") || lower.contains("latest notification") -> {
                args.put("limit", 1)
                Pair("readNotificationAloud", args)
            }

            // Emergency Stop (Canonical name: emergencyStopAllWork)
            lower.contains("ইমার্জেন্সি স্টপ") || lower.contains("জরুরি বিরতি") || lower.contains("সব বন্ধ করো") || lower == "stop" || lower == "থামাও" -> {
                Pair("emergencyStopAllWork", args)
            }

            // Camera (start, stop, switch)
            lower.contains("ক্যামেরা বন্ধ") || lower.contains("stop camera") || lower.contains("close camera") -> {
                Pair("stopCameraVision", args)
            }
            lower.contains("front camera") || lower.contains("সামনের ক্যামেরা") || lower.contains("ক্যামেরা ঘোরাও") || lower.contains("switch camera") -> {
                Pair("switchCameraLens", args)
            }
            lower.contains("ক্যামেরা খোলো") || lower.contains("ক্যামেরা অন") || lower.contains("ক্যামেরা চালু") || lower.contains("open camera") || lower.contains("live camera") -> {
                Pair("startCameraVision", args)
            }

            // In-Call Telephony
            lower.contains("কল রিসিভ") || lower.contains("কল ধরো") || lower.contains("কল তোলো") || lower == "answer call" -> {
                Pair("answerIncomingCall", args)
            }
            lower.contains("কল কেটে দাও") || lower.contains("কল কাটো") || lower.contains("রিজেক্ট করো") || lower == "reject call" -> {
                Pair("rejectIncomingCall", args)
            }
            lower.contains("সাইলেন্ট করো") || lower.contains("রিংটোন মিউট") || lower == "silence call" || lower == "mute ringer" -> {
                Pair("silenceIncomingCall", args)
            }
            lower.contains("চলতি কল শেষ") || lower.contains("কথা শেষ") || lower == "end call" || lower == "hang up" -> {
                Pair("endActiveCall", args)
            }
            lower.contains("কে কল করেছে") || lower.contains("কলার কে") || lower == "who is calling" -> {
                Pair("identifyIncomingCaller", args)
            }

            else -> Pair(null, args)
        }
    }
}
