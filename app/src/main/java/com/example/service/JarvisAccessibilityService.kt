package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

class JarvisAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var screenSummaryJob: Job? = null

    companion object {
        private var _instance: JarvisAccessibilityService? = null
        val instance: JarvisAccessibilityService? get() = _instance

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _currentForegroundApp = MutableStateFlow("")
        val currentForegroundApp: StateFlow<String> = _currentForegroundApp.asStateFlow()

        private val _currentScreenText = MutableStateFlow("")
        val currentScreenText: StateFlow<String> = _currentScreenText.asStateFlow()

        /**
         * Listener hook for demonstration learning / skill recording
         */
        var eventListener: ((AccessibilityEvent) -> Unit)? = null

        @androidx.annotation.VisibleForTesting
        internal var rootWindowOverrideForTest: (() -> AccessibilityNodeInfo?)? = null

        @androidx.annotation.VisibleForTesting
        internal fun setInstanceForTest(service: JarvisAccessibilityService?) {
            _instance = service
            _isServiceActive.value = (service != null)
            if (service == null) {
                rootWindowOverrideForTest = null
            }
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedComponentName = "${context.packageName}/${JarvisAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponentName, ignoreCase = true) ||
                    componentName.contains(context.packageName)
                ) {
                    return true
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        _isServiceActive.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        eventListener?.invoke(event)
        val pkg = event.packageName?.toString() ?: ""
        if (pkg.isNotBlank() && pkg != packageName) {
            _currentForegroundApp.value = pkg
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            screenSummaryJob?.cancel()
            screenSummaryJob = serviceScope.launch {
                delay(300) // Debounce rapid layout changes
                val summary = extractVisibleScreenSummary()
                _currentScreenText.value = summary
            }
        }
    }

    override fun onInterrupt() {
        // Required callback
    }

    override fun onDestroy() {
        _instance = null
        _isServiceActive.value = false
        screenSummaryJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // =================================================================
    // 1. GESTURE & CLICK DISPATCH (Async + Suspend Await Callback)
    // =================================================================

    suspend fun dispatchGestureAwait(gesture: GestureDescription, timeoutMs: Long = 2000L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }, null)
                if (!dispatched && cont.isActive) {
                    cont.resumeWith(Result.success(false))
                }
            }
        } ?: false
    }

    suspend fun clickCoordinatesAwait(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val clickPath = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    suspend fun swipeAwait(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(swipePath, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture, durationMs + 1500L)
    }

    suspend fun swipeDirectionAwait(direction: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        return when (direction.lowercase().trim()) {
            "up", "swipe_up", "উপরে", "scroll_down", "নিচে নামাও" ->
                swipeAwait(centerX, centerY + 350f, centerX, centerY - 350f, 250)
            "down", "swipe_down", "নিচে", "scroll_up", "উপরে তোলো" ->
                swipeAwait(centerX, centerY - 350f, centerX, centerY + 350f, 250)
            "left", "swipe_left", "বামে" ->
                swipeAwait(width * 0.85f, centerY, width * 0.15f, centerY, 250)
            "right", "swipe_right", "ডানে" ->
                swipeAwait(width * 0.15f, centerY, width * 0.85f, centerY, 250)
            else -> false
        }
    }

    suspend fun longPressCoordinatesAwait(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val clickPath = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture, durationMs + 1500L)
    }

    fun clickCoordinates(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete?.invoke(false)
            return
        }

        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete?.invoke(false)
            }
        }, null)
        if (!dispatched) onComplete?.invoke(false)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300, onComplete: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete?.invoke(false)
            return
        }

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(swipePath, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete?.invoke(false)
            }
        }, null)
        if (!dispatched) onComplete?.invoke(false)
    }

    fun swipeDirection(direction: String, onComplete: ((Boolean) -> Unit)? = null) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        when (direction.lowercase().trim()) {
            "up", "swipe_up", "উপরে", "scroll_down", "নিচে নামাও" -> {
                swipe(centerX, centerY + 350f, centerX, centerY - 350f, 250, onComplete)
            }
            "down", "swipe_down", "নিচে", "scroll_up", "উপরে তোলো" -> {
                swipe(centerX, centerY - 350f, centerX, centerY + 350f, 250, onComplete)
            }
            "left", "swipe_left", "বামে" -> {
                swipe(width * 0.85f, centerY, width * 0.15f, centerY, 250, onComplete)
            }
            "right", "swipe_right", "ডানে" -> {
                swipe(width * 0.15f, centerY, width * 0.85f, centerY, 250, onComplete)
            }
            else -> onComplete?.invoke(false)
        }
    }

    fun swipe(direction: String): Boolean {
        return try {
            swipeDirection(direction)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun longPressCoordinates(x: Float, y: Float, durationMs: Long = 600L, onComplete: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete?.invoke(false)
            return
        }

        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete?.invoke(false)
            }
        }, null)
        if (!dispatched) onComplete?.invoke(false)
    }

    fun clickNormalizedCoordinates(normX: Float, normY: Float, onComplete: ((Boolean) -> Unit)? = null) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val clampedX = (normX * width).coerceIn(0f, width)
        val clampedY = (normY * height).coerceIn(0f, height)
        clickCoordinates(clampedX, clampedY, onComplete)
    }

    // =================================================================
    // 2. ELEMENT SEARCH & CLICK
    // =================================================================

    fun getActiveRootWindowSafe(): AccessibilityNodeInfo? = rootWindowOverrideForTest?.invoke() ?: rootInActiveWindow

    suspend fun clickElementByTextAwait(targetText: String): Boolean {
        val root = getActiveRootWindowSafe() ?: return false
        val systemMatched = try { root.findAccessibilityNodeInfosByText(targetText) } catch (_: Exception) { null }
        val matchedNodes = if (!systemMatched.isNullOrEmpty()) systemMatched else findNodesRecursive(root) { n ->
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            t.contains(targetText, ignoreCase = true) || d.contains(targetText, ignoreCase = true)
        }
        if (matchedNodes.isEmpty()) return false

        for (node in matchedNodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                current = current.parent
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                return clickCoordinatesAwait(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }
        return false
    }

    fun clickElementByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByText(targetText)
        if (matchedNodes.isNullOrEmpty()) return false

        for (node in matchedNodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                current = current.parent
            }
            // Fallback: Click center coordinates of node if not marked clickable
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                clickCoordinates(bounds.exactCenterX(), bounds.exactCenterY())
                return true
            }
        }
        return false
    }

    fun inputText(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        // If no input is focused, search for any editable node
        val editableNode = findFirstEditableNode(root)
        if (editableNode != null) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    suspend fun longPressElementByTextAwait(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByText(targetText)
        if (matchedNodes.isNullOrEmpty()) return false

        for (node in matchedNodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isLongClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (clicked) return true
                }
                current = current.parent
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                return longPressCoordinatesAwait(bounds.exactCenterX(), bounds.exactCenterY(), 600L)
            }
        }
        return false
    }

    fun longPressElementByText(targetText: String, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByText(targetText)
        if (matchedNodes.isNullOrEmpty()) {
            onComplete?.invoke(false)
            return false
        }

        for (node in matchedNodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isLongClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (clicked) {
                        onComplete?.invoke(true)
                        return true
                    }
                }
                current = current.parent
            }
            // Fallback: Long press center coordinates of node (600ms)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                longPressCoordinates(bounds.exactCenterX(), bounds.exactCenterY(), 600L, onComplete)
                return true
            }
        }
        onComplete?.invoke(false)
        return false
    }

    fun clearText(): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root) ?: return false
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clearArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
    }

    fun clearInputAndType(textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root) ?: return false

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // 1. Clear existing text
        val clearArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

        // 2. Set new text
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findFirstEditableNode(child)
            if (res != null) return res
        }
        return null
    }

    private fun findNodesRecursive(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (node == null || depth > 16) return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        if (predicate(node)) out.add(node)
        for (i in 0 until node.childCount) {
            out.addAll(findNodesRecursive(node.getChild(i), depth + 1, predicate))
        }
        return out
    }

    suspend fun clickElementByIdAwait(viewId: String): Boolean {
        val root = getActiveRootWindowSafe() ?: return false
        val systemMatched = try { root.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Exception) { null }
        val matchedNodes = if (!systemMatched.isNullOrEmpty()) systemMatched else findNodesRecursive(root) { n ->
            val vid = n.viewIdResourceName.orEmpty()
            vid.equals(viewId, ignoreCase = true) || vid.endsWith("/$viewId", ignoreCase = true)
        }
        if (matchedNodes.isEmpty()) return false

        for (node in matchedNodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                current = current.parent
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                return clickCoordinatesAwait(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }
        return false
    }

    fun clickElementById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (matchedNodes.isNullOrEmpty()) return false

        for (node in matchedNodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && current.isEnabled) {
                    val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                current = current.parent
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                clickCoordinates(bounds.exactCenterX(), bounds.exactCenterY())
                return true
            }
        }
        return false
    }

    fun findAccessibilityNodeByTextOrId(query: String): AccessibilityNodeInfo? {
        val root = getActiveRootWindowSafe() ?: return null
        if (query.isBlank()) return null
        val byId = try { root.findAccessibilityNodeInfosByViewId(query) } catch (_: Exception) { null }
        if (!byId.isNullOrEmpty()) return byId.first()
        val byText = try { root.findAccessibilityNodeInfosByText(query) } catch (_: Exception) { null }
        if (!byText.isNullOrEmpty()) return byText.first()
        return null
    }

    fun findUiElement(query: String): AccessibilityNodeInfo? = findAccessibilityNodeByTextOrId(query)

    suspend fun waitForElement(
        query: String,
        timeoutMs: Long = 3000L,
        pollIntervalMs: Long = 200L
    ): AccessibilityNodeInfo? {
        if (query.isBlank()) return null
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(200L, 15_000L)
        val step = pollIntervalMs.coerceIn(50L, 1000L)
        while (true) {
            findUiElement(query)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            kotlinx.coroutines.delay(step)
        }
    }

    fun verifyScreenState(
        expectedPackage: String? = null,
        expectedText: String? = null,
        forbiddenText: String? = null
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!expectedPackage.isNullOrBlank()) {
            val fgPkg = root.packageName?.toString().orEmpty()
            if (!fgPkg.contains(expectedPackage.trim(), ignoreCase = true)) {
                return false
            }
        }
        if (!expectedText.isNullOrBlank()) {
            if (findUiElement(expectedText) == null) return false
        }
        if (!forbiddenText.isNullOrBlank()) {
            if (findUiElement(forbiddenText) != null) return false
        }
        return true
    }

    // =================================================================
    // 3. GLOBAL SYSTEM ACTIONS
    // =================================================================

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else false
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root)
        return scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    fun scrollBackward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root)
        return scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
    }

    suspend fun scrollHorizontalAwait(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root)
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val performed = scrollable?.performAction(action) ?: false
        if (performed) return true

        val direction = if (forward) "left" else "right"
        return swipeDirectionAwait(direction)
    }

    fun scrollHorizontal(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root)
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val performed = scrollable?.performAction(action) ?: false
        if (performed) return true

        val direction = if (forward) "left" else "right"
        swipeDirection(direction)
        return true
    }

    fun selectNodeText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByText(targetText)
        if (matchedNodes.isNullOrEmpty()) return false
        val node = matchedNodes.firstOrNull() ?: return false
        val textLength = node.text?.length ?: 0
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findFirstScrollableNode(child)
            if (res != null) return res
        }
        return null
    }

    // =================================================================
    // 4. SCREEN PERCEPTION & OCR
    // =================================================================

    fun extractVisibleScreenSummary(): String {
        val root = rootInActiveWindow ?: return "Screen is currently unavailable or locked."
        val textList = mutableListOf<String>()
        val packageName = root.packageName?.toString() ?: "Unknown App"
        textList.add("Current App: $packageName")

        collectNodeTexts(root, textList)
        return if (textList.size <= 1) {
            "App: $packageName (No readable text elements found on current screen)"
        } else {
            textList.take(60).joinToString("\n")
        }
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrBlank()) {
            val clickableSuffix = if (node.isClickable) " [Button/Clickable]" else ""
            list.add("• $text$clickableSuffix")
        } else if (!desc.isNullOrBlank()) {
            val clickableSuffix = if (node.isClickable) " [Button/Clickable]" else ""
            list.add("• $desc$clickableSuffix")
        }

        for (i in 0 until node.childCount) {
            collectNodeTexts(node.getChild(i), list)
        }
    }

    // Capture screenshot on Android 11+
    fun captureCurrentScreenBitmap(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val executor = Executor { command -> command.run() }
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                screenshotResult.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            onResult(bitmap)
                        } catch (e: Exception) {
                            onResult(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(null)
                    }
                }
            )
        } else {
            onResult(null)
        }
    }
}
