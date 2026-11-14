package com.example.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisNotificationListenerService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class AutomationToolHandler(private val context: Context) {

    suspend fun runPlaybook(playbookId: String, args: JSONObject): JSONObject {
        val result = JSONObject()
        val playbook = PlaybookStore.get(playbookId)
        if (playbook == null) {
            result.put("success", false)
            result.put("code", "PLAYBOOK_NOT_FOUND")
            result.put("messageBn", "প্লেবুক '$playbookId' খুঁজে পাওয়া যায়নি।")
            return result
        }

        val paramMap = mutableMapOf<String, String>()
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k != "playbookId") {
                paramMap[k] = args.optString(k, "")
            }
        }

        val runResult = PlaybookRunner.execute(context, playbook, paramMap)
        result.put("success", runResult.success)
        result.put("code", runResult.code)
        result.put("messageBn", runResult.messageBn)
        result.put("verified", runResult.verified)
        result.put("executionTimeMs", runResult.executionTimeMs)
        result.put("stepCount", runResult.stepRecords.size)

        val stepsArr = JSONArray()
        runResult.stepRecords.forEach { s ->
            val sObj = JSONObject()
            sObj.put("stepIndex", s.stepIndex)
            sObj.put("description", s.stepDescription)
            sObj.put("success", s.success)
            sObj.put("error", s.errorMessage ?: "")
            sObj.put("timeMs", s.executionTimeMs)
            stepsArr.put(sObj)
        }
        result.put("steps", stepsArr)
        return result
    }

    fun listPlaybooks(appPackage: String? = null): JSONObject {
        val result = JSONObject()
        val list = if (!appPackage.isNullOrBlank()) PlaybookStore.getByApp(appPackage) else PlaybookStore.getAll()
        val arr = JSONArray()

        list.forEach { p ->
            val pObj = JSONObject()
            pObj.put("id", p.id)
            pObj.put("titleBn", p.titleBn)
            pObj.put("appPackage", p.appPackage)
            pObj.put("risk", p.risk.name)
            val paramsArr = JSONArray()
            p.params.forEach { param ->
                val paramObj = JSONObject()
                paramObj.put("name", param.name)
                paramObj.put("type", param.type.name)
                paramObj.put("required", param.required)
                paramsArr.put(paramObj)
            }
            pObj.put("params", paramsArr)
            arr.put(pObj)
        }

        result.put("success", true)
        result.put("count", list.size)
        result.put("playbooks", arr)
        result.put("messageBn", "মোট ${list.size}টি প্লেবুক পাওয়া গেছে।")
        return result
    }

    fun getAppVersion(appPackage: String): JSONObject {
        val result = JSONObject()
        return try {
            val pm = context.packageManager
            val pInfo = pm.getPackageInfo(appPackage, 0)
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            result.put("success", true)
            result.put("installed", true)
            result.put("appPackage", appPackage)
            result.put("versionName", pInfo.versionName ?: "")
            result.put("versionCode", vCode)
            result.put("messageBn", "$appPackage সংস্করণ: ${pInfo.versionName} (কোড: $vCode)")
            result
        } catch (e: PackageManager.NameNotFoundException) {
            result.put("success", true)
            result.put("installed", false)
            result.put("appPackage", appPackage)
            result.put("messageBn", "অ্যাপটি ($appPackage) এই ফোনে ইনস্টল নেই।")
            result
        }
    }

    suspend fun waitForNode(selectorQuery: String, byStr: String = "TEXT", timeoutMs: Long = 4000): JSONObject {
        val result = JSONObject()
        val by = when (byStr.uppercase()) {
            "ID" -> By.ID
            "DESC" -> By.DESC
            "REGEX" -> By.REGEX
            "CLASS_INDEX" -> By.CLASS_INDEX
            else -> By.TEXT
        }
        val selector = Selector(by = by, value = selectorQuery)

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start <= timeoutMs) {
            val root = JarvisAccessibilityService.instance?.rootInActiveWindow
            val found = NodeSnapshot.findNode(root, selector)
            if (found != null) {
                result.put("success", true)
                result.put("found", true)
                result.put("text", found.text?.toString() ?: "")
                result.put("id", found.viewIdResourceName ?: "")
                result.put("messageBn", "'$selectorQuery' নোডটি স্ক্রিনে দৃশ্যমান পাওয়া গেছে।")
                return result
            }
            delay(120)
        }

        result.put("success", false)
        result.put("found", false)
        result.put("messageBn", "$timeoutMs ms অপেক্ষা করার পরও '$selectorQuery' নোড পাওয়া যায়নি।")
        return result
    }

    fun assertScreen(expectedPackage: String = "", expectedText: String = ""): JSONObject {
        val result = JSONObject()
        val currentForeground = JarvisAccessibilityService.currentForegroundApp.value
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow
        val visibleTexts = NodeSnapshot.readVisibleTextList(root)

        val pkgMatches = expectedPackage.isBlank() || currentForeground.contains(expectedPackage, ignoreCase = true)
        val textMatches = expectedText.isBlank() || visibleTexts.any { it.contains(expectedText, ignoreCase = true) }

        val success = pkgMatches && textMatches
        result.put("success", success)
        result.put("currentForegroundApp", currentForeground)
        result.put("pkgMatches", pkgMatches)
        result.put("textMatches", textMatches)
        result.put("messageBn", if (success) "স্ক্রিন যাচাই সফল: কাঙ্ক্ষিত অ্যাপ্লিকেশন সক্রিয় আছে।" else "স্ক্রিন অমিল: বর্তমান অ্যাপ: $currentForeground")
        return result
    }

    fun readVisibleList(): JSONObject {
        val result = JSONObject()
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow
        val list = NodeSnapshot.readVisibleTextList(root)
        val arr = JSONArray()
        list.forEach { arr.put(it) }

        val active = JarvisAccessibilityService.instance != null
        result.put("success", active)
        result.put("count", list.size)
        result.put("items", arr)
        result.put(
            "summaryBengali",
            if (active) "স্ক্রিনে দৃশ্যমান মোট ${list.size}টি টেক্সট উপাদান পাওয়া গেছে:\n" + list.take(15).joinToString("\n• ", prefix = "• ")
            else "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় না থাকায় দৃশ্যমান তালিকা পড়া যায়নি।"
        )
        return result
    }

    suspend fun scrollToText(targetText: String, direction: String = "DOWN", maxSwipes: Int = 6): JSONObject {
        val result = JSONObject()
        val a11y = JarvisAccessibilityService.instance
        if (a11y == null) {
            result.put("success", false)
            result.put("messageBn", "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।")
            return result
        }

        val dirStr = if (direction.equals("UP", ignoreCase = true)) "scroll_up" else "scroll_down"
        val selector = Selector(By.TEXT, targetText, alts = listOf(targetText))

        for (i in 0 until maxSwipes) {
            val root = a11y.rootInActiveWindow
            if (NodeSnapshot.findNode(root, selector) != null) {
                result.put("success", true)
                result.put("swipes", i)
                result.put("messageBn", "$i বার স্ক্রল করার পর '$targetText' পাওয়া গেছে।")
                return result
            }
            a11y.swipeDirection(dirStr)
            delay(500)
        }

        result.put("success", false)
        result.put("swipes", maxSwipes)
        result.put("messageBn", "$maxSwipes বার স্ক্রল করার পরও '$targetText' খুঁজে পাওয়া যায়নি।")
        return result
    }

    fun isAllowedDeepLinkScheme(uriStr: String): Boolean {
        val clean = uriStr.trim()
        if (clean.isBlank()) return false
        val scheme = try {
            Uri.parse(clean).scheme?.lowercase()?.trim()
        } catch (_: Exception) {
            null
        } ?: return false
        return scheme in ALLOWED_DEEP_LINK_SCHEMES
    }

    fun openDeepLink(uriStr: String, appPackage: String = ""): JSONObject {
        val result = JSONObject()
        val cleanUri = uriStr.trim()
        if (!isAllowedDeepLinkScheme(cleanUri)) {
            result.put("success", false)
            result.put("status", "BLOCKED_UNSAFE_URI")
            result.put("uri", cleanUri)
            result.put("messageBn", "নিরাপত্তার কারণে এই URI স্কিমটি ব্লক করা হয়েছে: '$cleanUri' (অনুমোদিত: https, http, tel, mailto, geo, whatsapp, fb, vnd.youtube)")
            return result
        }
        return try {
            val uri = Uri.parse(cleanUri)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                if (appPackage.isNotBlank()) setPackage(appPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                result.put("success", false)
                result.put("uri", cleanUri)
                result.put("messageBn", "এই ডিপ লিংকটি খোলার মতো উপযুক্ত অ্যাপ ডিভাইসে পাওয়া যায়নি: $cleanUri")
                return result
            }
            context.startActivity(intent)
            result.put("success", true)
            result.put("uri", cleanUri)
            result.put("messageBn", "ডিপ লিংক সফলভাবে ওপেন করা হয়েছে: $cleanUri")
            result
        } catch (e: Exception) {
            result.put("success", false)
            result.put("error", e.localizedMessage)
            result.put("messageBn", "ডিপ লিংক ওপেন করতে ত্রুটি: ${e.localizedMessage}")
            result
        }
    }

    companion object {
        val ALLOWED_DEEP_LINK_SCHEMES = setOf(
            "https",
            "http",
            "tel",
            "mailto",
            "geo",
            "whatsapp",
            "fb",
            "vnd.youtube"
        )
    }

    fun mediaControl(action: String, seekMs: Long? = null): JSONObject {
        val result = JSONObject()
        val (ok, mediaTitle) = JarvisNotificationListenerService.sendMediaAction(context, action, seekMs)
        result.put("success", ok)
        result.put("action", action)
        result.put("mediaTitle", mediaTitle ?: "")
        result.put("messageBn", if (ok) "মিডিয়া কমান্ড ($action) সফলভাবে পাঠানো হয়েছে (${mediaTitle ?: "চলমান মিডিয়া"})।" else "মিডিয়া সেশন নিয়ন্ত্রণ করা যায়নি।")
        return result
    }

    fun replyFromNotification(packageFilter: String, sender: String? = null, replyText: String): JSONObject {
        val result = JSONObject()
        val ok = JarvisNotificationListenerService.sendDirectReply(context, packageFilter, sender, replyText)
        result.put("success", ok)
        result.put("package", packageFilter)
        result.put("replyText", replyText)
        result.put("messageBn", if (ok) "নোটিফিকেশন থেকে সরাসরি উত্তর পাঠানো সম্পন্ন হয়েছে: \"$replyText\"" else "নির্দিষ্ট অ্যাপের নোটিফিকেশনে রিপ্লাই পাঠানোর মতো সক্রিয় নোটিফিকেশন পাওয়া যায়নি।")
        return result
    }

    fun shareViaIntent(targetPackage: String, text: String, mimeType: String = "text/plain"): JSONObject {
        val result = JSONObject()
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_TEXT, text)
                if (targetPackage.isNotBlank()) setPackage(targetPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            result.put("success", true)
            result.put("targetPackage", targetPackage)
            result.put("messageBn", "$targetPackage-এ শেয়ার উইন্ডো ওপেন করা হয়েছে।")
            result
        } catch (e: Exception) {
            result.put("success", false)
            result.put("error", e.localizedMessage)
            result.put("messageBn", "শেয়ার ইনটেন্ট ওপেন করতে সমস্যা: ${e.localizedMessage}")
            result
        }
    }

    fun getPlaybookHealth(playbookId: String? = null): JSONObject {
        val result = JSONObject()
        if (playbookId != null && playbookId.isNotBlank()) {
            val health = PlaybookStore.getHealth(playbookId)
            result.put("success", true)
            result.put("playbookId", health.playbookId)
            result.put("totalRuns", health.totalRuns)
            result.put("successRuns", health.successRuns)
            result.put("failedRuns", health.failedRuns)
            result.put("successRatePercent", health.successRatePercent)
            result.put("isHealthy", health.isHealthy)
            result.put("lastError", health.lastError ?: "")
            result.put("messageBn", "প্লেবুক '${health.playbookId}' স্বাস্থ্য স্কোর: ${health.successRatePercent}% (${health.successRuns}/${health.totalRuns} সফল)")
        } else {
            val allHealth = PlaybookStore.getAllHealth()
            val arr = JSONArray()
            allHealth.forEach { h ->
                val hObj = JSONObject()
                hObj.put("playbookId", h.playbookId)
                hObj.put("runs", h.totalRuns)
                hObj.put("rate", h.successRatePercent)
                hObj.put("healthy", h.isHealthy)
                arr.put(hObj)
            }
            result.put("success", true)
            result.put("count", allHealth.size)
            result.put("healthList", arr)
            result.put("messageBn", "সকল অটোমেশন প্লেবুকের স্বাস্থ্য প্রতিবেদন তৈরি করা হয়েছে।")
        }
        return result
    }

    fun validatePlaybook(playbookId: String): JSONObject {
        val result = JSONObject()
        val playbook = PlaybookStore.get(playbookId)
        if (playbook == null) {
            result.put("success", false)
            result.put("valid", false)
            result.put("messageBn", "প্লেবুক '$playbookId' পাওয়া যায়নি।")
            return result
        }

        val appVersionObj = getAppVersion(playbook.appPackage)
        val isAppInstalled = appVersionObj.optBoolean("installed", false)
        val isA11yActive = JarvisAccessibilityService.instance != null
        val isNotifActive = JarvisNotificationListenerService.isConnected()

        val valid = isAppInstalled && isA11yActive
        result.put("success", valid)
        result.put("valid", valid)
        result.put("playbookId", playbook.id)
        result.put("appInstalled", isAppInstalled)
        result.put("accessibilityActive", isA11yActive)
        result.put("notificationListenerActive", isNotifActive)
        result.put("riskLevel", playbook.risk.name)
        result.put("messageBn", if (valid) "প্লেবুক ড্রাই-রান সফল: অ্যাপ ও সিস্টেম অ্যাক্সেস প্রস্তুত রয়েছে।" else "ড্রাই-রান সতর্কতা: অ্যাপ ইনস্টল বা অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।")
        return result
    }

    fun verifyWhatsAppCallState(): JSONObject {
        val result = JSONObject()
        val currentForeground = JarvisAccessibilityService.currentForegroundApp.value
        val isWhatsApp = currentForeground.contains("whatsapp", ignoreCase = true)
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow
        val visibleTexts = NodeSnapshot.readVisibleTextList(root)

        val callMarkers = listOf("End call", "কল শেষ করুন", "Mute", "Speaker", "মাইক্রোফোন", "স্পিকার", "Ringing", "Connecting", "কলিং...", "সংযুক্ত হচ্ছে...")
        val hasCallMarker = visibleTexts.any { text ->
            callMarkers.any { marker -> text.contains(marker, ignoreCase = true) }
        } || NodeSnapshot.findNode(
            root,
            Selector(By.ID, "com.whatsapp:id/end_call_btn", alts = listOf("com.whatsapp:id/call_control_container"))
        ) != null

        val isCallActive = isWhatsApp && hasCallMarker
        val isVideo = visibleTexts.any { it.contains("video", ignoreCase = true) || it.contains("ভিডিও", ignoreCase = true) }

        result.put("success", true)
        result.put("isCallActive", isCallActive)
        result.put("isWhatsAppForeground", isWhatsApp)
        result.put("callType", if (isVideo) "VIDEO" else "VOICE")
        result.put("messageBn", if (isCallActive) "হোয়াটসঅ্যাপ কল বর্তমানে সক্রিয় রয়েছে।" else "স্ক্রিনে কোনো সক্রিয় হোয়াটসঅ্যাপ কল শনাক্ত হয়নি।")
        return result
    }

    suspend fun endWhatsAppCall(): JSONObject {
        val result = JSONObject()
        val a11y = JarvisAccessibilityService.instance
        if (a11y == null) {
            result.put("success", false)
            result.put("messageBn", "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই।")
            return result
        }

        val clicked = a11y.clickElementById("com.whatsapp:id/end_call_btn") ||
                a11y.clickElementByText("End call") ||
                a11y.clickElementByText("কল শেষ করুন") ||
                a11y.clickElementByText("Hang up")

        if (clicked) {
            delay(350)
            val stillInCall = verifyWhatsAppCallState().optBoolean("isCallActive", false)
            result.put("success", !stillInCall)
            result.put("actionStatus", if (!stillInCall) "VERIFIED_ENDED" else "REQUEST_ACCEPTED")
            result.put("verified", !stillInCall)
            result.put("messageBn", if (!stillInCall) "হোয়াটসঅ্যাপ কল সফলভাবে কেটে দেওয়া হয়েছে।" else "কল শেষ করার বাটন ক্লিক করা হয়েছে, তবে কলটি বন্ধ হয়েছে কিনা নিশ্চিত হওয়া যায়নি।")
        } else {
            result.put("success", false)
            result.put("actionStatus", "END_CALL_CONTROL_NOT_FOUND")
            result.put("verified", false)
            result.put("messageBn", "স্ক্রিনে হোয়াটসঅ্যাপের কল শেষ করার বাটন ('End call') খুঁজে পাওয়া যায়নি।")
        }
        return result
    }
}
