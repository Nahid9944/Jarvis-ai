package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.JarvisAccessibilityService
import com.example.service.JarvisScreenCaptureService
import com.example.util.JarvisScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

data class ScreenUiElement(
    val id: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val resourceId: String = "",
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val isSelected: Boolean = false,
    val isFocusable: Boolean = false,
    val isPassword: Boolean = false,
    val stateDescription: String = "",
    val hintText: String = "",
    val bounds: Rect = Rect(),
    val centerNormX: Float = 0f,
    val centerNormY: Float = 0f,
    val depth: Int = 0
)

data class ScreenContext(
    val timestamp: Long = System.currentTimeMillis(),
    val foregroundPackage: String = "",
    val foregroundActivityOrTitle: String = "",
    val currentBrowserUrl: String? = null,
    val isSettingsScreen: Boolean = false,
    val settingsSection: String? = null,
    val visibleHeadings: List<String> = emptyList(),
    val visibleTexts: List<String> = emptyList(),
    val clickableElements: List<ScreenUiElement> = emptyList(),
    val inputFields: List<ScreenUiElement> = emptyList(),
    val switchesAndStates: Map<String, Boolean> = emptyMap(),
    val dialogTitle: String? = null,
    val allElements: List<ScreenUiElement> = emptyList(),
    val accessibilityAvailable: Boolean = false,
    val mediaProjectionAvailable: Boolean = false,
    val screenshotCaptured: Boolean = false,
    val summaryBengali: String = ""
)

object ScreenPerceptionEngine {

