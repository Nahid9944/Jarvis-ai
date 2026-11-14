package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.Locale

data class NodeInfo(
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val bounds: Rect,
    val childCount: Int
)

object NodeSnapshot {

    fun computeScreenHash(root: AccessibilityNodeInfo?, currentPackage: String = ""): String {
        if (root == null) return "EMPTY_SCREEN"
        val sb = StringBuilder()
        sb.append(currentPackage).append(";")

        val collected = mutableListOf<String>()
        collectTopNodeSignatures(root, collected, maxNodes = 25)
        sb.append(collected.joinToString("|"))

        return sha1(sb.toString())
    }

    private fun collectTopNodeSignatures(
        node: AccessibilityNodeInfo,
        outList: MutableList<String>,
        maxNodes: Int
    ) {
        if (outList.size >= maxNodes) return
        val id = node.viewIdResourceName ?: ""
        val txt = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString() ?: ""

        if (id.isNotBlank() || txt.isNotBlank() || desc.isNotBlank()) {
            outList.add("$cls:$id:$txt:$desc")
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child != null) {
                collectTopNodeSignatures(child, outList, maxNodes)
                if (outList.size >= maxNodes) return
            }
        }
    }

    fun findNode(root: AccessibilityNodeInfo?, selector: Selector): AccessibilityNodeInfo? {
        if (root == null) return null

        // 1. If within container specified, resolve container first
        val searchRoot = if (selector.within != null) {
            findNode(root, selector.within) ?: return null
        } else {
            root
        }

        // 2. Try primary value
        var found = searchSingleCriteria(searchRoot, selector.by, selector.value, selector)
        if (found != null) return found

        // 3. Try alternatives in order
        for (alt in selector.alts) {
            found = searchSingleCriteria(searchRoot, selector.by, alt, selector)
            if (found != null) return found

            // Also try alt as text / desc if by was ID
            if (selector.by == By.ID) {
                val byText = searchSingleCriteria(searchRoot, By.TEXT, alt, selector)
                if (byText != null) return byText
                val byDesc = searchSingleCriteria(searchRoot, By.DESC, alt, selector)
                if (byDesc != null) return byDesc
            }
        }

        return null
    }

    private fun searchSingleCriteria(
        root: AccessibilityNodeInfo,
        by: By,
        query: String,
        selector: Selector
    ): AccessibilityNodeInfo? {
        val matches = mutableListOf<AccessibilityNodeInfo>()

        when (by) {
            By.ID -> {
                val list = root.findAccessibilityNodeInfosByViewId(query)
                if (!list.isNullOrEmpty()) {
                    matches.addAll(list)
                }
            }
            By.TEXT -> {
                val list = root.findAccessibilityNodeInfosByText(query)
                if (!list.isNullOrEmpty()) {
                    matches.addAll(list)
                }
            }
            By.DESC, By.REGEX, By.CLASS_INDEX -> {
                collectNodesByPredicate(root, matches) { node ->
                    matchesPredicate(node, by, query)
                }
            }
        }

        // Filter enabled and visible if required
        val filtered = matches.filter { node ->
            if (selector.requireEnabled && !node.isEnabled) return@filter false
            if (selector.requireVisible) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) return@filter false
            }
            true
        }

        if (filtered.isEmpty()) return null

        val idx = selector.index ?: 0
        return if (idx in filtered.indices) filtered[idx] else filtered.firstOrNull()
    }

    private fun matchesPredicate(node: AccessibilityNodeInfo, by: By, query: String): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString() ?: ""

        return when (by) {
            By.DESC -> desc.contains(query, ignoreCase = true)
            By.REGEX -> {
                try {
                    val regex = Regex(query, RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(text) || regex.containsMatchIn(desc)
                } catch (e: Exception) {
                    false
                }
            }
            By.CLASS_INDEX -> {
                if (query.contains(":")) {
                    val parts = query.split(":")
                    cls.equals(parts[0], ignoreCase = true)
                } else {
                    cls.equals(query, ignoreCase = true)
                }
            }
            else -> false
        }
    }

    private fun collectNodesByPredicate(
        node: AccessibilityNodeInfo,
        outList: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (predicate(node)) {
            outList.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            if (child != null) {
                collectNodesByPredicate(child, outList, predicate)
            }
        }
    }

    fun readVisibleTextList(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val list = mutableListOf<String>()
        collectAllVisibleStrings(root, list)
        return list.distinct()
    }

    private fun collectAllVisibleStrings(node: AccessibilityNodeInfo, outList: MutableList<String>) {
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrBlank() && txt.length > 1) {
            outList.add(txt)
        }
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length > 1 && desc != txt) {
            outList.add(desc)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                collectAllVisibleStrings(child, outList)
            }
        }
    }

    private fun sha1(input: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
}
