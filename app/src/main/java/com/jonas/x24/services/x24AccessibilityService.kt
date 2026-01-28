package com.jonas.x24.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
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
        // For now, we mainly use this service for performing actions.
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
            // Scroll UP means content moves down (swipe down) OR content moves up (swipe up)?
            // Usually "Scroll Down" means swipe UP.
            // "Scroll Up" means swipe DOWN.
            startY = centerY - 300
            endY = centerY + 300
        } else {
            startY = centerY + 300
            endY = centerY - 300
        }

        val path = Path()
        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)

        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 300))
        dispatchGesture(builder.build(), null, null)
    }
}
