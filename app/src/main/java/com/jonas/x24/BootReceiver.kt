package com.jonas.x24

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jonas.x24.services.FirebaseCommandService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("x24", "Boot completed. Checking if service needs to be started.")

            val prefs = context.getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
            val role = prefs.getString("ROLE", null)
            val sessionKey = prefs.getString("SESSION_KEY", null)

            if (role == "BE_MONITORED" && !sessionKey.isNullOrEmpty()) {
                var deviceId = prefs.getString("DEVICE_ID", null)
                if (deviceId == null) {
                    deviceId = java.util.UUID.randomUUID().toString()
                    prefs.edit().putString("DEVICE_ID", deviceId).apply()
                }

                Log.d("x24", "Device was previously monitored. Starting FirebaseCommandService.")
                val serviceIntent = Intent(context, FirebaseCommandService::class.java).apply {
                    putExtra("SESSION_KEY", sessionKey)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d("x24", "Device is not in a monitored state, no action taken.")
            }
        }
    }
}
