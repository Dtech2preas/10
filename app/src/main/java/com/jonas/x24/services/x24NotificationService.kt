package com.jonas.x24.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class x24NotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title")
        val text = extras.getCharSequence("android.text")?.toString()

        Log.d("x24Notify", "Notification from $packageName: $title - $text")

        // Auto-read Important Logic
        // In a real app, we would send this to MainActivity to speak out loud if "Auto-Read" is enabled.
        // For now, we log.

        // Example: If message from "Mom" -> Alert differently
        if (title?.contains("Mom", ignoreCase = true) == true) {
            // Trigger specific alert or TTS
            // Ideally broadcast this event to the main app or AutomationService
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
