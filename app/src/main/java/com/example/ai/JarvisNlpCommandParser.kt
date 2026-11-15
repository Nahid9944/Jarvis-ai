package com.example.ai

import android.content.Context
import com.example.util.JarvisDeviceAutomationHelper

/**
 * High-performance Natural Language Command Parser for Jarvis AI Operating System.
 * Maps transcribed spoken and typed intents in Bengali and English to concrete
 * Android OS automation and system function calls with zero-latency local execution.
 */
sealed class JarvisParsedCommand {
    data class Call(val target: String) : JarvisParsedCommand()
    data class OpenApp(val appKey: String, val rawQuery: String) : JarvisParsedCommand()
    object CloseApp : JarvisParsedCommand()
    data class PlayYouTube(val query: String) : JarvisParsedCommand()
    data class SendWhatsApp(val contactOrPhone: String, val message: String) : JarvisParsedCommand()
    data class WhatsAppVoiceCall(val contactOrPhone: String) : JarvisParsedCommand()
    data class WhatsAppVideoCall(val contactOrPhone: String) : JarvisParsedCommand()
    data class WhatsAppOpenChat(val contactOrPhone: String) : JarvisParsedCommand()
    data class SendSms(val phoneNumber: String, val message: String) : JarvisParsedCommand()
    object ReadLatestNotification : JarvisParsedCommand()
    object AnswerCall : JarvisParsedCommand()
    object RejectCall : JarvisParsedCommand()
    object SilenceCall : JarvisParsedCommand()
    object EndActiveCall : JarvisParsedCommand()
    object IdentifyCaller : JarvisParsedCommand()
    data class PostFacebook(val content: String) : JarvisParsedCommand()
    data class ClickElement(val text: String, val x: Float = 0f, val y: Float = 0f) : JarvisParsedCommand()
    data class TypeText(val text: String) : JarvisParsedCommand()
    data class Swipe(val direction: String) : JarvisParsedCommand()
    data class SystemNav(val action: String) : JarvisParsedCommand()
    object InspectScreen : JarvisParsedCommand()
    object StartScreenSharing : JarvisParsedCommand()
    object StopScreenSharing : JarvisParsedCommand()
    object CurrentLocation : JarvisParsedCommand()
    data class OpenSettings(val settingsType: String) : JarvisParsedCommand()
    data class StartSkillRecording(val skillName: String) : JarvisParsedCommand()
    object StopSkillRecording : JarvisParsedCommand()
    data class RunLearnedSkill(val skillName: String) : JarvisParsedCommand()
    data class Unrecognized(val rawQuery: String) : JarvisParsedCommand()
}

data class JarvisExecutionResult(
    val success: Boolean,
    val feedbackMessage: String,
    val command: JarvisParsedCommand,
    val needsAccessibilityPermission: Boolean = false,
    val needsLocationPermission: Boolean = false,
    val needsScreenSharingPrompt: Boolean = false
)

object JarvisNlpCommandParser {

