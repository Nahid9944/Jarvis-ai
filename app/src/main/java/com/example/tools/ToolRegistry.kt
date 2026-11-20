package com.example.tools

import org.json.JSONArray
import org.json.JSONObject

object ToolRegistry {

    private val toolsMap = LinkedHashMap<String, ToolSpec>()

    init {
        registerAllBuiltInTools()
    }

    fun register(spec: ToolSpec) {
        toolsMap[spec.name] = spec
    }

    fun get(name: String): ToolSpec? = toolsMap[name]

    fun getAll(): List<ToolSpec> = toolsMap.values.toList()

    fun getByCategory(category: ToolCategory): List<ToolSpec> = toolsMap.values.filter { it.category == category }

    fun getByPermission(permissionId: String): List<ToolSpec> = toolsMap.values.filter { it.requiredPermissionId == permissionId }

    fun getToolsRequiringConfirmation(): List<ToolSpec> = toolsMap.values.filter { 
        it.confirmationLevel == ConfirmationLevel.EXPLICIT_CONFIRM || 
        it.confirmationLevel == ConfirmationLevel.HUMAN_GATE ||
        it.confirmationLevel == ConfirmationLevel.BIOMETRIC
    }

    val totalCount: Int get() = toolsMap.size

    fun getDeclarationsJson(): JSONArray {
        val array = JSONArray()
        for (spec in toolsMap.values) {
            if (!spec.enabled) continue
            val obj = JSONObject()
            obj.put("name", spec.name)
            obj.put("description", "${spec.description} (${spec.titleBn})")

            val paramsObj = JSONObject()
            paramsObj.put("type", "object")
            val propsObj = JSONObject()
            val reqArray = JSONArray()

            for ((pName, pDesc) in spec.parameters) {
                val parsed = parseParamSchema(spec.name, pName, pDesc)
                val pObj = JSONObject()
                pObj.put("type", parsed.type)
                pObj.put("description", parsed.description)
                if (parsed.enumValues.isNotEmpty()) {
                    pObj.put("enum", JSONArray(parsed.enumValues))
                }
                propsObj.put(pName, pObj)
                if (parsed.isRequired) {
                    reqArray.put(pName)
                }
            }
            paramsObj.put("properties", propsObj)
            paramsObj.put("required", reqArray)
            obj.put("parameters", paramsObj)

            array.put(obj)
        }
        return array
    }

    private data class ParsedParamSchema(
        val type: String,
        val description: String,
        val isRequired: Boolean,
        val enumValues: List<String> = emptyList()
    )

    private val TOOL_ENUM_HINTS: Map<Pair<String, String>, List<String>> = mapOf(
        ("navigateApp" to "screen") to listOf("hud", "camera", "notepad", "profile", "settings", "notification_center", "firebase_backup"),
        ("openPermissionSettings" to "permissionType") to listOf("accessibility", "overlay", "battery", "notification", "app_info"),
        ("mediaControl" to "action") to listOf("PLAY", "PAUSE", "NEXT", "PREV", "SEEK"),
        ("performSystemAction" to "action") to listOf("home", "back", "recents", "notifications", "quick_settings", "scroll_down", "scroll_up"),
        ("getAllAiTasks" to "status") to listOf("PENDING", "COMPLETED", "ALL"),
        ("swipeScreen" to "direction") to listOf("UP", "DOWN", "LEFT", "RIGHT"),
        ("scrollScreen" to "direction") to listOf("UP", "DOWN"),
        ("executeRoutine" to "routineName") to listOf("Morning", "Office", "Night")
    )

    private fun parseParamSchema(toolName: String, pName: String, pDesc: String): ParsedParamSchema {
        val trimmedDesc = pDesc.trim()
        val lower = trimmedDesc.lowercase()

        // 1. Determine type
        val type = when {
            lower.startsWith("boolean:") || pName == "enabled" || pName.startsWith("is") || pName.endsWith("Enabled") || pName == "speakerOn" || pName == "muted" || pName == "confirm" || pName == "sanitize" || pName == "autoSend" -> "boolean"
            lower.startsWith("integer:") || lower.startsWith("int:") || pName.endsWith("Percent") || pName == "delayMinutes" || pName == "limit" || pName == "count" || pName == "level" || pName == "port" || pName == "seekMs" || pName == "maxSwipes" || pName == "stepIndex" || pName == "timeoutMs" || pName == "delayMs" || pName == "frameWidth" || pName == "frameHeight" -> "integer"
            lower.startsWith("number:") || lower.startsWith("float:") || pName == "latitude" || pName == "longitude" || pName == "zoomLevel" || pName == "zoomRatio" || pName == "x" || pName == "y" || pName == "xNorm" || pName == "yNorm" || pName == "confidence" -> "number"
            lower.startsWith("array:") || lower.startsWith("list:") || pName.endsWith("List") -> "array"
            lower.startsWith("object:") -> "object"
            else -> "string"
        }

        // 2. Clean description
        val cleanDesc = trimmedDesc
            .removePrefix("string: ")
            .removePrefix("String: ")
            .removePrefix("boolean: ")
            .removePrefix("Boolean: ")
            .removePrefix("integer: ")
            .removePrefix("Integer: ")
            .removePrefix("int: ")
            .removePrefix("number: ")
            .removePrefix("Number: ")
            .removePrefix("float: ")
            .removePrefix("array: ")
            .removePrefix("list: ")
            .removePrefix("object: ")
            .trim()

        // 3. Determine if required or optional
        val isRequired = !(cleanDesc.contains("(optional)", ignoreCase = true) ||
                cleanDesc.contains("optional", ignoreCase = true) ||
                cleanDesc.startsWith("ঐচ্ছিক") ||
                pName.startsWith("opt"))

        val enums = TOOL_ENUM_HINTS[toolName to pName].orEmpty()
        return ParsedParamSchema(type, cleanDesc, isRequired, enums)
    }

