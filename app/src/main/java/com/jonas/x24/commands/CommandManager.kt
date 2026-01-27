package com.jonas.x24.commands

import android.Manifest
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
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.Locale
import java.util.regex.Pattern

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

            // Remove the tag from the spoken text, but we might append a result later
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
                "FLASHLIGHT" -> toggleFlashlight(valueString == "ON")
                "BLUETOOTH" -> toggleBluetooth(valueString == "ON")
                "VOLUME" -> adjustVolume(valueString)
                "BRIGHTNESS" -> adjustBrightness(valueString)
                "OPEN_APP" -> {
                     if (!launchApp(valueString)) {
                         return "I couldn't find an app named $valueString."
                     }
                }
                "CALL" -> makeCall(valueString)
                "SMS" -> {
                    val parts = valueString.split("|")
                    if (parts.size >= 2) {
                        sendSMS(parts[0], parts[1])
                    }
                }
                "CAMERA" -> launchCamera()
                "ALARM" -> setAlarm(valueString)
                "TIMER" -> setTimer(valueString)
                "WIFI" -> openWifiSettings()
                "BATTERY" -> return getBatteryLevel()
                "LOCATION" -> return getLocation()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("CommandManager", "Error executing $type", e)
            return "I had trouble with that command."
        }
        return null
    }

    private fun toggleFlashlight(enable: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0] // Usually back camera
        cameraManager.setTorchMode(cameraId, enable)
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

        val targetPkg = packages.find {
            it.applicationInfo.loadLabel(pm).toString().contains(appName, ignoreCase = true)
        }

        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }
        return false
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

    // New Features

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun setAlarm(timeString: String) {
        // Expected format: HH:MM
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
        // Try GPS, then Network
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
                    // e.g. "Mountain View, California"
                    val locStr = "${address.locality ?: "Unknown City"}, ${address.adminArea ?: ""}"
                    return "You are currently in $locStr."
                }
            } catch (e: Exception) {
                // Geocoder can fail
            }
            return "Your coordinates are ${location.latitude}, ${location.longitude}."
        }

        return "I couldn't determine your location."
    }
}
