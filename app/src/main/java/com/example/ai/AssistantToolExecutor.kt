package com.example.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.local.*
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisFloatingOverlayService
import com.example.service.JarvisInCallService
import com.example.service.JarvisNotificationListenerService
import com.example.service.JarvisWakeWordService
import com.example.tools.ToolRegistry
import com.example.util.JarvisDeviceAutomationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantToolExecutor(
    private val dao: AssistantDao,
    private val onNavigate: (String) -> Unit,
    private val context: Context? = null
) {
    private val automationHandler by lazy { context?.let { com.example.automation.AutomationToolHandler(it) } }
    private val skillRepo by lazy {
        context?.let { ctx ->
            com.example.data.repository.JarvisSkillRepository(
                AssistantDatabase.getDatabase(ctx).jarvisSkillDao()
            )
        }
    }
    private val skillRecorder by lazy { com.example.automation.JarvisSkillRecorder() }

    suspend fun getTopMemoriesForContext(): String {
        return try {
            val list = dao.getAllActiveMemoriesSync()
            if (list.isEmpty()) ""
            else list.take(15).joinToString("\n") { "- [${it.category}] ${it.key}: ${it.content}" }
        } catch (e: Exception) {
            ""
        }
    }

    fun getToolDeclarations(): JSONArray {
        return ToolRegistry.getDeclarationsJson()
    }

    suspend fun executeTool(name: String, args: JSONObject): JSONObject =
        executeToolWithCaller(name, args, caller = "voice")

    suspend fun executeToolWithCaller(
        name: String,
        args: JSONObject,
        caller: String = "voice"
    ): JSONObject = withContext(Dispatchers.IO) {
        com.example.tools.ToolExecutionPipeline.execute(
            toolName = name,
            args = args,
            context = context,
            caller = caller
        ) { toolName, toolArgs ->
            executeCore(toolName, toolArgs)
        }
    }

    private suspend fun executeCore(name: String, args: JSONObject): JSONObject {
        val result = JSONObject()
        try {
            if (executeDeviceAndAutomationTools(name, args, result)) {
                // Successfully processed by automation handler
            } else when (name) {
                // -------------------------------------------------------------
                // NAVIGATION & SYSTEM ACTIONS
                // -------------------------------------------------------------
                "navigateApp" -> {
                    val screen = args.optString("screen", "hud").trim().lowercase()
                    onNavigate(screen)
                    result.put("success", true)
                    result.put("screen", screen)
                    result.put("summaryBengali", "$screen স্ক্রিনে নেভিগেশন সম্পন্ন হয়েছে।")
                }

                "goBack" -> {
                    onNavigate("hud")
                    result.put("success", true)
                    result.put("summaryBengali", "পূর্ববর্তী স্ক্রিনে ফেরত যাওয়া হয়েছে।")
                }

                "closeCurrentScreen" -> {
                    onNavigate("hud")
                    result.put("success", true)
                    result.put("summaryBengali", "বর্তমান স্ক্রিন বন্ধ করে হোম HUD-এ ফেরা হয়েছে।")
                }

                "openFirebaseBackup" -> {
                    onNavigate("firebase_backup")
                    result.put("success", true)
                    result.put("summaryBengali", "ক্লাউড ব্যাকআপ ও রিস্টোর স্ক্রিন ওপেন করা হয়েছে।")
                }

                "getDeviceInfo" -> {
                    val model = "${Build.MANUFACTURER} ${Build.MODEL}"
                    val os = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
                    result.put("success", true)
                    result.put("model", model)
                    result.put("osVersion", os)
                    result.put("coreStatus", "ONLINE")
                    result.put("summaryBengali", "ডিভাইস: $model, অপারেটিং সিস্টেম: $os। JARVIS OS কোর সক্রিয়।")
                }

                "getBatteryLevel" -> {
                    val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val rawLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    if (rawLevel in 0..100) {
                        result.put("success", true)
                        result.put("batteryPercent", rawLevel)
                        result.put("status", "AVAILABLE")
                        result.put("summaryBengali", "ডিভাইসের বর্তমান ব্যাটারি চার্জ $rawLevel%।")
                    } else {
                        result.put("success", false)
                        result.put("batteryPercent", -1)
                        result.put("status", "UNKNOWN")
                        result.put("summaryBengali", "ডিভাইসের ব্যাটারি লেভেল এই মুহূর্তে রিড করা যায়নি (স্ট্যাটাস: UNKNOWN)।")
                    }
                }

                "setVolume" -> {
                    val percent = args.optInt("volumePercent", args.optInt("percent", 50))
                    val streamType = args.optString("streamType", "media").trim()
                    if (context != null) {
                        val (ok, msg) = JarvisDeviceAutomationHelper.setVolume(context, percent, streamType)
                        result.put("success", ok)
                        result.put("volumePercent", percent)
                        result.put("streamType", streamType)
                        result.put("summaryBengali", msg)
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট পাওয়া যায়নি।")
                    }
                }

                "getSystemDiagnostics" -> {
                    // 1. Room Database Health Check
                    val dbHealth = JSONObject()
                    var dbOk = false
                    try {
                        val memoriesCount = dao.getAllActiveMemoriesSync().size
                        val tasksCount = dao.getAllAiTasksSync().size
                        val notesCount = dao.getAllNotesSync().size
                        dbHealth.put("status", "HEALTHY")
                        dbHealth.put("activeMemories", memoriesCount)
                        dbHealth.put("activeTasks", tasksCount)
                        dbHealth.put("notesCount", notesCount)
                        dbOk = true
                    } catch (e: Exception) {
                        dbHealth.put("status", "ERROR")
                        dbHealth.put("error", e.localizedMessage ?: "Database query failed")
                    }

                    // 2. Gemini API Connectivity Check
                    val geminiHealth = JSONObject()
                    if (context != null) {
                        try {
                            val aiRepo = AiSettingsRepository(context)
                            val currSettings = aiRepo.settingsState.value
                            geminiHealth.put("configured", currSettings.isApiKeyConfigured)
                            geminiHealth.put("model", currSettings.modelName)
                            geminiHealth.put("voice", currSettings.voiceName)

                            if (!currSettings.isApiKeyConfigured) {
                                geminiHealth.put("status", "NOT_CONFIGURED")
                                geminiHealth.put("details", "Gemini API Key সেট করা নেই")
                            } else {
                                val (connected, message) = aiRepo.testConnection(currSettings.apiKey, currSettings.modelName)
                                if (connected) {
                                    geminiHealth.put("status", "ONLINE")
                                    geminiHealth.put("details", message)
                                } else {
                                    geminiHealth.put("status", if (message.contains("নেটওয়ার্ক") || message.contains("টাইমআউট") || message.contains("ব্যর্থ")) "UNKNOWN" else "ERROR")
                                    geminiHealth.put("details", message)
                                }
                            }
                        } catch (e: Exception) {
                            geminiHealth.put("status", "UNKNOWN")
                            geminiHealth.put("details", "ব্যর্থ: ${e.localizedMessage}")
                        }
                    } else {
                        geminiHealth.put("status", "UNKNOWN")
                        geminiHealth.put("details", "কনটেক্সট অনুপস্থিত থাকায় নেটওয়ার্ক সংযোগ যাচাই করা সম্ভব হয়নি")
                    }

                    // 3. Background Services Status
                    val servicesHealth = JSONObject()
                    servicesHealth.put("accessibilityService", when {
                        JarvisAccessibilityService.instance != null -> "RUNNING"
                        context != null && JarvisAccessibilityService.isAccessibilityEnabled(context) -> "ENABLED_IDLE"
                        context != null -> "STOPPED"
                        else -> "UNKNOWN"
                    })
                    servicesHealth.put("notificationListenerService", when {
                        JarvisNotificationListenerService.isConnected() -> "CONNECTED"
                        context != null -> "DISCONNECTED"
                        else -> "UNKNOWN"
                    })
                    servicesHealth.put("telecomInCallService", when {
                        JarvisInCallService.isConnected() -> "BOUND"
                        context != null -> "NOT_BOUND"
                        else -> "UNKNOWN"
                    })
                    servicesHealth.put("wakeWordService", when {
                        JarvisWakeWordService.isRunning.value -> "LISTENING"
                        else -> "STOPPED"
                    })
                    servicesHealth.put("floatingOverlayService", when {
                        JarvisFloatingOverlayService.isRunning -> "ACTIVE"
                        else -> "INACTIVE"
                    })

                    // 4. Hardware Permissions Check
                    val permissionsHealth = JSONObject()
                    if (context != null) {
                        val micGranted = com.example.permissions.JarvisPermissionManager.hasAudioPermission(context)
                        val cameraGranted = com.example.permissions.JarvisPermissionManager.hasCameraPermission(context)
                        val locationGranted = com.example.permissions.JarvisPermissionManager.hasLocationPermission(context)
                        val overlayGranted = com.example.permissions.JarvisPermissionManager.isSystemPermissionGranted(context, "overlay")
                        val notifPostGranted = com.example.permissions.JarvisPermissionManager.hasPostNotificationsPermission(context)
                        val accessibilityGranted = com.example.permissions.JarvisPermissionManager.hasAccessibilityPermission(context)
                        val notifListenerGranted = com.example.permissions.JarvisPermissionManager.hasNotificationListenerPermission()

                        permissionsHealth.put("RECORD_AUDIO", if (micGranted) "GRANTED" else "DENIED")
                        permissionsHealth.put("CAMERA", if (cameraGranted) "GRANTED" else "DENIED")
                        permissionsHealth.put("ACCESS_FINE_LOCATION", if (locationGranted) "GRANTED" else "DENIED")
                        permissionsHealth.put("SYSTEM_ALERT_WINDOW", if (overlayGranted) "GRANTED" else "DENIED")
                        permissionsHealth.put("POST_NOTIFICATIONS", if (notifPostGranted) "GRANTED" else "DENIED")
                        permissionsHealth.put("ACCESSIBILITY_SERVICE", if (accessibilityGranted) "GRANTED" else "DENIED")
                        permissionsHealth.put("NOTIFICATION_LISTENER", if (notifListenerGranted) "GRANTED" else "DENIED")
                    } else {
                        permissionsHealth.put("RECORD_AUDIO", "UNKNOWN")
                        permissionsHealth.put("CAMERA", "UNKNOWN")
                        permissionsHealth.put("ACCESS_FINE_LOCATION", "UNKNOWN")
                        permissionsHealth.put("SYSTEM_ALERT_WINDOW", "UNKNOWN")
                        permissionsHealth.put("POST_NOTIFICATIONS", "UNKNOWN")
                        permissionsHealth.put("ACCESSIBILITY_SERVICE", "UNKNOWN")
                        permissionsHealth.put("NOTIFICATION_LISTENER", "UNKNOWN")
                    }

                    // 5. Battery and OS info
                    val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val batteryStatus = if (batteryLevel >= 0) "$batteryLevel%" else "UNKNOWN"

                    result.put("database", dbHealth)
                    result.put("geminiApi", geminiHealth)
                    result.put("backgroundServices", servicesHealth)
                    result.put("hardwarePermissions", permissionsHealth)
                    result.put("batteryLevel", batteryStatus)
                    result.put("deviceModel", Build.MODEL)
                    result.put("androidVersion", Build.VERSION.RELEASE)

                    // Truthful Bengali summary
                    val dbSummary = if (dbOk) "ডাটাবেজ: সক্রিয় (${dbHealth.optInt("activeMemories")} মেমোরি, ${dbHealth.optInt("activeTasks")} টাস্ক)" else "ডাটাবেজ: ত্রুটিযুক্ত"
                    val geminiStatusStr = geminiHealth.optString("status", "UNKNOWN")
                    val geminiSummary = when (geminiStatusStr) {
                        "ONLINE" -> "Gemini API: অনলাইন ও সক্রিয়"
                        "NOT_CONFIGURED" -> "Gemini API: কী কনফিগার করা হয়নি"
                        "ERROR" -> "Gemini API: ত্রুটি (${geminiHealth.optString("details")})"
                        else -> "Gemini API: স্ট্যাটাস UNKNOWN (যাচাই সম্ভব হয়নি)"
                    }
                    val micStatus = permissionsHealth.optString("RECORD_AUDIO", "UNKNOWN")
                    val accStatus = permissionsHealth.optString("ACCESSIBILITY_SERVICE", "UNKNOWN")
                    val notifStatus = servicesHealth.optString("notificationListenerService", "UNKNOWN")

                    result.put("summaryBengali", "সিস্টেম ডায়াগনস্টিক রিপোর্ট (প্রকৃত অবস্থা):\n" +
                            "• $dbSummary\n" +
                            "• $geminiSummary\n" +
                            "• মাইক্রোফোন অনুমতি: $micStatus | অ্যাক্সেসিবিলিটি: $accStatus\n" +
                            "• নোটিফিকেশন লিসেনার: $notifStatus | ব্যাটারি: $batteryStatus\n" +
                            "কোনো কাল্পনিক স্ট্যাটাস নেই — অযাচাইকৃত অংশগুলো UNKNOWN হিসেবে চিহ্নিত।")
                }

                // -------------------------------------------------------------
                // PERSONAL NOTEPAD & MEMOS
                // -------------------------------------------------------------
                "createNote", "addNoteDirect" -> {
                    val title = (args.optString("title", "").ifBlank { "নোট" }).trim()
                    val content = args.optString("content", "").trim()
                    val noteId = dao.insertNote(NoteEntity(title = title, content = content, timestamp = System.currentTimeMillis()))
                    result.put("success", true)
                    result.put("noteId", noteId)
                    result.put("summaryBengali", "নোটটি সফলভাবে সংরক্ষিত হয়েছে: '$title'")
                }

                "getNotes" -> {
                    val notes = dao.getAllNotesSync()
                    val arr = JSONArray()
                    notes.forEach { n ->
                        val obj = JSONObject()
                        obj.put("id", n.id)
                        obj.put("title", n.title)
                        obj.put("content", n.content)
                        arr.put(obj)
                    }
                    result.put("count", notes.size)
                    result.put("notes", arr)
                    result.put("summaryBengali", "নোটপ্যাডে মোট ${notes.size}টি নোট রয়েছে।")
                }

                "getLatestNote" -> {
                    val notes = dao.getAllNotesSync()
                    val latest = notes.firstOrNull()
                    if (latest != null) {
                        result.put("found", true)
                        result.put("title", latest.title)
                        result.put("content", latest.content)
                        result.put("summaryBengali", "সর্বশেষ নোট: '${latest.title}' — ${latest.content}")
                    } else {
                        result.put("found", false)
                        result.put("summaryBengali", "নোটপ্যাডে কোনো সংরক্ষিত নোট পাওয়া যায়নি।")
                    }
                }

                "deleteNote" -> {
                    val titleOrId = args.optString("titleOrId", "").trim()
                    if (titleOrId.isBlank()) {
                        result.put("success", false)
                        result.put("deleted", false)
                        result.put("summaryBengali", "নোট মুছে ফেলার জন্য শিরোনাম বা আইডি প্রদান করুন।")
                    } else {
                        val notes = dao.getAllNotesSync()
                        val numId = titleOrId.toLongOrNull()
                        val targetNote = if (numId != null) {
                            notes.firstOrNull { it.id == numId }
                        } else {
                            notes.firstOrNull { it.title.equals(titleOrId, ignoreCase = true) }
                                ?: notes.firstOrNull { it.title.contains(titleOrId, ignoreCase = true) }
                        }
                        if (targetNote != null) {
                            dao.deleteNoteById(targetNote.id)
                            val stillExists = dao.getAllNotesSync().any { it.id == targetNote.id }
                            result.put("success", !stillExists)
                            result.put("deleted", !stillExists)
                            result.put("deletedNoteId", targetNote.id)
                            result.put("summaryBengali", if (!stillExists) "নোট '${targetNote.title}' সফলভাবে মুছে ফেলা হয়েছে।" else "নোট মুছে ফেলা যাচাই করা যায়নি।")
                        } else {
                            result.put("success", false)
                            result.put("deleted", false)
                            result.put("found", false)
                            result.put("summaryBengali", "'$titleOrId' নামে কোনো নোট ডাটাবেজে খুঁজে পাওয়া যায়নি।")
                        }
                    }
                }

                // -------------------------------------------------------------
                // AI LONG-TERM MEMORY
                // -------------------------------------------------------------
                "saveMemory" -> {
                    val key = args.optString("key", "").trim().ifBlank { "তথ্য" }
                    val content = args.optString("content", "").trim()
                    val category = args.optString("category", "USER_PREFERENCE").trim()
                    val now = System.currentTimeMillis()
                    val entity = AiMemoryEntity(
                        key = key,
                        content = content,
                        category = category,
                        importance = 1,
                        createdAt = now,
                        updatedAt = now,
                        isPinned = false,
                        isActive = true
                    )
                    val id = dao.insertMemory(entity)
                    result.put("success", true)
                    result.put("memoryId", id)
                    result.put("summaryBengali", "জারভিস মেমোরিতে স্থায়ীভাবে সংরক্ষিত হয়েছে: '$key' — $content")
                }

                "searchMemories" -> {
                    val query = args.optString("query", "").trim()
                    val matches = dao.searchMemoriesSync(query)
                    val arr = JSONArray()
                    matches.forEach { m ->
                        val obj = JSONObject()
                        obj.put("id", m.id)
                        obj.put("key", m.key)
                        obj.put("content", m.content)
                        arr.put(obj)
                    }
                    result.put("count", matches.size)
                    result.put("memories", arr)
                    result.put("summaryBengali", if (matches.isEmpty()) "'$query' সম্পর্কিত কোনো মেমোরি খুঁজে পাওয়া যায়নি।" else "মেমোরি থেকে ${matches.size}টি তথ্য পাওয়া গেছে।")
                }

                "getAllMemories" -> {
                    val list = dao.getAllActiveMemoriesSync()
                    val arr = JSONArray()
                    list.forEach { m ->
                        val obj = JSONObject()
                        obj.put("id", m.id)
                        obj.put("key", m.key)
                        obj.put("content", m.content)
                        arr.put(obj)
                    }
                    result.put("count", list.size)
                    result.put("memories", arr)
                    result.put("summaryBengali", "জারভিস মেমোরিতে মোট ${list.size}টি তথ্য সংরক্ষিত আছে।")
                }

                "deleteMemory" -> {
                    val keyOrId = args.optString("keyOrId", "").trim()
                    if (keyOrId.isBlank()) {
                        result.put("success", false)
                        result.put("deleted", false)
                        result.put("summaryBengali", "মেমোরি মুছে ফেলার জন্য কী বা আইডি উল্লেখ করুন।")
                    } else {
                        val activeMemories = dao.getAllActiveMemoriesSync()
                        val numId = keyOrId.toLongOrNull()
                        val match = if (numId != null) {
                            activeMemories.firstOrNull { it.id == numId }
                        } else {
                            activeMemories.firstOrNull { it.key.equals(keyOrId, ignoreCase = true) }
                                ?: activeMemories.firstOrNull { it.key.contains(keyOrId, ignoreCase = true) }
                        }
                        if (match != null) {
                            dao.deleteMemoryById(match.id)
                            val stillActive = dao.getAllActiveMemoriesSync().any { it.id == match.id }
                            result.put("success", !stillActive)
                            result.put("deleted", !stillActive)
                            result.put("deletedMemoryId", match.id)
                            result.put("summaryBengali", if (!stillActive) "মেমোরি '${match.key}' সফলভাবে মুছে ফেলা হয়েছে।" else "মেমোরি মুছে ফেলা যাচাই করা যায়নি।")
                        } else {
                            result.put("success", false)
                            result.put("deleted", false)
                            result.put("found", false)
                            result.put("summaryBengali", "'$keyOrId' নামের কোনো সক্রিয় মেমোরি পাওয়া যায়নি।")
                        }
                    }
                }

                // -------------------------------------------------------------
                // AI TASKS & REMINDERS
                // -------------------------------------------------------------
                "createAiTask" -> {
                    val title = args.optString("title", "").trim().ifBlank { "নতুন টাস্ক" }
                    val timeOrMinutes = args.optString("timeOrMinutesFromNow", "30").trim()
                    val minutes = timeOrMinutes.filter { it.isDigit() }.toLongOrNull() ?: 30L
                    val scheduledTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
                    val timeString = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(scheduledTime))

                    val task = AiTaskEntity(
                        title = title,
                        description = args.optString("description", ""),
                        scheduledTime = scheduledTime,
                        scheduledTimeString = timeString,
                        status = "PENDING",
                        actionType = "REMINDER",
                        targetCommand = "",
                        isExecuted = false
                    )
                    val id = dao.insertAiTask(task)
                    context?.let { ctx ->
                        com.example.service.JarvisAlarmReceiver.scheduleAlarm(
                            context = ctx,
                            taskId = id,
                            title = "J.A.R.V.I.S.: $title",
                            description = task.description.ifBlank { "আপনার নির্ধারিত প্রোটোকল/টাস্কের সময় হয়েছে, স্যার।" },
                            triggerTimeMillis = scheduledTime
                        )
                    }
                    result.put("success", true)
                    result.put("taskId", id)
                    result.put("scheduledTime", timeString)
                    result.put("summaryBengali", "টাস্ক শিডিউল ও অ্যালার্ম সেট সম্পন্ন: '$title' ($timeString-এ অ্যালার্ট দেওয়া হবে)।")
                }

                "setExactScheduleAlarm" -> {
                    val title = args.optString("title", "ব্যক্তিগত শিডিউল অ্যালার্ম").trim().ifBlank { "ব্যক্তিগত শিডিউল অ্যালার্ম" }
                    val minutes = args.optString("delayMinutes", "60").filter { it.isDigit() }.toLongOrNull() ?: 60L
                    val scheduledTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
                    val timeString = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(scheduledTime))

                    val task = AiTaskEntity(
                        title = title,
                        description = "নির্ধারিত শিডিউল অ্যালার্ম: $title",
                        scheduledTime = scheduledTime,
                        scheduledTimeString = timeString,
                        status = "PENDING",
                        actionType = "REMINDER",
                        targetCommand = "",
                        isExecuted = false
                    )
                    val id = dao.insertAiTask(task)
                    context?.let { ctx ->
                        com.example.service.JarvisAlarmReceiver.scheduleAlarm(
                            context = ctx,
                            taskId = id,
                            title = "🔔 NAHID AI অ্যালার্ম: $title",
                            description = "আপনার নির্ধারিত শিডিউলের সময় হয়েছে, স্যার।",
                            triggerTimeMillis = scheduledTime
                        )
                    }
                    result.put("success", true)
                    result.put("taskId", id)
                    result.put("scheduledTime", timeString)
                    result.put("summaryBengali", "সঠিক সময়ের অ্যালার্ম সেট করা হয়েছে: '$title' ($timeString-এ অ্যালার্ম বাজবে)।")
                }

                "getAllAiTasks" -> {
                    val tasks = dao.getAllAiTasksSync()
                    val arr = JSONArray()
                    tasks.forEach { t ->
                        val obj = JSONObject()
                        obj.put("id", t.id)
                        obj.put("title", t.title)
                        obj.put("time", t.scheduledTimeString)
                        obj.put("status", t.status)
                        arr.put(obj)
                    }
                    result.put("count", tasks.size)
                    result.put("tasks", arr)
                    result.put("summaryBengali", "মোট ${tasks.size}টি শিডিউলড টাস্ক পাওয়া গেছে।")
                }

                "completeAiTask" -> {
                    val id = args.optLong("taskId", 0L)
                    if (id > 0L) {
                        dao.markAiTaskCompleted(id)
                        context?.let { ctx ->
                            com.example.service.JarvisAlarmReceiver.cancelAlarm(ctx, id)
                        }
                        result.put("success", true)
                        result.put("summaryBengali", "টাস্ক #$id সম্পন্ন হিসেবে চিহ্নিত করা হয়েছে এবং অ্যালার্ম নিভিয়ে দেওয়া হয়েছে।")
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "সঠিক টাস্ক আইডি পাওয়া যায়নি।")
                    }
                }

                "deleteAiTask" -> {
                    val id = args.optLong("taskId", 0L)
                    if (id > 0L) {
                        dao.deleteAiTask(id)
                        context?.let { ctx ->
                            com.example.service.JarvisAlarmReceiver.cancelAlarm(ctx, id)
                        }
                        result.put("success", true)
                        result.put("summaryBengali", "টাস্ক #$id বাতিল/মুছে ফেলা হয়েছে এবং অ্যালার্ম প্রত্যাহার করা হয়েছে।")
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "সঠিক টাস্ক আইডি পাওয়া যায়নি।")
                    }
                }

                // -------------------------------------------------------------
                // TELEPHONY & WHATSAPP
                // -------------------------------------------------------------
                "makePhoneCall" -> {
                    var phone = (args.optString("phoneNumber", "").ifBlank { args.optString("phone", "") }).trim()
                    val contact = (args.optString("contactName", "").ifBlank { args.optString("name", "") }).trim()
                    val queryTarget = if (phone.isNotBlank() && phone.any { it.isLetter() }) phone else contact

                    if (context != null) {
                        val resolvedContact = if (queryTarget.isNotBlank()) {
                            com.example.util.JarvisContactSearchEngine.searchContact(context, queryTarget, dao)
                        } else if (phone.isNotBlank()) {
                            com.example.util.JarvisContactSearchEngine.searchContact(context, phone, dao)
                        } else null

                        if (resolvedContact != null && resolvedContact.needsDisambiguation && resolvedContact.matches.isNotEmpty()) {
                            result.put("success", false)
                            result.put("needsDisambiguation", true)
                            result.put("status", "MULTIPLE_MATCHES")
                            val arr = JSONArray()
                            resolvedContact.matches.forEach { m ->
                                arr.put(JSONObject().apply {
                                    put("name", m.name)
                                    put("primaryPhoneNumber", m.primaryPhoneNumber)
                                    put("allPhoneNumbers", JSONArray(m.allPhoneNumbers))
                                })
                            }
                            result.put("matches", arr)
                            result.put("summaryBengali", resolvedContact.summaryBengali)
                        } else {
                            if (resolvedContact != null && resolvedContact.matches.isNotEmpty()) {
                                val targetMatch = resolvedContact.matches.first()
                                phone = targetMatch.primaryPhoneNumber
                                if (targetMatch.contactId > 0 && targetMatch.source == "DATABASE_ALIAS") {
                                    dao.incrementContactInteraction(targetMatch.contactId)
                                }
                            }

                            val cleanPhone = com.example.util.JarvisContactSearchEngine.normalizePhoneNumber(phone).filter { it.isDigit() || it == '+' }
                            if (cleanPhone.isNotBlank()) {
                                try {
                                    com.example.service.JarvisBackgroundService.start(context)
                                    val hasCallPerm = com.example.permissions.JarvisPermissionManager.hasCallPhonePermission(context)

                                    val callIntent = if (hasCallPerm) {
                                        Intent(Intent.ACTION_CALL).apply {
                                            data = Uri.parse("tel:$cleanPhone")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    } else {
                                        Intent(Intent.ACTION_DIAL).apply {
                                            data = Uri.parse("tel:$cleanPhone")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    }
                                    context.startActivity(callIntent)
                                    result.put("success", true)
                                    result.put("phoneNumber", cleanPhone)
                                    if (hasCallPerm) {
                                        result.put("actionStatus", "CALL_DISPATCHED")
                                        result.put("verified", true)
                                        result.put("summaryBengali", "$cleanPhone নম্বরে সরাসরি সিম কল সংযোগ দেওয়া হচ্ছে।")
                                    } else {
                                        result.put("actionStatus", "DIALER_OPENED_REQUIRES_MANUAL_CALL")
                                        result.put("verified", false)
                                        result.put("summaryBengali", "সরাসরি কলের অনুমতি (CALL_PHONE) না থাকায় ডায়ালারে $cleanPhone প্রস্তুত করা হয়েছে। কল সংযোগের জন্য ডায়াল বাটন ট্যাপ করুন।")
                                    }

                                    dao.insertTaskHistory(
                                        JarvisTaskHistoryEntity(
                                            taskTitle = "কল সংযোগ: $cleanPhone",
                                            taskCategory = "COMMUNICATION",
                                            triggerSource = "TOOL_EXECUTOR",
                                            rawPrompt = "Call $queryTarget",
                                            structuredCommand = "makePhoneCall",
                                            executionStatus = if (hasCallPerm) "CALL_DISPATCHED" else "DIALER_OPENED",
                                            resultSummary = "$cleanPhone নম্বরে কল ইনিশিয়েট করা হয়েছে।"
                                        )
                                    )
                                } catch (e: Exception) {
                                    result.put("success", false)
                                    result.put("summaryBengali", "কল সংযোগ করতে সমস্যা হয়েছে: ${e.message}")
                                }
                            } else {
                                result.put("success", false)
                                result.put("summaryBengali", "সঠিক ফোন নম্বর পাওয়া যায়নি।")
                            }
                        }
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট পাওয়া যায়নি।")
                    }
                }

                "sendWhatsAppMessage" -> {
                    var phone = (args.optString("phoneNumber", "").ifBlank { args.optString("phone", "") }).trim()
                    val message = (args.optString("messageText", "").ifBlank { args.optString("message", "") }).trim()
                    val contact = args.optString("contactName", "").trim()

                    if (context != null) {
                        val queryTarget = if (phone.isNotBlank() && phone.any { it.isLetter() }) phone else contact
                        if (queryTarget.isNotBlank()) {
                            val resolvedContact = com.example.util.JarvisContactSearchEngine.searchContact(context, queryTarget, dao)
                            if (resolvedContact.matches.isNotEmpty()) {
                                phone = resolvedContact.matches.first().primaryPhoneNumber
                            }
                        }

                        try {
                            com.example.service.JarvisBackgroundService.start(context)
                            var clean = phone.filter { it.isDigit() || it == '+' }
                            if (clean.startsWith("+880")) clean = clean.removePrefix("+")
                            else if (clean.startsWith("01")) clean = "88" + clean

                            val encoded = java.net.URLEncoder.encode(message, "UTF-8")
                            val url = if (clean.isNotBlank()) {
                                "https://api.whatsapp.com/send?phone=$clean&text=$encoded"
                            } else {
                                "https://api.whatsapp.com/send?text=$encoded"
                            }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(url)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            result.put("success", true)
                            result.put("actionStatus", "COMPOSER_OPENED_READY_TO_SEND")
                            result.put("verified", false)
                            result.put("summaryBengali", "হোয়াটসঅ্যাপ চ্যাটে বার্তার খসড়া প্রস্তুত করে ওপেন করা হয়েছে: '$message'। প্রেরণের জন্য হোয়াটসঅ্যাপে 'পাঠান' চাপুন।")

                            dao.insertTaskHistory(
                                JarvisTaskHistoryEntity(
                                    taskTitle = "হোয়াটসঅ্যাপ বার্তা খসড়া: $clean",
                                    taskCategory = "COMMUNICATION",
                                    triggerSource = "TOOL_EXECUTOR",
                                    rawPrompt = "WhatsApp $queryTarget: $message",
                                    structuredCommand = "sendWhatsAppMessage",
                                    executionStatus = "COMPOSER_OPENED",
                                    resultSummary = "হোয়াটসঅ্যাপ চ্যাটে বার্তার খসড়া লোড করা হয়েছে।"
                                )
                            )
                        } catch (e: Exception) {
                            result.put("success", false)
                            result.put("summaryBengali", "হোয়াটসঅ্যাপ ওপেন করা যায়নি: ${e.message}")
                        }
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট পাওয়া যায়নি।")
                    }
                }

                "startWhatsAppVoiceCall", "startWhatsAppVideoCall" -> {
                    val isVideo = name == "startWhatsAppVideoCall"
                    val target = (args.optString("contact", "").ifBlank { args.optString("phoneNumber", "") }).trim()
                    if (context != null && target.isNotBlank()) {
                        val resolved = com.example.util.JarvisContactSearchEngine.searchContact(context, target, dao)
                        if (resolved.needsDisambiguation && resolved.matches.size > 1) {
                            result.put("success", false)
                            result.put("status", "MULTIPLE_MATCHES")
                            result.put("needsDisambiguation", true)
                            result.put("summaryBengali", resolved.summaryBengali)
                        } else {
                            val isWhatsAppInstalled = try {
                                context.packageManager.getPackageInfo("com.whatsapp", 0)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            if (!isWhatsAppInstalled) {
                                result.put("success", false)
                                result.put("summaryBengali", "ফোনে হোয়াটসঅ্যাপ ইনস্টল করা নেই।")
                            } else {
                                val phone = if (resolved.matches.isNotEmpty()) resolved.matches.first().primaryPhoneNumber else target
                                var clean = phone.filter { it.isDigit() || it == '+' }
                                if (clean.startsWith("+880")) clean = clean.removePrefix("+")
                                else if (clean.startsWith("01")) clean = "88" + clean

                                if (clean.length < 7) {
                                    val (ok, msg) = JarvisDeviceAutomationHelper.openAndControlWhatsApp(context, target, "")
                                    result.put("success", ok)
                                    result.put("actionStatus", "WHATSAPP_CHAT_OPENED_CALL_REQUESTED")
                                    result.put("verified", false)
                                    result.put("summaryBengali", if (ok) "$target এর হোয়াটসঅ্যাপ চ্যাট খোলা হচ্ছে (নম্বর না থাকায় কল বাটন ম্যানুয়ালি বা অ্যাক্সেসিবিলিটি দিয়ে সম্পন্ন করতে হবে)।" else msg)
                                } else {
                                    try {
                                        val url = "https://wa.me/$clean"
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            setPackage("com.whatsapp")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)

                                        val service = JarvisAccessibilityService.instance
                                        if (service != null) {
                                            CoroutineScope(Dispatchers.Main).launch {
                                                delay(2000)
                                                val buttonLabel = if (isVideo) "Video call" else "Voice call"
                                                service.clickElementByText(buttonLabel)
                                            }
                                        }

                                        val callTypeBn = if (isVideo) "ভিডিও" else "ভয়েস"
                                        result.put("success", true)
                                        result.put("actionStatus", "WHATSAPP_CHAT_OPENED_CALL_REQUESTED")
                                        result.put("verified", false)
                                        result.put("summaryBengali", "$target ($clean) এর সাথে হোয়াটসঅ্যাপ $callTypeBn কল অনুরোধ পাঠানো হয়েছে।")
                                    } catch (e: Exception) {
                                        result.put("success", false)
                                        result.put("summaryBengali", "হোয়াটসঅ্যাপ কল শুরু করা যায়নি: ${e.message}")
                                    }
                                }
                            }
                        }
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কন্টাক্ট বা ফোন নম্বর দেওয়া হয়নি।")
                    }
                }

                "sendSmsMessage" -> {
                    var phone = (args.optString("phoneNumber", "").ifBlank { args.optString("phone", "") }).trim()
                    val message = (args.optString("message", "").ifBlank { args.optString("text", "") }).trim()
                    val contact = args.optString("contactName", "").trim()

                    if (context != null) {
                        var blockedByDisambiguation = false
                        val queryTarget = if (phone.isNotBlank() && phone.any { it.isLetter() }) phone else contact
                        if (queryTarget.isNotBlank()) {
                            val resolvedContact = com.example.util.JarvisContactSearchEngine.searchContact(context, queryTarget, dao)
                            if (resolvedContact.needsDisambiguation) {
                                result.put("success", false)
                                result.put("status", "MULTIPLE_MATCHES")
                                result.put("needsClarification", true)
                                result.put("summaryBengali", resolvedContact.summaryBengali)
                                blockedByDisambiguation = true
                            } else if (resolvedContact.matches.isNotEmpty()) {
                                phone = resolvedContact.matches.first().primaryPhoneNumber
                            }
                        }

                        if (!blockedByDisambiguation) {
                            val preferDirect = args.optBoolean("directSend", true)
                            val dispatchRes = com.example.util.JarvisSmsManager.dispatchSms(
                                context = context,
                                recipient = phone,
                                message = message,
                                preferDirectSend = preferDirect
                            )

                            result.put("success", dispatchRes.success)
                            result.put("actionStatus", dispatchRes.status)
                            result.put(
                                "status",
                                when (dispatchRes.status) {
                                    com.example.util.JarvisSmsManager.STATUS_SEND_REQUEST_ACCEPTED -> "REQUEST_ACCEPTED"
                                    com.example.util.JarvisSmsManager.STATUS_COMPOSER_OPENED -> "COMPOSER_OPENED_READY_TO_SEND"
                                    else -> dispatchRes.status
                                }
                            )
                            result.put("directSend", dispatchRes.directSend)
                            result.put("partsCount", dispatchRes.partsCount)
                            result.put("recipient", dispatchRes.recipient)
                            result.put("requestId", dispatchRes.requestId)
                            result.put("verified", false) // Carrier confirmation arrives via SmsStatusReceiver
                            result.put("summaryBengali", dispatchRes.summaryBengali)
                            if (dispatchRes.error != null) {
                                result.put("error", dispatchRes.error)
                            }
                        }
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট পাওয়া যায়নি।")
                    }
                }

                // -------------------------------------------------------------
                // INTELLIGENCE, WEATHER, LOCATION, WEB
                // -------------------------------------------------------------
                "getLiveWeather" -> {
                    val loc = args.optString("locationName", "ঢাকা").trim().ifBlank { "ঢাকা" }
                    val weather = WeatherService.fetchWeather(loc)
                    result.put("success", weather.isLiveData)
                    result.put("isLiveData", weather.isLiveData)
                    result.put("source", weather.source)
                    if (!weather.isLiveData) {
                        result.put("status", "OFFLINE_LIMITED")
                    }
                    result.put("location", weather.locationName)
                    result.put("temperature", weather.temperatureC)
                    result.put("condition", weather.conditionBengali)
                    result.put("humidity", weather.humidityPercent)
                    result.put("summaryBengali", weather.fullSummaryBengali)
                }

                "getCurrentGpsLocation" -> {
                    if (context == null) {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট অনুপস্থিত থাকায় জিপিএস লোকেশন পড়া সম্ভব হয়নি।")
                    } else if (!com.example.util.JarvisLiveLocationTracker.isLocationPermissionGranted(context)) {
                        result.put("success", false)
                        result.put("needsLocationPermission", true)
                        result.put("requiredPermission", "location")
                        result.put("summaryBengali", "রিয়েল-টাইম অবস্থান জানার জন্য লোকেশন পারমিশন (ACCESS_FINE_LOCATION) প্রয়োজন।")
                    } else {
                        try {
                            val (ok, liveLoc) = com.example.util.JarvisLiveLocationTracker.fetchCurrentLocation(context)
                            if (ok && liveLoc != null && (liveLoc.latitude != 0.0 || liveLoc.longitude != 0.0)) {
                                result.put("success", true)
                                result.put("latitude", liveLoc.latitude)
                                result.put("longitude", liveLoc.longitude)
                                result.put("accuracy", liveLoc.accuracy.toDouble())
                                result.put("address", liveLoc.fullAddress)
                                result.put("summaryBengali", com.example.util.JarvisLiveLocationTracker.formatLocationSpokenSummary(liveLoc))
                            } else {
                                result.put("success", false)
                                result.put("summaryBengali", "জিপিএস স্যাটেলাইট থেকে অবস্থান পাওয়া যায়নি। ফোনের লোকেশন/জিপিএস সার্ভিস চালু আছে কিনা পরীক্ষা করুন।")
                            }
                        } catch (e: Exception) {
                            result.put("success", false)
                            result.put("summaryBengali", "জিপিএস অবস্থান সংগ্রহ ব্যর্থ: ${e.localizedMessage ?: "অজানা ত্রুটি"}")
                        }
                    }
                }

                "searchWikipedia" -> {
                    val query = args.optString("query", "").trim()
                    val wiki = if (query.isNotBlank()) NewsAndRegulatoryService.searchWikipedia(query) else null
                    if (wiki != null && wiki.found) {
                        result.put("found", true)
                        result.put("title", wiki.title)
                        result.put("extract", wiki.extract)
                        result.put("summaryBengali", "উইকিপিডিয়া ফলাফল ('${wiki.title}'):\n${wiki.extract}")
                    } else {
                        result.put("found", false)
                        result.put("summaryBengali", "'$query' সম্পর্কিত কোনো উইকিপিডিয়া নিবন্ধ পাওয়া যায়নি।")
                    }
                }

                "getLiveNews" -> {
                    val category = args.optString("category", "সব").trim()
                    val newsList = NewsAndRegulatoryService.getRecentNews(category)
                    val arr = JSONArray()
                    newsList.forEach { n ->
                        val obj = JSONObject()
                        obj.put("title", n.title)
                        obj.put("summary", n.summary)
                        arr.put(obj)
                    }
                    result.put("success", newsList.isNotEmpty())
                    result.put("count", newsList.size)
                    result.put("news", arr)
                    result.put("summaryBengali", if (newsList.isNotEmpty()) "তাজা খবর:\n" + newsList.take(3).joinToString("\n• ") { it.title } else "এই মুহূর্তে কোনো তাজা সংবাদ সংগ্রহ করা যায়নি।")
                }

                "searchExternalKnowledge" -> {
                    val q = (args.optString("query", "").ifBlank { args.optString("topic", "") }).trim()
                    val wiki = if (q.isNotBlank()) NewsAndRegulatoryService.searchWikipedia(q) else null
                    if (wiki != null && wiki.found) {
                        result.put("found", true)
                        result.put("summaryBengali", "সার্চ ফলাফল ('${wiki.title}'):\n${wiki.extract}")
                    } else {
                        result.put("found", false)
                        result.put("summaryBengali", "বিষয়: '$q'। জারভিস নিউরাল কোর থেকে অনুসন্ধান সম্পন্ন।")
                    }
                }

                // -------------------------------------------------------------
                // 10. NOTIFICATION INTELLIGENCE TOOLS
                // -------------------------------------------------------------
                "getNotificationAccessStatus" -> {
                    val conn = com.example.service.JarvisNotificationListenerService.isConnected()
                    result.put("success", true)
                    result.put("connected", conn)
                    result.put("summaryBengali", if (conn) "নোটিফিকেশন রিডার সার্ভিস সক্রিয় ও সংযুক্ত রয়েছে।" else "নোটিফিকেশন অ্যাক্সেস অনুমতি প্রদান করা নেই। অনুগ্রহ করে পারমিশন সেন্টারে অনুমতি দিন।")
                }

                "getRecentNotifications" -> {
                    val limit = args.optString("limit", "10").toIntOrNull() ?: 10
                    val liveNotifs = com.example.service.JarvisNotificationListenerService.getActiveNotificationsList()
                    val persistedNotifs = if (context != null) {
                        com.example.data.repository.JarvisNotificationRepository.getRecentFromDb(context, limit)
                    } else {
                        com.example.data.repository.JarvisNotificationRepository.getRecent(limit)
                    }
                    val notifs = (liveNotifs + persistedNotifs).distinctBy { it.key }
                        .sortedByDescending { it.timestamp }
                        .take(limit)
                    result.put("success", true)
                    result.put("count", notifs.size)
                    val arr = JSONArray()
                    notifs.forEach { n ->
                        val safeBody = com.example.data.repository.JarvisNotificationRepository.formatSafeNotificationText(n)
                        arr.put(JSONObject().apply {
                            put("appName", n.appName)
                            put("sender", n.sender)
                            put("text", safeBody)
                            put("hasReply", n.hasReplyAction)
                            put("isSensitiveOtp", n.isSensitiveOtp)
                        })
                    }
                    result.put("notifications", arr)
                    val summary = if (notifs.isNotEmpty()) {
                        "সর্বশেষ ${notifs.size}টি নোটিফিকেশন:\n" + notifs.joinToString("\n") { "• [${it.appName}] ${it.sender}: ${if (it.isSensitiveOtp) "[ওটিপি সুরক্ষিত]" else it.text.take(35)}" }
                    } else {
                        "কোনো নতুন নোটিফিকেশন নেই, স্যার।"
                    }
                    result.put("summaryBengali", summary)
                }

                "getNotificationsByApp" -> {
                    val app = (args.optString("appName", "").ifBlank { args.optString("package", "") }).trim()
                    val liveNotifs = com.example.service.JarvisNotificationListenerService.getNotificationsByApp(app)
                    val dbNotifs = if (context != null) {
                        com.example.data.repository.JarvisNotificationRepository.getNotificationsByAppFromDb(context, app, 30)
                    } else emptyList()
                    val notifs = (liveNotifs + dbNotifs).distinctBy { it.key }.sortedByDescending { it.timestamp }
                    result.put("success", true)
                    result.put("count", notifs.size)
                    result.put("summaryBengali", if (notifs.isNotEmpty()) {
                        "$app-এর ${notifs.size}টি নোটিফিকেশন পাওয়া গেছে:\n" + notifs.joinToString("\n") { "• ${it.sender}: ${if (it.isSensitiveOtp) "[ওটিপি সুরক্ষিত]" else it.text}" }
                    } else {
                        "$app অ্যাপের কোনো নতুন নোটিফিকেশন পাওয়া যায়নি, স্যার।"
                    })
                }

                "getNotificationDetails" -> {
                    val app = args.optString("appName", "").trim()
                    val liveNotif = com.example.service.JarvisNotificationListenerService.getLatestNotification(app.ifBlank { null })
                    val notif = liveNotif ?: if (context != null) {
                        if (app.isNotBlank()) {
                            com.example.data.repository.JarvisNotificationRepository.getNotificationsByAppFromDb(context, app, 1).firstOrNull()
                        } else {
                            com.example.data.repository.JarvisNotificationRepository.getRecentFromDb(context, 1).firstOrNull()
                        }
                    } else null
                    if (notif != null) {
                        val content = com.example.data.repository.JarvisNotificationRepository.formatSafeNotificationText(notif)
                        result.put("success", true)
                        result.put("found", true)
                        result.put("appName", notif.appName)
                        result.put("sender", notif.sender)
                        result.put("text", content)
                        result.put("hasReplyAction", notif.hasReplyAction)
                        result.put("summaryBengali", "নোটিফিকেশন বিস্তারিত:\nঅ্যাপ: ${notif.appName}\nপ্রেরক: ${notif.sender}\nবার্তা: $content")
                    } else {
                        result.put("success", false)
                        result.put("found", false)
                        result.put("summaryBengali", "নির্দিষ্ট নোটিফিকেশন পাওয়া যায়নি।")
                    }
                }

                "announceNewNotification", "readNotificationAloud" -> {
                    val senderFilter = args.optString("sender", "").trim()
                    val liveNotifs = com.example.service.JarvisNotificationListenerService.getActiveNotificationsList()
                    val dbNotifs = if (context != null) {
                        if (senderFilter.isNotBlank()) {
                            com.example.data.repository.JarvisNotificationRepository.getNotificationsBySenderFromDb(context, senderFilter, 5)
                        } else {
                            com.example.data.repository.JarvisNotificationRepository.getRecentFromDb(context, 5)
                        }
                    } else emptyList()
                    val combined = (liveNotifs + dbNotifs).distinctBy { it.key }.sortedByDescending { it.timestamp }
                    val notif = if (senderFilter.isNotBlank()) {
                        combined.firstOrNull { it.sender.contains(senderFilter, true) || it.title.contains(senderFilter, true) }
                    } else {
                        combined.firstOrNull()
                    }
                    if (notif != null) {
                        val speech = if (notif.isSensitiveOtp) {
                            "স্যার, ${notif.appName} থেকে একটি গোপনীয় সিকিউরিটি ভেরিফিকেশন কোড এসেছে। নিরাপত্তার জন্য কোডটি পড়া হলো না।"
                        } else {
                            "স্যার, ${notif.appName}-এ ${notif.sender} মেসেজ পাঠিয়েছেন: \"${notif.text}\""
                        }
                        com.example.service.GeminiLiveAnnouncementAdapter.enqueueOrDispatch(
                            eventKey = notif.key,
                            rawText = speech,
                            category = com.example.service.QueuedAnnouncement.CATEGORY_NOTIFICATION,
                            isSensitiveOtp = notif.isSensitiveOtp
                        )
                        result.put("success", true)
                        result.put("found", true)
                        result.put("summaryBengali", speech)
                    } else {
                        result.put("success", false)
                        result.put("found", false)
                        result.put("summaryBengali", "পড়ার মতো কোনো নতুন নোটিফিকেশন পাওয়া যায়নি, স্যার।")
                    }
                }

                "summarizeNotification" -> {
                    val liveNotifs = com.example.service.JarvisNotificationListenerService.getActiveNotificationsList()
                    val dbNotifs = if (context != null) {
                        com.example.data.repository.JarvisNotificationRepository.getRecentFromDb(context, 30)
                    } else emptyList()
                    val notifs = (liveNotifs + dbNotifs).distinctBy { it.key }.sortedByDescending { it.timestamp }
                    val summary = if (notifs.isEmpty()) {
                        "বর্তমানে কোনো সক্রিয় নোটিফিকেশন নেই।"
                    } else {
                        notifs.groupBy { it.appName }.entries.joinToString("\n") { (app, list) ->
                            "• $app: ${list.size}টি নোটিফিকেশন (সর্বশেষ কলার/প্রেরক: ${list.first().sender})"
                        }
                    }
                    result.put("success", true)
                    result.put("count", notifs.size)
                    result.put("summaryBengali", "নোটিফিকেশন সারাংশ:\n$summary")
                }

                "classifyNotification" -> {
                    val text = args.optString("text", "")
                    val cat = when {
                        text.contains("otp", true) || text.contains("code", true) || text.contains("pin", true) -> "AUTHENTICATION_SECRET"
                        text.contains("meeting", true) || text.contains("office", true) || text.contains("work", true) -> "OFFICE_WORK"
                        text.contains("টাকা", true) || text.contains("পেমেন্ট", true) || text.contains("bank", true) -> "FINANCIAL"
                        else -> "GENERAL_MESSAGE"
                    }
                    result.put("success", true)
                    result.put("classification", cat)
                    result.put("summaryBengali", "নোটিফিকেশনের ধরণ: $cat")
                }

                "suggestSmartReplies" -> {
                    val msg = (args.optString("messageText", "").ifBlank { args.optString("text", "") }).trim()
                    val suggestions = com.example.service.JarvisNotificationListenerService.suggestSmartReplies(msg)
                    result.put("success", suggestions.isNotEmpty())
                    result.put("suggestions", JSONArray(suggestions))
                    result.put("summaryBengali", "প্রস্তাবিত স্মার্ট রিপ্লাইসমূহ:\n" + suggestions.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n"))
                }

                "sendNotificationReply" -> {
                    val pkg = args.optString("packageFilter", "com.whatsapp").trim()
                    val reply = (args.optString("replyText", "").ifBlank { args.optString("message", "") }).trim()
                    if (context != null && reply.isNotBlank()) {
                        val sent = com.example.service.JarvisNotificationListenerService.sendDirectReply(context, pkg, null, reply)
                        result.put("success", sent)
                        result.put("summaryBengali", if (sent) "নোটিফিকেশনে সরাসরি উত্তর পাঠানো হয়েছে: \"$reply\"" else "নোটিফিকেশন দিয়ে সরাসরি উত্তর পাঠানো সম্ভব হয়নি। অনুগ্রহ করে অ্যাপে গিয়ে রিপ্লাই করুন।")
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "রিপ্লাই মেসেজ ফাঁকা রাখা যাবে না।")
                    }
                }

                "dismissNotification" -> {
                    val key = args.optString("key", "").trim()
                    val ok = if (key.isNotBlank()) com.example.service.JarvisNotificationListenerService.dismissNotificationByKey(key) else false
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) "নোটিফিকেশন সফলভাবে ডিসমিস করা হয়েছে।" else "সঠিক নোটিফিকেশন কি দেওয়া হয়নি।")
                }

                // -------------------------------------------------------------
                // 11. INCOMING CALL & TELEPHONY INTELLIGENCE
                // -------------------------------------------------------------
                "getActiveCallState", "getIncomingCallDetails", "identifyIncomingCaller" -> {
                    val info = com.example.service.JarvisInCallService.currentCallInfo.value
                    result.put("success", true)
                    result.put("callerName", info.callerName)
                    result.put("phoneNumber", info.phoneNumber)
                    result.put("stateLabel", info.stateLabelBn)
                    result.put("isIncoming", info.isIncoming)
                    result.put("summaryBengali", if (info.phoneNumber.isNotBlank()) {
                        "কল বিবরণ: ${info.callerName} (${info.phoneNumber}) — ${info.stateLabelBn}।"
                    } else {
                        "বর্তমানে কোনো সক্রিয় বা ইনকামিং কল নেই, স্যার।"
                    })
                }

                "announceIncomingCaller" -> {
                    val info = com.example.service.JarvisInCallService.currentCallInfo.value
                    val hasCaller = info.phoneNumber.isNotBlank()
                    val speech = if (hasCaller) {
                        "Sir, ${info.callerName} আপনাকে কল করছেন।"
                    } else {
                        "Sir, বর্তমানে কোনো ইনকামিং কল বাজছে না।"
                    }
                    result.put("success", hasCaller)
                    result.put("summaryBengali", speech)
                }

                "answerIncomingCall" -> {
                    val ok = com.example.service.JarvisInCallService.answerCall()
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) "ইনকামিং কল রিসিভ করা হয়েছে, স্যার।" else "কল রিসিভ করা যায়নি। হয়তো কোনো ইনকামিং কল বাজছে না অথবা ডিফল্ট ডায়ালার পারমিশন প্রয়োজন।")
                }

                "rejectIncomingCall" -> {
                    val ok = com.example.service.JarvisInCallService.rejectCall()
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) "ইনকামিং কল রিজেক্ট করা হয়েছে, স্যার।" else "কল রিজেক্ট করার মতো সক্রিয় ইনকামিং কল পাওয়া যায়নি।")
                }

                "silenceIncomingCall" -> {
                    val ok = if (context != null) com.example.service.JarvisInCallService.silenceIncomingRinger(context) else false
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) "ইনকামিং কলের রিংটোন সাইলেন্ট করা হয়েছে, স্যার।" else "কল সাইলেন্ট করা সম্ভব হয়নি।")
                }

                "endActiveCall" -> {
                    val ok = com.example.service.JarvisInCallService.endCall()
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) "চলমান কল সমাপ্ত করা হয়েছে।" else "কোনো চলমান কল পাওয়া যায়নি।")
                }

                "muteActiveCall" -> {
                    val m = args.optBoolean("muted", true)
                    val ok = com.example.service.JarvisInCallService.setMute(m)
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) (if (m) "কল মিউট করা হয়েছে।" else "কল আনমিউট করা হয়েছে।") else "কল মিউট নিয়ন্ত্রণ করা যায়নি।")
                }

                "toggleCallSpeaker" -> {
                    val sp = args.optBoolean("speakerOn", true)
                    val ok = com.example.service.JarvisInCallService.setSpeaker(sp)
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) (if (sp) "লাউডস্পিকার সক্রিয় করা হয়েছে।" else "ইয়ারপিস মোড চালু করা হয়েছে।") else "স্পিকার পরিবর্তন করা যায়নি।")
                }

                "getCallDuration" -> {
                    val dur = com.example.service.JarvisInCallService.getCallDurationSeconds()
                    result.put("success", true)
                    result.put("durationSeconds", dur)
                    result.put("summaryBengali", "চলমান কলের সময়কাল: ${dur / 60} মিনিট ${dur % 60} সেকেন্ড।")
                }

                // -------------------------------------------------------------
                // 12. CAMERA VISION & MULTIMODAL INTELLIGENCE
                // -------------------------------------------------------------
                "startCameraVision" -> {
                    onNavigate("camera")
                    result.put("success", true)
                    result.put("summaryBengali", "ক্যামেরা ভিশন স্ক্রিন চালু করা হয়েছে, স্যার।")
                }

                "stopCameraVision" -> {
                    onNavigate("hud")
                    result.put("success", true)
                    result.put("summaryBengali", "ক্যামেরা ভিশন বন্ধ করে হোম HUD-এ ফেরা হয়েছে।")
                }

                "captureCameraFrame" -> {
                    val frame = if (context != null) com.example.util.JarvisCameraManager.captureFrame(context) else null
                    if (frame != null) {
                        result.put("success", true)
                        result.put("width", frame.width)
                        result.put("height", frame.height)
                        result.put("summaryBengali", "ক্যামেরা ফ্রেম ক্যাপচার সফল (${frame.width}x${frame.height})।")
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "ক্যামেরা ফ্রেম গ্রহণ করা যায়নি। ক্যামেরা স্ক্রিন বা ভিশন HUD চালু আছে কিনা নিশ্চিত করুন।")
                    }
                }

                "analyzeCameraView", "explainVisualScene" -> {
                    val q = (args.optString("question", "").ifBlank { args.optString("query", "ক্যামেরার দৃশ্য ও পরিবেশ বিশদভাবে পর্যবেক্ষণ করো") }).trim()
                    if (context != null) {
                        val key = AiSettingsRepository(context).settingsState.value.apiKey
                        val (isOk, analysis) = com.example.util.JarvisCameraManager.analyzeCameraView(context, key, q)
                        result.put("success", isOk)
                        result.put("analysis", analysis)
                        result.put("summaryBengali", analysis)
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট অনুপলব্ধ থাকায় ক্যামেরা ভিশন চালানো যায়নি।")
                    }
                }

                "detectObjectsInView" -> {
                    if (context != null) {
                        val key = AiSettingsRepository(context).settingsState.value.apiKey
                        val (isOk, detection) = com.example.util.JarvisCameraManager.detectObjects(context, key)
                        result.put("success", isOk)
                        result.put("objects", detection)
                        result.put("summaryBengali", detection)
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট অনুপলব্ধ।")
                    }
                }

                "readTextFromCamera" -> {
                    if (context != null) {
                        val key = AiSettingsRepository(context).settingsState.value.apiKey
                        val (isOk, ocrResult) = com.example.util.JarvisCameraManager.readTextFromCamera(context, key)
                        result.put("success", isOk)
                        result.put("text", ocrResult)
                        result.put("summaryBengali", ocrResult)
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কনটেক্সট অনুপলব্ধ।")
                    }
                }

                "switchCameraLens" -> {
                    val (ok, msg) = com.example.util.JarvisCameraManager.switchLens()
                    result.put("success", ok)
                    result.put("summaryBengali", msg)
                }

                "setCameraZoom" -> {
                    val z = args.optString("zoomRatio", "1.0").trim().toFloatOrNull() ?: 1.0f
                    val (ok, msg) = com.example.util.JarvisCameraManager.setZoom(z)
                    result.put("success", ok)
                    result.put("zoomRatio", z)
                    result.put("summaryBengali", msg)
                }

                "toggleCameraTorch", "toggleFlashlight" -> {
                    val t = if (args.has("enabled")) args.optBoolean("enabled", true)
                    else args.optString("enabled", "true").equals("true", ignoreCase = true)
                    val ok = if (context != null) JarvisDeviceAutomationHelper.toggleFlashlight(context, t) else false
                    result.put("success", ok)
                    result.put("enabled", t)
                    result.put(
                        "summaryBengali",
                        if (ok) {
                            if (t) "ফ্ল্যাশলাইট/টর্চ জ্বালানো হয়েছে।" else "ফ্ল্যাশলাইট/টর্চ বন্ধ করা হয়েছে।"
                        } else {
                            "ফ্ল্যাশলাইট/টর্চ নিয়ন্ত্রণ করা সম্ভব হয়নি (হার্ডওয়্যার বা ক্যামেরা অবস্থা অপ্রাপ্য)।"
                        }
                    )
                }

                // -------------------------------------------------------------
                // 13. SCREEN INTELLIGENCE & ACCESSIBILITY AUTOMATION
                // -------------------------------------------------------------
                "findUiElement" -> {
                    val q = args.optString("query", "").trim()
                    val screen = JarvisDeviceAutomationHelper.inspectCurrentScreen()
                    val found = q.isNotBlank() && screen.contains(q, ignoreCase = true)
                    result.put("success", found)
                    result.put("found", found)
                    result.put("summaryBengali", if (found) "স্ক্রিনে '$q' উপাদানটি শনাক্ত করা হয়েছে।" else "স্ক্রিনে '$q' খুঁজে পাওয়া যায়নি।")
                }

                "clickUiElement" -> {
                    val q = args.optString("query", "").trim()
                    val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreen(q, 0f, 0f)
                    result.put("success", ok)
                    result.put("summaryBengali", msg)
                }

                "longPressUiElement" -> {
                    val q = args.optString("query", "").trim()
                    val (ok, msg) = JarvisDeviceAutomationHelper.longPressElement(q, 0f, 0f)
                    result.put("success", ok)
                    result.put("summaryBengali", msg)
                }

                "swipeScreen" -> {
                    val dir = args.optString("direction", "DOWN").uppercase(Locale.ROOT)
                    val act = when (dir) {
                        "LEFT", "বামে", "SWIPE_LEFT" -> "swipe_left"
                        "RIGHT", "ডানে", "SWIPE_RIGHT" -> "swipe_right"
                        "UP", "উপরে", "SWIPE_UP" -> "swipe_up"
                        else -> "swipe_down"
                    }
                    val (ok, msg) = JarvisDeviceAutomationHelper.performSwipe(act)
                    result.put("success", ok)
                    result.put("direction", dir)
                    result.put("summaryBengali", msg)
                }

                "scrollScreen" -> {
                    val dir = args.optString("direction", "DOWN").uppercase(Locale.ROOT)
                    val act = if (dir == "UP" || dir == "উপরে") "scroll_up" else "scroll_down"
                    val (ok, msg) = JarvisDeviceAutomationHelper.performSystemAction(act)
                    result.put("success", ok)
                    result.put("direction", dir)
                    result.put("summaryBengali", msg)
                }

                "typeIntoFocusedField" -> {
                    val text = args.optString("text", "").trim()
                    val (ok, msg) = JarvisDeviceAutomationHelper.typeTextOnScreen(text)
                    result.put("success", ok)
                    result.put("summaryBengali", msg)
                }

                "verifyUiState" -> {
                    val expected = args.optString("expectedText", "").trim()
                    val screen = JarvisDeviceAutomationHelper.inspectCurrentScreen()
                    val matches = screen.contains(expected, ignoreCase = true)
                    result.put("success", matches)
                    result.put("summaryBengali", if (matches) "যাচাই সফল: স্ক্রিনে '$expected' দৃশ্যমান।" else "যাচাই সম্পন্ন: প্রত্যাশিত উপাদান দেখা যায়নি।")
                }

                // -------------------------------------------------------------
                // 14. SOCIAL & MESSAGING AUTOMATION
                // -------------------------------------------------------------
                "readFacebookFeed", "readFacebookPost" -> {
                    if (context == null || JarvisAccessibilityService.instance == null) {
                        result.put("success", false)
                        result.put("summaryBengali", "ফেসবুক স্ক্রিন পড়ার জন্য অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় থাকা প্রয়োজন।")
                    } else {
                        val sc = ScreenPerceptionEngine.inspectCurrentScreen(context, sanitize = true)
                        val isFacebookFg = sc.foregroundPackage.contains("facebook", ignoreCase = true)
                        if (isFacebookFg && sc.visibleTexts.isNotEmpty()) {
                            result.put("success", true)
                            result.put("foregroundPackage", sc.foregroundPackage)
                            result.put("summaryBengali", "দৃশ্যমান ফেসবুক কন্টেন্ট:\n" + sc.visibleTexts.take(8).joinToString("\n• "))
                        } else {
                            result.put("success", false)
                            result.put("foregroundPackage", sc.foregroundPackage)
                            result.put("summaryBengali", "বর্তমানে স্ক্রিনে ফেসবুক খোলা নেই (বর্তমান অ্যাপ: ${sc.foregroundPackage})। প্রথমে ফেসবুক ওপেন করুন।")
                        }
                    }
                }

                "reactToCurrentPost" -> {
                    val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreen("Like", 0f, 0f)
                    result.put("success", ok)
                    result.put("summaryBengali", if (ok) "পোস্টে লাইক বাটনে ক্লিক করা হয়েছে।" else msg)
                }

                "createFacebookPostDraft" -> {
                    val text = args.optString("text", "").trim()
                    if (context != null && text.isNotBlank()) {
                        val (ok, msg) = JarvisDeviceAutomationHelper.openFacebook(context, text)
                        result.put("success", ok)
                        result.put("summaryBengali", msg)
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "ফেসবুক পোস্টের জন্য টেক্সট প্রদান করুন।")
                    }
                }

                "openWhatsAppConversation" -> {
                    val target = (args.optString("contact", "").ifBlank { args.optString("phoneNumber", "") }).trim()
                    if (context != null && target.isNotBlank()) {
                        val resolved = com.example.util.JarvisContactSearchEngine.searchContact(context, target, dao)
                        if (resolved.needsDisambiguation && resolved.matches.size > 1) {
                            result.put("success", false)
                            result.put("status", "MULTIPLE_MATCHES")
                            result.put("needsDisambiguation", true)
                            result.put("summaryBengali", resolved.summaryBengali)
                        } else {
                            val resolvedTarget = if (resolved.matches.isNotEmpty()) resolved.matches.first().primaryPhoneNumber else target
                            val (ok, msg) = JarvisDeviceAutomationHelper.openAndControlWhatsApp(context, resolvedTarget, "")
                            result.put("success", ok)
                            result.put("actionStatus", if (ok) "COMPOSER_OPENED_READY_TO_SEND" else "FAILED")
                            result.put("verified", false)
                            result.put("summaryBengali", msg)
                        }
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "হোয়াটসঅ্যাপ চ্যাট খোলার জন্য কন্টাক্ট বা নম্বর প্রদান করুন।")
                    }
                }

                "searchContacts", "getContactDetails" -> {
                    val name = (args.optString("name", "").ifBlank { args.optString("contact", "") }).trim()
                    if (context != null && name.isNotBlank()) {
                        val searchRes = com.example.util.JarvisContactSearchEngine.searchContact(context, name, dao)
                        result.put("success", searchRes.matches.isNotEmpty())
                        result.put("found", searchRes.matches.isNotEmpty())
                        result.put("status", searchRes.status)
                        result.put("needsDisambiguation", searchRes.needsDisambiguation)
                        val arr = JSONArray()
                        searchRes.matches.forEach { m ->
                            arr.put(JSONObject().apply {
                                put("name", m.name)
                                put("primaryPhoneNumber", m.primaryPhoneNumber)
                                put("allPhoneNumbers", JSONArray(m.allPhoneNumbers))
                                put("alias", m.alias)
                                put("source", m.source)
                            })
                        }
                        result.put("matches", arr)
                        if (searchRes.matches.isNotEmpty()) {
                            result.put("name", searchRes.matches.first().name)
                            result.put("phoneNumber", searchRes.matches.first().primaryPhoneNumber)
                        }
                        result.put("summaryBengali", searchRes.summaryBengali)
                    } else {
                        result.put("success", false)
                        result.put("found", false)
                        result.put("summaryBengali", "কন্টাক্টের নাম পাওয়া যায়নি।")
                    }
                }

                "createContact" -> {
                    val name = args.optString("name", "").trim()
                    val phone = args.optString("phone", "").trim()
                    if (context != null) {
                        val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                            putExtra(ContactsContract.Intents.Insert.NAME, name)
                            putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        result.put("success", true)
                        result.put("summaryBengali", "নতুন কন্টাক্ট সেভ উইন্ডো ওপেন করা হয়েছে: $name ($phone)।")
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "কন্টাক্ট সংরক্ষণ উইন্ডো খোলা যায়নি।")
                    }
                }

                // -------------------------------------------------------------
                // 15. DEVICE INTELLIGENCE & SETTINGS
                // -------------------------------------------------------------
                "getForegroundApp" -> {
                    val app = if (context != null) JarvisDeviceAutomationHelper.getForegroundAppPackage(context) else "Unknown"
                    result.put("success", app != "Unknown" && context != null)
                    result.put("appPackage", app)
                    result.put("summaryBengali", "চলমান ফোরগ্রাউন্ড অ্যাপ: $app")
                }

                "getDeviceState", "getBatteryHealthInfo" -> {
                    val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    if (bat in 0..100) {
                        result.put("success", true)
                        result.put("batteryPercent", bat)
                        result.put("status", "AVAILABLE")
                        result.put("summaryBengali", "ডিভাইস ব্যাটারি $bat%, সিস্টেম টেলিমেট্রি স্বাভাবিক ও সুস্থ।")
                    } else {
                        result.put("success", false)
                        result.put("batteryPercent", -1)
                        result.put("status", "UNKNOWN")
                        result.put("summaryBengali", "ডিভাইসের ব্যাটারি শতাংশ এই মুহূর্তে অপাঠ্য (UNKNOWN)।")
                    }
                }

                "getNetworkStatus" -> {
                    val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val active = cm?.activeNetwork != null
                    result.put("success", cm != null)
                    result.put("connected", active)
                    result.put("summaryBengali", if (active) "নেটওয়ার্ক সংযোগ সক্রিয় আছে।" else "ডিভাইস অফলাইনে রয়েছে।")
                }

                "setBrightness" -> {
                    val p = args.optString("percent", "50").filter { it.isDigit() }.toIntOrNull() ?: 50
                    if (context != null) {
                        val (ok, msg) = JarvisDeviceAutomationHelper.setBrightness(context, p)
                        result.put("success", ok)
                        result.put("brightnessPercent", p)
                        result.put("summaryBengali", msg)
                        if (!ok) {
                            result.put("status", "PERMISSION_REQUIRED")
                            result.put("permissionRequired", "write_settings")
                        }
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "সিস্টেম কনটেক্সট পাওয়া যায়নি।")
                    }
                }

                "openWifiSettings" -> {
                    if (context != null) {
                        context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                        result.put("success", true)
                        result.put("summaryBengali", "ওয়াইফাই সেটিংস ওপেন করা হয়েছে।")
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "সেটিংস খোলা সম্ভব হয়নি।")
                    }
                }

                "openBluetoothSettings" -> {
                    if (context != null) {
                        context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                        result.put("success", true)
                        result.put("summaryBengali", "ব্লুটুথ সেটিংস ওপেন করা হয়েছে।")
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "সেটিংস খোলা সম্ভব হয়নি।")
                    }
                }

                // -------------------------------------------------------------
                // 16. PRODUCTIVITY, ROUTINES & EMERGENCY STOP
                // -------------------------------------------------------------
                "executeRoutine" -> {
                    val rName = args.optString("routineName", "Morning").trim()
                    val rEngine = if (context != null) com.example.automation.JarvisRoutineEngine(context, dao) else null
                    val rResult = rEngine?.executeRoutine(rName)
                    val ok = rResult?.success == true
                    result.put("success", ok)
                    result.put("routineName", rName)
                    result.put("summaryBengali", rResult?.summaryBengali ?: "রুটিন ইঞ্জিন অনুপলব্ধ।")
                }

                "listRoutines" -> {
                    result.put("success", true)
                    result.put("routines", JSONArray(listOf("Morning Briefing", "Office Routine", "Night Routine")))
                    result.put("summaryBengali", "সিস্টেম রুটিনসমূহ:\n১. Morning Briefing (আবহাওয়া, ব্যাটারি, কাজ)\n২. Office Routine (অফিস শিডিউল ও কাজ)\n৩. Night Routine (আগামীকালের প্রস্তুতি ও চার্জ অ্যালার্ট)")
                }

                "generateDailyBriefing" -> {
                    val rEngine = if (context != null) com.example.automation.JarvisRoutineEngine(context, dao) else null
                    val rResult = rEngine?.executeRoutine("Morning")
                    val ok = rResult?.success == true
                    result.put("success", ok)
                    result.put("summaryBengali", rResult?.summaryBengali ?: "দৈনিক ব্রিফিং প্রস্তুত করা যায়নি।")
                }

                "emergencyStopAllWork" -> {
                    val report = com.example.util.JarvisEmergencyStopCoordinator.triggerEmergencyStop(context)
                    onNavigate("hud")
                    result.put("success", report.success)
                    result.put("report", report.toJson())
                    result.put("summaryBengali", report.summaryBengali)
                }

                else -> {
                    result.put("error", "Unknown tool: $name")
                    result.put("summaryBengali", "অজ্ঞাত কমান্ড বা টুল: $name")
                }
            }
        } catch (e: Exception) {
            result.put("error", e.message ?: "Execution error")
            result.put("summaryBengali", "টুল এক্সিকিউশনে সমস্যা হয়েছে: ${e.message}")
        }
        return result
    }

    private suspend fun executeDeviceAndAutomationTools(name: String, args: JSONObject, result: JSONObject): Boolean {
        when (name) {
            "openApp" -> {
                val app = (args.optString("appName", "").ifBlank { args.optString("app", "") }).trim()
                if (context != null && app.isNotBlank()) {
                    val (ok, msg) = JarvisDeviceAutomationHelper.openApp(context, app)
                    result.put("success", ok)
                    result.put("message", msg)
                    result.put("summaryBengali", msg)
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "অ্যাপের নাম দেওয়া হয়নি।")
                }
            }

            "closeApp" -> {
                val (ok, msg) = JarvisDeviceAutomationHelper.closeCurrentApp()
                result.put("success", ok)
                result.put("summaryBengali", msg)
            }

            "searchAndPlayYouTube" -> {
                val q = (args.optString("query", "").ifBlank { args.optString("searchQuery", "") }).trim()
                if (context != null && q.isNotBlank()) {
                    val (ok, msg) = JarvisDeviceAutomationHelper.searchAndPlayYouTube(context, q)
                    result.put("success", ok)
                    result.put("summaryBengali", msg)
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "ইউটিউবে কি সার্চ করতে হবে তা উল্লেখ করুন।")
                }
            }

            "controlWhatsApp" -> {
                val contact = (args.optString("contact", "").ifBlank { args.optString("contactName", "") }).trim()
                val msgText = (args.optString("message", "").ifBlank { args.optString("messageText", "") }).trim()
                if (context != null) {
                    val (ok, msg) = JarvisDeviceAutomationHelper.openAndControlWhatsApp(context, contact, msgText)
                    result.put("success", ok)
                    result.put("summaryBengali", msg)
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "হোয়াটসঅ্যাপ খোলার ব্যবস্থা করা যায়নি।")
                }
            }

            "openFacebook" -> {
                val post = (args.optString("postText", "").ifBlank { args.optString("post", "") }).trim()
                if (context != null) {
                    val (ok, msg) = JarvisDeviceAutomationHelper.openFacebook(context, post)
                    result.put("success", ok)
                    result.put("summaryBengali", msg)
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "ফেসবুক ওপেন করা যায়নি।")
                }
            }

            "inspectScreen", "getScreenContext" -> {
                if (context != null) {
                    val sc = ScreenPerceptionEngine.inspectCurrentScreen(context, sanitize = true)
                    result.put("success", true)
                    result.put("foregroundPackage", sc.foregroundPackage)
                    result.put("foregroundTitle", sc.foregroundActivityOrTitle)
                    result.put("currentBrowserUrl", sc.currentBrowserUrl ?: "")
                    result.put("settingsSection", sc.settingsSection ?: "")
                    result.put("visibleHeadings", JSONArray(sc.visibleHeadings))
                    result.put("visibleTexts", JSONArray(sc.visibleTexts))
                    result.put("clickableCount", sc.clickableElements.size)
                    result.put("inputFieldsCount", sc.inputFields.size)
                    result.put("switches", JSONObject(sc.switchesAndStates))
                    result.put("summaryBengali", sc.summaryBengali)
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "কনটেক্সট পাওয়া যায়নি।")
                }
            }

            "clickScreen" -> {
                val target = args.optString("targetText", "").trim()
                val x = args.optString("x", "0").toFloatOrNull() ?: 0f
                val y = args.optString("y", "0").toFloatOrNull() ?: 0f
                val (ok, msg) = JarvisDeviceAutomationHelper.clickElementOnScreen(target, x, y)
                result.put("success", ok)
                result.put("summaryBengali", msg)
            }

            "longPressOnScreen" -> {
                val target = args.optString("targetText", "").trim()
                val x = args.optString("x", "0").toFloatOrNull() ?: 0f
                val y = args.optString("y", "0").toFloatOrNull() ?: 0f
                val (ok, msg) = JarvisDeviceAutomationHelper.longPressElement(target, x, y)
                result.put("success", ok)
                result.put("summaryBengali", msg)
            }

            "typeOnScreen" -> {
                val text = args.optString("text", "").trim()
                val (ok, msg) = JarvisDeviceAutomationHelper.typeTextOnScreen(text)
                result.put("success", ok)
                result.put("summaryBengali", msg)
            }

            "clearAndTypeOnScreen" -> {
                val text = args.optString("text", "").trim()
                val (ok, msg) = JarvisDeviceAutomationHelper.clearAndTypeText(text)
                result.put("success", ok)
                result.put("summaryBengali", msg)
            }

            "performSystemAction" -> {
                val action = args.optString("action", "home").trim()
                val (ok, msg) = JarvisDeviceAutomationHelper.performSystemAction(action)
                result.put("success", ok)
                result.put("summaryBengali", msg)
            }

            "startScreenSharing" -> {
                val running = com.example.service.JarvisScreenCaptureService.isRunning()
                if (running) {
                    result.put("success", true)
                    result.put("action", "SCREEN_SHARING_ALREADY_ACTIVE")
                    result.put("summaryBengali", "স্ক্রিন শেয়ারিং ইতিমধ্যেই চালু রয়েছে।")
                } else {
                    result.put("success", false)
                    result.put("status", "AWAITING_PROJECTION_CONSENT")
                    result.put("needsScreenSharingPrompt", true)
                    result.put("action", "START_SCREEN_SHARING")
                    result.put("summaryBengali", "স্ক্রিন শেয়ারিং চালু করতে ডিভাইসের স্ক্রিন প্রজেকশন অনুমতি দিন।")
                }
            }

            "pauseScreenVision" -> {
                val isActive = com.example.util.JarvisScreenCaptureManager.visionState.value == com.example.util.ScreenVisionState.ACTIVE
                if (context != null && isActive) {
                    com.example.util.JarvisScreenCaptureManager.pauseScreenVision(context)
                    result.put("success", true)
                    result.put("action", "PAUSE_SCREEN_VISION")
                    result.put("summaryBengali", "স্ক্রিন ভিশন সাময়িক বিরতি দেওয়া হয়েছে।")
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "বিরতি দেওয়ার মতো কোনো সক্রিয় স্ক্রিন ভিশন চালু নেই।")
                }
            }

            "resumeScreenVision" -> {
                val isPaused = com.example.util.JarvisScreenCaptureManager.visionState.value == com.example.util.ScreenVisionState.PAUSED &&
                        com.example.service.JarvisScreenCaptureService.isRunning()
                if (context != null && isPaused) {
                    com.example.util.JarvisScreenCaptureManager.resumeScreenVision(context)
                    result.put("success", true)
                    result.put("action", "RESUME_SCREEN_VISION")
                    result.put("summaryBengali", "স্ক্রিন ভিশন পুনরায় চালু করা হয়েছে।")
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "পুনরায় চালু করার মতো কোনো স্থগিত স্ক্রিন ভিশন নেই।")
                }
            }

            "stopScreenSharing" -> {
                if (context != null) {
                    com.example.util.JarvisScreenCaptureManager.stopScreenCaptureService(context)
                    result.put("success", true)
                    result.put("action", "STOP_SCREEN_SHARING")
                    result.put("summaryBengali", "স্ক্রিন শেয়ারিং বন্ধ করা হয়েছে।")
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "স্ক্রিন শেয়ারিং বন্ধ করা সম্ভব হয়নি।")
                }
            }

            "verifyWhatsAppCallState" -> {
                val res = automationHandler?.verifyWhatsAppCallState() ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
                result.put("summaryBengali", res.optString("messageBn", "হোয়াটসঅ্যাপ কল অবস্থা যাচাই করা হয়েছে।"))
            }

            "endWhatsAppCall" -> {
                val res = automationHandler?.endWhatsAppCall() ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
                result.put("summaryBengali", res.optString("messageBn", "হোয়াটসঅ্যাপ কল সমাপ্ত করা হয়েছে।"))
            }

            "runAutonomousTask" -> {
                val goal = args.optString("goal", "").trim()
                val planStepsRaw = args.opt("planSteps")
                val planArr = when (planStepsRaw) {
                    is JSONArray -> planStepsRaw
                    is String -> try { JSONArray(planStepsRaw) } catch (_: Exception) { null }
                    else -> null
                }
                if (context != null && goal.isNotBlank()) {
                    val taskResult = com.example.automation.JarvisAutonomousLoopOrchestrator.executeTask(context, goal, planArr)
                    val taskJson = com.example.automation.JarvisAutonomousLoopOrchestrator.toJsonObject(taskResult)
                    taskJson.keys().forEach { k -> result.put(k, taskJson.get(k)) }
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "টাস্ক লক্ষ্য (goal) ফাঁকা রাখা যাবে না।")
                }
            }

            "openPermissionSettings" -> {
                val pType = args.optString("permissionType", "accessibility").trim().lowercase()
                if (context != null) {
                    when (pType) {
                        "accessibility", "অ্যাক্সেসিবিলিটি" -> JarvisDeviceAutomationHelper.openAccessibilitySettings(context)
                        "overlay", "ওভারলে" -> JarvisDeviceAutomationHelper.openOverlaySettings(context)
                        "battery", "ব্যাটারি" -> JarvisDeviceAutomationHelper.openBatteryOptimizationSettings(context)
                        "notification", "নোটিফিকেশন" -> JarvisDeviceAutomationHelper.openAppNotificationSettings(context)
                        else -> JarvisDeviceAutomationHelper.openApplicationDetailsSettings(context)
                    }
                    result.put("success", true)
                    result.put("summaryBengali", "অ্যান্ড্রয়েড সেটিংস ওপেন করা হয়েছে।")
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "সেটিংস ওপেন করা সম্ভব হয়নি।")
                }
            }

            "captureAndAnalyzeScreen" -> {
                val question = args.optString("question", "").trim()
                if (context != null) {
                    val apiKey = AiSettingsRepository(context).settingsState.value.apiKey
                    val (screenContext, visualAnalysis) = ScreenPerceptionEngine.perceiveScreen(context, apiKey, question)
                    result.put("success", screenContext.accessibilityAvailable || visualAnalysis != null)
                    result.put("detectedApp", visualAnalysis?.detectedApp ?: screenContext.foregroundPackage)
                    result.put("screenDescription", visualAnalysis?.screenDescription ?: screenContext.summaryBengali)
                    result.put("summaryBengali", visualAnalysis?.summaryBengali ?: screenContext.summaryBengali)
                    result.put("browserUrl", screenContext.currentBrowserUrl ?: "")
                    result.put("settingsSection", screenContext.settingsSection ?: "")
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "কনটেক্সট পাওয়া যায়নি।")
                }
            }

            "runPlaybook" -> {
                val pId = args.optString("playbookId", "").trim()
                val res = automationHandler?.runPlaybook(pId, args) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "listPlaybooks" -> {
                val pkg = args.optString("appPackage", "").trim()
                val res = automationHandler?.listPlaybooks(pkg.ifBlank { null }) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "recordPlaybook" -> {
                val id = args.optString("id", "").trim()
                val titleBn = args.optString("titleBn", id).trim()
                val pkg = args.optString("appPackage", "").trim()
                if (id.isNotBlank() && pkg.isNotBlank()) {
                    val playbook = com.example.automation.Playbook(
                        id = id,
                        titleBn = titleBn,
                        appPackage = pkg,
                        risk = com.example.automation.RiskLevel.INTERACTION,
                        steps = listOf(com.example.automation.Step.Sleep(500)),
                        verify = com.example.automation.VerifySpec(type = com.example.automation.VerifyType.SCREEN_HASH_CHANGED)
                    )
                    com.example.automation.PlaybookStore.registerPlaybook(playbook)
                    result.put("success", true)
                    result.put("messageBn", "নতুন প্লেবুক '$id' রেকর্ড করা হয়েছে।")
                } else {
                    result.put("success", false)
                    result.put("messageBn", "প্লেবুক আইডি বা অ্যাপ প্যাকেজ দেওয়া হয়নি।")
                }
            }

            "validatePlaybook" -> {
                val pId = args.optString("playbookId", "").trim()
                val res = automationHandler?.validatePlaybook(pId) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "getAppVersion" -> {
                val pkg = args.optString("appPackage", "").trim()
                val res = automationHandler?.getAppVersion(pkg) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "waitForNode" -> {
                val query = (args.optString("selectorQuery", "").ifBlank { args.optString("query", "") }).trim()
                val by = args.optString("by", "TEXT").trim()
                val timeout = args.optLong("timeoutMs", 4000L)
                val res = automationHandler?.waitForNode(query, by, timeout) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "assertScreen" -> {
                val pkg = args.optString("expectedPackage", "").trim()
                val text = args.optString("expectedText", "").trim()
                val res = automationHandler?.assertScreen(pkg, text) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "readVisibleList" -> {
                val res = automationHandler?.readVisibleList() ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "scrollToText" -> {
                val text = (args.optString("targetText", "").ifBlank { args.optString("text", "") }).trim()
                val dir = args.optString("direction", "DOWN").trim()
                val maxSwipes = args.optInt("maxSwipes", 6)
                val res = automationHandler?.scrollToText(text, dir, maxSwipes) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "openDeepLink" -> {
                val uri = (args.optString("uri", "").ifBlank { args.optString("link", "") }).trim()
                val pkg = args.optString("appPackage", "").trim()
                val res = automationHandler?.openDeepLink(uri, pkg) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "mediaControl" -> {
                val action = (args.optString("action", "").ifBlank { args.optString("command", "") }).trim()
                val seekMs = if (args.has("seekMs")) args.optLong("seekMs") else null
                val res = automationHandler?.mediaControl(action, seekMs) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "replyFromNotification" -> {
                val pkg = args.optString("packageFilter", "com.whatsapp").trim()
                val sender = args.optString("sender", "").trim().ifBlank { null }
                val text = (args.optString("replyText", "").ifBlank { args.optString("text", "") }).trim()
                val res = automationHandler?.replyFromNotification(pkg, sender, text) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "shareViaIntent" -> {
                val pkg = args.optString("targetPackage", "").trim()
                val text = (args.optString("text", "").ifBlank { args.optString("content", "") }).trim()
                val mime = args.optString("mimeType", "text/plain").trim()
                val res = automationHandler?.shareViaIntent(pkg, text, mime) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "getPlaybookHealth" -> {
                val pId = args.optString("playbookId", "").trim().ifBlank { null }
                val res = automationHandler?.getPlaybookHealth(pId) ?: JSONObject().put("error", "কনটেক্সট পাওয়া যায়নি")
                res.keys().forEach { k -> result.put(k, res.get(k)) }
            }

            "humanConfirm" -> {
                val action = args.optString("actionType", "CONFIRMATION").trim()
                val target = args.optString("target", "ব্যবহারকারী").trim()
                val content = args.optString("content", "").trim()
                val (promptBn, _) = com.example.automation.HumanGate.buildPrompt(action, target, content)
                result.put("success", false)
                result.put("status", "CONFIRMATION_REQUIRED")
                result.put("promptBengali", promptBn)
                result.put("summaryBengali", promptBn)
            }

            "confirmPendingAction" -> {
                val requestedApprovalId = args.optString("approvalId", "").trim().ifBlank { null }
                val phrase = args.optString("userConfirmationPhrase", args.optString("decision", "")).trim()
                val pending = com.example.permissions.JarvisPermissionManager.pendingUserApproval.value
                if (pending == null) {
                    result.put("success", false)
                    result.put("status", "NO_PENDING_ACTION")
                    result.put("summaryBengali", "এই মুহূর্তে সম্মতির অপেক্ষায় কোনো পেন্ডিং কাজ নেই।")
                } else {
                    when (com.example.permissions.ActionApprovalRegistry.classifyApprovalReply(phrase)) {
                        com.example.permissions.ApprovalReplyDecision.APPROVE -> {
                            val approved = com.example.permissions.JarvisPermissionManager.approveUserAction(
                                source = "gemini_live_voice_confirmation",
                                approvalId = requestedApprovalId
                            )
                            if (approved == null) {
                                result.put("success", false)
                                result.put("status", "APPROVAL_INVALID_OR_EXPIRED")
                                result.put("summaryBengali", "সম্মতির টোকেনটির মেয়াদ শেষ বা আইডি মেলেনি। অনুগ্রহ করে কাজটি আবার বলুন।")
                            } else {
                                val execArgs = try {
                                    JSONObject(approved.argsJson)
                                } catch (_: Exception) {
                                    JSONObject()
                                }
                                execArgs.put("_approvalId", approved.approvalId)
                                execArgs.put("_executionId", approved.executionId)
                                val execRes = executeToolWithCaller(approved.toolName, execArgs, "voice")
                                execRes.keys().forEach { k -> result.put(k, execRes.get(k)) }
                            }
                        }
                        com.example.permissions.ApprovalReplyDecision.REJECT,
                        com.example.permissions.ApprovalReplyDecision.CORRECTION -> {
                            com.example.permissions.JarvisPermissionManager.rejectUserAction(
                                source = "gemini_live_voice_rejection",
                                reason = "ব্যবহারকারী না বলেছেন ($phrase)"
                            )
                            result.put("success", true)
                            result.put("status", "CANCELLED")
                            result.put("verified", true)
                            result.put("summaryBengali", "ঠিক আছে, আপনার নির্দেশ অনুযায়ী পেন্ডিং কাজটি বাতিল করা হয়েছে।")
                        }
                        com.example.permissions.ApprovalReplyDecision.UNRELATED -> {
                            result.put("success", false)
                            result.put("status", "CONFIRMATION_PHRASE_UNCLEAR")
                            result.put("summaryBengali", "অনুগ্রহ করে স্পষ্টভাবে বলুন 'হ্যাঁ, করো' অথবা 'না, বাতিল করো'।")
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // SKILL LIBRARY & DEMONSTRATION LEARNING
            // -------------------------------------------------------------
            "startSkillRecording" -> {
                val skillName = args.optString("skillName", "New Learned Skill").trim()
                val skillNameBn = args.optString("skillNameBengali", skillName).trim()
                val trigger = args.optString("triggerPhrase", skillNameBn).trim()
                val targetApp = args.optString("targetApp", "").trim()
                val repo = skillRepo
                if (repo != null) {
                    val sessionId = skillRecorder.startRecording(
                        skillName = skillName,
                        skillNameBengali = skillNameBn,
                        triggerPhrase = trigger,
                        targetAppPackage = targetApp,
                        repository = repo
                    )
                    result.put("success", true)
                    result.put("sessionId", sessionId)
                    result.put("status", "RECORDING")
                    result.put("summaryBengali", "‘$skillNameBn’ কাজের জন্য ডেমোনস্ট্রেশন রেকর্ডিং শুরু হয়েছে। আপনি স্ক্রিনে কাজটি করে দেখান, অ্যাক্সেসিবিলিটি সার্ভিস প্রতিটি ধাপ রেকর্ড করবে।")
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "রেকর্ডিং শুরু করা সম্ভব হয়নি — ডাটাবেজ এক্সেস ত্রুটি।")
                }
            }

            "stopAndSaveSkill" -> {
                val desc = args.optString("description", "").trim()
                val repo = skillRepo
                if (repo != null) {
                    val saved = skillRecorder.stopAndSaveSkill(description = desc, repository = repo)
                    result.put("success", true)
                    result.put("skillId", saved.skillId)
                    result.put("name", saved.name)
                    result.put("stepCount", saved.stepCount)
                    result.put("summaryBengali", "‘${saved.nameBengali}’ কাজটি সফলভাবে স্কিল লাইব্রেরিতে সংরক্ষিত হয়েছে (${saved.stepCount}টি ধাপ রেকর্ড করা হয়েছে)।")
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "স্কিল সেভ করা সম্ভব হয়নি — রিপোজিটরি অনুপস্থিত।")
                }
            }

            "listLearnedSkills" -> {
                val repo = skillRepo
                if (repo != null && context != null) {
                    val list = AssistantDatabase.getDatabase(context).jarvisSkillDao().getAllSkillsSync()
                    val arr = JSONArray()
                    list.forEach { s ->
                        arr.put(JSONObject().apply {
                            put("skillId", s.skillId)
                            put("name", s.name)
                            put("nameBengali", s.nameBengali)
                            put("stepCount", s.stepCount)
                            put("status", s.status)
                            put("successCount", s.successCount)
                        })
                    }
                    result.put("count", list.size)
                    result.put("skills", arr)
                    result.put("summaryBengali", "মোট ${list.size}টি শেখা কাজ পাওয়া গেছে।")
                } else {
                    result.put("count", 0)
                    result.put("skills", JSONArray())
                    result.put("summaryBengali", "কোনো শেখা কাজ পাওয়া যায়নি।")
                }
            }

            "executeLearnedSkill" -> {
                val query = args.optString("skillNameOrTrigger", "").trim()
                val repo = skillRepo
                if (repo != null) {
                    val matchedSkill = repo.findMatchingSkill(query)
                    if (matchedSkill != null) {
                        val steps = repo.getStepsForSkill(matchedSkill.skillId)
                        val requiredParams = com.example.automation.JarvisSkillRecorder.extractUserRequiredParameters(steps)
                        val nowDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
                        val nowTime = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.ROOT).format(java.util.Date())

                        val paramValues = mutableMapOf(
                            "date" to nowDate,
                            "time" to nowTime
                        )
                        val candidateMap = mapOf(
                            "contact" to listOf("contact", "recipient", "contactName"),
                            "recipient" to listOf("recipient", "contact", "contactName"),
                            "phone_number" to listOf("phone_number", "phoneNumber", "phone"),
                            "message" to listOf("message", "text", "body"),
                            "search_query" to listOf("search_query", "query", "search"),
                            "app_name" to listOf("app_name", "appName", "app"),
                            "file_name" to listOf("file_name", "fileName", "file"),
                            "amount" to listOf("amount", "price", "value")
                        )
                        requiredParams.forEach { paramName ->
                            val aliases = candidateMap[paramName.lowercase()] ?: listOf(paramName)
                            val resolved = aliases.firstNotNullOfOrNull { key ->
                                args.optString(key, "").trim().takeIf { it.isNotBlank() }
                            }
                            if (resolved != null) {
                                paramValues[paramName] = resolved
                            }
                        }

                        val missingParams = requiredParams.filter { !paramValues.containsKey(it) }
                        if (missingParams.isNotEmpty()) {
                            result.put("success", false)
                            result.put("status", "PARAMETERS_REQUIRED")
                            result.put("skillId", matchedSkill.skillId)
                            result.put("missingParameters", JSONArray(missingParams))
                            result.put(
                                "summaryBengali",
                                "‘${matchedSkill.nameBengali}’ স্কিলটি চালানোর জন্য প্রয়োজনীয় তথ্য (${missingParams.joinToString(", ")}) প্রদান করুন।"
                            )
                        } else {
                            fun applyParams(raw: String?): String {
                                var s = raw ?: return ""
                                paramValues.forEach { (k, v) ->
                                    s = s.replace("{$k}", v, ignoreCase = true)
                                }
                                return s
                            }

                            var executedCount = 0
                            var cancelled = false
                            val service = JarvisAccessibilityService.instance
                            if (service != null) {
                                for (step in steps) {
                                    if (com.example.util.JarvisEmergencyStopCoordinator.isEmergencyStopActive.value) {
                                        cancelled = true
                                        break
                                    }
                                    val resolvedTargetText = applyParams(step.targetText).ifBlank { null }
                                    val resolvedTargetId = applyParams(step.targetViewId).ifBlank { null }
                                    val resolvedPayload = applyParams(step.inputPayload).ifBlank { null }

                                    when (step.actionType) {
                                        "OPEN_APP" -> {
                                            val pkgTarget = resolvedTargetId ?: resolvedTargetText
                                            if (!pkgTarget.isNullOrBlank() && context != null) {
                                                val (opened, _) = JarvisDeviceAutomationHelper.openApp(context, pkgTarget)
                                                if (opened || step.isOptional) executedCount++
                                                kotlinx.coroutines.delay(step.delayAfterMs)
                                            }
                                        }
                                        "CLICK" -> {
                                            val clicked = when {
                                                !resolvedTargetId.isNullOrBlank() -> service.clickElementById(resolvedTargetId)
                                                !resolvedTargetText.isNullOrBlank() -> service.clickElementByText(resolvedTargetText)
                                                else -> false
                                            }
                                            if (clicked || step.isOptional) executedCount++
                                            kotlinx.coroutines.delay(step.delayAfterMs)
                                        }
                                        "INPUT_TEXT" -> {
                                            val payload = resolvedPayload ?: ""
                                            val typed = if (payload.isNotBlank()) service.inputText(payload) else false
                                            if (typed || step.isOptional) executedCount++
                                            kotlinx.coroutines.delay(step.delayAfterMs)
                                        }
                                        "SCROLL_FORWARD" -> {
                                            service.swipeDirection("down")
                                            executedCount++
                                            kotlinx.coroutines.delay(step.delayAfterMs)
                                        }
                                        "SCROLL_BACKWARD" -> {
                                            service.swipeDirection("up")
                                            executedCount++
                                            kotlinx.coroutines.delay(step.delayAfterMs)
                                        }
                                        "BACK" -> {
                                            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                                            executedCount++
                                            kotlinx.coroutines.delay(step.delayAfterMs)
                                        }
                                        "HOME" -> {
                                            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                                            executedCount++
                                            kotlinx.coroutines.delay(step.delayAfterMs)
                                        }
                                        "WAIT" -> {
                                            kotlinx.coroutines.delay(step.delayAfterMs.coerceAtLeast(1000L))
                                            executedCount++
                                        }
                                        "WAIT_FOR_ELEMENT" -> {
                                            var found = false
                                            val target = resolvedTargetText ?: resolvedTargetId ?: ""
                                            for (retry in 0 until 6) {
                                                if (service.findAccessibilityNodeByTextOrId(target) != null) {
                                                    found = true
                                                    break
                                                }
                                                kotlinx.coroutines.delay(500L)
                                            }
                                            if (found || step.isOptional) executedCount++
                                        }
                                        "VERIFY_TEXT" -> {
                                            val target = resolvedTargetText ?: ""
                                            val verified = target.isBlank() || service.findAccessibilityNodeByTextOrId(target) != null
                                            if (verified || step.isOptional) executedCount++
                                        }
                                    }
                                }
                                val allSucceeded = !cancelled && executedCount > 0 && executedCount == steps.size
                                repo.recordExecution(matchedSkill.skillId, success = allSucceeded)
                                result.put("success", allSucceeded)
                                result.put(
                                    "status",
                                    when {
                                        cancelled -> "CANCELLED"
                                        allSucceeded -> "SUCCESS"
                                        executedCount > 0 -> "PARTIAL_SUCCESS"
                                        else -> "FAILED"
                                    }
                                )
                                result.put("executedSteps", executedCount)
                                result.put("totalSteps", steps.size)
                                result.put("summaryBengali", when {
                                    cancelled -> "জরুরি বিরতির কারণে ‘${matchedSkill.nameBengali}’ স্কিল বাতিল হয়েছে।"
                                    allSucceeded -> "‘${matchedSkill.nameBengali}’ কাজটি সফলভাবে সম্পাদিত হয়েছে ($executedCount/${steps.size} ধাপ সম্পন্ন)।"
                                    else -> "‘${matchedSkill.nameBengali}’ কাজের মাত্র $executedCount/${steps.size} ধাপ সম্পন্ন হয়েছে। সম্পূর্ণ কাজ সফল হয়নি।"
                                })
                            } else {
                                result.put("success", false)
                                result.put("summaryBengali", "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় না থাকায় কাজটি চালানো যায়নি।")
                            }
                        }
                    } else {
                        result.put("success", false)
                        result.put("summaryBengali", "‘$query’ নামের কোনো শেখা কাজ পাওয়া যায়নি।")
                    }
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "রিপোজিটরি প্রস্তুত নয়।")
                }
            }

            "deleteLearnedSkill" -> {
                val skillId = args.optString("skillId", "").trim()
                val repo = skillRepo
                if (repo != null && context != null && skillId.isNotBlank()) {
                    val skillDao = AssistantDatabase.getDatabase(context).jarvisSkillDao()
                    val existing = skillDao.getAllSkillsSync().firstOrNull {
                        it.skillId == skillId || it.name.equals(skillId, ignoreCase = true) || it.nameBengali.equals(skillId, ignoreCase = true)
                    }
                    if (existing != null) {
                        repo.deleteSkill(existing.skillId)
                        result.put("success", true)
                        result.put("deleted", true)
                        result.put("summaryBengali", "স্কিলটি (${existing.nameBengali}) সফলভাবে মুছে ফেলা হয়েছে।")
                    } else {
                        result.put("success", false)
                        result.put("deleted", false)
                        result.put("found", false)
                        result.put("summaryBengali", "'$skillId' আইডি বা নামের কোনো স্কিল লাইব্রেরিতে পাওয়া যায়নি।")
                    }
                } else {
                    result.put("success", false)
                    result.put("deleted", false)
                    result.put("summaryBengali", "স্কিল আইডি প্রদান করুন।")
                }
            }

            "importSkillFromYouTube" -> {
                val url = args.optString("url", "").trim()
                val title = args.optString("skillName", "").trim()
                val transcript = args.optString("transcript", "").trim()
                if (context != null && url.isNotBlank()) {
                    val ytResult = com.example.automation.YouTubeTutorialImporter.importFromTutorial(
                        context = context,
                        url = url,
                        userProvidedTitle = title.ifBlank { null },
                        userProvidedTranscript = transcript.ifBlank { null },
                        skillRepo = skillRepo
                    )
                    ytResult.keys().forEach { k -> result.put(k, ytResult.get(k)) }
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "ইউটিউব টিউটোরিয়াল লিঙ্ক প্রদান করুন।")
                }
            }

            "importSkillFromGitHub" -> {
                val repoUrl = args.optString("repoUrl", "").trim()
                val branch = args.optString("branch", "main").trim()
                if (context != null && repoUrl.isNotBlank()) {
                    val ghResult = com.example.automation.GitHubRepositoryLearningEngine.importFromRepository(
                        context = context,
                        repoUrl = repoUrl,
                        branch = branch,
                        skillRepo = skillRepo
                    )
                    ghResult.keys().forEach { k -> result.put(k, ghResult.get(k)) }
                } else {
                    result.put("success", false)
                    result.put("summaryBengali", "গিটহাব রিপোজিটরি ইউআরএল প্রদান করুন।")
                }
            }

            else -> return false
        }
        return true
    }
}
