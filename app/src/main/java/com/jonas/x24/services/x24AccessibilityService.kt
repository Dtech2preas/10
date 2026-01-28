package com.jonas.x24.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class x24AccessibilityService : AccessibilityService() {

    companion object {
        var instance: x24AccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("x24Access", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We can listen to events here (e.g. notifications, window changes)
    }

    override fun onInterrupt() {
        Log.d("x24Access", "Service Interrupted")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // --- Actions ---

    fun performGlobal(action: Int): Boolean {
        return performGlobalAction(action)
    }

    fun click(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(builder.build(), null, null)
    }

    fun scroll(direction: String) {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        val startY: Float
        val endY: Float

        if (direction == "UP") {
            // "Scroll Down" content -> Swipe UP
            startY = centerY + 300
            endY = centerY - 300
        } else {
            // "Scroll Up" content -> Swipe DOWN
            startY = centerY - 300
            endY = centerY + 300
        }

        val path = Path()
        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)

        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 300))
        dispatchGesture(builder.build(), null, null)
    }

    // --- Screen Context & Interaction ---

    fun getScreenContext(): String {
        val root = rootInActiveWindow ?: return "No screen content available."
        val builder = StringBuilder()
        traverseNode(root, builder)
        return builder.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (node == null) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
            val label = text ?: desc
            val type = when {
                isEditable -> "Input"
                isClickable -> "Button"
                else -> "Text"
            }
            // Limit length to avoid blowing up context
            if ((label?.length ?: 0) < 50) {
                 builder.append("[$type] $label\n")
            }
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), builder)
        }
    }

    fun clickNodeByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)

        // 1. Exact/Contains Match
        for (node in nodes) {
            if (performClick(node)) return true
        }

        // 2. Manual Traversal (fallback for complex hierarchies)
        return traverseAndClick(root, targetText)
    }

    private fun traverseAndClick(node: AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true)) {
            if (performClick(node)) return true
        }

        for (i in 0 until node.childCount) {
            if (traverseAndClick(node.getChild(i), target)) return true
        }
        return false
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Try parent
        val parent = node.parent
        if (parent != null) {
            return performClick(parent)
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focus != null && focus.isEditable) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }
}
