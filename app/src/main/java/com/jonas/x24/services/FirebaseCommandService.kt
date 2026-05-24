package com.jonas.x24.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jonas.x24.CommandReceiverActivity

class FirebaseCommandService : Service() {

    private lateinit var database: DatabaseReference
    private var sessionKey: String? = null
    private var listener: ValueEventListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra("SESSION_KEY")
        if (key != null) {
            sessionKey = key
            setupFirebaseListener(key)
        }

        startForeground(1001, createNotification())
        return START_STICKY
    }

    private fun setupFirebaseListener(key: String) {
        database = FirebaseDatabase.getInstance().reference.child("sessions").child(key)

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val commandsSnapshot = snapshot.child("commands")
                val lastCommandNode = commandsSnapshot.children.lastOrNull()

                if (lastCommandNode != null) {
                    val commandStr = lastCommandNode.child("command").value as? String
                    val processed = lastCommandNode.child("processed").value as? Boolean ?: false

                    if (commandStr != null && !processed) {
                        // Mark as processed immediately so we don't duplicate
                        lastCommandNode.ref.child("processed").setValue(true)
                        processCommand(commandStr)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseService", "Listener cancelled: ${error.message}")
            }
        }

        database.addValueEventListener(listener!!)
    }

    private fun processCommand(command: String) {
        Log.d("FirebaseService", "Received command: $command")

        // Ensure CommandReceiverActivity is running to receive the broadcast/intent
        val activityIntent = Intent(this, CommandReceiverActivity::class.java)
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activityIntent.putExtra("COMMAND", command)
        activityIntent.putExtra("SESSION_KEY", sessionKey)
        startActivity(activityIntent)

        // Broadcast the command so that the receiver activity can process it
        val intent = Intent("com.jonas.x24.COMMAND_RECEIVED")
        intent.putExtra("COMMAND", command)
        intent.putExtra("SESSION_KEY", sessionKey)
        sendBroadcast(intent)
    }

    private fun createNotification(): android.app.Notification {
        val channelId = "x24_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("x24 Active")
            .setContentText("Monitoring for commands...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (listener != null && ::database.isInitialized) {
            database.removeEventListener(listener!!)
        }
    }
}
