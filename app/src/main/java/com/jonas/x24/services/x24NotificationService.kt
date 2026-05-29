package com.jonas.x24.services

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log


class x24NotificationService : NotificationListenerService() {
    companion object {
        val recentNotifications = java.util.concurrent.CopyOnWriteArrayList<String>()
        var instance: x24NotificationService? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("x24Notify", "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    fun getActiveNotificationsList(): List<String> {
        val notifList = mutableListOf<String>()
        try {
            val activeNotifs = activeNotifications
            if (activeNotifs != null) {
                for (sbn in activeNotifs) {
                    val packageName = sbn.packageName
                    // Ignore system UI and ongoing notifications
                    if (sbn.isOngoing || packageName == "android" || packageName == "com.android.systemui") continue

                    val extras = sbn.notification.extras
                    val title = extras.getString("android.title")
                    val text = extras.getCharSequence("android.text")?.toString()

                    if (!title.isNullOrEmpty() && !text.isNullOrEmpty()) {
                        notifList.add("$title: $text")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("x24Notify", "Error getting active notifications", e)
        }
        return notifList
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title")
        val text = extras.getCharSequence("android.text")?.toString()

        Log.d("x24Notify", "Notification from $packageName: $title - $text")

        // Store recent notification (keep last 10)
        if (!title.isNullOrEmpty() && !text.isNullOrEmpty()) {
            val formatted = "$title: $text"
            if (!recentNotifications.contains(formatted)) {
                recentNotifications.add(0, formatted)
                if (recentNotifications.size > 10) {
                    recentNotifications.removeAt(recentNotifications.size - 1)
                }
            }
        }


        // Broadcast notification to be read aloud
        if (!title.isNullOrEmpty() && !text.isNullOrEmpty()) {
            val intent = Intent("com.jonas.x24.NOTIFICATION_POSTED")
            intent.setPackage(applicationContext.packageName)
            intent.putExtra("title", title)
            intent.putExtra("text", text)
            intent.putExtra("package", packageName)
            sendBroadcast(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