    private fun registerAllBuiltInTools() {
        // -------------------------------------------------------------
        // 1. SYSTEM ACTIONS & APP NAVIGATION
        // -------------------------------------------------------------
        reg("openApp", "অ্যাপ্লিকেশন ওপেন করা", "Launch or open any Android application on the phone by name (e.g. 'YouTube', 'WhatsApp', 'Facebook', 'Chrome', 'Settings', 'Camera', 'Calculator', 'Phone') or package.", ToolCategory.SYSTEM_ACTIONS, mapOf("appName" to "string: App name (e.g. YouTube, WhatsApp, Facebook, Settings)"))
        reg("closeApp", "অ্যাপ্লিকেশন বন্ধ করা", "Close the currently open application and return to Home screen.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), isMutating = true, risk = 25)
        reg("navigateApp", "নির্দিষ্ট স্ক্রিনে নেভিগেশন", "Navigate to any internal screen. Allowed: hud, camera, notepad, profile, settings, notification_center, firebase_backup.", ToolCategory.SYSTEM_ACTIONS, mapOf("screen" to "string: Destination screen key"))
        reg("goBack", "পূর্ববর্তী স্ক্রিনে ফেরত", "Navigate back to previous screen or HUD.", ToolCategory.SYSTEM_ACTIONS, emptyMap())
        reg("closeCurrentScreen", "বর্তমান স্ক্রিন বন্ধ করা", "Close current screen and return to HUD.", ToolCategory.SYSTEM_ACTIONS, emptyMap())
        reg("openPermissionSettings", "অনুমতি সেটিংস স্ক্রিন", "Jump directly to specific Android Settings: accessibility, overlay, battery, notification, app_info.", ToolCategory.SYSTEM_ACTIONS, mapOf("permissionType" to "string: 'accessibility', 'overlay', 'battery', 'notification', 'app_info'"))
        reg("openFirebaseBackup", "ক্লাউড ব্যাকআপ স্ক্রিন", "Open the Cloud Backup and Restore screen.", ToolCategory.SYSTEM_ACTIONS, emptyMap())
        reg("openDeepLink", "ডিপ লিংক ওপেন করা", "Open a verified deep link or custom URI schema.", ToolCategory.SYSTEM_ACTIONS, mapOf("uri" to "string: Deep link URI string"))
        reg("getDeviceInfo", "ডিভাইস ও সিস্টেম তথ্য", "Get hardware model, OS version, battery status, and runtime info.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), risk = 5)
        reg("getBatteryLevel", "ব্যাটারি চার্জ শতাংশ", "Get live battery charge percentage and charging state.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), risk = 5)

        // -------------------------------------------------------------
        // 2. DEVICE & MEDIA CONTROL
        // -------------------------------------------------------------
        reg("searchAndPlayYouTube", "ইউটিউবে অনুসন্ধান ও প্লে", "Search YouTube for any video, music, song, or topic and automatically play the video.", ToolCategory.MEDIA_CONTROL, mapOf("query" to "string: Search term or video title to play on YouTube"), isMutating = true, risk = 25)
        reg("mediaControl", "মিডিয়া প্লেয়ার নিয়ন্ত্রণ", "Control media playback (PLAY, PAUSE, NEXT, PREV, SEEK) across YouTube or background audio.", ToolCategory.MEDIA_CONTROL, mapOf("action" to "string: Transport action ('PLAY', 'PAUSE', 'NEXT', 'PREV', 'SEEK')"), isMutating = true, risk = 20)
        reg("openFacebook", "ফেসবুক ওপেন করা", "Open Facebook application, and optionally initiate a new post with specified text.", ToolCategory.SYSTEM_ACTIONS, mapOf("postText" to "string: Optional post text to publish on Facebook"))
        reg("toggleFlashlight", "ফ্ল্যাশলাইট অন/অফ", "Turn camera flashlight on or off.", ToolCategory.DEVICE_CONTROL, mapOf("enabled" to "boolean: true to turn on, false to turn off"), isMutating = true, risk = 15)
        reg("setVolume", "সাউন্ড ভলিউম নির্ধারণ", "Set media, notification, or ring volume (0 to 100).", ToolCategory.DEVICE_CONTROL, mapOf("volumePercent" to "integer: Volume percentage (0-100)"), isMutating = true, risk = 15)

        // -------------------------------------------------------------
        // 3. COMMUNICATION (PHONE, SMS, WHATSAPP)
        // -------------------------------------------------------------
        reg("makePhoneCall", "সরাসরি ফোন কল করা", "Initiate a direct SIM phone call through the user's mobile phone to a given number or contact.", ToolCategory.COMMUNICATION, mapOf("phoneNumber" to "string: The phone number to dial (e.g. 01712345678)", "contactName" to "string: Optional contact name"), permId = "call_phone", confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 80)
        reg("sendWhatsAppMessage", "হোয়াটসঅ্যাপ বার্তা পাঠানো", "Open WhatsApp direct chat to send a message to a phone number.", ToolCategory.COMMUNICATION, mapOf("phoneNumber" to "string: Recipient phone number", "messageText" to "string: Message text to send"), confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 75)
        reg("startWhatsAppVoiceCall", "হোয়াটসঅ্যাপ ভয়েস কল করা", "Initiate a direct WhatsApp voice call to a contact or phone number.", ToolCategory.COMMUNICATION, mapOf("contact" to "string: Contact name or phone number"), confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 75)
        reg("startWhatsAppVideoCall", "হোয়াটসঅ্যাপ ভিডিও কল করা", "Initiate a direct WhatsApp video call to a contact or phone number.", ToolCategory.COMMUNICATION, mapOf("contact" to "string: Contact name or phone number"), confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 75)
        reg("verifyWhatsAppCallState", "হোয়াটসঅ্যাপ কল অবস্থা যাচাই", "Check if WhatsApp voice or video call is currently active on screen.", ToolCategory.COMMUNICATION, emptyMap(), risk = 10)
        reg("endWhatsAppCall", "হোয়াটসঅ্যাপ কল সমাপ্তকরণ", "Terminate or hang up currently active WhatsApp voice or video call.", ToolCategory.COMMUNICATION, emptyMap(), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("controlWhatsApp", "হোয়াটসঅ্যাপ পূর্ণ অটোমেশন", "Open WhatsApp, search contact, type message, and send via accessibility automation.", ToolCategory.APP_AUTOMATION, mapOf("contact" to "string: Contact name or phone number", "message" to "string: Message text to type and send"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("sendSmsMessage", "এসএমএস বার্তা পাঠানো", "Send SMS message directly via mobile carrier SIM.", ToolCategory.COMMUNICATION, mapOf("phoneNumber" to "string: Recipient phone number", "message" to "string: Text content"), permId = "send_sms", confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 80)
        reg("replyFromNotification", "নোটিফিকেশন থেকে রিপ্লাই", "Directly reply to incoming WhatsApp or messaging notifications without opening the app UI.", ToolCategory.APP_AUTOMATION, mapOf("packageFilter" to "string: App package ('com.whatsapp')", "replyText" to "string: Text reply message"), permId = "notification_listener", confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 75)
        reg("shareViaIntent", "শেয়ার ইনটেন্ট কল করা", "Share text or media content into external applications using Android ACTION_SEND intent.", ToolCategory.COMMUNICATION, mapOf("targetPackage" to "string: Target app package name", "text" to "string: Content text to share"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 35)

        // -------------------------------------------------------------
        // 4. SCREEN PERCEPTION & ACCESSIBILITY AUTOMATION
        // -------------------------------------------------------------
        reg("inspectScreen", "স্ক্রিন পরিদর্শন ও রিড", "Real-time screen perception: reads all visible text elements, buttons, and content currently visible on the phone screen via Jarvis Accessibility Service.", ToolCategory.SCREEN_CAPTURE, emptyMap(), permId = "accessibility", risk = 15)
        reg("getScreenContext", "সম্পূর্ণ স্ক্রিন কনটেক্সট", "Extract structured screen elements: headings, buttons, input fields, toggle switches, browser URL, and foreground app.", ToolCategory.SCREEN_CAPTURE, emptyMap(), permId = "accessibility", risk = 15)
        reg("clickScreen", "স্ক্রিনে ক্লিক সিমুলেশন", "Physically tap or click an element on screen by its visible text label or specific (x, y) coordinates.", ToolCategory.APP_AUTOMATION, mapOf("targetText" to "string: Visible text or button label to click", "x" to "number: Optional screen X coordinate float", "y" to "number: Optional screen Y coordinate float"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("longPressOnScreen", "স্ক্রিন উপাদানে লং-প্রেস", "Perform long press (600ms) on a specific text label or coordinates.", ToolCategory.APP_AUTOMATION, mapOf("targetText" to "string: Visible text to long press", "x" to "number: Optional screen X coordinate", "y" to "number: Optional screen Y coordinate"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("typeOnScreen", "স্ক্রিনে টেক্সট ইনপুট", "Type text into currently focused input field on screen.", ToolCategory.APP_AUTOMATION, mapOf("text" to "string: Text string to type"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("clearAndTypeOnScreen", "ইনপুট ফিল্ড ক্লিয়ার করে টাইপ", "Focus input field, clear previous text, and type new text string.", ToolCategory.APP_AUTOMATION, mapOf("text" to "string: Text to enter"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("performSystemAction", "সিস্টেম বোতাম অ্যাকশন", "Perform Android system navigation action: 'home', 'back', 'recents', 'notifications', 'quick_settings', 'scroll_down', 'scroll_up'.", ToolCategory.APP_AUTOMATION, mapOf("action" to "string: Action ('home', 'back', 'recents', 'notifications', 'quick_settings', 'scroll_down', 'scroll_up')"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("captureAndAnalyzeScreen", "স্ক্রিন ক্যাপচার ও বিশ্লেষণ", "Captures user's mobile screen using MediaProjection API or Accessibility screenshot and performs multimodal Gemini visual analysis.", ToolCategory.SCREEN_CAPTURE, mapOf("question" to "string: Optional user question or specific focus"), permId = "screen_capture", risk = 25)
        reg("executeVisualSuggestion", "ভিজ্যুয়াল উপাদানে ক্লিক/ইনপুট", "Execute a structured visual suggestion returned by screen vision using semantic node lookup or confidence-gated normalized coordinate fallback.", ToolCategory.APP_AUTOMATION, mapOf("actionType" to "string: Action ('click', 'type', 'scroll', 'open_app', 'system')", "title" to "string: Human-readable title (optional)", "targetTextOrId" to "string: Visible text or target label", "viewId" to "string: Android view resource ID (optional)", "xNorm" to "number: Normalized screen X (0..1) (optional)", "yNorm" to "number: Normalized screen Y (0..1) (optional)", "frameWidth" to "integer: Captured screenshot width px (optional)", "frameHeight" to "integer: Captured screenshot height px (optional)", "confidence" to "number: Model confidence 0..1 (optional)", "inputPayload" to "string: Text to type when actionType='type' (optional)"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 45)
        reg("waitForNode", "নোড প্রদর্শনের অপেক্ষা", "Wait until specific UI element appears on screen with polling.", ToolCategory.APP_AUTOMATION, mapOf("selectorQuery" to "string: View ID, text, or content description to wait for"), permId = "accessibility", risk = 15)
        reg("assertScreen", "স্ক্রিন অবস্থা যাচাই", "Deterministic assertion checking foreground package and visible text.", ToolCategory.APP_AUTOMATION, mapOf("expectedPackage" to "string: Expected package name"), permId = "accessibility", risk = 15)
        reg("readVisibleList", "দৃশ্যমান তালিকা পাঠ", "Extract all currently visible text and lists from active screen.", ToolCategory.SCREEN_CAPTURE, emptyMap(), permId = "accessibility", risk = 15)
        reg("scrollToText", "নির্দিষ্ট লেখায় স্ক্রোল", "Scroll active screen until target text is reached.", ToolCategory.APP_AUTOMATION, mapOf("targetText" to "string: Text label to scroll to"), permId = "accessibility", risk = 15)
        reg("startScreenSharing", "স্ক্রিন শেয়ারিং শুরু", "Trigger screen capture projection service for live AI perception.", ToolCategory.SCREEN_CAPTURE, emptyMap(), permId = "screen_capture", risk = 25)
        reg("pauseScreenVision", "স্ক্রিন ভিশন সাময়িক বিরতি", "Pause live screen capture stream and visual AI polling.", ToolCategory.SCREEN_CAPTURE, emptyMap(), risk = 10)
        reg("resumeScreenVision", "স্ক্রিন ভিশন পুনরায় চালু", "Resume live screen capture stream and visual perception.", ToolCategory.SCREEN_CAPTURE, emptyMap(), risk = 10)
        reg("stopScreenSharing", "স্ক্রিন শেয়ারিং বন্ধ", "Stop screen capture projection service.", ToolCategory.SCREEN_CAPTURE, emptyMap(), risk = 10)

        // -------------------------------------------------------------
        // 5. PLAYBOOK RUNNER & WORKFLOW ENGINE
        // -------------------------------------------------------------
        reg("runPlaybook", "প্লেবুক অটোমেশন চালানো", "Execute a verified deterministic automation playbook for WhatsApp, YouTube, Facebook, or system apps.", ToolCategory.APP_AUTOMATION, mapOf("playbookId" to "string: Playbook ID (e.g. 'wa.send_text', 'yt.search_play', 'fb.compose_post')"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 50)
        reg("runAutonomousTask", "স্বায়ত্তশাসিত এআই টাস্ক চালানো", "Execute unified 11-step autonomous task loop (understand -> observe -> plan -> check -> act -> wait -> observe -> verify -> replan -> report).", ToolCategory.APP_AUTOMATION, mapOf("goal" to "string: Goal description in natural language", "planSteps" to "string: Optional JSON array of steps"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 60)
        reg("listPlaybooks", "প্লেবুক তালিকা প্রদর্শন", "List available automation playbooks, optionally filtered by application package name.", ToolCategory.APP_AUTOMATION, mapOf("appPackage" to "string: Optional package name filter"))
        reg("recordPlaybook", "নতুন প্লেবুক রেকর্ড", "Register a custom automation workflow playbook into the JARVIS Playbook Store.", ToolCategory.APP_AUTOMATION, mapOf("id" to "string: Unique playbook identifier", "titleBn" to "string: Bengali description", "appPackage" to "string: Target package"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 50)
        reg("validatePlaybook", "প্লেবুক বৈধতা পরীক্ষা", "Perform dry-run check of a playbook against current device state.", ToolCategory.APP_AUTOMATION, mapOf("playbookId" to "string: Playbook ID to validate"))
        reg("getAppVersion", "অ্যাপ্লিকেশন সংস্করণ তথ্য", "Query installed version and versionCode of an Android app.", ToolCategory.APP_AUTOMATION, mapOf("appPackage" to "string: Package name"))
        reg("getPlaybookHealth", "প্লেবুক কার্যকারিতা পরীক্ষা", "Check health metrics, error logs, and failure rates of automation playbooks.", ToolCategory.APP_AUTOMATION, mapOf("playbookId" to "string: Optional specific playbook ID"))
        reg("humanConfirm", "ব্যবহারকারীর সম্মতি প্রক্রিয়া", "Request mandatory user confirmation before executing external communication or critical actions.", ToolCategory.APP_AUTOMATION, mapOf("actionType" to "string: Action type", "target" to "string: Recipient name or target"))
        reg("confirmPendingAction", "পেন্ডিং অ্যাকশন নিশ্চিতকরণ ও কার্যকর", "When user replies 'হ্যাঁ' / 'Yes' / 'অনুমতি দিলাম' / 'Proceed' or 'না' / 'Cancel' to a pending confirmation prompt, approve and execute the pending action immediately or cancel it.", ToolCategory.APP_AUTOMATION, mapOf("approvalId" to "string: Pending approvalId returned by previous CONFIRMATION_REQUIRED response", "userConfirmationPhrase" to "string: Exact affirmative or negative phrase spoken by user (e.g. 'হ্যাঁ, পাঠিয়ে দাও', 'অনুমতি দিলাম', 'Yes', 'Cancel')"))

        // -------------------------------------------------------------
        // 6. AI LONG-TERM MEMORY
        // -------------------------------------------------------------
        reg("saveMemory", "তথ্য মেমোরিতে সংরক্ষণ", "Save a new custom fact, rule, user preference, or personal instruction into AI long-term memory so Jarvis remembers permanently.", ToolCategory.AI_MEMORY, mapOf("key" to "string: Short title or keyword (e.g. 'মালিকের নাম', 'User Rule')", "content" to "string: Detailed instruction or fact", "category" to "string: Category ('USER_PREFERENCE', 'AI_BEHAVIOR', 'SYSTEM_RULE', 'GENERAL')"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 30)
        reg("searchMemories", "মেমোরি অনুসন্ধান", "Search all saved AI long-term memories by keyword or topic.", ToolCategory.AI_MEMORY, mapOf("query" to "string: Keyword to search in memory"), risk = 5)
        reg("getAllMemories", "সকল মেমোরি তালিকা", "Retrieve all active memories stored in Jarvis AI long-term memory.", ToolCategory.AI_MEMORY, emptyMap(), risk = 5)
        reg("deleteMemory", "মেমোরি মুছে ফেলা", "Delete a specific memory by its title/key or ID from long-term memory.", ToolCategory.AI_MEMORY, mapOf("keyOrId" to "string: Key title or ID to delete"), confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 85)

        // -------------------------------------------------------------
        // 7. AUTONOMOUS AI TASKS & REMINDERS
        // -------------------------------------------------------------
        reg("createAiTask", "নতুন এআই টাস্ক তৈরি", "Schedule or create an autonomous AI task or reminder for NAHID AI (e.g. remind at 8 AM, daily backup, battery check).", ToolCategory.AI_MEMORY, mapOf("title" to "string: Short task title", "timeOrMinutesFromNow" to "string: Schedule delay in minutes (e.g. '30') or time string (e.g. '08:00 AM')"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 30)
        reg("setExactScheduleAlarm", "নির্দিষ্ট শিডিউল অ্যালার্ম", "Schedule an exact system-level clock alarm for a personal task or reminder.", ToolCategory.AI_MEMORY, mapOf("title" to "string: Title of alarm", "delayMinutes" to "integer: Delay in minutes from now"), permId = "exact_alarm", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 30)
        reg("getAllAiTasks", "সকল এআই টাস্ক তালিকা", "Get list of all scheduled and active AI tasks.", ToolCategory.AI_MEMORY, mapOf("status" to "string: 'PENDING', 'COMPLETED', or 'ALL'"), risk = 5)
        reg("completeAiTask", "এআই টাস্ক সম্পন্নকরণ", "Mark an AI task as finished/completed.", ToolCategory.AI_MEMORY, mapOf("taskId" to "integer: ID of the task"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 30)
        reg("deleteAiTask", "এআই টাস্ক মুছে ফেলা", "Delete or cancel a scheduled AI task.", ToolCategory.AI_MEMORY, mapOf("taskId" to "integer: ID of the task"), confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 85)

        // -------------------------------------------------------------
        // 8. PERSONAL NOTEPAD / VOICE LOGS
        // -------------------------------------------------------------
        reg("createNote", "নতুন নোট তৈরি", "Save a new personal memo or note.", ToolCategory.PERSONAL_NOTEPAD, mapOf("title" to "string: Note title", "content" to "string: Note content"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 30)
        reg("addNoteDirect", "সরাসরি নোট সংরক্ষণ", "Quickly save note title and content.", ToolCategory.PERSONAL_NOTEPAD, mapOf("title" to "string: Note title", "content" to "string: Note content"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 30)
        reg("getNotes", "সকল নোটের তালিকা", "Get all personal notes and memos.", ToolCategory.PERSONAL_NOTEPAD, emptyMap(), risk = 5)
        reg("getLatestNote", "সর্বশেষ সংরক্ষিত নোট", "Get the most recent personal note.", ToolCategory.PERSONAL_NOTEPAD, emptyMap(), risk = 5)
        reg("deleteNote", "নোট মুছে ফেলা", "Delete a note from notepad.", ToolCategory.PERSONAL_NOTEPAD, mapOf("titleOrId" to "string: Title or ID of the note"), confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 85)

        // -------------------------------------------------------------
        // 9. INTELLIGENCE, LOCATION, WEATHER, WEB
        // -------------------------------------------------------------
        reg("getCurrentGpsLocation", "বর্তমান জিপিএস লোকেশন", "Get real-time device GPS coordinates and street address.", ToolCategory.LOCATION, emptyMap(), permId = "location_fine", risk = 15)
        reg("getLiveWeather", "লাইভ আবহাওয়া বার্তা", "Get real-time weather report, temperature in Celsius, humidity, wind speed, and rain forecast for user location or any specified city.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("locationName" to "string: Location name, defaults to Morrelganj / Dhaka"), risk = 5)
        reg("searchWikipedia", "উইকিপিডিয়া অনুসন্ধান", "Search Wikipedia online encyclopedia (Bengali or English) for scientific, historical, or general knowledge summaries.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("query" to "string: Subject, article title, or keyword"), risk = 5)
        reg("getLiveNews", "তাজা সংবাদ অনুসন্ধান", "Fetch live current news bulletins and updates.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("category" to "string: News category ('সব', 'বিজ্ঞান ও প্রযুক্তি', 'বাংলাদেশ', 'আন্তর্জাতিক')"), risk = 5)
        reg("searchExternalKnowledge", "বহিঃস্থ জ্ঞানভাণ্ডার সন্ধান", "Search broader world knowledge, internet/web info, general science, math, or external facts.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("query" to "string: Search topic or question"), risk = 5)
        reg("getSystemDiagnostics", "সিস্টেম ডায়াগনস্টিকস", "Run full system diagnostic: Arc Reactor core, battery health, neural memory usage, permission status, and connected nodes.", ToolCategory.SECURITY_DIAGNOSTICS, emptyMap(), risk = 5)

        // -------------------------------------------------------------
        // 10. NOTIFICATION INTELLIGENCE
        // -------------------------------------------------------------
        reg("getNotificationAccessStatus", "নোটিফিকেশন অনুমতি স্ট্যাটাস", "Check if notification listener service is running and granted.", ToolCategory.SYSTEM_ACTIONS)
        reg("getRecentNotifications", "সাম্প্রতিক নোটিফিকেশন তালিকা", "Get list of recent notifications with sender, app name, and time.", ToolCategory.SYSTEM_ACTIONS, mapOf("limit" to "integer: Optional max count (default 10)"))
        reg("getNotificationsByApp", "অ্যাপ অনুযায়ী নোটিফিকেশন", "Get notifications filtered by app name or package (e.g. WhatsApp, Messenger).", ToolCategory.SYSTEM_ACTIONS, mapOf("appName" to "string: App name or package"))
        reg("getNotificationDetails", "নোটিফিকেশন বিস্তারিত তথ্য", "Get full details of a specific notification by sender or package.", ToolCategory.SYSTEM_ACTIONS, mapOf("appName" to "string: App name", "sender" to "string: Optional sender"))
        reg("announceNewNotification", "নোটিফিকেশন ভয়েস ঘোষণা", "Announce latest notification via voice speech.", ToolCategory.SYSTEM_ACTIONS)
        reg("readNotificationAloud", "নোটিফিকেশন পড়ে শোনানো", "Read notification text aloud in Bengali.", ToolCategory.SYSTEM_ACTIONS, mapOf("sender" to "string: Optional sender name"))
        reg("summarizeNotification", "নোটিফিকেশন সংক্ষেপণ", "Summarize recent unread notifications.", ToolCategory.INTELLIGENCE_REPORTS)
        reg("classifyNotification", "নোটিফিকেশন শ্রেণীকরণ", "Classify notification into Personal, Work, Alert, or Informational.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("text" to "string: Notification text"))
        reg("suggestSmartReplies", "স্মার্ট রিপ্লাই সাজেশন", "Generate 3 intelligent contextual reply suggestions for a message.", ToolCategory.COMMUNICATION, mapOf("messageText" to "string: Message text received"))
        reg("sendNotificationReply", "নোটিফিকেশনে সরাসরি উত্তর", "Send a direct reply to a notification using Android RemoteInput.", ToolCategory.COMMUNICATION, mapOf("packageFilter" to "string: App package name (e.g. com.whatsapp)", "replyText" to "string: Text reply to send"), permId = "notification_listener", confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 75)
        reg("dismissNotification", "নোটিফিকেশন ডিসমিস করা", "Dismiss/cancel a notification by key.", ToolCategory.SYSTEM_ACTIONS, mapOf("key" to "string: Notification key"), permId = "notification_listener", isMutating = true, risk = 20)

        // -------------------------------------------------------------
        // 11. INCOMING CALL & TELEPHONY INTELLIGENCE
        // -------------------------------------------------------------
        reg("getActiveCallState", "সক্রিয় কল স্ট্যাটাস", "Get current ongoing or incoming telephone call state.", ToolCategory.COMMUNICATION, emptyMap(), permId = "phone_state")
        reg("getIncomingCallDetails", "ইনকামিং কল বিস্তারিত", "Get caller phone number, resolved contact name, and duration.", ToolCategory.COMMUNICATION, emptyMap(), permId = "phone_state")
        reg("identifyIncomingCaller", "ইনকামিং কলার শনাক্তকরণ", "Identify the name and contact details of caller currently ringing.", ToolCategory.COMMUNICATION, emptyMap(), permId = "phone_state")
        reg("announceIncomingCaller", "কলার নাম ঘোষণা", "Announce incoming caller name via voice speech.", ToolCategory.COMMUNICATION)
        reg("answerIncomingCall", "কল রিসিভ করা", "Voice-controlled answering of currently ringing incoming phone call.", ToolCategory.COMMUNICATION, emptyMap(), permId = "answer_phone_calls", isMutating = true, risk = 30)
        reg("rejectIncomingCall", "কল রিজেক্ট বা কেটে দেওয়া", "Reject or hang up incoming phone call.", ToolCategory.COMMUNICATION, emptyMap(), isMutating = true, risk = 30)
        reg("silenceIncomingCall", "কল সাইলেন্ট করা", "Silence phone ringer for incoming call.", ToolCategory.COMMUNICATION, emptyMap(), isMutating = true, risk = 10)
        reg("endActiveCall", "চলমান কল সমাপ্ত করা", "End/disconnect active phone call.", ToolCategory.COMMUNICATION, emptyMap(), isMutating = true, risk = 30)
        reg("muteActiveCall", "কল মিউট/আনমিউট", "Mute or unmute microphone during active phone call.", ToolCategory.COMMUNICATION, mapOf("muted" to "boolean: true to mute, false to unmute"), isMutating = true, risk = 15)
        reg("toggleCallSpeaker", "কল স্পিকার লাউডস্পিকার", "Toggle speakerphone on or off during phone call.", ToolCategory.COMMUNICATION, mapOf("speakerOn" to "boolean: true for speaker, false for earpiece"), isMutating = true, risk = 15)
        reg("getCallDuration", "কলের সময়কাল", "Get duration of active phone call.", ToolCategory.COMMUNICATION)

        // -------------------------------------------------------------
        // 12. CAMERA VISION & MULTIMODAL INTELLIGENCE
        // -------------------------------------------------------------
        reg("startCameraVision", "ক্যামেরা ভিশন শুরু", "Start live camera perception session.", ToolCategory.CAMERA_VISION, emptyMap(), permId = "camera", risk = 20)
        reg("stopCameraVision", "ক্যামেরা ভিশন বন্ধ", "Stop active camera session and release hardware.", ToolCategory.CAMERA_VISION, emptyMap(), risk = 5)
        reg("captureCameraFrame", "ক্যামেরা ফ্রেম ক্যাপচার", "Capture an image frame from camera for AI analysis.", ToolCategory.CAMERA_VISION, emptyMap(), permId = "camera", risk = 15)
        reg("analyzeCameraView", "ক্যামেরা দৃশ্য বিশ্লেষণ", "Analyze what the camera currently sees and answer questions.", ToolCategory.CAMERA_VISION, mapOf("question" to "string: User question about the scene"), permId = "camera", risk = 20)
        reg("switchCameraLens", "ক্যামেরা লেন্স পরিবর্তন", "Switch between front and back camera.", ToolCategory.CAMERA_VISION, emptyMap(), permId = "camera", isMutating = true, risk = 10)
        reg("setCameraZoom", "ক্যামেরা জুম নির্ধারণ", "Set camera zoom level (1.0 to 5.0).", ToolCategory.CAMERA_VISION, mapOf("zoomRatio" to "number: Zoom multiplier"), permId = "camera", isMutating = true, risk = 10)
        reg("toggleCameraTorch", "ক্যামেরা টর্চ অন/অফ", "Toggle camera flashlight torch.", ToolCategory.CAMERA_VISION, mapOf("enabled" to "boolean: true or false"), permId = "camera", isMutating = true, risk = 10)
        reg("detectObjectsInView", "বস্তু শনাক্তকরণ", "Detect and list visible objects in camera view.", ToolCategory.CAMERA_VISION, emptyMap(), permId = "camera", risk = 20)
        reg("readTextFromCamera", "ক্যামেরা টেক্সট পাঠ (OCR)", "Extract printed or handwritten text visible in camera.", ToolCategory.CAMERA_VISION, emptyMap(), permId = "camera", risk = 20)
        reg("explainVisualScene", "দৃশ্য বর্ণনা", "Describe the environment and objects in front of the camera in Bengali.", ToolCategory.CAMERA_VISION, emptyMap(), permId = "camera", risk = 20)

        // -------------------------------------------------------------
        // 13. SCREEN INTELLIGENCE & ACCESSIBILITY AUTOMATION
        // -------------------------------------------------------------
        reg("findUiElement", "স্ক্রিন উপাদান সন্ধান", "Find an on-screen button or input by its visible text label or ID.", ToolCategory.APP_AUTOMATION, mapOf("query" to "string: Button text or query"), permId = "accessibility", risk = 15)
        reg("clickUiElement", "স্ক্রিন উপাদানে ক্লিক", "Click a specific UI element found on screen.", ToolCategory.APP_AUTOMATION, mapOf("query" to "string: Text of button to click"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("longPressUiElement", "স্ক্রিন লং প্রেস", "Long press on a UI element.", ToolCategory.APP_AUTOMATION, mapOf("query" to "string: Text of element to long press"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("swipeScreen", "স্ক্রিন সোয়াইপ", "Perform swipe gesture ('UP', 'DOWN', 'LEFT', 'RIGHT').", ToolCategory.APP_AUTOMATION, mapOf("direction" to "string: Swipe direction"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 35)
        reg("scrollScreen", "স্ক্রিন স্ক্রোল", "Scroll active screen up or down.", ToolCategory.APP_AUTOMATION, mapOf("direction" to "string: 'UP' or 'DOWN'"), permId = "accessibility", isMutating = true, risk = 20)
        reg("typeIntoFocusedField", "টেক্সট ফিল্ডে টাইপ", "Type text into active focused input box.", ToolCategory.APP_AUTOMATION, mapOf("text" to "string: Text to type"), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("verifyUiState", "স্ক্রিন অবস্থা যাচাই", "Verify if expected text or screen change occurred.", ToolCategory.APP_AUTOMATION, mapOf("expectedText" to "string: Expected text"), permId = "accessibility", risk = 15)

        // -------------------------------------------------------------
        // 14. SOCIAL & MESSAGING AUTOMATION
        // -------------------------------------------------------------
        reg("readFacebookFeed", "ফেসবুক ফিড পাঠ", "Read currently visible Facebook feed posts.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), permId = "accessibility", risk = 20)
        reg("readFacebookPost", "ফেসবুক পোস্ট পাঠ", "Read details of active Facebook post.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), permId = "accessibility", risk = 20)
        reg("reactToCurrentPost", "পোস্টে লাইক দেওয়া", "Like currently visible post on screen.", ToolCategory.APP_AUTOMATION, emptyMap(), permId = "accessibility", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 35)
        reg("commentOnFacebookPost", "ফেসবুক পোস্টে কমেন্ট করা", "Post a comment on the currently visible Facebook post after explicit user confirmation.", ToolCategory.APP_AUTOMATION, mapOf("text" to "string: Comment text to post on Facebook"), permId = "accessibility", confirm = ConfirmationLevel.HUMAN_GATE, isMutating = true, risk = 65)
        reg("createFacebookPostDraft", "ফেসবুক পোস্ট ড্রাফট", "Create a new Facebook post draft with specified text and optional Android Photo Picker media URI.", ToolCategory.SYSTEM_ACTIONS, mapOf("text" to "string: Post content", "mediaUri" to "string: Optional Android Photo Picker content:// URI (optional)"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("openWhatsAppConversation", "হোয়াটসঅ্যাপ চ্যাট খোলা", "Open WhatsApp conversation with contact or phone number.", ToolCategory.COMMUNICATION, mapOf("contact" to "string: Contact name or phone number"), isMutating = true, risk = 25)
        reg("searchContacts", "কন্টাক্ট সন্ধান", "Search phone contacts by name.", ToolCategory.COMMUNICATION, mapOf("name" to "string: Contact name to search"), permId = "contacts_read", risk = 10)
        reg("getContactDetails", "কন্টাক্ট বিস্তারিত", "Get phone number and details of contact.", ToolCategory.COMMUNICATION, mapOf("name" to "string: Contact name"), permId = "contacts_read", risk = 10)
        reg("createContact", "নতুন কন্টাক্ট সেভ", "Save a new phone number to contacts.", ToolCategory.COMMUNICATION, mapOf("name" to "string: Contact name", "phone" to "string: Phone number"), permId = "contacts_write", confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)

        // -------------------------------------------------------------
        // 15. DEVICE INTELLIGENCE & SETTINGS
        // -------------------------------------------------------------
        reg("getForegroundApp", "চলমান অ্যাপের নাম", "Identify which app is currently open in foreground.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), permId = "usage_access", risk = 10)
        reg("getDeviceState", "ডিভাইস সামগ্রিক অবস্থা", "Get battery, network, storage, volume, and brightness state.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), risk = 5)
        reg("getBatteryHealthInfo", "ব্যাটারি স্বাস্থ্য তথ্য", "Get battery percentage, charging state, and temperature.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), risk = 5)
        reg("getNetworkStatus", "নেটওয়ার্ক সংযোগ স্ট্যাটাস", "Get active Wi-Fi / mobile data connectivity status.", ToolCategory.SYSTEM_ACTIONS, emptyMap(), risk = 5)
        reg("setBrightness", "স্ক্রিন ব্রাইটনেস নির্ধারণ", "Set display brightness level (0 to 100).", ToolCategory.DEVICE_CONTROL, mapOf("percent" to "integer: Brightness percentage 0-100"), permId = "write_settings", isMutating = true, risk = 15)
        reg("openWifiSettings", "ওয়াইফাই সেটিংস খোলা", "Open system Wi-Fi settings page.", ToolCategory.DEVICE_CONTROL, emptyMap(), risk = 10)
        reg("openBluetoothSettings", "ব্লুটুথ সেটিংস খোলা", "Open system Bluetooth settings page.", ToolCategory.DEVICE_CONTROL, emptyMap(), risk = 10)

        // -------------------------------------------------------------
        // 16. PRODUCTIVITY, ROUTINES & EMERGENCY STOP
        // -------------------------------------------------------------
        reg("executeRoutine", "রুটিন চালানো", "Execute Morning Briefing, Office Protocol, or Night Review routine.", ToolCategory.AI_MEMORY, mapOf("routineName" to "string: Routine ('Morning', 'Office', 'Night')"), isMutating = true, risk = 15)
        reg("listRoutines", "রুটিন তালিকা", "List all predefined and custom autonomous routines.", ToolCategory.AI_MEMORY, emptyMap(), risk = 5)
        reg("generateDailyBriefing", "দৈনিক ব্রিফিং তৈরি", "Generate comprehensive daily audio briefing (time, weather, tasks, news).", ToolCategory.INTELLIGENCE_REPORTS, emptyMap(), risk = 5)
        reg("emergencyStopAllWork", "সব কাজ জরুরি বন্ধ", "Global Emergency Stop: immediately halts active automation, camera, screen recording, and pending tasks.", ToolCategory.SECURITY_DIAGNOSTICS, emptyMap(), isMutating = true, risk = 90)

        // -------------------------------------------------------------
        // 17. SKILL LIBRARY & DEMONSTRATION LEARNING
        // -------------------------------------------------------------
        reg("startSkillRecording", "নতুন কাজ শেখা শুরু", "Start demonstration learning session to record user actions into a new skill.", ToolCategory.APP_AUTOMATION, mapOf("skillName" to "string: Skill title", "triggerPhrase" to "string: Voice trigger phrase", "targetApp" to "string: Target app package or name (optional)"), permId = "accessibility", isMutating = true, risk = 20)
        reg("stopAndSaveSkill", "শেখা কাজ সংরক্ষণ", "Stop active demonstration recording session and save compiled workflow to Skill Library.", ToolCategory.APP_AUTOMATION, mapOf("description" to "string: Skill description (optional)"), permId = "accessibility", isMutating = true, risk = 20)
        reg("listLearnedSkills", "শেখা কাজের তালিকা", "List all custom skills learned via demonstration, voice teaching, or import.", ToolCategory.APP_AUTOMATION, emptyMap(), risk = 5)
        reg("executeLearnedSkill", "শেখা কাজ চালানো", "Execute a custom workflow skill from the Skill Library by name or trigger phrase.", ToolCategory.APP_AUTOMATION, mapOf("skillNameOrTrigger" to "string: Name or trigger phrase of the learned skill"), permId = "accessibility", isMutating = true, risk = 30)
        reg("deleteLearnedSkill", "শেখা কাজ মুছে ফেলা", "Delete a custom learned skill from the Skill Library.", ToolCategory.APP_AUTOMATION, mapOf("skillId" to "string: Unique skill ID to delete"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 40)
        reg("importSkillFromYouTube", "ইউটিউব থেকে কাজ শেখা", "Import procedural Android automation workflow by parsing a YouTube tutorial link and optional transcript.", ToolCategory.APP_AUTOMATION, mapOf("url" to "string: YouTube tutorial URL", "skillName" to "string: Skill title (optional)", "transcript" to "string: Video transcript or notes (optional)"), permId = "accessibility", isMutating = true, risk = 25)
        reg("importSkillFromGitHub", "গিটহাব থেকে স্কিল ইমপোর্ট", "Import declared Android automation playbooks and skill definitions from a GitHub repository.", ToolCategory.APP_AUTOMATION, mapOf("repoUrl" to "string: GitHub repo URL (e.g. https://github.com/owner/repo)", "branch" to "string: Branch name (optional, defaults to main)"), permId = "accessibility", isMutating = true, risk = 25)
        reg("learnFromText", "টেক্সট থেকে কাজ ও নিয়ম শেখা", "Classify and learn Bengali/Banglish/English instructions into Memory, Routine, or a validated Skill Draft.", ToolCategory.AI_MEMORY, mapOf("instruction" to "string: Natural language instruction or steps to learn", "title" to "string: Optional title", "dryRun" to "boolean: Validate without saving (optional)"), isMutating = true, risk = 20)
        reg("deepResearchWeb", "গভীর ওয়েব গবেষণা ও সূত্র যাচাই", "Search public web sources, extract full page content, cross-check facts across domains, and return cited Bengali research.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("query" to "string: Research topic or question", "maxPages" to "integer: Optional max pages to extract (default 3)"), risk = 5)
        reg("calculateDistanceAndRoute", "দূরত্ব হিসাব ও গুগল ম্যাপস রুট", "Geocode places worldwide via OSM/Geocoder, calculate live OSRM road route by travelMode (driving/walking/cycling/transit) or honest Haversine ETA, and optionally open Google Maps route navigation.", ToolCategory.LOCATION, mapOf("destination" to "string: Destination city/place or 'lat,lng'", "origin" to "string: Optional origin city/place or 'lat,lng' (uses live device GPS if omitted)", "travelMode" to "string: Optional travelMode: 'driving', 'walking', 'cycling', or 'transit' (optional)", "launchMaps" to "boolean: Launch Google Maps route (optional)"), risk = 10)
        reg("getBangladeshDateTimeAndCalendar", "বাংলাদেশ সময় ও সংশোধিত বাংলা বর্ষপঞ্জি", "Get exact Asia/Dhaka time/date and Bangla Academy 2019 Revised Bangladesh Bangla Calendar date.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("isoDate" to "string: Optional YYYY-MM-DD date to convert (optional)"), risk = 5)
        reg("verifyWhatsAppMessageStatus", "হোয়াটসঅ্যাপ ডেলিভারি/রিড স্ট্যাটাস যাচাই", "Inspect visible WhatsApp chat tick status (Sent, Delivered, Read) via Accessibility.", ToolCategory.COMMUNICATION, emptyMap(), permId = "accessibility", risk = 15)
        reg("listInstalledApps", "ফোনের ইনস্টলকৃত অ্যাপ তালিকা", "Discover launchable apps installed on the phone by Bengali/Banglish/English name with pagination (no hardcoded 40-app cap) or check availability.", ToolCategory.SYSTEM_ACTIONS, mapOf("query" to "string: Optional app filter query", "page" to "integer: Optional 0-based page index (default 0)", "pageSize" to "integer: Optional items per page up to 500 (default 100)", "checkApp" to "string: Optional exact app/package to verify availability (optional)"), risk = 5)
        reg("readVisibleAppData", "সক্রিয় অ্যাপের দৃশ্যমান তালিকা ও স্টক পড়া", "Read and deduplicate visible list items, products, or stock quantities from the active foreground app screen via Accessibility only.", ToolCategory.APP_AUTOMATION, mapOf("filterKeyword" to "string: Optional item or stock keyword filter (optional)"), permId = "accessibility", risk = 10)

        // -------------------------------------------------------------
        // 18. HOME DEVELOPER & AUTONOMOUS SKILL FACTORY
        // -------------------------------------------------------------
        reg("startHomeDevTask", "হোম ডেভেলপার কাজ শুরু", "Start a tracked HOME DEVELOPER task with checkpointed execution (feature, skill, self-repair, or web workspace).", ToolCategory.APP_AUTOMATION, mapOf("goal" to "string: Task goal description", "category" to "string: FEATURE, SKILL_LEARN, CODE_PATCH, SELF_REPAIR, or WEB_APP (optional)", "sourceType" to "string: TEXT, YOUTUBE, GITHUB, DOCUMENT, SCREENSHOT (optional)", "sourceUrl" to "string: Source URL or target file path (optional)"), isMutating = true, risk = 30)
        reg("pauseHomeDeveloper", "হোম ডেভেলপার কাজ বিরতি", "Cleanly pause active HOME DEVELOPER work and persist checkpoint state.", ToolCategory.APP_AUTOMATION, mapOf("reason" to "string: Pause reason (optional)"), isMutating = true, risk = 10)
        reg("resumeHomeDeveloper", "হোম ডেভেলপার রিজিউম", "Resume paused HOME DEVELOPER task from its persisted Room checkpoint.", ToolCategory.APP_AUTOMATION, mapOf("taskId" to "string: Specific task ID to resume (optional)"), isMutating = true, risk = 20)
        reg("cancelHomeDeveloper", "হোম ডেভেলপার কাজ বাতিল", "Cancel active HOME DEVELOPER task and quarantine unapproved drafts.", ToolCategory.APP_AUTOMATION, mapOf("taskId" to "string: Specific task ID to cancel (optional)"), isMutating = true, risk = 25)
        reg("getHomeDevStatus", "হোম ডেভেলপার স্ট্যাটাস ও রিপোর্ট", "Get truthful live progress, phase, capability gaps, and Bengali voice status from HOME DEVELOPER.", ToolCategory.INTELLIGENCE_REPORTS, emptyMap(), risk = 5)
        reg("runSelfRepairAudit", "সেল্ফ-রিপেয়ার ও গ্যাপ অডিট", "Inspect all registered tools, permissions, failing skills, and capability gaps without fabricating health.", ToolCategory.SECURITY_DIAGNOSTICS, emptyMap(), isMutating = true, risk = 15)
        reg("createCombinedSkill", "একাধিক স্কিল কম্বাইন করা", "Combine 2 or more existing learned skills into one validated composite multi-step skill.", ToolCategory.APP_AUTOMATION, mapOf("skillIds" to "string: Comma-separated skill IDs", "newSkillName" to "string: Combined skill title"), isMutating = true, risk = 35)
        reg("learnFromDocument", "ডকুমেন্ট বা কোড থেকে শেখা", "Extract safe declarative steps or knowledge artifact from markdown, JSON, or instruction document.", ToolCategory.AI_MEMORY, mapOf("fileName" to "string: Document file name", "content" to "string: Document text content"), isMutating = true, risk = 25)
        reg("learnFromScreenshot", "স্ক্রিনশট/ভিশন থেকে স্কিল শেখা", "Convert visible OCR/screen layout text into a validated DRAFT automation skill.", ToolCategory.SCREEN_CAPTURE, mapOf("observedText" to "string: Visible text or UI tree hints", "targetApp" to "string: Target app package or name (optional)", "title" to "string: Skill title (optional)"), isMutating = true, risk = 25)
        reg("setSelfDevelopmentMode", "সেল্ফ-ডেভেলপমেন্ট মোড নিয়ন্ত্রণ", "Turn HOME DEVELOPER self-development mode ON or OFF with optional autonomy level.", ToolCategory.SECURITY_DIAGNOSTICS, mapOf("enabled" to "boolean: True to enable, false to disable", "autonomyLevel" to "string: L1_ADVISORY, L2_SAFE_AUTONOMOUS, L3_FEATURE_BUILDER, L4_SCHEDULED_BACKGROUND (optional)"), confirm = ConfirmationLevel.EXPLICIT_CONFIRM, isMutating = true, risk = 45)
        reg("generateDeveloperArtifact", "কোড প্যাচ বা ওয়েব অ্যাপ তৈরি", "Generate multi-language code patch, unit test stub, or standalone web app workspace artifact with security scan.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("targetFile" to "string: Target file name e.g. feature.kt or app.html", "goal" to "string: Code or web workspace specification", "isWebApp" to "boolean: Generate standalone web workspace bundle (optional)"), isMutating = true, risk = 30)

        // -------------------------------------------------------------
        // 19. INTELLIGENT KNOWLEDGE ENGINE (L1 -> L2 -> L3 -> L4)
        // -------------------------------------------------------------
        reg("intelligentKnowledgeQuery", "ফাস্ট-ফার্স্ট নলেজ ও প্রমাণ যাচাই", "Retrieve knowledge via L1 RAM -> L2 Room -> L3 Server -> L4 Bounded Parallel Web Research with claim-level verification.", ToolCategory.INTELLIGENCE_REPORTS, mapOf("query" to "string: Question or research topic", "mode" to "string: Optional retrieval mode ('FAST_RETRIEVAL' or 'DEEP_RESEARCH')"), risk = 5)
        reg("getKnowledgeSystemStatus", "নলেজ সিস্টেম লাইভ স্ট্যাটাস", "Get live status of JARVIS_MASTER + INTELLIGENT_AGENT knowledge cache tier, latency, storage sync, and verification state.", ToolCategory.INTELLIGENCE_REPORTS, emptyMap(), risk = 5)
        reg("cancelDeepResearch", "গভীর গবেষণা বাতিল", "Cancel any currently running deep research or L4 web search immediately.", ToolCategory.INTELLIGENCE_REPORTS, emptyMap(), isMutating = true, risk = 10)
    }

    private fun reg(
        name: String,
        titleBn: String,
        desc: String,
        cat: ToolCategory,
        params: Map<String, String> = emptyMap(),
        permId: String? = null,
        confirm: ConfirmationLevel = ConfirmationLevel.NONE,
        isMutating: Boolean = false,
        risk: Int = 10
    ) {
        val spec = ToolSpec(
            name = name,
            titleBn = titleBn,
            description = desc,
            category = cat,
            parameters = params,
            requiredPermissionId = permId,
            confirmationLevel = confirm,
            isMutating = isMutating,
            riskScore = risk,
            auditRequired = true,
            enabled = true
        )
        toolsMap[name] = spec
    }
}
