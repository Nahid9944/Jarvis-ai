package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisFloatingOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

object JarvisDeviceAutomationHelper {

    // Common Package Names map
    private val KNOWN_APPS = mapOf(
        "youtube" to "com.google.android.youtube",
        "ইউটিউব" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "হোয়াটস্যাপ" to "com.whatsapp",
        "facebook" to "com.facebook.katana",
        "ফেসবুক" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "মেসেঞ্জার" to "com.facebook.orca",
        "chrome" to "com.android.chrome",
        "ক্রোম" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "settings" to "com.android.settings",
        "সেটিংস" to "com.android.settings",
        "camera" to "camera",
        "ক্যামেরা" to "camera",
        "phone" to "dialer",
        "ফোন" to "dialer",
        "কল" to "dialer",
        "play store" to "com.android.vending",
        "প্লে স্টোর" to "com.android.vending",
        "calculator" to "calculator",
        "ক্যালকুলেটর" to "calculator",
        "clock" to "clock",
        "ঘড়ি" to "clock",
        "maps" to "com.google.android.apps.maps",
        "ম্যাপস" to "com.google.android.apps.maps",
        "telegram" to "org.telegram.messenger",
        "টেলিগ্রাম" to "org.telegram.messenger",
        "tiktok" to "com.zhiliaoapp.musically",
        "টিকটক" to "com.zhiliaoapp.musically",
        "instagram" to "com.instagram.android",
        "ইনস্টাগ্রাম" to "com.instagram.android"
    )

    // =================================================================
    // 1. APP LAUNCHER & CLOSER
    // =================================================================

    fun openApp(context: Context, appNameOrPackage: String): Pair<Boolean, String> {
        val query = appNameOrPackage.trim().lowercase()

        // 1. Check known shortcuts
        val mappedPackage = KNOWN_APPS[query]

        if (mappedPackage == "camera") {
            return try {
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Pair(true, "ক্যামেরা ওপেন করা হয়েছে।")
            } catch (e: Exception) {
                Pair(false, "ক্যামেরা ওপেন করতে সমস্যা হয়েছে: ${e.localizedMessage}")
            }
        }

        if (mappedPackage == "dialer") {
            return try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Pair(true, "ফোন ডায়ালার ওপেন করা হয়েছে।")
            } catch (e: Exception) {
                Pair(false, "ডায়ালার ওপেন করতে সমস্যা হয়েছে: ${e.localizedMessage}")
            }
        }

        // 2. Try launching by explicit package
        val targetPackage = mappedPackage ?: if (query.contains(".")) query else null