    private val CARD_REGEX = Pattern.compile("""\b(?:\d{4}[ -]?){3}\d{4}\b""")
    private val OTP_REGEX = Pattern.compile("""\b(?:OTP|code|verification code|পিন|কোড)(?:\s+(?:is|হলো|হচ্ছে))?[:\s]+(\d{4,6})\b""", Pattern.CASE_INSENSITIVE)

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "org.chromium.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser"
    )

    private val BROWSER_URL_RES_IDS = setOf(
        "url_bar",
        "location_bar_edit_text",
        "toolbar",
        "url_bar_title",
        "search_box_text"
    )

    /**
     * Sanitizes strings by masking potential credit cards, bank account numbers, or OTP codes.
     */
    fun sanitizeText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var sanitized = CARD_REGEX.matcher(input).replaceAll("[REDACTED_CARD]")
        sanitized = OTP_REGEX.matcher(sanitized).replaceAll("[REDACTED_OTP]")
        return sanitized.trim()
    }

    /**
     * Extracts structured ScreenContext from Accessibility tree and system display state.
     */
    fun inspectCurrentScreen(context: Context, sanitize: Boolean = true): ScreenContext {
        val accessibilityService = JarvisAccessibilityService.instance
        if (accessibilityService == null) {
            return ScreenContext(
                foregroundPackage = "UNKNOWN",
                accessibilityAvailable = false,
                mediaProjectionAvailable = JarvisScreenCaptureService.isRunning(),
                summaryBengali = "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয় নেই। স্ক্রিনের অভ্যন্তরীণ উপাদান রিড করতে Accessibility চালু করুন।"
            )
        }

        val root = accessibilityService.rootInActiveWindow
        if (root == null) {
            val pkg = JarvisAccessibilityService.currentForegroundApp.value.ifBlank { "UNKNOWN" }
            return ScreenContext(
                foregroundPackage = pkg,
                accessibilityAvailable = true,
                mediaProjectionAvailable = JarvisScreenCaptureService.isRunning(),
                summaryBengali = "স্ক্রিন বর্তমানে লক বা কোনো উইন্ডো নোড অ্যাক্সেসযোগ্য নয়।"
            )
        }

        val displayMetrics = context.resources.displayMetrics
        val screenW = displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = displayMetrics.heightPixels.coerceAtLeast(1)

        val elements = mutableListOf<ScreenUiElement>()
        walkNodeTree(root, 0, screenW, screenH, sanitize, elements)

        val pkg = root.packageName?.toString() ?: JarvisAccessibilityService.currentForegroundApp.value
        val isSettings = pkg.contains("settings", ignoreCase = true)

        var detectedUrl: String? = null
        if (BROWSER_PACKAGES.any { pkg.contains(it, ignoreCase = true) }) {
            detectedUrl = extractBrowserUrl(elements)
        }

        var detectedSettingsSection: String? = null
        if (isSettings) {
            detectedSettingsSection = extractSettingsSection(elements)
        }

        val visibleHeadings = elements.filter {
            it.depth <= 2 && it.text.isNotBlank() && it.text.length <= 40 && !it.isClickable
        }.map { it.text }.distinct()

        val visibleTexts = elements.mapNotNull {
            val t = it.text.ifBlank { it.contentDescription }
            if (t.isNotBlank() && t.length > 1) t else null
        }.distinct()

        val clickable = elements.filter { it.isClickable }
        val inputFields = elements.filter { it.isEditable }

        val switches = mutableMapOf<String, Boolean>()
        elements.filter { it.isCheckable }.forEach { el ->
            val label = el.text.ifBlank { el.contentDescription }.ifBlank { el.resourceId }
            if (label.isNotBlank()) {
                switches[label] = el.isChecked
            }
        }

        // Dialog detection
        val dialogTitle = if (elements.any { it.text.equals("OK", true) || it.text.equals("Cancel", true) || it.text.equals("Allow", true) || it.text.equals("বাতিল", true) }) {
            visibleHeadings.firstOrNull()
        } else null

        val summaryBn = buildString {
            append("বর্তমান অ্যাপ: $pkg। ")
            if (!detectedUrl.isNullOrBlank()) {
                append("ব্রাউজার পেইজ: $detectedUrl। ")
            }
            if (!detectedSettingsSection.isNullOrBlank()) {
                append("সেটিংস বিভাগ: $detectedSettingsSection। ")
            }
            if (clickable.isNotEmpty()) {
                append("${clickable.size}টি ক্লিকযোগ্য বোতাম/উপাদান সনাক্ত হয়েছে। ")
            }
            if (inputFields.isNotEmpty()) {
                append("${inputFields.size}টি ইনপুট ফিল্ড রয়েছে। ")
            }
            if (switches.isNotEmpty()) {
                append("সুইচ ও টগল: ${switches.map { "${it.key}=${if (it.value) "চালু" else "বন্ধ"}" }.joinToString(", ")}।")
            }
        }

        return ScreenContext(
            foregroundPackage = pkg,
            foregroundActivityOrTitle = visibleHeadings.firstOrNull() ?: pkg,
            currentBrowserUrl = detectedUrl,
            isSettingsScreen = isSettings,
            settingsSection = detectedSettingsSection,
            visibleHeadings = visibleHeadings,
            visibleTexts = visibleTexts.take(40),
            clickableElements = clickable,
            inputFields = inputFields,
            switchesAndStates = switches,
            dialogTitle = dialogTitle,
            allElements = elements,
            accessibilityAvailable = true,
            mediaProjectionAvailable = JarvisScreenCaptureService.isRunning() || JarvisScreenCaptureManager.hasMediaProjectionConsent.value,
            screenshotCaptured = false,
            summaryBengali = summaryBn
        )
    }

    private fun walkNodeTree(
        node: AccessibilityNodeInfo?,
        depth: Int,
        screenW: Int,
        screenH: Int,
        sanitize: Boolean,
        outList: MutableList<ScreenUiElement>
    ) {
        if (node == null || depth > 15) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val rawText = node.text?.toString() ?: ""
        val rawDesc = node.contentDescription?.toString() ?: ""
        val isPass = node.isPassword

        val finalText = when {
            isPass -> "[PROTECTED_PASSWORD]"
            sanitize -> sanitizeText(rawText)
            else -> rawText
        }

        val finalDesc = when {
            isPass -> "[PROTECTED_PASSWORD]"
            sanitize -> sanitizeText(rawDesc)
            else -> rawDesc
        }

        val normX = if (screenW > 0) bounds.exactCenterX() / screenW.toFloat() else 0f
        val normY = if (screenH > 0) bounds.exactCenterY() / screenH.toFloat() else 0f

        val resId = node.viewIdResourceName ?: ""
        val classNameStr = node.className?.toString() ?: ""
        val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString() ?: ""
        } else ""

        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString() ?: ""
        } else ""

        if (finalText.isNotBlank() || finalDesc.isNotBlank() || node.isClickable || node.isEditable || node.isCheckable) {
            outList.add(
                ScreenUiElement(
                    id = resId,
                    text = finalText,
                    contentDescription = finalDesc,
                    className = classNameStr,
                    resourceId = resId,
                    isClickable = node.isClickable,
                    isEnabled = node.isEnabled,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    isSelected = node.isSelected,
                    isFocusable = node.isFocusable,
                    isPassword = isPass,
                    stateDescription = stateDesc,
                    hintText = hint,
                    bounds = bounds,
                    centerNormX = normX.coerceIn(0f, 1f),
                    centerNormY = normY.coerceIn(0f, 1f),
                    depth = depth
                )
            )
        }

        for (i in 0 until node.childCount) {
            walkNodeTree(node.getChild(i), depth + 1, screenW, screenH, sanitize, outList)
        }
    }

    /**
     * Converts a ScreenContext into the structured JSON schema for Gemini reasoning
     */
    fun toStructuredJson(context: ScreenContext): JSONObject {
        val root = JSONObject()
        root.put("foregroundApp", context.foregroundPackage)
        root.put("screenTitle", context.foregroundActivityOrTitle)
        root.put("isSettingsScreen", context.isSettingsScreen)
        if (!context.settingsSection.isNullOrBlank()) {
            root.put("settingsSection", context.settingsSection)
        }
        if (!context.currentBrowserUrl.isNullOrBlank()) {
            root.put("browserUrl", context.currentBrowserUrl)
        }
        if (!context.dialogTitle.isNullOrBlank()) {
            root.put("dialogTitle", context.dialogTitle)
        }

        val visibleElementsArr = JSONArray()
        context.allElements.forEach { el ->
            val obj = JSONObject()
            if (el.text.isNotBlank()) obj.put("text", el.text)
            if (el.contentDescription.isNotBlank()) obj.put("contentDescription", el.contentDescription)
            val typeStr = if (el.className.isNotBlank()) el.className.substringAfterLast('.') else "View"
            obj.put("type", typeStr)
            if (el.resourceId.isNotBlank()) obj.put("resourceId", el.resourceId)
            obj.put("clickable", el.isClickable)
            obj.put("enabled", el.isEnabled)
            obj.put("editable", el.isEditable)
            obj.put("scrollable", el.isScrollable)
            obj.put("focusable", el.isFocusable)
            if (el.isCheckable) {
                obj.put("checkable", true)
                obj.put("checked", el.isChecked)
            }
            if (el.isSelected) obj.put("selected", true)
            if (el.stateDescription.isNotBlank()) obj.put("stateDescription", el.stateDescription)
            if (el.hintText.isNotBlank()) obj.put("hintText", el.hintText)
            obj.put("bounds", JSONArray(listOf(el.bounds.left, el.bounds.top, el.bounds.right, el.bounds.bottom)))
            obj.put("centerNorm", JSONArray(listOf(el.centerNormX, el.centerNormY)))
            visibleElementsArr.put(obj)
        }
        root.put("visibleElements", visibleElementsArr)
        root.put("accessibilityAvailable", context.accessibilityAvailable)
        root.put("mediaProjectionAvailable", context.mediaProjectionAvailable)
        root.put("screenshotCaptured", context.screenshotCaptured)
        root.put("summaryBengali", context.summaryBengali)
        return root
    }

    private fun extractBrowserUrl(elements: List<ScreenUiElement>): String? {
        val urlElement = elements.firstOrNull { el ->
            BROWSER_URL_RES_IDS.any { el.resourceId.contains(it, ignoreCase = true) } ||
                    el.contentDescription.contains("address", ignoreCase = true) ||
                    el.text.startsWith("http://") || el.text.startsWith("https://") ||
                    (el.text.contains(".") && !el.text.contains(" ") && el.text.length > 4)
        }
        return urlElement?.text?.ifBlank { urlElement.contentDescription }
    }

    private fun extractSettingsSection(elements: List<ScreenUiElement>): String? {
        val heading = elements.firstOrNull { it.depth <= 2 && it.text.isNotBlank() && !it.isClickable }
        return heading?.text
    }

    /**
     * Unified Multimodal Perception combining Accessibility UI elements and Visual Bitmap analysis.
     */
    suspend fun perceiveScreen(
        context: Context,
        apiKey: String? = null,
        userQuery: String = ""
    ): Pair<ScreenContext, ScreenVisualAnalysis?> = withContext(Dispatchers.Default) {
        val screenContext = inspectCurrentScreen(context, sanitize = true)

        val cleanKey = apiKey?.trim() ?: ""
        val canAnalyzeVisual = cleanKey.isNotBlank() && cleanKey != "MY_GEMINI_API_KEY"

        var visualAnalysis: ScreenVisualAnalysis? = null
        var bitmapCaptured = false

        if (canAnalyzeVisual) {
            val (bitmap, analysis) = ScreenVisualAnalyzer.captureAndAnalyze(context, cleanKey, userQuery)
            if (bitmap != null) {
                bitmapCaptured = true
                visualAnalysis = analysis
            }
        }

        val enrichedContext = screenContext.copy(
            screenshotCaptured = bitmapCaptured,
            summaryBengali = if (visualAnalysis != null && visualAnalysis.summaryBengali.isNotBlank()) {
                "${screenContext.summaryBengali}\nভিজ্যুয়াল এআই পর্যালোচনা: ${visualAnalysis.summaryBengali}"
            } else {
                screenContext.summaryBengali
            }
        )

        Pair(enrichedContext, visualAnalysis)
    }
}
