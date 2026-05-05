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

    fun swipe(direction: String) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        var startX = centerX
        var startY = centerY
        var endX = centerX
        var endY = centerY

        when (direction) {
            "UP" -> {
                startY = centerY + 300
                endY = centerY - 300
            }
            "DOWN" -> {
                startY = centerY - 300
                endY = centerY + 300
            }
            "LEFT" -> {
                startX = centerX + 300
                endX = centerX - 300
            }
            "RIGHT" -> {
                startX = centerX - 300
                endX = centerX + 300
            }
        }

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 300))
        dispatchGesture(builder.build(), null, null)
    }

    fun longClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
        dispatchGesture(builder.build(), null, null)
    }

    fun longClickText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)

        var clicked = false
        if (nodes != null) {
            for (node in nodes) {
                if (!clicked && performLongClick(node)) {
                    clicked = true
                }
                node.recycle()
            }
        }
        if (clicked) {
            root.recycle()
            return true
        }

        val result = traverseAndLongClick(root, targetText)
        root.recycle()
        return result
    }

    private fun traverseAndLongClick(node: AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true)) {
            if (performLongClick(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (traverseAndLongClick(child, target)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }
        return false
    }

    private fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isLongClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        val parent = node.parent
        if (parent != null) {
            val result = performLongClick(parent)
            parent.recycle()
            return result
        }
        return false
    }


    fun getScreenContext(): String {
        val root = rootInActiveWindow ?: return "No screen content available."
        val builder = StringBuilder()
        traverseNode(root, builder)
        root.recycle()
        return builder.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (node == null) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || isScrollable) {
            val label = text ?: desc ?: "Scrollable Area"
            val type = when {
                isEditable -> "Input"
                isClickable -> "Button"
                isScrollable -> "Scrollable"
                else -> "Text"
            }

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            // Limit length to avoid blowing up context
            if (label.length < 50) {
                 builder.append("[$type] $label (bounds: ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom})\n")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseNode(child, builder)
            child?.recycle()
        }
    }

    fun clickNodeByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)

        var clicked = false
        // 1. Exact/Contains Match
        if (nodes != null) {
            for (node in nodes) {
                if (!clicked && performClick(node)) {
                    clicked = true
                }
                node.recycle()
            }
        }
        if (clicked) {
            root.recycle()
            return true
        }

        // 2. Manual Traversal (fallback for complex hierarchies)
        val result = traverseAndClick(root, targetText)
        root.recycle()
        return result
    }

    private fun traverseAndClick(node: AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true)) {
            if (performClick(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (traverseAndClick(child, target)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }
        return false
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Try parent - careful here, we cannot easily recycle parent if we got it via getParent()
        // unless we strictly manage it.
        // However, node.parent returns a NEW node.
        val parent = node.parent
        if (parent != null) {
            val result = performClick(parent)
            parent.recycle()
            return result
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        var result = false
        if (focus != null) {
            if (focus.isEditable) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                result = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            focus.recycle()
        }
        root.recycle()
        return result
    }
}
