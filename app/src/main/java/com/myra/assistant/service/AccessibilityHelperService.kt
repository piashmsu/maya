package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Accessibility helper used by MainViewModel to close the foreground app,
 * scroll, click on text, and type into focused EditTexts.
 */
class AccessibilityHelperService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't react to events; this service is used purely for global actions.
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    fun closeCurrentApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun clickOnText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findAccessibilityNodeInfosByText(text).firstOrNull() ?: return false
        return clickRecursive(target)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findFocusedEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollDown(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    fun scrollUp(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scroll(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        return scrollRecursive(root, action)
    }

    private fun scrollRecursive(node: AccessibilityNodeInfo?, action: Int): Boolean {
        node ?: return false
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) {
            if (scrollRecursive(node.getChild(i), action)) return true
        }
        return false
    }

    private fun clickRecursive(node: AccessibilityNodeInfo?): Boolean {
        var n: AccessibilityNodeInfo? = node ?: return false
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            n = n.parent
        }
        return false
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFocusedEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: AccessibilityHelperService? = null
            internal set

        fun isEnabled(context: Context): Boolean {
            val pkg = context.packageName.lowercase(Locale.ENGLISH)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            if (enabledServices.isEmpty()) return false
            val selfComponent = ComponentName(context, AccessibilityHelperService::class.java)
                .flattenToString().lowercase(Locale.ENGLISH)
            return enabledServices
                .lowercase(Locale.ENGLISH)
                .split(":")
                .any { it.contains(selfComponent) || it.startsWith("$pkg/") }
        }
    }
}
