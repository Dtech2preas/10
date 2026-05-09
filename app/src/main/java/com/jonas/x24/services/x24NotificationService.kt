package com.jonas.x24.services

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class x24NotificationService : NotificationListenerService() {
    companion object {
        val recentNotifications = java.util.concurrent.CopyOnWriteArrayList<String>()
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
            intent.putExtra("title", title)
            intent.putExtra("text", text)
            intent.putExtra("package", packageName)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
