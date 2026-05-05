package com.jonas.x24.commands

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import com.jonas.x24.services.x24AccessibilityService
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

class CommandManager(private val context: Context) {

    fun executeCommand(rawText: String): String {
        // Regex to find [[COMMAND:TYPE|VALUE]] or [[COMMAND:TYPE|VAL1|VAL2]]
        val pattern = Pattern.compile("\\[\\[COMMAND:(.*?)\\|(.*?)\\]\\]")
        val matcher = pattern.matcher(rawText)

        var cleanText = rawText
        val additionalOutput = StringBuilder()

        while (matcher.find()) {
            val fullTag = matcher.group(0)
            val type = matcher.group(1)
            val valueString = matcher.group(2)

            // Remove the tag from the spoken text
            cleanText = cleanText.replace(fullTag, "")

            val result = performAction(type, valueString)
            if (!result.isNullOrEmpty()) {
                additionalOutput.append(" ").append(result)
            }
        }

        return (cleanText + additionalOutput.toString()).trim()
    }

    private fun performAction(type: String, valueString: String): String? {
        try {
            when (type) {
                                // Hardware / System
                "FLASHLIGHT" -> toggleFlashlight(valueString == "ON")
                "BLUETOOTH" -> toggleBluetooth(valueString == "ON")
                "VOLUME" -> adjustVolume(valueString)
                "BRIGHTNESS" -> adjustBrightness(valueString)
                "WIFI" -> toggleWifi(valueString)
                "MOBILE_DATA" -> openMobileDataSettings()
                "BATTERY" -> return getBatteryLevel()
                "LOCATION" -> return getLocation()
                "DND" -> toggleDnd(valueString == "ON")
                "ROTATE" -> toggleAutoRotate(valueString == "ON")
                "DATE" -> return getDate()
                "TIME" -> return getTime()
                "DEVICE_INFO" -> return getDeviceInfo()

                // Apps / Communication
                "OPEN_APP" -> {
                     if (!launchApp(valueString)) {
                         return "I couldn't find an app named $valueString."
                     }
                }
                "SEARCH_APP" -> {
                    val parts = valueString.split("|", limit = 2)
                    if (parts.size >= 2) {
                        searchApp(parts[0], parts[1])
                    }
                }
                "OPEN_URL" -> openUrl(valueString)
                "CALL" -> makeCall(valueString)
                "SMS" -> {
                    val parts = valueString.split("|")
                    if (parts.size >= 2) {
                        sendSMS(parts[0], parts[1])
                    }
                }

                // Media / Tools
                "CAMERA" -> launchCamera()
                "ALARM" -> setAlarm(valueString)
                "TIMER" -> setTimer(valueString)
                "MEDIA" -> controlMedia(valueString)
                "RECORD_AUDIO" -> recordAudio()
                "CALENDAR" -> createCalendarEvent(valueString)

                // Accessibility / Navigation
                "HOME" -> performGlobal(AccessibilityService.GLOBAL_ACTION_HOME)
                "BACK" -> performGlobal(AccessibilityService.GLOBAL_ACTION_BACK)
                "RECENTS" -> performGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "LOCK" -> performGlobal(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                "SCREENSHOT" -> performGlobal(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                "SCROLL" -> scroll(valueString)
                "SWIPE" -> swipe(valueString)
                "CLICK" -> click(valueString)
                "LONG_CLICK" -> longClick(valueString)
                "CLICK_TEXT" -> clickText(valueString)
                "INPUT_TEXT" -> inputText(valueString)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("CommandManager", "Error executing $type", e)
            return "I had trouble with that command."
        }
        return null
    }

    // --- Implementation Details ---
    // --- New Command Implementations ---

    private fun openMobileDataSettings() {
        val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun toggleWifi(action: String) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Cannot toggle directly in Android 10+, fallback to settings
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            @Suppress("DEPRECATION")
            when (action) {
                "ON" -> wifiManager?.isWifiEnabled = true
                "OFF" -> wifiManager?.isWifiEnabled = false
                "SETTINGS" -> {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        }
    }

    private fun toggleDnd(enable: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            val filter = if (enable) android.app.NotificationManager.INTERRUPTION_FILTER_NONE else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            notificationManager.setInterruptionFilter(filter)
        } else {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun toggleAutoRotate(enable: Boolean) {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enable) 1 else 0)
    }

    private fun getDate(): String {
        return "Today is " + java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(java.util.Date())
    }

    private fun getTime(): String {
        return "The time is " + java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
    }

    private fun getDeviceInfo(): String {
        return "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, running Android ${android.os.Build.VERSION.RELEASE}"
    }

    private fun openUrl(url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "https://$finalUrl"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun searchApp(appName: String, query: String) {
        // Try launching and then input text if we can't formulate a specific intent
        if (appName.equals("youtube", ignoreCase = true)) {
            val intent = Intent(Intent.ACTION_SEARCH)
            intent.setPackage("com.google.android.youtube")
            intent.putExtra("query", query)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // fallback
                launchApp("youtube")
                Thread.sleep(2000)
                inputText(query)
            }
        } else if (appName.equals("web", ignoreCase = true) || appName.equals("google", ignoreCase = true)) {
            val intent = Intent(Intent.ACTION_WEB_SEARCH)
            intent.putExtra(android.app.SearchManager.QUERY, query)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            launchApp(appName)
            Thread.sleep(2000)
            inputText(query)
        }
    }

    private fun recordAudio() {
        val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CommandManager", "No audio recorder app found.")
        }
    }

    private fun createCalendarEvent(title: String) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CommandManager", "No calendar app found.")
        }
    }

