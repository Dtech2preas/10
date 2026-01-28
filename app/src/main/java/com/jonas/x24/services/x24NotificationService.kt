package com.jonas.x24.services

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class ActiveNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

class x24NotificationService : NotificationListenerService() {

    companion object {
        val activeNotifications = mutableListOf<ActiveNotification>()

        fun getContextString(): String {
            synchronized(activeNotifications) {
                if (activeNotifications.isEmpty()) return "No active notifications."
                // Limit to last 5 to avoid overflowing context
                val recent = activeNotifications.sortedByDescending { it.timestamp }.take(5)
                return recent.joinToString("\n") {
                    "- [${it.packageName}]: ${it.title} says '${it.text}'"
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: "Unknown"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // Filter out ongoing/low priority or system notifications that might be spammy
        if (sbn.isOngoing) return
        if (packageName == this.packageName) return // Don't announce own notifications

        val notification = ActiveNotification(
            sbn.key, packageName, title, text, System.currentTimeMillis()
        )

        // Update list
        synchronized(activeNotifications) {
            activeNotifications.removeAll { it.key == sbn.key }
            activeNotifications.add(notification)
        }

        Log.d("x24Notify", "Notification: $title - $text")

        // Announce via Broadcast
        // OverlayService will listen for this
        val intent = Intent("com.jonas.x24.ANNOUNCE_NOTIFICATION")
        intent.putExtra("text", "Notification from $title. $text")
        // Check if we should filter annoying apps? For now, announce all.
        sendBroadcast(intent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        synchronized(activeNotifications) {
            activeNotifications.removeAll { it.key == sbn.key }
        }
    }
}
