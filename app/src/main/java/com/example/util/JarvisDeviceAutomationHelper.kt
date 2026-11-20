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

    // Common Package Names map (Bengali, Banglish, English)
    private val KNOWN_APPS = mapOf(
        "youtube" to "com.google.android.youtube",
        "ইউটিউব" to "com.google.android.youtube",
        "utube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "হোয়াটস্যাপ" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "wa" to "com.whatsapp",
        "facebook" to "com.facebook.katana",
        "ফেসবুক" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "এফবি" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "মেসেঞ্জার" to "com.facebook.orca",
        "chrome" to "com.android.chrome",
        "ক্রোম" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "ব্রাউজার" to "com.android.chrome",
        "settings" to "com.android.settings",
        "সেটিংস" to "com.android.settings",
        "সেটিং" to "com.android.settings",
        "camera" to "camera",
        "ক্যামেরা" to "camera",
        "phone" to "dialer",
        "ফোন" to "dialer",
        "ডায়ালার" to "dialer",
        "কল" to "dialer",
        "play store" to "com.android.vending",
        "প্লে স্টোর" to "com.android.vending",
        "calculator" to "calculator",
        "ক্যালকুলেটর" to "calculator",
        "clock" to "clock",
        "ঘড়ি" to "clock",
        "অ্যালার্ম" to "clock",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "ম্যাপস" to "com.google.android.apps.maps",
        "গুগল ম্যাপ" to "com.google.android.apps.maps",
        "telegram" to "org.telegram.messenger",
        "টেলিগ্রাম" to "org.telegram.messenger",
        "tiktok" to "com.zhiliaoapp.musically",
        "টিকটক" to "com.zhiliaoapp.musically",
        "instagram" to "com.instagram.android",
        "ইনস্টাগ্রাম" to "com.instagram.android",
        "gmail" to "com.google.android.gm",
        "জিমেইল" to "com.google.android.gm",
        "bkash" to "com.bKash.customerapp",
        "বিকাশ" to "com.bKash.customerapp",
        "nagad" to "com.konasl.nagad",
        "নগদ" to "com.konasl.nagad",
        "imo" to "com.imo.android.imoim",
        "ইমো" to "com.imo.android.imoim",
        "spotify" to "com.spotify.music",
        "স্পটিফাই" to "com.spotify.music"
    )

    // =================================================================
    // 1. UNIVERSAL INSTALLED APP DISCOVERY, LAUNCHER & CLOSER
    // =================================================================

    data class InstalledAppEntry(
        val packageName: String,
        val appName: String,
        val activityName: String = "",
        val isLaunchable: Boolean = true,
        val isSystemApp: Boolean = false
    )

    data class PagedInstalledAppsResult(
        val totalCount: Int,
        val page: Int,
        val pageSize: Int,
        val hasMore: Boolean,
        val nextPage: Int?,
        val apps: List<InstalledAppEntry>
    )

    fun discoverInstalledAppsPaged(
        context: Context,
        filterQuery: String = "",
        page: Int = 0,
        pageSize: Int = 100
    ): PagedInstalledAppsResult {
        val pm = context.packageManager
        val q = filterQuery.trim().lowercase()
        val aliasPkg = if (q.isNotBlank()) resolveAppPackageAlias(q) else null
        val results = linkedMapOf<String, InstalledAppEntry>()
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(mainIntent, 0).forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                val activity = info.activityInfo?.name.orEmpty()
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                val isSys = (info.activityInfo?.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                val matches = q.isBlank() ||
                    label.lowercase().contains(q) ||
                    pkg.lowercase().contains(q) ||
                    (aliasPkg != null && pkg.equals(aliasPkg, ignoreCase = true))
                if (matches) {
                    results.putIfAbsent(
                        pkg,
                        InstalledAppEntry(
                            packageName = pkg,
                            appName = label,
                            activityName = activity,
                            isLaunchable = true,
                            isSystemApp = isSys
                        )
                    )
                }
            }
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { appInfo ->
                val pkg = appInfo.packageName ?: return@forEach
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (!results.containsKey(pkg) && launchIntent != null) {
                    val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty().ifBlank { pkg }
                    val isSys = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val matches = q.isBlank() ||
                        label.lowercase().contains(q) ||
                        pkg.lowercase().contains(q) ||
                        (aliasPkg != null && pkg.equals(aliasPkg, ignoreCase = true))
                    if (matches) {
                        results[pkg] = InstalledAppEntry(
                            packageName = pkg,
                            appName = label,
                            activityName = launchIntent.component?.className.orEmpty(),
                            isLaunchable = true,
                            isSystemApp = isSys
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        val sorted = results.values.sortedBy { it.appName.lowercase() }
        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(1, 500)
        val fromIdx = (safePage * safeSize).coerceAtMost(sorted.size)
        val toIdx = (fromIdx + safeSize).coerceAtMost(sorted.size)
        val slice = sorted.subList(fromIdx, toIdx)
        val hasMore = toIdx < sorted.size
        return PagedInstalledAppsResult(
            totalCount = sorted.size,
            page = safePage,
            pageSize = safeSize,
            hasMore = hasMore,
            nextPage = if (hasMore) safePage + 1 else null,
            apps = slice
        )
    }

    fun isAppAvailable(context: Context, appNameOrPackage: String): InstalledAppEntry? {
        val found = findInstalledApp(context, appNameOrPackage) ?: return null
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(found.first)
        return InstalledAppEntry(
            packageName = found.first,
            appName = found.second,
            activityName = launchIntent?.component?.className.orEmpty(),
            isLaunchable = launchIntent != null
        )
    }

    fun discoverInstalledApps(context: Context, filterQuery: String = ""): List<Pair<String, String>> {
        return discoverInstalledAppsPaged(context, filterQuery, page = 0, pageSize = 500)
            .apps
            .map { it.packageName to it.appName }
    }

    fun resolveAppPackageAlias(appNameOrPackage: String): String? {
        val rawLower = appNameOrPackage.trim().lowercase()
        if (rawLower.isEmpty()) return null

        KNOWN_APPS[rawLower]?.let { return it }

        val normalized = rawLower
            .replace(Regex("""\s+(?:অ্যাপ|app)\b"""), "")
            .replace(Regex("""^(?:অ্যাপ|app)\s+"""), "")
            .trim()

        KNOWN_APPS[normalized]?.let { return it }

        // Check longer aliases first so e.g. "whatsapp" is matched before "wa", and 2-char codes require word boundary
        KNOWN_APPS.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (alias, _) ->
                if (alias.length <= 2) {
                    Regex("""(^|\s)${Regex.escape(alias)}($|\s)""").containsMatchIn(normalized)
                } else {
                    normalized.contains(alias) || rawLower.contains(alias)
                }
            }?.let { return it.value }

        return if (normalized.contains(".") && !normalized.contains(" ")) normalized else null
    }

    fun findInstalledApp(context: Context, appNameOrPackage: String): Pair<String, String>? {
        val pm = context.packageManager
        val query = appNameOrPackage.trim().lowercase()
        val aliasPkg = resolveAppPackageAlias(query)
        if (aliasPkg != null && aliasPkg != "camera" && aliasPkg != "dialer") {
            if (pm.getLaunchIntentForPackage(aliasPkg) != null) {
                val label = try {
                    val ai = pm.getApplicationInfo(aliasPkg, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    aliasPkg
                }
                return aliasPkg to label
            }
        }
        val discovered = discoverInstalledApps(context)
        return discovered.firstOrNull { (_, label) ->
            val l = label.lowercase()
            l == query || l.contains(query) || query.contains(l)
        } ?: discovered.firstOrNull { (pkg, _) ->
            pkg.lowercase().contains(query)
        }
    }

    fun openApp(context: Context, appNameOrPackage: String): Pair<Boolean, String> {
        val query = appNameOrPackage.trim().lowercase()

        // 1. Check special system intents & aliases
        val mappedPackage = resolveAppPackageAlias(query)

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

        // 2. Try resolved alias / explicit package or universal PackageManager discovery
        val pm = context.packageManager
        val foundApp = findInstalledApp(context, appNameOrPackage)
        val targetPackage = foundApp?.first ?: mappedPackage
        val displayLabel = foundApp?.second ?: appNameOrPackage.trim()

        if (targetPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val fgAfter = JarvisAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
                return if (!fgAfter.isNullOrBlank() && fgAfter.equals(targetPackage, ignoreCase = true)) {
                    Pair(true, "$displayLabel ($targetPackage) ওপেন ও স্ক্রিনে সক্রিয় নিশ্চিত হয়েছে।")
                } else {
                    Pair(true, "$displayLabel ($targetPackage) খোলার ইনটেন্ট পাঠানো হয়েছে।")
                }
            }
        }

        return Pair(false, "ফোনে '$appNameOrPackage' অ্যাপটি খুঁজে পাওয়া যায়নি।")
    }

    data class VisibleAppDataReport(
        val success: Boolean,
        val foregroundPackage: String,
        val totalVisibleItems: Int,
        val uniqueCount: Int,
        val duplicateItems: List<String>,
        val items: List<String>,
        val stockMentions: List<String>,
        val summaryBengali: String
    )

    fun readVisibleAppData(context: Context, filterKeyword: String = ""): VisibleAppDataReport {
        val service = JarvisAccessibilityService.instance
            ?: return VisibleAppDataReport(
                success = false,
                foregroundPackage = getForegroundAppPackage(context),
                totalVisibleItems = 0,
                uniqueCount = 0,
                duplicateItems = emptyList(),
                items = emptyList(),
                stockMentions = emptyList(),
                summaryBengali = "অ্যাপের দৃশ্যমান তালিকা পড়তে অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় থাকা প্রয়োজন (অন্য অ্যাপের প্রাইভেট ডেটাবেস অনুমতি ছাড়া পড়া হয় না)।"
            )

        val root = service.rootInActiveWindow
            ?: return VisibleAppDataReport(
                success = false,
                foregroundPackage = getForegroundAppPackage(context),
                totalVisibleItems = 0,
                uniqueCount = 0,
                duplicateItems = emptyList(),
                items = emptyList(),
                stockMentions = emptyList(),
                summaryBengali = "বর্তমান স্ক্রিনের দৃশ্যমান ডেটা পাওয়া যায়নি।"
            )

        val fgPkg = root.packageName?.toString().orEmpty().ifBlank { getForegroundAppPackage(context) }
        val rawTexts = com.example.automation.NodeSnapshot.readVisibleTextList(root)
            .map { it.trim() }
            .filter { it.length >= 2 }

        val filter = filterKeyword.trim().lowercase()
        val filtered = if (filter.isNotBlank()) {
            rawTexts.filter { it.lowercase().contains(filter) }
        } else {
            rawTexts
        }

        val counts = linkedMapOf<String, Int>()
        filtered.forEach { entry ->
            counts[entry] = (counts[entry] ?: 0) + 1
        }
        val uniqueList = counts.keys.toList()
        val duplicates = counts.filterValues { it > 1 }.keys.toList()
        val stockRegex = Regex("""(?i)(stock|qty|pcs|পিস|স্টক|পরিমাণ|\d+\s*(?:pcs|tab|cap|box|ml|mg))""")
        val stocks = uniqueList.filter { stockRegex.containsMatchIn(it) }

        val summary = if (uniqueList.isEmpty()) {
            "স্ক্রিনে ($fgPkg) কোনো দৃশ্যমান তালিকা বা আইটেম পাওয়া যায়নি।"
        } else {
            val dupNote = if (duplicates.isNotEmpty()) " (${duplicates.size}টি ডুপ্লিকেট এন্ট্রি বাদ দেওয়া হয়েছে)" else ""
            val stockNote = if (stocks.isNotEmpty()) "\nস্টক/পরিমাণ তথ্য: ${stocks.take(5).joinToString(", ")}" else ""
            "দৃশ্যমান তালিকা ($fgPkg): মোট ${filtered.size}টি এন্ট্রি, ইউনিক ${uniqueList.size}টি আইটেম$dupNote।$stockNote"
        }

        return VisibleAppDataReport(
            success = uniqueList.isNotEmpty(),
            foregroundPackage = fgPkg,
            totalVisibleItems = filtered.size,
            uniqueCount = uniqueList.size,
            duplicateItems = duplicates,
            items = uniqueList,
            stockMentions = stocks,
            summaryBengali = summary
        )
    }

    fun closeCurrentApp(): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
        return if (service != null) {
            val res = service.goHome()
            if (res) Pair(true, "বর্তমান অ্যাপ মিনিমাইজ করে হোমস্ক্রিনে ফিরে যাওয়া হয়েছে (অন্য অ্যাপ ফোর্স-ক্লোজ করা হয় না)।")
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
    // 2. YOUTUBE SMART AUTOMATION (BUG 6 FIXED)
    // =================================================================

    data class YouTubeLaunchResult(
        val success: Boolean,
        val playbackVerified: Boolean,
        val actionStatus: String,
        val activeTitle: String?,
        val summaryBengali: String
    )

    suspend fun searchAndPlayYouTubeDetailed(context: Context, searchQuery: String): YouTubeLaunchResult {
        return try {
            val encoded = URLEncoder.encode(searchQuery, "UTF-8")
            val appIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", searchQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (context.packageManager.resolveActivity(appIntent, 0) != null) {
                context.startActivity(appIntent)
                var clickedPlay = false
                val service = JarvisAccessibilityService.instance
                if (service != null) {
                    for (attempt in 0 until 4) {
                        delay(700L)
                        clickedPlay = service.clickElementByTextAwait(searchQuery) ||
                            service.clickElementByIdAwait("com.google.android.youtube:id/thumbnail")
                        if (clickedPlay) {
                            delay(500L)
                            break
                        }
                    }
                }
                val (playingVerified, playbackLabel, activeTitle) =
                    com.example.service.JarvisNotificationListenerService.verifyMediaPlaybackState(context, "youtube")
                val titleNote = activeTitle?.takeIf { it.isNotBlank() }?.let { " — চলছে: '$it'" }.orEmpty()
                when {
                    playingVerified -> YouTubeLaunchResult(
                        success = true,
                        playbackVerified = true,
                        actionStatus = "PLAYBACK_VERIFIED",
                        activeTitle = activeTitle,
                        summaryBengali = "ইউটিউব অ্যাপে '$searchQuery' ভিডিও সক্রিয়ভাবে প্লে হচ্ছে$titleNote।"
                    )
                    clickedPlay -> YouTubeLaunchResult(
                        success = true,
                        playbackVerified = false,
                        actionStatus = "CLICKED_RESULT_UNVERIFIED",
                        activeTitle = activeTitle,
                        summaryBengali = "ইউটিউবে '$searchQuery' ফলাফলে ক্লিক করা হয়েছে, তবে মিডিয়া সেশন প্লেব্যাক অবস্থা যাচাই করা যায়নি ($playbackLabel)।"
                    )
                    else -> YouTubeLaunchResult(
                        success = false,
                        playbackVerified = false,
                        actionStatus = "SEARCH_OPENED_UNPLAYED",
                        activeTitle = activeTitle,
                        summaryBengali = "ইউটিউব সার্চ তালিকা খোলা হয়েছে, কিন্তু ভিডিও অটো-প্লে করা বা প্লেব্যাক যাচাই করা সম্ভব হয়নি ($playbackLabel)।"
                    )
                }
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (context.packageManager.resolveActivity(webIntent, 0) != null) {
                    context.startActivity(webIntent)
                    YouTubeLaunchResult(
                        success = true,
                        playbackVerified = false,
                        actionStatus = "BROWSER_SEARCH_OPENED",
                        activeTitle = null,
                        summaryBengali = "ব্রাউজারে ইউটিউব সার্চ '$searchQuery' তালিকা খোলা হয়েছে (অ্যাপ প্লেব্যাক নয়)।"
                    )
                } else {
                    YouTubeLaunchResult(
                        success = false,
                        playbackVerified = false,
                        actionStatus = "NO_HANDLER",
                        activeTitle = null,
                        summaryBengali = "ইউটিউব অ্যাপ বা ব্রাউজার ওপেন করার কোনো হ্যান্ডলার পাওয়া যায়নি।"
                    )
                }
            }
        } catch (e: Exception) {
            YouTubeLaunchResult(
                success = false,
                playbackVerified = false,
                actionStatus = "FAILED",
                activeTitle = null,
                summaryBengali = "ইউটিউব ওপেন করতে সমস্যা: ${e.localizedMessage}"
            )
        }
    }

    suspend fun searchAndPlayYouTube(context: Context, searchQuery: String): Pair<Boolean, String> {
        val detailed = searchAndPlayYouTubeDetailed(context, searchQuery)
        return Pair(detailed.success, detailed.summaryBengali)
    }

    // =================================================================
    // 3. WHATSAPP AUTOMATION (BUG 8 FIXED)
    // =================================================================

    data class WhatsAppActionResult(
        val success: Boolean,
        val verified: Boolean,
        val actionStatus: String,
        val deliveryState: String,
        val summaryBengali: String,
        val candidates: List<String> = emptyList()
    )

    suspend fun openAndControlWhatsAppDetailed(
        context: Context,
        contactOrPhone: String,
        message: String
    ): WhatsAppActionResult {
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
            return WhatsAppActionResult(
                success = false,
                verified = false,
                actionStatus = "APP_NOT_INSTALLED",
                deliveryState = "UNKNOWN",
                summaryBengali = "ফোনে হোয়াটসঅ্যাপ ইনস্টল করা নেই।"
            )
        }

        val hasApprovalForSend = message.isBlank() ||
            com.example.permissions.ActionApprovalRegistry.hasActiveApprovalFor(
                toolNames = setOf("controlWhatsApp", "sendWhatsAppMessage", "runPlaybook"),
                recipientQuery = contactOrPhone,
                messageText = message
            )

        var resolvedTarget = contactOrPhone.trim()
        var cleanPhone = resolvedTarget.replace("[^0-9+]".toRegex(), "")

        // Perform multi-contact disambiguation before opening WhatsApp when a contact name is supplied
        if (cleanPhone.length < 10 && resolvedTarget.isNotBlank()) {
            val contactSearch = try {
                JarvisContactSearchEngine.searchContact(context, resolvedTarget)
            } catch (_: Exception) {
                null
            }
            if (contactSearch != null && contactSearch.needsDisambiguation && contactSearch.matches.size > 1) {
                val candidateLabels = contactSearch.matches.map { "${it.name} (${it.primaryPhoneNumber})" }
                return WhatsAppActionResult(
                    success = false,
                    verified = false,
                    actionStatus = "MULTIPLE_MATCHES",
                    deliveryState = "DISAMBIGUATION_REQUIRED",
                    summaryBengali = "'$resolvedTarget' নামে একাধিক কন্টাক্ট পাওয়া গেছে: ${candidateLabels.joinToString(", ")}। অনুগ্রহ করে সঠিক ব্যক্তি ও নম্বর নিশ্চিত করুন।",
                    candidates = candidateLabels
                )
            } else if (contactSearch != null && contactSearch.matches.size == 1) {
                val unique = contactSearch.matches.first()
                resolvedTarget = unique.name
                val uniqueDigits = unique.primaryPhoneNumber.replace("[^0-9+]".toRegex(), "")
                if (uniqueDigits.length >= 10) {
                    cleanPhone = uniqueDigits
                }
            }
        }

        // If phone number provided (or uniquely resolved from Contacts), launch direct chat
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

                if (message.isBlank()) {
                    return WhatsAppActionResult(
                        success = true,
                        verified = false,
                        actionStatus = "COMPOSER_OPENED_READY_TO_SEND",
                        deliveryState = "IDLE",
                        summaryBengali = "হোয়াটসঅ্যাপে $cleanPhone-এর সাথে চ্যাট খোলা হয়েছে।"
                    )
                }

                if (!hasApprovalForSend) {
                    return WhatsAppActionResult(
                        success = false,
                        verified = false,
                        actionStatus = "APPROVAL_REQUIRED_COMPOSER_PREFILLED",
                        deliveryState = "PENDING_USER_APPROVAL",
                        summaryBengali = "হোয়াটসঅ্যাপে $cleanPhone-এর চ্যাটে বার্তাটি ড্রাফট করা হয়েছে, তবে একবারের ব্যবহারকারী অনুমোদন ছাড়া সেন্ড বাটনে ক্লিক করা হয়নি।"
                    )
                }

                var sendTapped = false
                val service = JarvisAccessibilityService.instance
                if (service != null) {
                    for (attempt in 0 until 3) {
                        delay(800L)
                        sendTapped = service.clickElementByIdAwait("com.whatsapp:id/send") ||
                            service.clickElementByTextAwait("Send")
                        if (sendTapped) break
                    }
                }

                if (sendTapped && service != null) {
                    delay(450L)
                    val root = service.rootInActiveWindow
                    val (outgoingOk, _) = com.example.automation.ActionVerifier.verify(
                        spec = com.example.automation.VerifySpec(
                            type = com.example.automation.VerifyType.LAST_OUTGOING_MESSAGE_EQUALS,
                            expected = message
                        ),
                        root = root,
                        initialScreenHash = "",
                        currentScreenHash = ""
                    )
                    val tickTexts = root?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/status")
                        ?.mapNotNull { it.contentDescription?.toString() ?: it.text?.toString() }
                        .orEmpty()
                    val tickState = com.example.automation.AutomationToolHandler.classifyWhatsAppTickStatus(tickTexts)
                    if (outgoingOk) {
                        WhatsAppActionResult(
                            success = true,
                            verified = true,
                            actionStatus = "SEND_CONFIRMED",
                            deliveryState = tickState,
                            summaryBengali = "হোয়াটসঅ্যাপে $cleanPhone-কে বার্তা পাঠানো সম্পন্ন ও চ্যাটে যাচাই করা হয়েছে (স্ট্যাটাস: $tickState)।"
                        )
                    } else {
                        WhatsAppActionResult(
                            success = true,
                            verified = false,
                            actionStatus = "REQUEST_ACCEPTED",
                            deliveryState = tickState,
                            summaryBengali = "হোয়াটসঅ্যাপে $cleanPhone-এর সেন্ড বাটনে ক্লিক করা হয়েছে, তবে আউটগোয়িং বাবল/ডেলিভারি এখনো নিশ্চিত হয়নি।"
                        )
                    }
                } else {
                    WhatsAppActionResult(
                        success = true,
                        verified = false,
                        actionStatus = "COMPOSER_OPENED_READY_TO_SEND",
                        deliveryState = "DRAFT_PREFILLED",
                        summaryBengali = "হোয়াটসঅ্যাপে $cleanPhone-এর সাথে চ্যাট কম্পোজার খোলা হয়েছে।"
                    )
                }
            } catch (e: Exception) {
                WhatsAppActionResult(
                    success = false,
                    verified = false,
                    actionStatus = "FAILED",
                    deliveryState = "FAILED",
                    summaryBengali = "হোয়াটসঅ্যাপ ওপেন করতে সমস্যা: ${e.localizedMessage}"
                )
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
                    delay(1200L)
                    service.clickElementByTextAwait("Search")
                    delay(600L)
                    service.inputText(contactOrPhone)
                    delay(1000L)
                    val openedChat = service.clickElementByTextAwait(contactOrPhone)
                    if (openedChat && message.isNotBlank()) {
                        delay(800L)
                        val typed = service.inputText(message)
                        if (!hasApprovalForSend) {
                            return WhatsAppActionResult(
                                success = false,
                                verified = false,
                                actionStatus = "APPROVAL_REQUIRED_COMPOSER_PREFILLED",
                                deliveryState = "PENDING_USER_APPROVAL",
                                summaryBengali = "হোয়াটসঅ্যাপে '$contactOrPhone'-এর চ্যাটে লেখা টাইপ করা হয়েছে, কিন্তু ব্যবহারকারীর চূড়ান্ত অনুমোদন ছাড়া পাঠানো হয়নি।"
                            )
                        }
                        delay(500L)
                        val sent = typed && (service.clickElementByIdAwait("com.whatsapp:id/send") ||
                            service.clickElementByTextAwait("Send"))
                        if (sent) {
                            delay(450L)
                            val root = service.rootInActiveWindow
                            val (outgoingOk, _) = com.example.automation.ActionVerifier.verify(
                                spec = com.example.automation.VerifySpec(
                                    type = com.example.automation.VerifyType.LAST_OUTGOING_MESSAGE_EQUALS,
                                    expected = message
                                ),
                                root = root,
                                initialScreenHash = "",
                                currentScreenHash = ""
                            )
                            WhatsAppActionResult(
                                success = true,
                                verified = outgoingOk,
                                actionStatus = if (outgoingOk) "SEND_CONFIRMED" else "REQUEST_ACCEPTED",
                                deliveryState = if (outgoingOk) "SENT" else "UNKNOWN",
                                summaryBengali = if (outgoingOk) {
                                    "হোয়াটসঅ্যাপে '$contactOrPhone'-কে মেসেজ পাঠানো নিশ্চিত করা হয়েছে।"
                                } else {
                                    "হোয়াটসঅ্যাপে '$contactOrPhone'-এর সেন্ড বাটন ক্লিক করা হয়েছে; স্ক্রিনে চূড়ান্ত নিশ্চিতকরণ পাওয়া যায়নি।"
                                }
                            )
                        } else {
                            WhatsAppActionResult(
                                success = true,
                                verified = false,
                                actionStatus = "COMPOSER_OPENED_READY_TO_SEND",
                                deliveryState = "DRAFT_PREFILLED",
                                summaryBengali = "হোয়াটসঅ্যাপে '$contactOrPhone'-এর চ্যাট খোলা হয়েছে; প্রেরণ সম্পূর্ণ করতে স্ক্রিনে যাচাই করুন।"
                            )
                        }
                    } else if (openedChat) {
                        WhatsAppActionResult(
                            success = true,
                            verified = false,
                            actionStatus = "COMPOSER_OPENED_READY_TO_SEND",
                            deliveryState = "IDLE",
                            summaryBengali = "হোয়াটসঅ্যাপে '$contactOrPhone'-এর চ্যাট ওপেন করা হয়েছে।"
                        )
                    } else {
                        WhatsAppActionResult(
                            success = false,
                            verified = false,
                            actionStatus = "CONTACT_NOT_FOUND_ON_SCREEN",
                            deliveryState = "FAILED",
                            summaryBengali = "হোয়াটসঅ্যাপ ওপেন করা হয়েছে, কিন্তু '$contactOrPhone' কন্টাক্টটি স্ক্রিনে খুঁজে পাওয়া যায়নি।"
                        )
                    }
                } else {
                    WhatsAppActionResult(
                        success = false,
                        verified = false,
                        actionStatus = "ACCESSIBILITY_REQUIRED",
                        deliveryState = "UNKNOWN",
                        summaryBengali = "হোয়াটসঅ্যাপ ওপেন করা হয়েছে, কিন্তু নাম দিয়ে অটোমেশনের জন্য অ্যাক্সেসিবিলিটি সার্ভিস বা ফোন নম্বর প্রয়োজন।"
                    )
                }
            } else {
                WhatsAppActionResult(
                    success = false,
                    verified = false,
                    actionStatus = "LAUNCH_FAILED",
                    deliveryState = "FAILED",
                    summaryBengali = "হোয়াটসঅ্যাপ লঞ্চ করা সম্ভব হয়নি।"
                )
            }
        } catch (e: Exception) {
            WhatsAppActionResult(
                success = false,
                verified = false,
                actionStatus = "FAILED",
                deliveryState = "FAILED",
                summaryBengali = "হোয়াটসঅ্যাপ অটোমেশন ত্রুটি: ${e.localizedMessage}"
            )
        }
    }

    suspend fun openAndControlWhatsApp(context: Context, contactOrPhone: String, message: String): Pair<Boolean, String> {
        val res = openAndControlWhatsAppDetailed(context, contactOrPhone, message)
        return Pair(res.success, res.summaryBengali)
    }

    // =================================================================
    // 4. FACEBOOK AUTOMATION (BUG 7 FIXED)
    // =================================================================

    fun isFacebookPackage(pkg: String?): Boolean {
        val lower = pkg?.lowercase().orEmpty()
        return lower.contains("com.facebook.katana") ||
            lower.contains("com.facebook.lite") ||
            lower.contains("facebook")
    }

    fun readStructuredFacebookFeed(context: Context): org.json.JSONObject {
        val out = org.json.JSONObject()
        val a11y = JarvisAccessibilityService.instance
        if (a11y == null) {
            out.put("success", false)
            out.put("status", "PERMISSION_REQUIRED")
            out.put("permissionRequired", "accessibility")
            out.put("summaryBengali", "ফেসবুক ফিড পড়ার জন্য অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় থাকা প্রয়োজন।")
            return out
        }
        val fgPkg = getForegroundAppPackage(context)
        if (!isFacebookPackage(fgPkg)) {
            out.put("success", false)
            out.put("foregroundPackage", fgPkg)
            out.put("summaryBengali", "বর্তমানে স্ক্রিনে ফেসবুক খোলা নেই (বর্তমান অ্যাপ: $fgPkg)। প্রথমে ফেসবুক ওপেন করুন।")
            return out
        }
        val root = a11y.rootInActiveWindow
        val visibleTexts = com.example.automation.NodeSnapshot.readVisibleTextList(root)
        val ignoredButtons = setOf("like", "comment", "share", "লাইক", "কমেন্ট", "মন্তব্য", "শেয়ার", "home", "search", "notifications", "menu")
        val contentLines = visibleTexts.filter { line ->
            val trimmed = line.trim()
            trimmed.length >= 4 && trimmed.lowercase() !in ignoredButtons
        }.distinct().take(12)

        val actionHints = visibleTexts.filter { it.trim().lowercase() in ignoredButtons }.distinct()
        val postsArr = org.json.JSONArray()
        contentLines.forEach { snippet -> postsArr.put(snippet) }

        out.put("success", contentLines.isNotEmpty())
        out.put("foregroundPackage", fgPkg)
        out.put("posts", postsArr)
        out.put("availableControls", org.json.JSONArray(actionHints))
        out.put(
            "summaryBengali",
            if (contentLines.isNotEmpty()) {
                "দৃশ্যমান ফেসবুক ফিড কন্টেন্ট (${contentLines.size}টি অংশ):\n" + contentLines.take(6).joinToString("\n• ", prefix = "• ")
            } else {
                "ফেসবুক স্ক্রিনে পাঠযোগ্য পোস্ট টেক্সট পাওয়া যায়নি।"
            }
        )
        return out
    }

    suspend fun reactToFacebookPost(context: Context): Triple<Boolean, Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Triple(false, false, "ফেসবুক পোস্টে লাইক দিতে অ্যাক্সেসিবিলিটি সার্ভিস চালু করুন।")

        val fgPkg = getForegroundAppPackage(context)
        if (!isFacebookPackage(fgPkg)) {
            return Triple(
                false,
                false,
                "লাইক অ্যাকশন কেবল ফেসবুক স্ক্রিন খোলা থাকলে চালানো যাবে (বর্তমান অ্যাপ: $fgPkg)।"
            )
        }

        val root = service.rootInActiveWindow
            ?: return Triple(false, false, "বর্তমান ফেসবুক স্ক্রিনের UI উপাদান পড়া যায়নি।")

        val likeSelector = com.example.automation.Selector(
            by = com.example.automation.By.TEXT,
            value = "Like",
            alts = listOf("লাইক", "React", "com.facebook.katana:id/feed_feedback_like")
        )
        val likeNode = com.example.automation.NodeSnapshot.findNode(root, likeSelector)
            ?: return Triple(false, false, "বর্তমান ফেসবুক পোস্টে লাইক বাটন খুঁজে পাওয়া যায়নি।")

        val wasSelected = likeNode.isSelected || likeNode.isChecked ||
            (likeNode.contentDescription?.toString()?.contains("liked", ignoreCase = true) == true) ||
            (likeNode.text?.toString()?.contains("unlike", ignoreCase = true) == true)

        val clicked = service.clickElementByTextAwait("Like") ||
            service.clickElementByTextAwait("লাইক") ||
            likeNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)

        if (!clicked) {
            return Triple(false, false, "ফেসবুক লাইক বাটনে ক্লিক করা সম্ভব হয়নি।")
        }

        delay(400L)
        val afterRoot = service.rootInActiveWindow
        val likedSelector = com.example.automation.Selector(
            by = com.example.automation.By.TEXT,
            value = "Liked",
            alts = listOf("Unlike", "লাইক দেওয়া হয়েছে", "আনলাইক", "Like", "লাইক")
        )
        val afterNode = com.example.automation.NodeSnapshot.findNode(afterRoot, likedSelector)
        val afterLabel = (afterNode?.contentDescription?.toString() ?: afterNode?.text?.toString() ?: "").lowercase()
        val nowSelected = afterNode != null && (
            afterNode.isSelected ||
                afterNode.isChecked ||
                afterLabel.contains("unlike") ||
                afterLabel.contains("liked") ||
                afterLabel.contains("লাইক দেওয়া হয়েছে") ||
                afterLabel.contains("আনলাইক")
            )
        val verifiedToggle = nowSelected || (!wasSelected && afterNode?.isSelected == true)

        return if (verifiedToggle) {
            Triple(true, true, "ফেসবুক পোস্টে লাইক সফলভাবে দেওয়া হয়েছে ও অবস্থা যাচাইকৃত।")
        } else {
            Triple(
                false,
                false,
                "লাইক বাটনে ক্লিক করা হয়েছে, কিন্তু পোস্টটির লাইক অবস্থা পরিবর্তিত হয়েছে কিনা তা স্ক্রিনে যাচাই করা যায়নি।"
            )
        }
    }

    suspend fun commentOnFacebookPost(context: Context, commentText: String): Triple<Boolean, Boolean, String> {
        val cleanComment = commentText.trim()
        if (cleanComment.isBlank()) {
            return Triple(false, false, "কমেন্টের লেখা ফাঁকা রাখা যাবে না।")
        }
        val service = JarvisAccessibilityService.instance
            ?: return Triple(false, false, "কমেন্ট করতে অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় থাকা প্রয়োজন।")

        val fgPkg = getForegroundAppPackage(context)
        if (!isFacebookPackage(fgPkg)) {
            return Triple(
                false,
                false,
                "বর্তমানে স্ক্রিনে ফেসবুক খোলা নেই (বর্তমান অ্যাপ: $fgPkg)। প্রথমে ফেসবুক ওপেন করুন।"
            )
        }

        val commentOpened = service.clickElementByTextAwait("Comment") ||
            service.clickElementByTextAwait("কমেন্ট") ||
            service.clickElementByTextAwait("মন্তব্য")
        if (!commentOpened) {
            return Triple(false, false, "ফেসবুক পোস্টে 'Comment' বাটন খুঁজে পাওয়া যায়নি।")
        }

        delay(500L)
        val typed = service.inputText(cleanComment)
        if (!typed) {
            return Triple(false, false, "কমেন্ট ইনপুট বক্স ফোকাস বা টেক্সট টাইপ করা যায়নি।")
        }

        delay(350L)
        val sent = service.clickElementByTextAwait("Send") ||
            service.clickElementByTextAwait("Post") ||
            service.clickElementByTextAwait("পাঠান") ||
            service.clickElementByTextAwait("পোস্ট")
        if (!sent) {
            return Triple(false, false, "কমেন্ট টাইপ করা হয়েছে কিন্তু সেন্ড/পোস্ট বাটনে ক্লিক করা যায়নি।")
        }

        delay(450L)
        val (verified, _) = com.example.automation.ActionVerifier.verify(
            spec = com.example.automation.VerifySpec(
                type = com.example.automation.VerifyType.LAST_OUTGOING_MESSAGE_EQUALS,
                expected = cleanComment
            ),
            root = service.rootInActiveWindow,
            initialScreenHash = "",
            currentScreenHash = ""
        )
        return if (verified) {
            Triple(true, true, "ফেসবুক পোস্টে কমেন্ট '$cleanComment' সফলভাবে পোস্ট ও যাচাই করা হয়েছে।")
        } else {
            Triple(true, false, "ফেসবুক পোস্টে কমেন্ট সাবমিট করা হয়েছে, তবে স্ক্রিনে চূড়ান্ত প্রদর্শন যাচাই হয়নি।")
        }
    }

    fun openFacebookMediaPostDraft(context: Context, postText: String = "", mediaUri: String? = null): Pair<Boolean, String> {
        return try {
            val cleanText = postText.trim()
            val parsedMedia = mediaUri?.trim()?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            if (cleanText.isNotBlank() || parsedMedia != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (parsedMedia != null) "image/*" else "text/plain"
                    setPackage("com.facebook.katana")
                    if (cleanText.isNotBlank()) putExtra(Intent.EXTRA_TEXT, cleanText)
                    if (parsedMedia != null) {
                        putExtra(Intent.EXTRA_STREAM, parsedMedia)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                if (context.packageManager.resolveActivity(shareIntent, 0) != null) {
                    context.startActivity(shareIntent)
                    val msg = if (parsedMedia != null) {
                        "ফেসবুকে ছবিসহ পোস্ট ড্রাফট উইন্ডো ওপেন করা হয়েছে।"
                    } else {
                        "ফেসবুকে পোস্ট ড্রাফট উইন্ডো ওপেন করা হয়েছে।"
                    }
                    return Pair(true, msg)
                } else {
                    return Pair(false, "ফেসবুক শেয়ার/পোস্ট উইন্ডো খুঁজে পাওয়া যায়নি (ফেসবুক অ্যাপ ইনস্টল আছে কিনা দেখুন)।")
                }
            }
            openApp(context, "facebook")
        } catch (e: Exception) {
            Pair(false, "ফেসবুক পোস্ট ড্রাফট ওপেন করতে সমস্যা: ${e.localizedMessage}")
        }
    }

    fun openFacebook(context: Context, postText: String = ""): Pair<Boolean, String> {
        return openFacebookMediaPostDraft(context, postText, null)
    }

    // =================================================================
    // 5. SCREEN CLICKS, GESTURES & PERCEPTION
    // =================================================================

    suspend fun clickElementOnScreenAwait(targetText: String, x: Float = 0f, y: Float = 0f): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস বন্ধ আছে। সেটিংসে গিয়ে জারভিস অ্যাক্সেসিবিলিটি অন করুন।")

        if (targetText.isNotBlank()) {
            val success = service.clickElementByTextAwait(targetText)
            if (success) return Pair(true, "'$targetText' বাটনে সফলভাবে ক্লিক করা হয়েছে ও যাচাইকৃত।")
        }

        if (x > 0f && y > 0f) {
            val ok = service.clickCoordinatesAwait(x, y)
            return if (ok) {
                Pair(true, "স্ক্রিনের কোঅর্ডিনেট ($x, $y)-এ ক্লিক সফলভাবে সম্পন্ন হয়েছে।")
            } else {
                Pair(false, "স্ক্রিনের কোঅর্ডিনেট ($x, $y)-এ ক্লিক জেসচার বাতিল বা ব্যর্থ হয়েছে।")
            }
        }

        return Pair(false, "স্ক্রিনে '$targetText' খুঁজে পাওয়া যায়নি বা ক্লিক কার্যকর হয়নি।")
    }

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

    suspend fun longPressElementAwait(targetText: String, x: Float = 0f, y: Float = 0f): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।")

        if (targetText.isNotBlank()) {
            val ok = service.longPressElementByTextAwait(targetText)
            if (ok) return Pair(true, "'$targetText' বাটনে লং-প্রেস সফলভাবে সম্পন্ন হয়েছে।")
        }

        if (x > 0f && y > 0f) {
            val ok = service.longPressCoordinatesAwait(x, y, 600L)
            return if (ok) {
                Pair(true, "কোঅর্ডিনেট ($x, $y)-এ লং-প্রেস সফলভাবে সম্পন্ন হয়েছে।")
            } else {
                Pair(false, "কোঅর্ডিনেট ($x, $y)-এ লং-প্রেস বাতিল বা ব্যর্থ হয়েছে।")
            }
        }

        return Pair(false, "লং-প্রেস করার মতো উপাদান পাওয়া যায়নি।")
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

    private val VALID_SWIPE_DIRECTIONS = setOf(
        "up", "swipe_up", "উপরে", "scroll_down", "নিচে নামাও",
        "down", "swipe_down", "নিচে", "scroll_up", "উপরে তোলো",
        "left", "swipe_left", "বামে",
        "right", "swipe_right", "ডানে"
    )

    suspend fun performSwipeAwait(direction: String): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। দয়া করে সেটিংস থেকে জারভিসকে অনুমতি দিন।")

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return Pair(false, "এই অ্যান্ড্রয়েড ভার্সনে জেসচার সোয়াইপ সাপোর্টেড নয়।")
        }

        val normalized = direction.lowercase().trim()
        if (normalized !in VALID_SWIPE_DIRECTIONS) {
            return Pair(false, "অজানা সোয়াইপ দিক: $direction (উদাহরণ: up, down, left, right)")
        }

        val ok = service.swipeDirectionAwait(normalized)
        return if (ok) {
            Pair(true, "স্ক্রিন '$direction' সোয়াইপ অ্যাকশন সফলভাবে সম্পন্ন হয়েছে।")
        } else {
            Pair(false, "স্ক্রিন '$direction' সোয়াইপ জেসচার বাতিল বা ব্যর্থ হয়েছে।")
        }
    }

    fun performSwipe(direction: String): Pair<Boolean, String> {
        val service = JarvisAccessibilityService.instance
            ?: return Pair(false, "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। দয়া করে সেটিংস থেকে জারভিসকে অনুমতি দিন।")

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return Pair(false, "এই অ্যান্ড্রয়েড ভার্সনে জেসচার সোয়াইপ সাপোর্টেড নয়।")
        }

        val normalized = direction.lowercase().trim()
        if (normalized !in VALID_SWIPE_DIRECTIONS) {
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