    private val PREFIX_REGEX = Regex(
        "^(?:jarvis|hey jarvis|ok jarvis|hi jarvis|hello jarvis|জারভিস|এই জারভিস|ওহে জারভিস|শোনো জারভিস|please|দয়া করে|দয়া করে|অনুগ্রহ করে)[,:\\s]+",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parses transcribed speech or text into a typed JarvisParsedCommand.
     */
    fun parse(rawInput: String): JarvisParsedCommand {
        val trimmed = rawInput.trim().replace("।", "").replace("?", "").replace("!", "").trim()
        if (trimmed.isBlank()) return JarvisParsedCommand.Unrecognized("")

        // Strip wake words and polite prefixes
        var cleaned = trimmed.replace(PREFIX_REGEX, "").trim()
        val lower = cleaned.lowercase()

        // -2. DEMONSTRATION LEARNING & SKILL INTENTS
        if (matchesStartLearning(lower)) {
            val name = extractSkillName(cleaned)
            return JarvisParsedCommand.StartSkillRecording(name)
        }
        if (matchesStopLearning(lower)) {
            return JarvisParsedCommand.StopSkillRecording
        }
        if (matchesRunLearnedSkill(lower)) {
            val name = extractRunSkillName(cleaned)
            return JarvisParsedCommand.RunLearnedSkill(name)
        }

        // -1. SCREEN SHARING INTENTS (START / STOP)
        if (matchesStartScreenSharing(lower)) {
            return JarvisParsedCommand.StartScreenSharing
        }
        if (matchesStopScreenSharing(lower)) {
            return JarvisParsedCommand.StopScreenSharing
        }

        // 0. TELECOM INCOMING & ACTIVE CALL INTENTS
        if (matchesAnswerCall(lower)) return JarvisParsedCommand.AnswerCall
        if (matchesRejectCall(lower)) return JarvisParsedCommand.RejectCall
        if (matchesSilenceCall(lower)) return JarvisParsedCommand.SilenceCall
        if (matchesEndActiveCall(lower)) return JarvisParsedCommand.EndActiveCall
        if (matchesIdentifyCaller(lower)) return JarvisParsedCommand.IdentifyCaller

        // 0.1 NOTIFICATION INTELLIGENCE INTENTS
        if (matchesReadLatestNotification(lower)) return JarvisParsedCommand.ReadLatestNotification

        // 0.2 SIM SMS INTENTS
        val smsParsed = parseSmsCommand(cleaned, lower)
        if (smsParsed != null) return smsParsed

        // 0.3 REAL-TIME GPS LOCATION & 'WHERE AM I' INTENTS
        if (matchesLocationQuery(lower)) {
            return JarvisParsedCommand.CurrentLocation
        }

        // 1. INSPECT SCREEN / OCR INTENTS
        if (matchesInspectScreen(lower)) {
            return JarvisParsedCommand.InspectScreen
        }

        // 2. CLOSE CURRENT APP / GO HOME INTENTS
        if (matchesCloseApp(lower)) {
            return JarvisParsedCommand.CloseApp
        }

        // 3. SYSTEM NAVIGATION INTENTS (Back, Recents, Notifications, etc.)
        val navAction = parseSystemNavigation(lower)
        if (navAction != null) {
            return JarvisParsedCommand.SystemNav(navAction)
        }

        // 4. SWIPE / SCROLL GESTURES
        val swipeDir = parseSwipeGesture(lower)
        if (swipeDir != null) {
            return JarvisParsedCommand.Swipe(swipeDir)
        }

        // 5. YOUTUBE SMART AUTOMATION
        val ytQuery = parseYouTubeQuery(cleaned, lower)
        if (ytQuery != null) {
            return JarvisParsedCommand.PlayYouTube(ytQuery)
        }

        // 6. WHATSAPP AUTOMATION
        val waParsed = parseWhatsAppCommand(cleaned, lower)
        if (waParsed != null) {
            return waParsed
        }

        // 7. FACEBOOK AUTOMATION
        val fbParsed = parseFacebookCommand(cleaned, lower)
        if (fbParsed != null) {
            return fbParsed
        }

        // 8. PHONE CALL INTENTS
        val callTarget = parseCallCommand(cleaned, lower)
        if (!callTarget.isNullOrBlank()) {
            return JarvisParsedCommand.Call(callTarget)
        }

        // 9. SCREEN CLICK INTENTS
        val clickTarget = parseClickCommand(cleaned, lower)
        if (!clickTarget.isNullOrBlank()) {
            return JarvisParsedCommand.ClickElement(clickTarget)
        }

        // 10. SCREEN TYPE INTENTS
        val typeContent = parseTypeCommand(cleaned, lower)
        if (!typeContent.isNullOrBlank()) {
            return JarvisParsedCommand.TypeText(typeContent)
        }

        // 11. OPEN SPECIFIC SETTINGS
        val settingsType = parseSettingsCommand(lower)
        if (settingsType != null) {
            return JarvisParsedCommand.OpenSettings(settingsType)
        }

        // 12. OPEN GENERAL APP INTENTS
        val appQuery = parseOpenAppCommand(cleaned, lower)
        if (!appQuery.isNullOrBlank()) {
            return JarvisParsedCommand.OpenApp(appKey = appQuery.lowercase(), rawQuery = appQuery)
        }

        // 13. Fallback to LLM / conversational Jarvis
        return JarvisParsedCommand.Unrecognized(rawInput)
    }

    /**
     * Determines if a command strictly relies on Android AccessibilityService
     */
    fun requiresAccessibility(command: JarvisParsedCommand): Boolean {
        return when (command) {
            is JarvisParsedCommand.CloseApp -> true
            is JarvisParsedCommand.ClickElement -> true
            is JarvisParsedCommand.TypeText -> true
            is JarvisParsedCommand.Swipe -> true
            is JarvisParsedCommand.SystemNav -> true
            is JarvisParsedCommand.InspectScreen -> true
            is JarvisParsedCommand.StartSkillRecording -> true
            is JarvisParsedCommand.RunLearnedSkill -> true
            is JarvisParsedCommand.WhatsAppOpenChat -> true
            else -> false
        }
    }

    /**
     * Executes the parsed command and reports result and feedback.
     */
    fun execute(
        context: Context,
        command: JarvisParsedCommand,
        onPromptAccessibility: () -> Unit
    ): JarvisExecutionResult {
        // Check accessibility requirement
        if (requiresAccessibility(command) && !JarvisDeviceAutomationHelper.isAccessibilityGranted(context)) {
            onPromptAccessibility()
            return JarvisExecutionResult(
                success = false,
                feedbackMessage = "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। স্ক্রিন নিয়ন্ত্রণ ও অটোমেশনের জন্য সেটিংসে গিয়ে জারভিস অ্যাক্সেসিবিলিটি অনুমতি দিন।",
                command = command,
                needsAccessibilityPermission = true
            )
        }

        return when (command) {
            is JarvisParsedCommand.Call -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.makePhoneCall(context, command.target)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.OpenApp -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.openApp(context, command.appKey)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.CloseApp -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.closeCurrentApp()
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.PlayYouTube -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.searchAndPlayYouTube(context, command.query)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.SendWhatsApp -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.openAndControlWhatsApp(
                    context = context,
                    contactOrPhone = command.contactOrPhone,
                    message = command.message
                )
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.WhatsAppVoiceCall -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.openAndControlWhatsApp(
                    context = context,
                    contactOrPhone = command.contactOrPhone,
                    message = ""
                )
                JarvisExecutionResult(ok, if (ok) "হোয়াটসঅ্যাপে ${command.contactOrPhone}-কে ভয়েস কল শুরু করা হচ্ছে..." else msg, command)
            }
            is JarvisParsedCommand.WhatsAppVideoCall -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.openAndControlWhatsApp(
                    context = context,
                    contactOrPhone = command.contactOrPhone,
                    message = ""
                )
                JarvisExecutionResult(ok, if (ok) "হোয়াটসঅ্যাপে ${command.contactOrPhone}-কে ভিডিও কল শুরু করা হচ্ছে..." else msg, command)
            }
            is JarvisParsedCommand.WhatsAppOpenChat -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.openAndControlWhatsApp(
                    context = context,
                    contactOrPhone = command.contactOrPhone,
                    message = ""
                )
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.SendSms -> {
                val res = com.example.util.JarvisSmsManager.dispatchSms(
                    context = context,
                    recipient = command.phoneNumber,
                    message = command.message
                )
                JarvisExecutionResult(res.success, res.summaryBengali, command)
            }
            is JarvisParsedCommand.AnswerCall -> {
                val ok = com.example.service.JarvisInCallService.answerCall()
                JarvisExecutionResult(ok, if (ok) "ইনকামিং কল রিসিভ করা হয়েছে।" else "কল রিসিভ করা যায়নি।", command)
            }
            is JarvisParsedCommand.RejectCall -> {
                val ok = com.example.service.JarvisInCallService.rejectCall()
                JarvisExecutionResult(ok, if (ok) "ইনকামিং কল রিজেক্ট করা হয়েছে।" else "কল রিজেক্ট করার মতো সক্রিয় কল পাওয়া যায়নি।", command)
            }
            is JarvisParsedCommand.SilenceCall -> {
                val ok = com.example.service.JarvisInCallService.silenceIncomingRinger(context)
                JarvisExecutionResult(ok, if (ok) "ইনকামিং কলের রিংটোন সাইলেন্ট করা হয়েছে।" else "সাইলেন্ট করা সম্ভব হয়নি।", command)
            }
            is JarvisParsedCommand.EndActiveCall -> {
                val ok = com.example.service.JarvisInCallService.endCall()
                JarvisExecutionResult(ok, if (ok) "চলমান কল সমাপ্ত করা হয়েছে।" else "কোনো চলমান কল পাওয়া যায়নি।", command)
            }
            is JarvisParsedCommand.IdentifyCaller -> {
                val info = com.example.service.JarvisInCallService.currentCallInfo.value
                val name = info.callerName.ifBlank { info.phoneNumber }
                val msg = if (name.isNotBlank()) {
                    "ইনকামিং কলার: $name (${info.stateLabelBn})"
                } else {
                    "বর্তমানে কোনো সক্রিয় বা ইনকামিং কল নেই।"
                }
                JarvisExecutionResult(true, msg, command)
            }
            is JarvisParsedCommand.ReadLatestNotification -> {
                val notifs = com.example.data.repository.JarvisNotificationRepository.getRecent(1)
                val msg = if (notifs.isNotEmpty()) {
                    val n = notifs.first()
                    "${n.appName} থেকে ${n.sender}: ${n.text}"
                } else {
                    "বর্তমানে কোনো নতুন নোটিফিকেশন নেই।"
                }
                com.example.util.JarvisVoiceSpeaker.speak(msg)
                JarvisExecutionResult(true, msg, command)
            }
            is JarvisParsedCommand.PostFacebook -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.openFacebook(context, command.content)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.ClickElement -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreen(command.text, command.x, command.y)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.TypeText -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.typeTextOnScreen(command.text)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.Swipe -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.performSwipe(command.direction)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.SystemNav -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.performSystemAction(command.action)
                JarvisExecutionResult(ok, msg, command)
            }
            is JarvisParsedCommand.InspectScreen -> {
                val summary = JarvisDeviceAutomationHelper.inspectCurrentScreen()
                JarvisExecutionResult(true, "বর্তমান স্ক্রিনের বিবরণ:\n$summary", command)
            }
            is JarvisParsedCommand.StartScreenSharing -> {
                JarvisExecutionResult(
                    success = true,
                    feedbackMessage = "স্ক্রিন শেয়ারিং শুরু করার জন্য স্ক্রিন প্রজেকশন অনুমতি দিন।",
                    command = command,
                    needsScreenSharingPrompt = true
                )
            }
            is JarvisParsedCommand.StopScreenSharing -> {
                com.example.util.JarvisScreenCaptureManager.stopScreenCaptureService(context)
                JarvisExecutionResult(
                    success = true,
                    feedbackMessage = "স্ক্রিন শেয়ারিং সফলভাবে বন্ধ করা হয়েছে।",
                    command = command
                )
            }
            is JarvisParsedCommand.CurrentLocation -> {
                if (!com.example.util.JarvisLiveLocationTracker.isLocationPermissionGranted(context)) {
                    JarvisExecutionResult(
                        success = false,
                        feedbackMessage = "রিয়েল-টাইম জিপিএস লোকেশন জানার জন্য লোকেশন পারমিশন প্রয়োজন। অনুগ্রহ করে পারমিশন দিন।",
                        command = command,
                        needsLocationPermission = true
                    )
                } else {
                    val (ok, loc) = kotlinx.coroutines.runBlocking {
                        com.example.util.JarvisLiveLocationTracker.fetchCurrentLocation(context)
                    }
                    if (ok && loc != null) {
                        val spoken = com.example.util.JarvisLiveLocationTracker.formatLocationSpokenSummary(loc)
                        JarvisExecutionResult(true, spoken, command)
                    } else {
                        JarvisExecutionResult(
                            false,
                            "স্যাটেলাইট থেকে জিপিএস লোকেশন সিগন্যাল পাওয়া যায়নি। অনুগ্রহ করে ফোনের লোকেশন/জিপিএস সার্ভিস চালু আছে কিনা চেক করুন।",
                            command
                        )
                    }
                }
            }
            is JarvisParsedCommand.OpenSettings -> {
                when (command.settingsType) {
                    "accessibility" -> JarvisDeviceAutomationHelper.openAccessibilitySettings(context)
                    "overlay" -> JarvisDeviceAutomationHelper.openOverlaySettings(context)
                    "battery" -> JarvisDeviceAutomationHelper.openBatteryOptimizationSettings(context)
                    "notification" -> JarvisDeviceAutomationHelper.openAppNotificationSettings(context)
                    else -> JarvisDeviceAutomationHelper.openApplicationDetailsSettings(context)
                }
                JarvisExecutionResult(true, "${command.settingsType} সেটিংস পেজ ওপেন করা হয়েছে।", command)
            }
            is JarvisParsedCommand.StartSkillRecording -> {
                val ok = com.example.automation.JarvisSkillRecorder.startRecording(context, command.skillName)
                JarvisExecutionResult(
                    success = ok,
                    feedbackMessage = if (ok) "‘${command.skillName}’ কাজের জন্য ডেমোনস্ট্রেশন লার্নিং মোড সক্রিয় করা হয়েছে। স্ক্রিনে কাজটি করে দেখান।" else "ডেমোনস্ট্রেশন লার্নিং মোড শুরু করা যায়নি।",
                    command = command
                )
            }
            is JarvisParsedCommand.StopSkillRecording -> {
                val skill = kotlinx.coroutines.runBlocking {
                    com.example.automation.JarvisSkillRecorder.stopAndSaveSkill(context)
                }
                JarvisExecutionResult(
                    success = skill != null,
                    feedbackMessage = if (skill != null) "ডেমোনস্ট্রেশন রেকর্ডিং সম্পন্ন। ‘${skill.nameBengali}’ স্কিল লাইব্রেরিতে সংরক্ষিত হয়েছে।" else "রেকর্ডকৃত কোনো ধাপ পাওয়া যায়নি বা সংরক্ষণ ব্যর্থ হয়েছে।",
                    command = command
                )
            }
            is JarvisParsedCommand.RunLearnedSkill -> {
                val repo = com.example.data.repository.JarvisSkillRepository(
                    com.example.data.local.AssistantDatabase.getDatabase(context).jarvisSkillDao()
                )
                val skill = kotlinx.coroutines.runBlocking { repo.findMatchingSkill(command.skillName) }
                if (skill != null) {
                    val steps = kotlinx.coroutines.runBlocking { repo.getStepsForSkill(skill.skillId) }
                    val service = com.example.service.JarvisAccessibilityService.instance
                    if (service != null) {
                        var executedCount = 0
                        steps.forEach { step ->
                            when (step.actionType) {
                                "OPEN_APP" -> if (!step.targetViewId.isNullOrBlank()) JarvisDeviceAutomationHelper.openApp(context, step.targetViewId)
                                "CLICK" -> if (!step.targetText.isNullOrBlank()) service.clickElementByText(step.targetText)
                                "INPUT_TEXT" -> if (!step.inputPayload.isNullOrBlank()) service.inputText(step.inputPayload)
                                "SCROLL_FORWARD" -> service.scrollForward()
                                "SCROLL_BACKWARD" -> service.scrollBackward()
                                "BACK" -> service.goBack()
                                "HOME" -> service.goHome()
                            }
                            executedCount++
                        }
                        kotlinx.coroutines.runBlocking { repo.recordExecution(skill.skillId, success = true) }
                        JarvisExecutionResult(
                            success = true,
                            feedbackMessage = "‘${skill.nameBengali}’ কাজটি সফলভাবে সম্পন্ন হয়েছে ($executedCount ধাপ সম্পন্ন)।",
                            command = command
                        )
                    } else {
                        JarvisExecutionResult(
                            success = false,
                            feedbackMessage = "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।",
                            command = command,
                            needsAccessibilityPermission = true
                        )
                    }
                } else {
                    JarvisExecutionResult(
                        success = false,
                        feedbackMessage = "‘${command.skillName}’ নামের কোনো শেখা কাজ লাইব্রেরিতে খুঁজে পাওয়া যায়নি।",
                        command = command
                    )
                }
            }
            is JarvisParsedCommand.Unrecognized -> {
                JarvisExecutionResult(false, "অজানা নির্দেশ। জারভিস জেমিনাই কোরে পাঠানো হচ্ছে...", command)
            }
        }
    }

    // =================================================================
    // PARSING HELPERS (BENGALI & ENGLISH REGEX MATCHERS)
    // =================================================================

    private fun matchesStartLearning(lower: String): Boolean {
        return lower.contains("কাজ শেখো") ||
                lower.contains("কাজটি শেখো") ||
                lower.contains("লার্নিং মোড") ||
                lower.contains("লার্ন করো") ||
                lower.contains("রেকর্ড করো") ||
                lower.contains("start learning") ||
                lower.contains("learn skill") ||
                lower.contains("record skill")
    }

    private fun matchesStopLearning(lower: String): Boolean {
        return lower.contains("শেখা শেষ") ||
                lower.contains("রেকর্ডিং শেষ") ||
                lower.contains("রেকর্ডিং থামাও") ||
                lower.contains("কাজটি সেভ করো") ||
                lower.contains("stop learning") ||
                lower.contains("stop recording") ||
                lower.contains("save skill")
    }

    private fun matchesRunLearnedSkill(lower: String): Boolean {
        return lower.contains("শেখা কাজ চালাও") ||
                lower.contains("স্কিল চালাও") ||
                lower.contains("স্কিল রান করো") ||
                lower.contains("execute skill") ||
                lower.contains("run skill")
    }

    private fun extractSkillName(cleaned: String): String {
        val regex = Regex("(?:কাজ শেখো|লার্নিং মোড|record skill|learn skill|কাজটি শেখো)\\s*(?:নাম)?\\s*(.+)?", RegexOption.IGNORE_CASE)
        val match = regex.find(cleaned)
        return match?.groupValues?.getOrNull(1)?.trim()?.ifBlank { "নতুন শেখা কাজ" } ?: "নতুন শেখা কাজ"
    }

    private fun extractRunSkillName(cleaned: String): String {
        val regex = Regex("(?:শেখা কাজ চালাও|স্কিল চালাও|run skill|execute skill)\\s*(.+)?", RegexOption.IGNORE_CASE)
        val match = regex.find(cleaned)
        return match?.groupValues?.getOrNull(1)?.trim()?.ifBlank { "" } ?: ""
    }

    private fun matchesLocationQuery(lower: String): Boolean {
        return lower.contains("কোথায় আছি") ||
                lower.contains("কোথায় আছি") ||
                lower.contains("আমার লোকেশন") ||
                lower.contains("আমার অবস্থান") ||
                lower.contains("লাইভ লোকেশন") ||
                lower.contains("রিয়েল টাইম লোকেশন") ||
                lower.contains("রিয়েল টাইম লোকেশন") ||
                lower.contains("লোকেশন ট্র্যাক") ||
                lower.contains("লোকেশন বলো") ||
                lower.contains("অবস্থান কত") ||
                lower.contains("where am i") ||
                lower.contains("current location") ||
                lower.contains("my location") ||
                lower.contains("track my location") ||
                lower.contains("track location") ||
                lower.contains("what is my location")
    }

    private fun matchesInspectScreen(lower: String): Boolean {
        return lower.contains("স্ক্রিন দেখো") ||
                lower.contains("স্ক্রিনে কী আছে") ||
                lower.contains("স্ক্রিন পড়ো") ||
                lower.contains("স্ক্রিন ক্যাপচার") ||
                lower.contains("স্ক্রিন স্ক্যান") ||
                lower.contains("inspect screen") ||
                lower.contains("read screen") ||
                lower.contains("what is on my screen") ||
                lower.contains("what's on my screen") ||
                lower.contains("scan screen")
    }

    private fun matchesStartScreenSharing(lower: String): Boolean {
        return lower.contains("start screen sharing") ||
                lower.contains("start screen share") ||
                lower.contains("share screen") ||
                lower.contains("স্ক্রিন শেয়ার শুরু") ||
                lower.contains("স্ক্রিন শেয়ার শুরু") ||
                lower.contains("স্ক্রিন শেয়ার অন") ||
                lower.contains("স্ক্রিন শেয়ার অন") ||
                lower.contains("স্ক্রিন শেয়ার চালু") ||
                lower.contains("স্ক্রিন শেয়ার চালু")
    }

    private fun matchesStopScreenSharing(lower: String): Boolean {
        return lower.contains("stop screen sharing") ||
                lower.contains("stop screen share") ||
                lower.contains("স্ক্রিন শেয়ার বন্ধ") ||
                lower.contains("স্ক্রিন শেয়ার বন্ধ") ||
                lower.contains("স্ক্রিন শেয়ার অফ") ||
                lower.contains("স্ক্রিন শেয়ার অফ") ||
                lower.contains("স্ক্রিন শেয়ার থামাও") ||
                lower.contains("স্ক্রিন শেয়ার থামাও")
    }

    private fun matchesCloseApp(lower: String): Boolean {
        return lower == "হোমে যাও" ||
                lower == "হোম" ||
                lower == "go home" ||
                lower == "home" ||
                lower == "হোমস্ক্রিনে যাও" ||
                lower == "হোমস্ক্রিনে ফিরে যাও" ||
                lower.contains("অ্যাপ বন্ধ করো") ||
                lower.contains("অ্যাপ ক্লোজ করো") ||
                lower == "close app" ||
                lower == "exit app" ||
                lower == "close current app"
    }

    private fun parseSystemNavigation(lower: String): String? {
        return when {
            lower == "back" || lower == "go back" || lower == "পেছনে যাও" || lower == "এক ধাপ পেছনে" || lower == "ব্যাক" -> "back"
            lower == "recents" || lower == "recent apps" || lower == "রিসেন্ট" || lower == "রিসেন্ট অ্যাপস" || lower.contains("সাম্প্রতিক অ্যাপ") -> "recents"
            lower == "notifications" || lower == "notification" || lower == "নোটিফিকেশন" || lower.contains("নোটিফিকেশন বার") -> "notifications"
            lower == "quick settings" || lower == "কুইক সেটিংস" || lower.contains("কন্ট্রোল প্যানেল") -> "quick_settings"
            lower == "lock" || lower == "lock screen" || lower.contains("স্ক্রিন লক") || lower.contains("ফোন লক") -> "lock"
            else -> null
        }
    }

    private fun parseSwipeGesture(lower: String): String? {
        return when {
            lower.contains("scroll down") || lower.contains("স্ক্রোল ডাউন") || lower.contains("নিচে নামাও") || lower.contains("swipe down") -> "scroll_down"
            lower.contains("scroll up") || lower.contains("স্ক্রোল আপ") || lower.contains("উপরে তোলো") || lower.contains("swipe up") -> "scroll_up"
            lower.contains("swipe left") || lower.contains("বামে সোয়াইপ") || lower.contains("বামে যাও") -> "left"
            lower.contains("swipe right") || lower.contains("ডানে সোয়াইপ") || lower.contains("ডানে যাও") -> "right"
            else -> null
        }
    }

    private fun parseYouTubeQuery(cleaned: String, lower: String): String? {
        // e.g. "play বাংলা গান on youtube"
        val ytEnRegex = Regex("^(?:play|search)\\s+(.+?)\\s+(?:on|in)\\s+youtube$", RegexOption.IGNORE_CASE)
        val matchEn = ytEnRegex.find(cleaned)
        if (matchEn != null) return matchEn.groupValues[1].trim()

        // e.g. "youtube play ..." or "youtube search ..."
        val ytPrefixEn = Regex("^youtube\\s+(?:play|search)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val matchPrefixEn = ytPrefixEn.find(cleaned)
        if (matchPrefixEn != null) return matchPrefixEn.groupValues[1].trim()

        // e.g. "ইউটিউবে রবীন্দ্রনাথের গান চালাও" or "ইউটিউবে গিয়ে বাংলা গান সার্চ করো"
        val ytBnRegex1 = Regex("^(?:ইউটিউব|ইউটিউবে)\\s*(?:গিয়ে|গিয়ে)?\\s*(.+?)\\s*(?:চালাও|প্লে করো|বাজাও|সার্চ করো|সার্চ দাও)$")
        val matchBn1 = ytBnRegex1.find(cleaned)
        if (matchBn1 != null) return matchBn1.groupValues[1].trim()

        // e.g. "বাংলা গান ইউটিউবে চালাও"
        val ytBnRegex2 = Regex("^(.+?)\\s*(?:ইউটিউব|ইউটিউবে)\\s*(?:চালাও|বাজাও|প্লে করো)$")
        val matchBn2 = ytBnRegex2.find(cleaned)
        if (matchBn2 != null) return matchBn2.groupValues[1].trim()

        // Banglish: "Motu Patlu play করো YouTube-এ" / "YouTube-এ গিয়ে নাটক চালাও"
        if ((lower.contains("ইউটিউব") || lower.contains("youtube")) && (lower.contains("চালাও") || lower.contains("প্লে") || lower.contains("play") || lower.contains("সার্চ") || lower.contains("search"))) {
            val extracted = cleaned
                .replace(Regex("ইউটিউব-এ|ইউটিউবে|ইউটিউব|youtube-এ|youtube-এ|youtube\\s*এ|youtube", RegexOption.IGNORE_CASE), "")
                .replace(Regex("(?:গিয়ে|গিয়ে|চালাও|প্লে করো|প্লে|সার্চ করো|সার্চ দাও|play করো|play|search|করো|দাও|on|in)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^[-–\\s]+|[-–\\s]+$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (extracted.isNotBlank()) return extracted
        }

        return null
    }

    private fun parseWhatsAppCommand(cleaned: String, lower: String): JarvisParsedCommand? {
        if (!lower.contains("whatsapp") && !lower.contains("হোয়াটসঅ্যাপ") && !lower.contains("হোয়াটস্যাপ")) {
            return null
        }

        fun cleanContact(raw: String): String {
            var name = raw.trim()
            if (name.endsWith("ের")) {
                name = name.substring(0, name.length - 2).trim()
            } else if (name.endsWith("এর")) {
                name = name.substring(0, name.length - 2).trim()
            } else if (name.endsWith("েকে")) {
                name = name.substring(0, name.length - 3).trim()
            } else if (name.endsWith("কে")) {
                name = name.substring(0, name.length - 2).trim()
            } else if (name.endsWith("র") && name.length > 2) {
                name = name.substring(0, name.length - 1).trim()
            }
            return name
        }

        // 1. WhatsApp Video Call: "হোয়াটসঅ্যাপে বাবাকে ভিডিও কল দাও"
        val waVideoRegex = Regex("(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ দিয়ে|whatsapp)\\s*(.+?)(?:\\s*(?:ভিডিও\\s*কল|video\\s*call))\\s*(?:দাও|করো|করুন|দাও)?", RegexOption.IGNORE_CASE)
        val matchVideo = waVideoRegex.find(cleaned)
        if (matchVideo != null) {
            val contact = cleanContact(matchVideo.groupValues[1])
            if (contact.isNotBlank()) return JarvisParsedCommand.WhatsAppVideoCall(contact)
        }

        // 2. WhatsApp Voice Call: "হোয়াটসঅ্যাপে বাবাকে কল দাও"
        val waCallRegexBn = Regex("(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ দিয়ে|whatsapp)\\s*(.+?)(?:\\s*(?:ভয়েস\\s*কল|ভয়েস\\s*কল|কল|ফোন|voice\\s*call|call))\\s*(?:দাও|করো|করুন)", RegexOption.IGNORE_CASE)
        val matchCallBn = waCallRegexBn.find(cleaned)
        if (matchCallBn != null) {
            val contact = cleanContact(matchCallBn.groupValues[1])
            if (contact.isNotBlank()) return JarvisParsedCommand.WhatsAppVoiceCall(contact)
        }

        // 3. WhatsApp Open Chat: "হোয়াটসঅ্যাপে তানভিরের চ্যাট খোলো" / "হোয়াটসঅ্যাপে চ্যাট খোলো তানভির"
        val waChatRegexBn = Regex("(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ দিয়ে|whatsapp)\\s*(.+?)(?:\\s+(?:চ্যাট|ইনবক্স|chat|inbox))\\s*(?:খোলো|খুলুন|ওপেন\\s*করো|open)", RegexOption.IGNORE_CASE)
        val matchChatBn = waChatRegexBn.find(cleaned)
        if (matchChatBn != null) {
            val contact = cleanContact(matchChatBn.groupValues[1])
            if (contact.isNotBlank()) return JarvisParsedCommand.WhatsAppOpenChat(contact)
        }
        val waChatRegexBn2 = Regex("(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ দিয়ে|whatsapp)\\s*(?:চ্যাট|ইনবক্স|chat|inbox)\\s*(?:খোলো|খুলুন|ওপেন\\s*করো|open)\\s*(.+)", RegexOption.IGNORE_CASE)
        val matchChatBn2 = waChatRegexBn2.find(cleaned)
        if (matchChatBn2 != null) {
            val contact = cleanContact(matchChatBn2.groupValues[1])
            if (contact.isNotBlank()) return JarvisParsedCommand.WhatsAppOpenChat(contact)
        }

        // 4. WhatsApp Message: "হোয়াটসঅ্যাপে তানভিরকে মেসেজ পাঠাও যে আমি ৫ মিনিট পর পৌঁছাচ্ছি"
        val waMsgRegexBn = Regex("(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ দিয়ে|whatsapp)\\s+(.+?)\\s+(?:মেসেজ|বার্তা|এসএমএস|বলো|টেক্সট)\\s*(?:দাও|পাঠাও|লিখো|করো)?\\s*(?:যে|বল)?\\s*(.*)", RegexOption.IGNORE_CASE)
        val matchMsgBn = waMsgRegexBn.find(cleaned)
        if (matchMsgBn != null) {
            val contact = cleanContact(matchMsgBn.groupValues[1])
            val msg = matchMsgBn.groupValues[2].trim()
            if (contact.isNotBlank()) return JarvisParsedCommand.SendWhatsApp(contact, msg)
        }
        val waMsgRegexBn2 = Regex("(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ দিয়ে|whatsapp)\\s+(.+?)\\s+(?:পাঠাও|লিখো|বলো)\\s+(?:যে\\s+)?(.*)", RegexOption.IGNORE_CASE)
        val matchMsgBn2 = waMsgRegexBn2.find(cleaned)
        if (matchMsgBn2 != null) {
            val contact = cleanContact(matchMsgBn2.groupValues[1])
            val msg = matchMsgBn2.groupValues[2].trim()
            if (contact.isNotBlank()) return JarvisParsedCommand.SendWhatsApp(contact, msg)
        }

        // English WhatsApp
        val waEnRegex = Regex("(?:send\\s+)?whatsapp\\s+(?:message\\s+)?to\\s+(.+?)(?:\\s+(?:saying|message|text)\\s+(.+))?$", RegexOption.IGNORE_CASE)
        val matchEn = waEnRegex.find(cleaned)
        if (matchEn != null) {
            val contact = matchEn.groupValues[1].trim()
            val msg = matchEn.groupValues.getOrNull(2)?.trim() ?: ""
            return JarvisParsedCommand.SendWhatsApp(contact, msg)
        }

        return JarvisParsedCommand.OpenApp("whatsapp", "whatsapp")
    }

    private fun parseFacebookCommand(cleaned: String, lower: String): JarvisParsedCommand? {
        if (!lower.contains("facebook") && !lower.contains("ফেসবুক") && !lower.contains("fb")) {
            return null
        }

        // Post on facebook: "ফেসবুকে পোস্ট করো শুভ সকাল"
        val fbPostRegexBn = Regex("(?:ফেসবুক|ফেসবুকে)\\s*(?:পোস্ট|স্ট্যাটাস)\\s*(?:দাও|করো|লিখো)\\s*(?:যে)?\\s*(.+)$")
        val matchBn = fbPostRegexBn.find(cleaned)
        if (matchBn != null) {
            return JarvisParsedCommand.PostFacebook(matchBn.groupValues[1].trim())
        }

        val fbPostRegexEn = Regex("(?:post|write)\\s+(?:on\\s+)?facebook\\s+(.+)$", RegexOption.IGNORE_CASE)
        val matchEn = fbPostRegexEn.find(cleaned)
        if (matchEn != null) {
            return JarvisParsedCommand.PostFacebook(matchEn.groupValues[1].trim())
        }

        return JarvisParsedCommand.OpenApp("facebook", "facebook")
    }

    private fun parseCallCommand(cleaned: String, lower: String): String? {
        // e.g. "রহিমকে কল দাও", "রহিমকে ফোন করো", "করিমকে কল দাও"
        val bnCallRegex1 = Regex("^(.+?)(?:কে| এ)?\\s*(?:কল|ফোন|ডায়াল)\\s*(?:দাও|করো|করুন|লাগান)$")
        val matchBn1 = bnCallRegex1.find(cleaned)
        if (matchBn1 != null) {
            val target = matchBn1.groupValues[1].trim()
            if (target.isNotBlank() && target != "কারো" && target != "কাউকে") return target
        }

        // e.g. "কল করো রহিমকে", "ফোন দাও ০১৭..."
        val bnCallRegex2 = Regex("^(?:কল|ফোন|ডায়াল)\\s*(?:করো|দাও|করুন)\\s*(.+)$")
        val matchBn2 = bnCallRegex2.find(cleaned)
        if (matchBn2 != null) {
            return matchBn2.groupValues[1].replace(Regex("কে$"), "").trim()
        }

        // English: "call Rahim", "dial 01712345678", "phone Mom"
        val enCallRegex = Regex("^(?:call|dial|phone|ring)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val matchEn = enCallRegex.find(cleaned)
        if (matchEn != null) {
            return matchEn.groupValues[1].trim()
        }

        return null
    }

    private fun parseClickCommand(cleaned: String, lower: String): String? {
        // e.g. "স্ক্রিনে সার্চ-এ ক্লিক করো" or "সার্চ বাটনে ক্লিক করো"
        val clickBnRegex = Regex("^(?:স্ক্রিনে|স্ক্রিনের)?\\s*(.+?)(?:-এ| এ| বাটনে| এ বাটনে)?\\s*(?:ক্লিক|ট্যাপ|প্রেস)\\s*(?:করো|করুন)$")
        val matchBn = clickBnRegex.find(cleaned)
        if (matchBn != null) {
            val target = matchBn.groupValues[1].trim()
            if (target.isNotBlank()) return target
        }

        // e.g. "ক্লিক করো সার্চ"
        val clickPrefixBn = Regex("^(?:ক্লিক|ট্যাপ)\\s*(?:করো|করুন)?\\s*(?:অন|on)?\\s*(.+)$")
        val matchPrefixBn = clickPrefixBn.find(cleaned)
        if (matchPrefixBn != null) {
            return matchPrefixBn.groupValues[1].trim()
        }

        // English: "click search", "tap login", "press submit"
        val clickEnRegex = Regex("^(?:click|tap|press)\\s+(?:on\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        val matchEn = clickEnRegex.find(cleaned)
        if (matchEn != null) {
            return matchEn.groupValues[1].trim()
        }

        return null
    }

    private fun parseTypeCommand(cleaned: String, lower: String): String? {
        // e.g. "টাইপ করো হ্যালো বাংলাদেশ", "লিখো ঢাকা"
        val typeBnRegex = Regex("^(?:টাইপ|লিখো|লেখো|লিখে দাও)\\s*(?:করো|করুন)?\\s*(.+)$")
        val matchBn = typeBnRegex.find(cleaned)
        if (matchBn != null) {
            return matchBn.groupValues[1].trim()
        }

        // English: "type hello world", "write Dhaka"
        val typeEnRegex = Regex("^(?:type|write|input)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val matchEn = typeEnRegex.find(cleaned)
        if (matchEn != null) {
            return matchEn.groupValues[1].trim()
        }

        return null
    }

    private fun parseSettingsCommand(lower: String): String? {
        return when {
            lower.contains("accessibility settings") || lower.contains("অ্যাক্সেসিবিলিটি সেটিংস") -> "accessibility"
            lower.contains("overlay settings") || lower.contains("ওভারলে সেটিংস") -> "overlay"
            lower.contains("battery settings") || lower.contains("ব্যাটারি সেটিংস") -> "battery"
            lower.contains("notification settings") || lower.contains("নোটিফিকেশন সেটিংস") -> "notification"
            lower == "settings" || lower == "সেটিংস" || lower.contains("settings খোলো") || lower.contains("সেটিংস ওপেন") -> "settings"
            else -> null
        }
    }

    private fun parseOpenAppCommand(cleaned: String, lower: String): String? {
        fun isReservedDeviceControlTarget(candidate: String): Boolean {
            val c = candidate.lowercase(java.util.Locale.ROOT).trim()
            return c in setOf("এই", "আমার", "ফোন", "স্ক্রিন", "screen", "camera", "ক্যামেরা", "front camera", "সামনের ক্যামেরা", "back camera", "flashlight", "ফ্ল্যাশলাইট", "torch", "টর্চ", "টর্চলাইট")
        }

        // e.g. "ইউটিউব খোলো", "ফেসবুক ওপেন করো", "Instagram open করো", "YouTube launch দাও"
        val openAppBnRegex1 = Regex("^(.+?)\\s*(?:অ্যাপ|অ্যাপস|app)?\\s*(?:ওপেন|চালু|খোলো|খুলুন|অন|open|launch|start)\\s*(?:করো|করুন|দাও)?$", RegexOption.IGNORE_CASE)
        val matchBn1 = openAppBnRegex1.find(cleaned)
        if (matchBn1 != null) {
            val app = matchBn1.groupValues[1].trim()
            if (app.isNotBlank() && !isReservedDeviceControlTarget(app)) return app
        }

        // e.g. "ওপেন করো ইউটিউব", "open করো Instagram", "খোলো ফেসবুক"
        val openAppBnRegex2 = Regex("^(?:ওপেন|চালু|খোলো|খুলুন|open|launch|start)\\s*(?:করো|করুন|দাও)?\\s*(.+)$", RegexOption.IGNORE_CASE)
        val matchBn2 = openAppBnRegex2.find(cleaned)
        if (matchBn2 != null) {
            val app = matchBn2.groupValues[1].trim()
            if (app.isNotBlank() && !isReservedDeviceControlTarget(app)) return app
        }

        // English: "open YouTube", "launch Facebook", "switch to Instagram"
        val openAppEnRegex = Regex("^(?:open|launch|start|switch to)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val matchEn = openAppEnRegex.find(cleaned)
        if (matchEn != null) {
            val app = matchEn.groupValues[1].trim()
            if (app.isNotBlank() && !isReservedDeviceControlTarget(app)) return app
        }

        return null
    }

    private fun matchesAnswerCall(lower: String): Boolean {
        return lower == "কল রিসিভ করো" || lower == "কল তোলো" || lower == "কল ধরো" ||
                lower == "answer call" || lower == "pick up" || lower == "receive call" ||
                lower.contains("কল ধর") || lower.contains("কল রিসিভ")
    }

    private fun matchesRejectCall(lower: String): Boolean {
        return lower == "কল কেটে দাও" || lower == "কল কাটো" || lower == "কল রিজেক্ট করো" ||
                lower == "রিজেক্ট করো" || lower == "reject call" || lower == "decline call" ||
                lower.contains("কল রিজেক্ট") || lower.contains("কল বাতিল")
    }

    private fun matchesSilenceCall(lower: String): Boolean {
        return lower == "কল সাইলেন্ট করো" || lower == "ফোন সাইলেন্ট করো" || lower == "রিংটোন মিউট করো" ||
                lower == "রিংটোন সাইলেন্ট করো" || lower == "silence call" || lower == "mute ringer" ||
                lower.contains("রিংটোন মিউট") || lower.contains("রিং মিউট")
    }

    private fun matchesEndActiveCall(lower: String): Boolean {
        return lower == "চলতি কল কেটে দাও" || lower == "কল শেষ করো" || lower == "চলতি কল শেষ করো" ||
                lower == "end call" || lower == "hang up" || lower == "disconnect call" ||
                lower.contains("চলতি কল") || lower.contains("কল ডিসকানেক্ট")
    }

    private fun matchesIdentifyCaller(lower: String): Boolean {
        return lower == "কে কল করেছে" || lower == "কলার কে" || lower == "কে ফোন করেছে" ||
                lower == "কে ফোন দিয়েছে" || lower == "who is calling" || lower == "who's calling" ||
                lower.startsWith("কে কল") || lower.contains("কলার কে") || lower.contains("কে কল করেছে") ||
                lower.contains("কে ফোন করেছে") || lower.contains("কে ফোন দিয়েছে")
    }

    private fun matchesReadLatestNotification(lower: String): Boolean {
        return lower.contains("সর্বশেষ নোটিফিকেশন") ||
                lower.contains("নোটিফিকেশন পড়ে শোনাও") ||
                lower.contains("নোটিফিকেশন পড়ো") ||
                lower.contains("read notification") ||
                lower.contains("read latest notification") ||
                lower.contains("নতুন নোটিফিকেশন কি") ||
                lower.contains("নতুন কোনো নোটিফিকেশন")
    }

    private fun parseSmsCommand(cleaned: String, lower: String): JarvisParsedCommand? {
        if (!lower.contains("এসএমএস") && !lower.contains("sms") && !lower.contains("মেসেজ পাঠাও") && !lower.contains("বার্তা পাঠাও")) {
            return null
        }
        val smsRegexBn = Regex("([0-9+]+)\\s*(?:নম্বরে|নাম্বারে)?\\s*(?:এসএমএস|মেসেজ|বার্তা|sms)\\s*(?:পাঠাও|দাও|করো)?\\s*[:\\-]?\\s*(.*)", RegexOption.IGNORE_CASE)
        val matchBn = smsRegexBn.find(cleaned)
        if (matchBn != null) {
            val phone = matchBn.groupValues[1].trim()
            val msg = matchBn.groupValues[2].trim()
            if (phone.length >= 5) {
                return JarvisParsedCommand.SendSms(phone, msg)
            }
        }
        val smsEnRegex = Regex("(?:send\\s+)?sms\\s+(?:to\\s+)?([0-9+]+)\\s*(?:message|text)?\\s*[:\\-]?\\s*(.*)", RegexOption.IGNORE_CASE)
        val matchEn = smsEnRegex.find(cleaned)
        if (matchEn != null) {
            val phone = matchEn.groupValues[1].trim()
            val msg = matchEn.groupValues[2].trim()
            return JarvisParsedCommand.SendSms(phone, msg)
        }
        return null
    }
}
