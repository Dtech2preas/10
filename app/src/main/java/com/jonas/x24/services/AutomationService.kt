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
        // If battery low (< 20%) -> Turn on battery saver (if feasible) or notify
        if (pct < 20 && !prefs.getBoolean("low_batt_triggered", false)) {
            // Logic to trigger actions
            // For now, we simulate by adjusting brightness down
            adjustBrightness(50)
            prefs.edit().putBoolean("low_batt_triggered", true).apply()
            Log.d("x24Auto", "Low Battery: Reducing brightness")
        } else if (pct > 20) {
            prefs.edit().putBoolean("low_batt_triggered", false).apply()
        }
    }

    private fun checkChargingRules(isCharging: Boolean) {
        // If charging -> Increase brightness / Performance?
        if (isCharging) {
             adjustBrightness(200)
             Log.d("x24Auto", "Charging: Brightness up")
        }
    }

    private fun checkConnectivityRules() {
        // If WiFi connected -> maybe turn off Data (system handles this usually)
        // If Headphones connected -> Open Music? (Need specific receiver for headset)
    }

    private fun checkTimeRules() {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // Morning Briefing Trigger (e.g., 8 AM)
        if (hour == 8 && !prefs.getBoolean("morning_brief_done", false)) {
             // We can't speak directly from Service easily without TTS instance.
             // We could send broadcast to MainActivity if it's alive, or Notification.
             sendNotification("Good Morning", "Ready for your briefing?")
             prefs.edit().putBoolean("morning_brief_done", true).apply()
        }

        // Reset flags at midnight
        if (hour == 0) {
             prefs.edit().putBoolean("morning_brief_done", false).apply()
        }

        // Night Mode (e.g., 11 PM)
        if (hour == 23) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            adjustBrightness(20)
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
        manager.notify(System.currentTimeMillis().toInt(), notification)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("x24 Service")
            .setContentText("Automation running in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }
}
