package com.jonas.x24.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jonas.x24.MainActivity
import com.jonas.x24.R
import java.util.Calendar

class AutomationService : Service() {

    private val CHANNEL_ID = "x24_automation_channel"
    private var isRunning = false
    private lateinit var prefs: SharedPreferences

    // --- Receivers ---

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = (level * 100) / scale.toFloat()

                checkBatteryRules(pct)
            } else if (intent?.action == Intent.ACTION_POWER_CONNECTED) {
                checkChargingRules(true)
            } else if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) {
                checkChargingRules(false)
            }
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
             checkConnectivityRules()
        }
    }

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkTimeRules()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences("x24_automation", MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            val notification = createNotification()
            startForeground(1, notification)

            // Register Receivers
            val battFilter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(batteryReceiver, battFilter)

            val connFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            registerReceiver(connectivityReceiver, connFilter)

            registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))

            isRunning = true
            Log.d("x24Auto", "Automation Service Started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(connectivityReceiver)
        unregisterReceiver(timeTickReceiver)
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // --- Rule Engines ---

    private fun checkBatteryRules(pct: Float) {
        // If battery low (< 20%) -> Proactively warn user via OverlayService
        if (pct < 20 && !prefs.getBoolean("low_batt_triggered", false)) {
            prefs.edit().putBoolean("low_batt_triggered", true).apply()
            Log.d("x24Auto", "Low Battery Triggered")
            OverlayService.instance?.triggerProactiveConversation("[SYSTEM: The device battery has dropped to ${pct.toInt()}%. Proactively speak to the user to warn them warmly.]")
        } else if (pct > 25) { // Reset threshold higher to prevent bouncing
            prefs.edit().putBoolean("low_batt_triggered", false).apply()
        }
    }

    private fun checkChargingRules(isCharging: Boolean) {
        // Currently handled implicitly or by system. Can add AI triggers later.
    }

    private fun checkConnectivityRules() {
        // Currently handled implicitly or by system. Can add AI triggers later.
    }

    private fun checkTimeRules() {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // Morning Briefing Trigger (e.g., 8 AM)
        if (hour == 8 && !prefs.getBoolean("morning_brief_done", false)) {
             prefs.edit().putBoolean("morning_brief_done", true).apply()
             OverlayService.instance?.triggerProactiveConversation("[SYSTEM: It is 8 AM. Proactively start a morning conversation with the user. Greet them, ask how they slept, or suggest checking notifications.]")
        }

        // Reset flags at midnight
        if (hour == 0) {
             prefs.edit().putBoolean("morning_brief_done", false).apply()
        }

        // Night Mode trigger (e.g. 11 PM)
        if (hour == 23 && !prefs.getBoolean("night_brief_done", false)) {
             prefs.edit().putBoolean("night_brief_done", true).apply()
             OverlayService.instance?.triggerProactiveConversation("[SYSTEM: It is 11 PM. Proactively tell the user it is getting late, ask if they need an alarm set, or just wish them a good night.]")
        }

        // Reset night flag at noon
        if (hour == 12) {
             prefs.edit().putBoolean("night_brief_done", false).apply()
        }
    }

    // --- Actions ---

    private fun adjustBrightness(value: Int) {
        if (Settings.System.canWrite(this)) {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }
    }

    private fun sendNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e("x24Auto", "Missing POST_NOTIFICATIONS permission", e)
        }
    }

    // --- Setup ---

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "x24 Automation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val listenIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("START_LISTENING", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val listenPendingIntent = PendingIntent.getActivity(
            this,
            1,
            listenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("x24 Service")
            .setContentText("Automation running in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk", listenPendingIntent)
            .build()
    }
}
