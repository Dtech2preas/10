package com.jonas.x24.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jonas.x24.MonitorActivity
import com.jonas.x24.R

class MonitorAlertService : Service() {

    private val CHANNEL_ID = "monitor_alerts_channel"
    private lateinit var sessionKey: String
    private val database = FirebaseDatabase.getInstance()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionKey = intent?.getStringExtra("SESSION_KEY") ?: return START_NOT_STICKY

        // Listen for new alerts in the Firebase database
        val alertsRef = database.getReference("sessions").child(sessionKey).child("alerts")
        alertsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // If a new alert is added or updated, we trigger a notification
                // To keep it simple, we listen to changes in the "latestAlert" node
                val latestAlert = snapshot.child("latestAlert").getValue(String::class.java)
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                // Only alert if it's recent (e.g. within last 30 seconds) to avoid old alerts firing on startup
                if (latestAlert != null && (System.currentTimeMillis() - timestamp) < 30000) {
                    showNotification(latestAlert)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        return START_STICKY
    }

    private fun showNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val intent = Intent(this, MonitorActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("SESSION_KEY", sessionKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("x24 Alert")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        with(NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Monitor Alerts"
            val descriptionText = "Alerts from monitored device"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