        if (targetPackage != null) {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return Pair(true, "$query অ্যাপটি সফলভাবে ওপেন করা হয়েছে।")
            }
        }

        // 3. Search installed apps by human-readable label
        try {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.contains(query) || query.contains(label)) {
                    val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return Pair(true, "${pm.getApplicationLabel(app)} ওপেন করা হয়েছে।")
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return Pair(false, "ফোন এ '$appNameOrPackage' অ্যাপটি খুঁজে পাওয়া যায়নি।")
    }

    fun closeCurrentApp(): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
        return if (service != null) {
            val res = service.goHome()
            if (res) Pair(true, "বর্তমান অ্যাপ ক্লোজ করে হোমস্ক্রিনে ফিরে যাওয়া হয়েছে।")
            else Pair(false, "হোমস্ক্রিনে ফিরে যেতে পারেনি।")
        } else {
            Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। দয়া করে সেটিংস থেকে জারভিসকে অনুমতি দিন।")
        }
    }

    fun getForegroundAppPackage(context: Context): String {
        return try {
            val accessibilityService = JarvisAccessibilityService.instance
            val rootNode = accessibilityService?.rootInActiveWindow
            val currentPkg = rootNode?.packageName?.toString()
            if (!currentPkg.isNullOrBlank()) {
                currentPkg
            } else {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
                val top = stats?.maxByOrNull { it.lastTimeUsed }
                top?.packageName ?: "com.example"
            }
        } catch (e: Exception) {
            "com.example"
        }
    }

    // =================================================================
    // 2. YOUTUBE SMART AUTOMATION
    // =================================================================

    fun searchAndPlayYouTube(context: Context, searchQuery: String): Pair<Boolean, String> {
        return try {
            val encoded = URLEncoder.encode(searchQuery, "UTF-8")
            val appIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", searchQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (context.packageManager.resolveActivity(appIntent, 0) != null) {
                context.startActivity(appIntent)
                JarvisAccessibilityService.instance?.let { service ->
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2200)
                        // Prefer semantic node lookup over blind coordinates
                        val clickedPlay = service.clickElementByText(searchQuery) ||
                                service.clickElementById("com.google.android.youtube:id/thumbnail")
                        if (!clickedPlay) {
                            android.util.Log.d("JarvisDeviceAuto", "YouTube video node not auto-clicked for '$searchQuery'")
                        }
                    }
                }
                Pair(true, "ইউটিউব অ্যাপে '$searchQuery' সার্চ ওপেন করা হয়েছে।")
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (context.packageManager.resolveActivity(webIntent, 0) != null) {
                    context.startActivity(webIntent)
                    Pair(true, "ব্রাউজারে ইউটিউব সার্চ '$searchQuery' ওপেন করা হয়েছে।")
                } else {
                    Pair(false, "ইউটিউব অ্যাপ বা ব্রাউজার ওপেন করার কোনো হ্যান্ডলার পাওয়া যায়নি।")
                }
            }
        } catch (e: Exception) {
            Pair(false, "ইউটিউব ওপেন করতে সমস্যা: ${e.localizedMessage}")
        }
    }

    // =================================================================
    // 3. WHATSAPP AUTOMATION
    // =================================================================

    fun openAndControlWhatsApp(context: Context, contactOrPhone: String, message: String): Pair<Boolean, String> {
        val pm = context.packageManager
        val isWhatsAppInstalled = try {
            pm.getPackageInfo("com.whatsapp", 0)
            true
        } catch (_: Exception) {
            try {
                pm.getPackageInfo("com.whatsapp.w4b", 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        if (!isWhatsAppInstalled) {
            return Pair(false, "ফোনে হোয়াটসঅ্যাপ ইনস্টল করা নেই।")
        }

        val cleanPhone = contactOrPhone.replace("[^0-9+]".toRegex(), "")

        // If phone number provided, launch direct chat
        if (cleanPhone.length >= 10) {
            return try {
                val formatted = cleanPhone.removePrefix("+").let {
                    if (it.startsWith("01")) "88$it" else it
                }
                val encodedMsg = URLEncoder.encode(message, "UTF-8")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$formatted&text=$encodedMsg")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)

                JarvisAccessibilityService.instance?.let { service ->
                    if (message.isNotBlank()) {
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(1800)
                            service.clickElementByText("Send")
                        }
                    }
                }

                Pair(true, "হোয়াটসঅ্যাপে $cleanPhone-এর সাথে চ্যাট খোলা হয়েছে।")
            } catch (e: Exception) {
                Pair(false, "হোয়াটসঅ্যাপ ওপেন করতে সমস্যা: ${e.localizedMessage}")
            }
        }

        // If contact name provided, launch WhatsApp and automate search only if Accessibility is active
        return try {
            val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)

                val service = JarvisAccessibilityService.instance
                if (service != null && contactOrPhone.isNotBlank()) {
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(1500)
                        service.clickElementByText("Search")
                        delay(800)
                        service.inputText(contactOrPhone)
                        delay(1200)
                        service.clickElementByText(contactOrPhone)
                        delay(1000)
                        if (message.isNotBlank()) {
                            service.inputText(message)
                            delay(600)
                            service.clickElementByText("Send")
                        }
                    }
                    Pair(true, "হোয়াটসঅ্যাপ ওপেন করে '$contactOrPhone'-কে সার্চ করার অটোমেশন পাঠানো হয়েছে।")
                } else {
                    Pair(false, "হোয়াটসঅ্যাপ ওপেন করা হয়েছে, কিন্তু নাম দিয়ে অটোমেশনের জন্য অ্যাক্সেসিবিলিটি সার্ভিস বা ফোন নম্বর প্রয়োজন।")
                }
            } else {
                Pair(false, "হোয়াটসঅ্যাপ লঞ্চ করা সম্ভব হয়নি।")
            }
        } catch (e: Exception) {
            Pair(false, "হোয়াটসঅ্যাপ অটোমেশন ত্রুটি: ${e.localizedMessage}")
        }
    }

    // =================================================================
    // 4. FACEBOOK AUTOMATION
    // =================================================================

    fun openFacebook(context: Context, postText: String = ""): Pair<Boolean, String> {
        return try {
            if (postText.isNotBlank()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.facebook.katana")
                    putExtra(Intent.EXTRA_TEXT, postText)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (context.packageManager.resolveActivity(shareIntent, 0) != null) {
                    context.startActivity(shareIntent)
                    return Pair(true, "ফেসবুকে পোস্ট ড্রাফট উইন্ডো ওপেন করা হয়েছে।")
                } else {
                    return Pair(false, "ফেসবুক শেয়ার/পোস্ট উইন্ডো খুঁজে পাওয়া যায়নি (ফেসবুক অ্যাপ ইনস্টল আছে কিনা দেখুন)।")
                }
            }
            openApp(context, "facebook")
        } catch (e: Exception) {
            Pair(false, "ফেসবুক ওপেন করতে সমস্যা: ${e.localizedMessage}")
        }
    }

    // =================================================================
    // 5. SCREEN CLICKS, GESTURES & PERCEPTION
    // =================================================================

    fun clickElementOnScreen(targetText: String, x: Float = 0f, y: Float = 0f): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ আছে। সেটিংসে গিয়ে জারভিস অ্যাক্সেসিবিলিটি অন করুন।")

        if (targetText.isNotBlank()) {
            val success = service.clickElementByText(targetText)
            if (success) return Pair(true, "'$targetText' বাটনে সফলভাবে ক্লিক করা হয়েছে।")
        }

        if (x > 0f && y > 0f) {
            service.clickCoordinates(x, y)
            return Pair(true, "স্ক্রিনের কোঅর্ডিনেট ($x, $y)-এ ক্লিক ডিসপ্যাচ করা হয়েছে।")
        }

        return Pair(false, "স্ক্রিনে '$targetText' খুঁজে পাওয়া যায়নি।")
    }

    fun longPressElement(targetText: String, x: Float = 0f, y: Float = 0f): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।")

        if (targetText.isNotBlank()) {
            val ok = service.longPressElementByText(targetText)
            if (ok) return Pair(true, "'$targetText' বাটনে লং-প্রেস করা হয়েছে।")
        }

        if (x > 0f && y > 0f) {
            service.longPressCoordinates(x, y, 600L)
            return Pair(true, "কোঅর্ডিনেট ($x, $y)-এ লং-প্রেস ডিসপ্যাচ করা হয়েছে।")
        }

        return Pair(false, "লং-প্রেস করার মতো উপাদান পাওয়া যায়নি।")
    }

    fun clearAndTypeText(text: String): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ আছে।")

        val ok = service.clearInputAndType(text)
        return if (ok) Pair(true, "পূর্ববর্তী টেক্সট মুছে নতুন টেক্সট '$text' টাইপ করা হয়েছে।")
        else Pair(false, "ইনপুট ফিল্ড ক্লিয়ার করে টাইপ করা সম্ভব হয়নি।")
    }

    fun typeTextOnScreen(text: String): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ আছে। সেটিংসে গিয়ে জারভিস অ্যাক্সেসিবিলিটি অন করুন।")

        val success = service.inputText(text)
        return if (success) Pair(true, "'$text' সফলভাবে টাইপ করা হয়েছে।")
        else Pair(false, "টাইপ করার জন্য কোনো ইনপুট ফিল্ড ফোকাসড নেই।")
    }

    fun performSystemAction(action: String): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ আছে।")

        return when (action.lowercase().trim()) {
            "home", "হোম" -> {
                val ok = service.goHome()
                Pair(ok, if (ok) "হোমস্ক্রিনে ফিরে যাওয়া হয়েছে।" else "হোম অ্যাকশন কার্যকর করা যায়নি।")
            }
            "back", "ব্যাক" -> {
                val ok = service.goBack()
                Pair(ok, if (ok) "এক ধাপ পেছনে ফেরা হয়েছে।" else "ব্যাক অ্যাকশন কার্যকর করা যায়নি।")
            }
            "recents", "রিসেন্ট" -> {
                val ok = service.openRecents()
                Pair(ok, if (ok) "রিসেন্ট অ্যাপস তালিকা ওপেন করা হয়েছে।" else "রিসেন্ট তালিকা খোলা যায়নি।")
            }
            "notifications", "নোটিফিকেশন" -> {
                val ok = service.openNotifications()
                Pair(ok, if (ok) "নোটিফিকেশন বার নামানো হয়েছে।" else "নোটিফিকেশন শেড নামানো যায়নি।")
            }
            "quick_settings", "কুইক সেটিংস", "settings_shade" -> {
                val ok = service.openQuickSettings()
                Pair(ok, if (ok) "কুইক সেটিংস প্যানেল নামানো হয়েছে।" else "কুইক সেটিংস খোলা যায়নি।")
            }
            "scroll_down", "নিচে নামাও" -> {
                val ok = service.scrollForward()
                Pair(ok, if (ok) "স্ক্রিন নিচে স্ক্রোল করা হয়েছে।" else "আর নিচে স্ক্রোল করা যাচ্ছে না।")
            }
            "scroll_up", "উপরে তোলো" -> {
                val ok = service.scrollBackward()
                Pair(ok, if (ok) "স্ক্রিন উপরে স্ক্রোল করা হয়েছে।" else "আর উপরে স্ক্রোল করা যাচ্ছে না।")
            }
            else -> Pair(false, "অজানা সিস্টেম অ্যাকশন: $action")
        }
    }

    fun performSwipe(direction: String): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। দয়া করে সেটিংস থেকে জারভিসকে অনুমতি দিন।")

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return Pair(false, "এই অ্যান্ড্রয়েড ভার্সনে জেসচার সোয়াইপ সাপোর্টেড নয়।")
        }

        val normalized = direction.lowercase().trim()
        val valid = normalized in setOf(
            "up", "উপরে", "scroll_down", "নিচে নামাও",
            "down", "নিচে", "scroll_up", "উপরে তোলো",
            "left", "বামে",
            "right", "ডানে"
        )
        if (!valid) {
            return Pair(false, "অজানা সোয়াইপ দিক: $direction (উদাহরণ: up, down, left, right)")
        }

        service.swipeDirection(normalized)
        return Pair(true, "স্ক্রিন '$direction' সোয়াইপ অ্যাকশন পাঠানো হয়েছে।")
    }

    fun makePhoneCall(context: Context, contactOrNumber: String): Pair<Boolean, String> {
        val clean = contactOrNumber.trim()
        val digits = clean.replace("[^0-9+]".toRegex(), "")

        return try {
            if (digits.length >= 3) {
                // Numeric phone number
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$digits")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$digits")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                if (com.example.permissions.JarvisPermissionManager.hasCallPhonePermission(context)) {
                    context.startActivity(callIntent)
                    Pair(true, "$digits নম্বরে সরাসরি কল দেওয়া হচ্ছে...")
                } else {
                    context.startActivity(dialIntent)
                    Pair(true, "$digits নম্বর ডায়ালারে লোড করা হয়েছে।")
                }
            } else {
                // Name provided (e.g. "রহিম", "John")
                // Search in contacts or launch dialer with search
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)

                // If accessibility is enabled, attempt searching name in dialer
                JarvisAccessibilityService.instance?.let { service ->
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(1200)
                        service.clickElementByText("Search")
                        delay(600)
                        service.inputText(clean)
                    }
                }

                Pair(true, "'$clean'-কে কল করার জন্য ডায়ালার ওপেন করা হয়েছে।")
            }
        } catch (e: Exception) {
            Pair(false, "কল করতে সমস্যা হয়েছে: ${e.localizedMessage}")
        }
    }

    fun inspectCurrentScreen(): String {
        val service = JarvisAccessibilityService.instance
        return service?.extractVisibleScreenSummary()
            ?: "স্ক্রিন পড়ার অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। অনুগ্রহ করে অ্যান্ড্রয়েড সেটিংস থেকে জারভিস অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় করুন।"
    }

    // =================================================================
    // 6. SYSTEM PERMISSION INTENTS (DIRECT NAVIGATION TO EXACT SETTINGS)
    // =================================================================

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            }
        }
    }

    fun openAppNotificationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun openApplicationDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // Checkers
    fun isAccessibilityGranted(context: Context): Boolean =
        JarvisAccessibilityService.isAccessibilityEnabled(context)

    fun isOverlayGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    fun isBatteryOptimizationIgnored(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else true

    private var isTorchOn = false

    fun toggleFlashlight(context: Context, forceState: Boolean? = null): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return false
            val target = forceState ?: !isTorchOn
            cameraManager.setTorchMode(cameraId, target)
            isTorchOn = target
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setVolume(context: Context, percent: Int, streamType: String = "media"): Pair<Boolean, String> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return Pair(false, "অডিও ম্যানেজার পাওয়া যায়নি।")
            val stream = when (streamType.lowercase()) {
                "ring", "রিংটোন" -> android.media.AudioManager.STREAM_RING
                "alarm", "অ্যালার্ম" -> android.media.AudioManager.STREAM_ALARM
                "notification", "নোটিফিকেশন" -> android.media.AudioManager.STREAM_NOTIFICATION
                else -> android.media.AudioManager.STREAM_MUSIC
            }
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            val clamped = percent.coerceIn(0, 100)
            val target = ((clamped / 100f) * maxVol).toInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(stream, target, android.media.AudioManager.FLAG_SHOW_UI)
            val actual = audioManager.getStreamVolume(stream)
            val actualPct = ((actual.toFloat() / maxVol) * 100f).toInt().coerceIn(0, 100)
            val verified = kotlin.math.abs(actual - target) <= 1
            Pair(
                verified,
                if (verified) "ভলিউম $actualPct% (লেভেল $actual/$maxVol) নির্ধারণ ও যাচাই করা হয়েছে।"
                else "ভলিউম কমান্ড পাঠানো হয়েছে, তবে সিস্টেম/DND নীতির কারণে বর্তমান লেভেল $actual/$maxVol ($actualPct%)।"
            )
        } catch (e: Exception) {
            Pair(false, "ভলিউম পরিবর্তন করতে সমস্যা হয়েছে: ${e.localizedMessage}")
        }
    }

    fun openWriteSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallbackIntent)
                    true
                } catch (e: Exception) {
                    try {
                        val generalSettings = Intent(Settings.ACTION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(generalSettings)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
        return true
    }

    fun isWriteSettingsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else true
    }

    fun getBrightness(context: Context): Pair<Int, Int> {
        return try {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val pct = ((raw / 255f) * 100).toInt().coerceIn(0, 100)
            Pair(pct, raw)
        } catch (_: Exception) {
            Pair(50, 128)
        }
    }

    fun setBrightness(context: Context, percent: Int): Pair<Boolean, String> {
        val clamped = percent.coerceIn(0, 100)
        if (!isWriteSettingsGranted(context)) {
            val opened = openWriteSettings(context)
            return Pair(
                false,
                if (opened) {
                    "স্ক্রিন ব্রাইটনেস পরিবর্তন করতে সিস্টেম সেটিংস পরিবর্তনের অনুমতি (WRITE_SETTINGS) প্রয়োজন। সেটিংস উইন্ডো খোলা হয়েছে, অনুগ্রহ করে অনুমতি প্রদান করুন।"
                } else {
                    "স্ক্রিন ব্রাইটনেস পরিবর্তন করতে WRITE_SETTINGS অনুমতি প্রয়োজন।"
                }
            )
        }

        return try {
            val contentResolver = context.contentResolver
            try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            } catch (_: Exception) {}

            val brightnessValue = ((clamped / 100f) * 255).toInt().coerceIn(1, 255)
            val success = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )

            if (success) {
                val verifiedValue = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                Pair(true, "স্ক্রিন ব্রাইটনেস সফলভাবে $clamped% (মান: $verifiedValue/255) নির্ধারণ ও যাচাই করা হয়েছে।")
            } else {
                Pair(false, "সিস্টেম ব্রাইটনেস পরিবর্তন সম্পন্ন করা যায়নি।")
            }
        } catch (e: Exception) {
            Pair(false, "ব্রাইটনেস পরিবর্তন করতে সমস্যা হয়েছে: ${e.localizedMessage}")
        }
    }
}
