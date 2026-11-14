package com.example.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisScreenCaptureService
import com.example.util.JarvisDeviceAutomationHelper

object PermissionRegistry {

    // Helper to check standard runtime permissions
    private fun checkRuntimePermissions(context: Context, permissions: List<String>): Boolean {
        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isNotificationListenerGranted(context: Context): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledListeners.contains(context.packageName)
    }

    private fun isUsageAccessGranted(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun isWriteSettingsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else true
    }

    private fun isExactAlarmGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: false
        } else true
    }

    val SPECS: List<PermissionSpec> = listOf(
        // 1. Camera
        PermissionSpec(
            id = "camera",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.CAMERA),
            titleBn = "ক্যামেরা এক্সেস",
            whyBn = "ডকুমেন্ট স্ক্যান, লাইভ অবজেক্ট শনাক্তকরণ এবং চাক্ষুষ বিশ্লেষণের জন্য প্রয়োজন।",
            whatItEnablesBn = "লাইভ ক্যামেরা ভিশন, টেক্সট রিডার ও ভিজ্যুয়াল অ্যাসিস্ট্যান্স",
            privacyNoteBn = "শুধুমাত্র ব্যবহারকারী যখন অনুরোধ করবেন তখনই ক্যামেরা সক্রিয় হবে।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.CAMERA)) },
            toolIds = listOf(
                "captureCameraImage",
                "startCameraVision", "captureCameraFrame", "analyzeCameraView",
                "switchCameraLens", "setCameraZoom", "toggleCameraTorch",
                "detectObjectsInView", "readTextFromCamera", "explainVisualScene"
            ),
            iconKey = "camera"
        ),

        // 2. Microphone
        PermissionSpec(
            id = "microphone",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.RECORD_AUDIO),
            titleBn = "মাইক্রোফোন এক্সেস",
            whyBn = "জারভিসের লাইভ ভয়েস সেশন ও হ্যান্ডস-ফ্রি অডিও কমান্ড গ্রহণের জন্য প্রয়োজন।",
            whatItEnablesBn = "Gemini Live দ্বি-মুখী ভয়েস কথোপকথন ও ওয়েক-ওয়ার্ড ডিটেকশন",
            privacyNoteBn = "ভয়েস কমান্ড প্রসেস ছাড়া কোনো অডিও ব্যাকগ্রাউন্ডে সংরক্ষণ করা হয় না।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.RECORD_AUDIO)) },
            toolIds = listOf("startLiveVoiceSession", "listenVoiceCommand", "startWakeWordDetector"),
            iconKey = "mic"
        ),

        // 3. Post Notifications
        PermissionSpec(
            id = "post_notifications",
            kind = PermissionKind.RUNTIME,
            androidPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else emptyList(),
            titleBn = "নোটিফিকেশন পাঠানো",
            whyBn = "শিডিউল অ্যালার্ট, অটোমেশন স্ট্যাটাস এবং গুরুত্বপূর্ণ কাজের রিমাইন্ডার প্রদর্শন।",
            whatItEnablesBn = "প্রো-অ্যাক্টিভ পুশ অ্যালার্ট ও ব্যক্তিগত টাস্ক নোটিফিকেশন",
            privacyNoteBn = "অপ্রয়োজনীয় বিজ্ঞাপনী নোটিফিকেশন কখনও পাঠানো হয় না।",
            isGranted = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkRuntimePermissions(ctx, listOf(Manifest.permission.POST_NOTIFICATIONS))
                } else true
            },
            toolIds = listOf("showTaskNotification", "sendScheduleAlert", "sendAutomationAlert"),
            iconKey = "notifications"
        ),

        // 4. Contacts Read
        PermissionSpec(
            id = "contacts_read",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.READ_CONTACTS),
            titleBn = "কন্টাক্ট তালিকা পড়া",
            whyBn = "নাম বলে সরাসরি কল বা হোয়াটসঅ্যাপ বার্তা পাঠানোর জন্য কন্টাক্ট নম্বর খুঁজে নেওয়া।",
            whatItEnablesBn = "নামের মাধ্যমে কল ও মেসেজ পাঠানো ('রহিমকে কল করো')",
            privacyNoteBn = "কন্টাক্ট ডেটা সম্পূর্ণ লোকাল থাকে, কোনো রিমোট সার্ভারে আপলোড হয় না।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_CONTACTS)) },
            toolIds = listOf("searchContacts", "readContacts", "getContactPhoneNumber", "getContactDetails", "callContactByName"),
            iconKey = "contacts"
        ),

        // 5. Contacts Write
        PermissionSpec(
            id = "contacts_write",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.WRITE_CONTACTS),
            titleBn = "নতুন কন্টাক্ট সংরক্ষণ",
            whyBn = "ভয়েস নির্দেশে নতুন সাপ্লায়ার, ডাক্তার বা কাস্টমারের ফোন নম্বর ফোনবুকে সংরক্ষণ।",
            whatItEnablesBn = "স্বয়ংক্রিয় কন্টাক্ট সেভ ও আপডেট",
            privacyNoteBn = "শুধুমাত্র আপনার নির্দেশিত নম্বরটি ফোনবুকে যোগ করা হবে।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.WRITE_CONTACTS)) },
            toolIds = listOf("createContact", "saveSupplierContact"),
            iconKey = "person_add"
        ),

        // 6. Phone State
        PermissionSpec(
            id = "phone_state",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.READ_PHONE_STATE),
            titleBn = "ফোন স্টেট ও কল পর্যবেক্ষণ",
            whyBn = "ইনকামিং কল আসলে জারভিসের স্পিচ পজ করা এবং কলার নাম ঘোষণা করা।",
            whatItEnablesBn = "স্মার্ট কলার ঘোষণা ও কল ইন্টারাপশন হ্যান্ডলিং",
            privacyNoteBn = "কোনো কল অডিও বা ব্যক্তিগত মেটাডেটা রেকর্ড করা হয় না।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_PHONE_STATE)) },
            toolIds = listOf("getPhoneCallState", "announceCallerName", "getActiveCallState", "getIncomingCallDetails", "identifyIncomingCaller"),
            iconKey = "phone_android"
        ),

        // 7. Call Phone
        PermissionSpec(
            id = "call_phone",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.CALL_PHONE),
            titleBn = "সরাসরি ফোন কল করা",
            whyBn = "জরুরি পরিস্থিতিতে বা ভয়েস নির্দেশে ডায়ালপ্যাডে না গিয়ে সরাসরি কল করা।",
            whatItEnablesBn = "হ্যান্ডস-ফ্রি সরাসরি ফোন কল ('জরুরি ডাক্তারকে কল দাও')",
            privacyNoteBn = "ব্যবহারকারীর ভয়েস নিশ্চিতকরণ ছাড়া কখনোই স্বতঃপ্রণোদিত কল হয় না।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.CALL_PHONE)) },
            toolIds = listOf("makePhoneCall", "directCall", "callEmergencyNumber"),
            iconKey = "call"
        ),

        // 8. Answer Phone Calls
        PermissionSpec(
            id = "answer_phone_calls",
            kind = PermissionKind.RUNTIME,
            androidPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                listOf(Manifest.permission.ANSWER_PHONE_CALLS)
            } else emptyList(),
            titleBn = "ভয়েসে কল রিসিভ করা",
            whyBn = "ড্রাইভিং বা দূরবর্তী অবস্থায় ভয়েস নির্দেশে কল রিসিভ করার সুবিধা।",
            whatItEnablesBn = "ভয়েস অ্যানসারিং ('জারভিস কল রিসিভ করো')",
            privacyNoteBn = "শুধুমাত্র আপনি বললে ইনকামিং কল রিসিভ হবে।",
            isGranted = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    checkRuntimePermissions(ctx, listOf(Manifest.permission.ANSWER_PHONE_CALLS))
                } else true
            },
            toolIds = listOf("answerIncomingCall"),
            iconKey = "call_received"
        ),

        // 9. SMS Receive
        PermissionSpec(
            id = "sms_receive",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.RECEIVE_SMS),
            titleBn = "এসএমএস গ্রহণ ও সতর্কতা",
            whyBn = "ব্যাংক অ্যালার্ট বা জরুরি এসএমএস তাৎক্ষণিক শনাক্ত করে জারভিস আপনাকে জানাতে পারবে।",
            whatItEnablesBn = "রিয়েল-টাইম এসএমএস ব্রিফিং ও ওটিপি ডিটেকশন",
            privacyNoteBn = "এসএমএস বার্তা শুধুমাত্র আপনার ডিভাইসে সংরক্ষিত থাকবে।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.RECEIVE_SMS)) },
            toolIds = listOf("listenIncomingSms", "parseOrderSms"),
            iconKey = "sms"
        ),

        // 10. SMS Read
        PermissionSpec(
            id = "sms_read",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.READ_SMS),
            titleBn = "এসএমএস পড়া",
            whyBn = "কাস্টমারের অর্ডারের বার্তা বা লেনদেনের এসএমএস পড়ে তথ্য জানানো।",
            whatItEnablesBn = "সাম্প্রতিক এসএমএস সামারি ও ইনবক্স পড়া",
            privacyNoteBn = "তথ্য এনক্রিপ্টেড ও লোকাল মেমরিতে সীমাবদ্ধ থাকে।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_SMS)) },
            toolIds = listOf("readSms", "getRecentSms", "searchSmsBySender"),
            iconKey = "mark_chat_unread"
        ),

        // 11. SMS Send
        PermissionSpec(
            id = "sms_send",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.SEND_SMS),
            titleBn = "এসএমএস পাঠানো",
            whyBn = "ভয়েস নির্দেশে কাস্টমারকে অর্ডারের নিশ্চয়তা বা ইনভয়েস বার্তা পাঠানো।",
            whatItEnablesBn = "স্বয়ংক্রিয় এসএমএস মেসেজিং ('রহিমকে এসএমএস পাঠাও')",
            privacyNoteBn = "বার্তা পাঠানোর আগে নিশ্চিতকরণ বার্তা দেখানো হয়।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.SEND_SMS)) },
            toolIds = listOf("sendSms", "sendQuickSms", "draftSms", "sendSmsMessage"),
            iconKey = "send"
        ),

        // 12. Location Fine
        PermissionSpec(
            id = "location_fine",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            titleBn = "সুনির্দিষ্ট জিপিএস লোকেশন",
            whyBn = "আপনি বর্তমানে কোথায় আছেন তা সঠিকভাবে বলা এবং নিকটবর্তী জায়গা ও আবহাওয়া খুঁজে দেওয়া।",
            whatItEnablesBn = "রিয়েল-টাইম লাইভ লোকেশন ট্র্যাকিং ও আবহাওয়ার তথ্য",
            privacyNoteBn = "আপনার জিপিএস অবস্থান ডিভাইসেই থাকে, বহিরাগত কারো সাথে শেয়ার হয় না।",
            isGranted = { ctx ->
                checkRuntimePermissions(ctx, listOf(Manifest.permission.ACCESS_FINE_LOCATION))
            },
            toolIds = listOf("getCurrentGpsLocation", "shareLiveLocation", "getLiveGpsCoordinates", "getLiveWeatherForecast"),
            iconKey = "my_location"
        ),

        // 13. Location Background
        PermissionSpec(
            id = "location_background",
            kind = PermissionKind.RUNTIME,
            androidPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else emptyList(),
            titleBn = "ব্যাকগ্রাউন্ড লোকেশন এক্সেস",
            whyBn = "অ্যাপ ব্যাকগ্রাউন্ডে বা স্ক্রিন বন্ধ থাকা অবস্থায়ও লাইভ রুট ট্র্যাকিং সচল রাখা।",
            whatItEnablesBn = "অবিরাম রুট ট্র্যাকিং ও জিও-ফেন্স অ্যালার্ট",
            privacyNoteBn = "জরুরি সুরক্ষা সার্ভিস ছাড়া ব্যাকগ্রাউন্ডে লোকেশন পোলিং বন্ধ থাকে।",
            isGranted = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    checkRuntimePermissions(ctx, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                } else true
            },
            toolIds = listOf("startBackgroundLocationTracking"),
            iconKey = "location_searching"
        ),

        // 14. Calendar Read
        PermissionSpec(
            id = "calendar_read",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.READ_CALENDAR),
            titleBn = "ক্যালেন্ডার ও শিডিউল দেখা",
            whyBn = "আজকের শিডিউল, ডাক্তারের অ্যাপয়েন্টমেন্ট ও জরুরি মিটিং সময়সূচি দেখা।",
            whatItEnablesBn = "দৈনিক শিডিউল ব্রিফিং ('আজকের মিটিং কখন?')",
            privacyNoteBn = "ক্যালেন্ডার ইভেন্ট লোকালি পড়ে জারভিস উত্তর দেয়।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_CALENDAR)) },
            toolIds = listOf("getCalendarEvents", "checkScheduleForDay"),
            iconKey = "calendar_month"
        ),

        // 15. Calendar Write
        PermissionSpec(
            id = "calendar_write",
            kind = PermissionKind.RUNTIME,
            androidPermissions = listOf(Manifest.permission.WRITE_CALENDAR),
            titleBn = "ক্যালেন্ডারে শিডিউল তৈরি",
            whyBn = "ভয়েস নির্দেশে নতুন ডাক্তারের ভিজিট বা সাপ্লায়ার পেমেন্টের ডেট শিডিউল করা।",
            whatItEnablesBn = "নতুন ক্যালেন্ডার ইভেন্ট যোগ ('কাল বিকেলে মিটিং সেট করো')",
            privacyNoteBn = "শুধুমাত্র আপনার নির্দেশিত ইভেন্ট ক্যালেন্ডারে বসানো হবে।",
            isGranted = { ctx -> checkRuntimePermissions(ctx, listOf(Manifest.permission.WRITE_CALENDAR)) },
            toolIds = listOf("createCalendarEvent", "scheduleAppointment"),
            iconKey = "event"
        ),

        // 16. Media Images
        PermissionSpec(
            id = "media_images",
            kind = PermissionKind.RUNTIME,
            androidPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            titleBn = "ছবি ও ডকুমেন্ট গ্যালারি",
            whyBn = "ব্যবহারকারীর নির্বাচিত ছবি বা ডকুমেন্ট এআই দিয়ে এনালাইসিস করার জন্য প্রয়োজন।",
            whatItEnablesBn = "নির্বাচিত ইমেজ বিশ্লেষণ ও ভিজ্যুয়াল প্রশ্নোত্তর",
            privacyNoteBn = "নির্বাচিত ছবি ছাড়া আপনার গ্যালারির অন্য কোনো ছবি দেখা হয় না।",
            isGranted = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_MEDIA_IMAGES))
                } else {
                    checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                }
            },
            toolIds = listOf("analyzeGalleryImage", "readTextFromImage"),
            iconKey = "photo_library"
        ),

        // 17. Media Audio
        PermissionSpec(
            id = "media_audio",
            kind = PermissionKind.RUNTIME,
            androidPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            titleBn = "অডিও ফাইল এক্সেস",
            whyBn = "ভয়েস নোট বা অডিও রেকর্ডিং প্লে করে টেক্সটে রূপান্তর।",
            whatItEnablesBn = "অডিও ভয়েস নোট প্লে ও ট্রান্সক্রিপশন",
            privacyNoteBn = "শুধুমাত্র নির্বাচিত অডিও ফাইল প্রসেস করা হয়।",
            isGranted = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_MEDIA_AUDIO))
                } else {
                    checkRuntimePermissions(ctx, listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                }
            },
            toolIds = listOf("transcribeAudioFile", "playRecordedNote"),
            iconKey = "audiotrack"
        ),

        // 18. Notification Listener (Special Access)
        PermissionSpec(
            id = "notification_listener",
            kind = PermissionKind.SPECIAL_ACCESS,
            titleBn = "নোটিফিকেশন রিডার এক্সেস",
            whyBn = "হোয়াটসঅ্যাপ, মেসেঞ্জার বা এসএমএস আসলে জারভিস নিজে পড়ে শোনাবে।",
            whatItEnablesBn = "হ্যান্ডস-ফ্রি নোটিফিকেশন সামারি ও অটো-রিপ্লাই সাজেশন",
            privacyNoteBn = "মেসেজ মেমোরিতে প্রসেস করে তৎক্ষণাৎ মুছে ফেলা হয়।",
            settingsIntent = { _ ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            },
            isGranted = { ctx -> isNotificationListenerGranted(ctx) },
            toolIds = listOf(
                "readIncomingAppNotifications", "summarizeUnreadNotifications",
                "replyFromNotification", "sendNotificationReply", "dismissNotification",
                "getRecentNotifications", "getNotificationsByApp", "getNotificationDetails"
            ),
            iconKey = "notifications_active"
        ),

        // 19. Accessibility Service (Special Access)
        PermissionSpec(
            id = "accessibility",
            kind = PermissionKind.SPECIAL_ACCESS,
            titleBn = "অ্যাক্সেসিবিলিটি অটোমেশন সার্ভিস",
            whyBn = "ইউটিউব, হোয়াটসঅ্যাপ বা যেকোনো অ্যাপ ওপেন করে স্ক্রিনে ক্লিক ও ভয়েসে নিয়ন্ত্রণ।",
            whatItEnablesBn = "হাতে না ছুঁয়ে অ্যাপ চালানো, ক্লিক, স্ক্রল ও ফুল ফোন অটোমেশন",
            privacyNoteBn = "জরুরি সিস্টেম সেটিংস ও পাসওয়ার্ড কখনো স্পর্শ করা হয় না।",
            settingsIntent = { _ ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            },
            isGranted = { ctx -> JarvisAccessibilityService.isAccessibilityEnabled(ctx) },
            toolIds = listOf(
                "performSystemAction", "clickScreen", "longPressOnScreen", "clickOnScreenText",
                "scrollDown", "scrollUp", "scrollScreen", "swipeScreen", "scrollToText",
                "openAppAndClick", "typeOnScreen", "clearAndTypeOnScreen", "typeIntoFocusedField",
                "inspectScreen", "getScreenContext", "readVisibleList", "waitForNode", "assertScreen",
                "findUiElement", "clickUiElement", "longPressUiElement", "verifyUiState",
                "controlWhatsApp", "runPlaybook", "runAutonomousTask", "recordPlaybook",
                "startSkillRecording", "stopAndSaveSkill", "executeLearnedSkill",
                "readFacebookFeed", "readFacebookPost", "reactToCurrentPost"
            ),
            iconKey = "accessibility_new"
        ),

        // 20. System Alert Window / Overlay (Special Access)
        PermissionSpec(
            id = "overlay",
            kind = PermissionKind.SPECIAL_ACCESS,
            titleBn = "ফ্লোটিং হলো অর্ব ওভারলে",
            whyBn = "অন্যান্য অ্যাপ ব্যবহারের সময়ও স্ক্রিনের ওপর জারভিসের হলো অর্ব দৃশ্যমান রাখা।",
            whatItEnablesBn = "সব অ্যাপের ওপর ভাসমান আইকন ও কুইক কমান্ড পপআপ",
            privacyNoteBn = "ভাসমান উইন্ডো অন্য অ্যাপের ডেটায় হস্তক্ষেপ করে না।",
            settingsIntent = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                }
            },
            isGranted = { ctx -> JarvisDeviceAutomationHelper.isOverlayGranted(ctx) },
            toolIds = listOf("showFloatingOverlay", "showFloatingJarvisOrb", "hideFloatingOverlay"),
            iconKey = "layers"
        ),

        // 21. Battery Optimization Ignore (Special Access)
        PermissionSpec(
            id = "battery_optimization",
            kind = PermissionKind.SPECIAL_ACCESS,
            titleBn = "ব্যাটারি অপ্টিমাইজেশন ছাড়",
            whyBn = "স্ক্রিন বন্ধ বা ব্যাকগ্রাউন্ডে থাকলেও 'Jarvis' ডাকলেই সঙ্গে সঙ্গে সাড়া দেওয়া।",
            whatItEnablesBn = "২৪/৭ নিরবচ্ছিন্ন ওয়েক-ওয়ার্ড লিসেনিং ও ব্যাকগ্রাউন্ড সার্ভিস",
            privacyNoteBn = "স্মার্ট পাওয়ার ম্যানেজমেন্ট দ্বারা ব্যাটারি ক্ষয় সর্বনিম্ন রাখা হয়।",
            settingsIntent = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                }
            },
            isGranted = { ctx -> JarvisDeviceAutomationHelper.isBatteryOptimizationIgnored(ctx) },
            toolIds = listOf("keepJarvisAliveInBackground", "startContinuousWakeWordListening"),
            iconKey = "battery_charging_full"
        ),

        // 22. Exact Alarm (Special Access / Runtime)
        PermissionSpec(
            id = "exact_alarm",
            kind = PermissionKind.SPECIAL_ACCESS,
            titleBn = "নির্দিষ্ট সময়ের অ্যালার্ম ও শিডিউল",
            whyBn = "জরুরি ব্যক্তিগত কাজ, মিটিং বা দৈনন্দিন রুটিনের সময় কাঁটায় কাঁটায় অ্যালার্ম বাজানো।",
            whatItEnablesBn = "সঠিক সময়ে এআই শিডিউল অ্যালার্ম ও রিমাইন্ডার",
            privacyNoteBn = "নির্দিষ্ট টাইমার ব্যতিরেকে কোনো অ্যালার্ম সেট হয় না।",
            settingsIntent = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                }
            },
            isGranted = { ctx -> isExactAlarmGranted(ctx) },
            toolIds = listOf("setExactScheduleAlarm"),
            iconKey = "alarm"
        ),

        // 23. Usage Access (Special Access)
        PermissionSpec(
            id = "usage_access",
            kind = PermissionKind.SPECIAL_ACCESS,
            titleBn = "অ্যাপ ব্যবহারের পরিসংখ্যান",
            whyBn = "বর্তমানে আপনি কোন অ্যাপ দেখছেন তা শনাক্ত করে স্বয়ংক্রিয় সাহায্য দেওয়া।",
            whatItEnablesBn = "চলমান অ্যাপ শনাক্তকরণ ও স্ক্রিনটাইম বিশ্লেষণ",
            privacyNoteBn = "অ্যাপের ভেতরের ডেটা দেখা হয় না, শুধু কোন অ্যাপ চালু তা জানা যায়।",
            settingsIntent = { _ ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            },
            isGranted = { ctx -> isUsageAccessGranted(ctx) },
            toolIds = listOf("getActiveForegroundApp", "getAppUsageStats", "getForegroundApp"),
            iconKey = "query_stats"
        ),

        // 24. Screen Capture (Per Session)
        PermissionSpec(
            id = "screen_capture",
            kind = PermissionKind.PER_SESSION,
            titleBn = "স্ক্রিন প্রজেকশন ও ক্যাপচার",
            whyBn = "স্ক্রিনের অবস্থা দেখে জেমিনি এআই যাতে বুঝতে পারে এবং পরামর্শ দিতে পারে।",
            whatItEnablesBn = "স্ক্রিনের চাক্ষুষ বিশ্লেষণ ও ডেসক্রিপশন ('আমার স্ক্রিনে কি আছে?')",
            privacyNoteBn = "ক্যাপচার অনুমতি প্রতি সেশনে একবার নেওয়া হয় এবং রিয়েল-টাইমে প্রসেস হয়।",
            isGranted = { _ ->
                JarvisScreenCaptureService.isRunning() || com.example.util.JarvisScreenCaptureManager.hasMediaProjectionConsent.value
            },
            toolIds = listOf("captureAndAnalyzeScreen", "describeCurrentScreen", "startScreenSharing"),
            iconKey = "screenshot"
        ),

        // 25. Write Settings (Special Access)
        PermissionSpec(
            id = "write_settings",
            kind = PermissionKind.SPECIAL_ACCESS,
            titleBn = "সিস্টেম সেটিংস পরিবর্তন",
            whyBn = "ভয়েস নির্দেশে স্ক্রিন ব্রাইটনেস, ভলিউম বা স্ক্রিন টাইমআউট নিয়ন্ত্রণ।",
            whatItEnablesBn = "স্ক্রিন আলো ও সাউন্ড ভলিউম নিয়ন্ত্রণ ('ব্রাইটনেস ৫০% করো')",
            privacyNoteBn = "শুধুমাত্র ব্যবহারকারী যে সেটিং পরিবর্তন করতে বলেন তা পরিবর্তন হবে।",
            settingsIntent = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                }
            },
            isGranted = { ctx -> isWriteSettingsGranted(ctx) },
            toolIds = listOf("setBrightness", "setScreenBrightness", "setSystemVolumeLevel", "getBrightness"),
            iconKey = "tune"
        ),

        // 26. App Info (Manifest Only)
        PermissionSpec(
            id = "app_info",
            kind = PermissionKind.MANIFEST_ONLY,
            titleBn = "ইনস্টল করা অ্যাপের তালিকা",
            whyBn = "ফোনে কোন কোন অ্যাপ ইনস্টল আছে তা জেনে ভয়েস নির্দেশে সেগুলো ওপেন করা।",
            whatItEnablesBn = "ফোনের যেকোনো অ্যাপ সরাসরি ওপেন করা ('ফেসবুক খোলো')",
            privacyNoteBn = "তালিকা শুধুমাত্র আপনার ডিভাইসের সিস্টেম ম্যানেজার থেকে নেওয়া হয়।",
            isGranted = { ctx ->
                try {
                    val pm = ctx.packageManager
                    pm.getInstalledApplications(0).isNotEmpty()
                } catch (e: Exception) {
                    true
                }
            },
            toolIds = listOf("launchAppByVoice", "closeAppByVoice", "listInstalledApps"),
            iconKey = "apps"
        )
    )

    private val specsMap: Map<String, PermissionSpec> = SPECS.associateBy { it.id }

    private val toolToSpecsMap: Map<String, List<PermissionSpec>> = buildMap {
        for (spec in SPECS) {
            for (toolId in spec.toolIds) {
                val list = getOrPut(toolId) { mutableListOf() } as MutableList
                list.add(spec)
            }
        }
    }

    fun get(id: String): PermissionSpec? {
        if (id == "send_sms") return specsMap["sms_send"] ?: specsMap[id]
        if (id == "sms_send") return specsMap["sms_send"] ?: specsMap["send_sms"]
        return specsMap[id]
    }

    fun getAll(): List<PermissionSpec> = SPECS

    fun getForTool(toolName: String): List<PermissionSpec> = toolToSpecsMap[toolName] ?: emptyList()

    fun getResolvedForTool(toolName: String, requiredPermissionId: String? = null): List<PermissionSpec> {
        val merged = mutableListOf<PermissionSpec>()
        if (!requiredPermissionId.isNullOrBlank()) {
            get(requiredPermissionId)?.let { merged.add(it) }
        }
        merged.addAll(getForTool(toolName))
        return merged.distinctBy { it.id }
    }

    fun isGranted(context: Context, id: String): Boolean {
        val spec = get(id) ?: return true
        return try {
            spec.isGranted(context)
        } catch (e: Exception) {
            false
        }
    }

    fun getGrantedCount(context: Context): Int {
        return SPECS.count { isGranted(context, it.id) }
    }
}
