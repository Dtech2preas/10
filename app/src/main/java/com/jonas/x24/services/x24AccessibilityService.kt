package com.jonas.x24.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class x24AccessibilityService : AccessibilityService() {

    companion object {
        var instance: x24AccessibilityService? = null
    }

    private var watchedApps: List<String> = listOf()
    private var sessionKey: String? = null
    private var isListeningToFirebase = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("x24Access", "Service Connected")

        val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
        sessionKey = prefs.getString("SESSION_KEY", null)
        setupFirebaseListener()
    }

    private fun setupFirebaseListener() {
        if (sessionKey == null || isListeningToFirebase) return
        isListeningToFirebase = true
        val stateRef = FirebaseDatabase.getInstance().getReference("sessions").child(sessionKey!!).child("state").child("watchedApps")
        stateRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<String>()
                for (child in snapshot.children) {
                    val app = child.getValue(String::class.java)
                    if (app != null) {
                        list.add(app)
                    }
                }
                watchedApps = list
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private val lastAlertTime = mutableMapOf<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null && watchedApps.contains(packageName) && sessionKey != null) {
                val currentTime = System.currentTimeMillis()
                val lastAlert = lastAlertTime[packageName] ?: 0L

                // Only alert once every 5 minutes (300,000 ms) per app
                if (currentTime - lastAlert > 5 * 60 * 1000) {
                    lastAlertTime[packageName] = currentTime

                    val alertsRef = FirebaseDatabase.getInstance().getReference("sessions").child(sessionKey!!).child("alerts")
                    val alertData = mapOf(
                        "latestAlert" to "Watched App Opened: $packageName",
                        "timestamp" to currentTime
                    )
                    alertsRef.setValue(alertData)
                }
            }
        }
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

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || isScrollable || isEditable) {
            var label = text ?: desc ?: "Empty"
            if (isScrollable && label == "Empty") label = "Scrollable Area"
            if (isEditable && label == "Empty") label = "Input Field"

            val type = when {
                isEditable -> "Input"
                isClickable -> "Button"
                isScrollable -> "Scrollable"
                else -> "Text"
            }

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            // Include view ID resource name for better element identification (like 'search_button')
            val viewId = node.viewIdResourceName?.let { id ->
                val simpleId = id.substringAfterLast("/")
                " id: $simpleId"
            } ?: ""

            // Limit length to avoid blowing up context
            if (label.length < 5000) { // Increased length to 5000 to capture long chat messages
                 builder.append("[$type] $label (bounds: ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom})$viewId\n")
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


    fun pressEnterOrSubmit() {
        val root = rootInActiveWindow ?: return
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focus != null) {
            // Some keyboards/apps respond to ACTION_CLICK or specific editor actions when focused
            focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            focus.recycle()
        }

        // Also try to find a submit/search/send button and click it as a fallback
        val possibleLabels = listOf("Search", "Send", "Submit", "Done", "Go", "Enter")
        var clicked = false

        for (label in possibleLabels) {
            if (clicked) break
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (nodes != null) {
                 for (node in nodes) {
                     if (!clicked && performClick(node)) {
                          clicked = true
                     }
                     node.recycle()
                 }
            }
        }

        // Try content descriptions if text wasn't found
        if (!clicked) {
            val sendNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByContentDescription(root, "Send", sendNodes)
            findNodesByContentDescription(root, "Search", sendNodes)
            for (node in sendNodes) {
                if (!clicked && performClick(node)) {
                    clicked = true
                }
                node.recycle()
            }
        }

        // Hardware keyboard enter simulation is not fully supported via GestureDescription.
        // If the above FOCUS_INPUT and text matching fail, we gracefully do nothing
        // to avoid unintended touches.

        root.recycle()
    }

    private fun findNodesByContentDescription(node: AccessibilityNodeInfo?, targetDesc: String, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val desc = node.contentDescription?.toString()
        if (desc != null && desc.contains(targetDesc, ignoreCase = true)) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            findNodesByContentDescription(node.getChild(i), targetDesc, list)
        }
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        var result = false
        if (focus != null) {
            if (focus.isEditable) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                result = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            focus.recycle()
        } else {
            // Fallback: Find the first editable node on the screen
            val editableNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditableNodes(root, editableNodes)
            if (editableNodes.isNotEmpty()) {
                val node = editableNodes[0]
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                for (n in editableNodes) n.recycle()
            }
        }
        root.recycle()
        return result
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable) {
            list.add(node)
            // Don't recycle here, we need it in the list
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            findEditableNodes(child, list)
            // Child might be added to list, so we can't blindly recycle unless we manage it carefully.
            // For simplicity and safety against memory leaks, we let the caller recycle the list.
            if (child != null && !child.isEditable) {
                child.recycle()
            }
        }
    }
}