    private fun swipe(direction: String) {
        val service = x24AccessibilityService.instance
        service?.swipe(direction)
    }

    private fun longClick(args: String) {
        val service = x24AccessibilityService.instance
        if (service == null) return

        if (args.contains(",")) {
            val parts = args.split(",")
            if (parts.size == 2) {
                val x = parts[0].toFloatOrNull() ?: 500f
                val y = parts[1].toFloatOrNull() ?: 500f
                service.longClick(x, y)
            }
        } else {
            service.longClickText(args)
        }
    }


    private fun performGlobal(action: Int) {
        val service = x24AccessibilityService.instance
        if (service != null) {
            service.performGlobal(action)
        } else {
            Log.e("CommandManager", "Accessibility Service not connected.")
        }
    }

    private fun scroll(direction: String) {
        val service = x24AccessibilityService.instance
        service?.scroll(direction)
    }

    private fun click(args: String) {
        val service = x24AccessibilityService.instance
        if (service == null) return

        if (args.contains(",")) {
            // Coordinate click: x,y
            val parts = args.split(",")
            if (parts.size == 2) {
                val x = parts[0].toFloatOrNull() ?: 500f
                val y = parts[1].toFloatOrNull() ?: 500f
                service.click(x, y)
            }
        }
    }

    private fun clickText(text: String) {
        val service = x24AccessibilityService.instance
        if (service?.clickNodeByText(text) == true) {
            // Success
        } else {
             // Fallback or Log
             Log.w("Cmd", "Could not find text: $text")
        }
    }

    private fun inputText(text: String) {
        val service = x24AccessibilityService.instance
        service?.inputText(text)
    }

    private fun controlMedia(action: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = android.os.SystemClock.uptimeMillis()

        val key = when(action) {
            "PLAY", "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }

        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, key, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, key, 0))
    }

    private fun toggleFlashlight(enable: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
             val cameraId = cameraManager.cameraIdList[0]
             cameraManager.setTorchMode(cameraId, enable)
        } catch (e: Exception) {
             Log.e("Cmd", "Flashlight error", e)
        }
    }

    private fun toggleBluetooth(enable: Boolean) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
             return
        }
        if (enable) bluetoothAdapter?.enable() else bluetoothAdapter?.disable()
    }

    private fun adjustVolume(action: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action) {
            "UP" -> audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "DOWN" -> audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "MAX" -> audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
            "MUTE" -> audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun adjustBrightness(action: String) {
        if (!Settings.System.canWrite(context)) return

        val resolver = context.contentResolver
        var current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)

        when (action) {
            "UP" -> current = (current + 50).coerceAtMost(255)
            "DOWN" -> current = (current - 50).coerceAtLeast(0)
            "MAX" -> current = 255
        }

        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, current)
    }

    private fun launchApp(appName: String): Boolean {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)

        // Exact match first
        var targetPkg = packages.find {
            it.applicationInfo.loadLabel(pm).toString().equals(appName, ignoreCase = true)
        }

        // Fuzzy match if exact fails
        if (targetPkg == null) {
            targetPkg = packages.maxByOrNull {
                fuzzyScore(it.applicationInfo.loadLabel(pm).toString(), appName)
            }

            // Check if score is decent (arbitrary threshold)
            val label = targetPkg?.applicationInfo?.loadLabel(pm)?.toString() ?: ""
            if (fuzzyScore(label, appName) < 0.3) {
                targetPkg = null // Too weak match
            }
        }

        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            try { Thread.sleep(3000) } catch (e: Exception) {}
            return true
        }
        return false
    }

    // Simple similarity score (0.0 to 1.0)
    private fun fuzzyScore(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1.lowercase() else s2.lowercase()
        val shorter = if (s1.length > s2.length) s2.lowercase() else s1.lowercase()

        if (longer.contains(shorter)) return 0.8 // High score for substring

        // Very basic char match count
        var matches = 0
        for (char in shorter) {
            if (longer.contains(char)) matches++
        }
        return matches.toDouble() / longer.length
    }

    private fun makeCall(number: String) {
        val intent = Intent(Intent.ACTION_CALL)
        intent.data = Uri.parse("tel:$number")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun sendSMS(number: String, message: String) {
        val smsManager = SmsManager.getDefault()
        smsManager.sendTextMessage(number, null, message, null, null)
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun setAlarm(timeString: String) {
        val parts = timeString.split(":")
        if (parts.size == 2) {
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "Set by x24")
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun setTimer(secondsString: String) {
        val seconds = secondsString.toIntOrNull() ?: 60
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun getBatteryLevel(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Your battery is at $level percent."
    }

    private fun getLocation(): String {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "I need location permissions to do that."
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var location: Location? = null
        try {
            location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch(e: Exception) {
            Log.e("CommandManager", "Location error", e)
        }

        if (location != null) {
            val geocoder = Geocoder(context, Locale.getDefault())
            try {
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val locStr = "${address.locality ?: "Unknown City"}, ${address.adminArea ?: ""}"
                    return "You are currently in $locStr."
                }
            } catch (e: Exception) { }
            return "Your coordinates are ${location.latitude}, ${location.longitude}."
        }

        return "I couldn't determine your location."
    }
}
