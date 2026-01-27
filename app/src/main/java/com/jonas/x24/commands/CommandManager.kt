package com.jonas.x24.commands

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.util.regex.Pattern

class CommandManager(private val context: Context) {

    fun executeCommand(rawText: String): String {
        // Regex to find [[COMMAND:TYPE|VALUE]] or [[COMMAND:TYPE|VAL1|VAL2]]
        val pattern = Pattern.compile("\\[\\[COMMAND:(.*?)\\|(.*?)\\]\\]")
        val matcher = pattern.matcher(rawText)

        var cleanText = rawText

        while (matcher.find()) {
            val fullTag = matcher.group(0)
            val type = matcher.group(1)
            val valueString = matcher.group(2)

            cleanText = cleanText.replace(fullTag, "")

            performAction(type, valueString)
        }

        return cleanText.trim()
    }

    private fun performAction(type: String, valueString: String) {
        try {
            when (type) {
                "FLASHLIGHT" -> toggleFlashlight(valueString == "ON")
                "BLUETOOTH" -> toggleBluetooth(valueString == "ON")
                "VOLUME" -> adjustVolume(valueString)
                "BRIGHTNESS" -> adjustBrightness(valueString)
                "OPEN_APP" -> launchApp(valueString)
                "CALL" -> makeCall(valueString)
                "SMS" -> {
                    val parts = valueString.split("|")
                    if (parts.size >= 2) {
                        sendSMS(parts[0], parts[1])
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error executing $type: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFlashlight(enable: Boolean) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0] // Usually back camera
        cameraManager.setTorchMode(cameraId, enable)
    }

    private fun toggleBluetooth(enable: Boolean) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
             // In a real app we would request permission here, but this is a service class
             // We rely on MainActivity to have requested it.
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

    private fun launchApp(appName: String) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)

        // Simple fuzzy search
        val targetPkg = packages.find {
            it.applicationInfo.loadLabel(pm).toString().contains(appName, ignoreCase = true)
        }

        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "App not found: $appName", Toast.LENGTH_SHORT).show()
        }
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
}
