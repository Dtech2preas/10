package com.jonas.x24

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*
import java.util.UUID
import android.os.Environment
import java.io.File
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build

class AdminActivity : AppCompatActivity() {

    private val MASTER_PASSWORD = "D-TECH_services"
    private lateinit var llLoginView: LinearLayout
    private lateinit var llDashboardView: LinearLayout
    private lateinit var llSessionsList: LinearLayout
    private lateinit var tvGeneratedCode: TextView
    private val onlineStatusListeners = mutableMapOf<String, ValueEventListener>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        llLoginView = findViewById(R.id.llLoginView)
        llDashboardView = findViewById(R.id.llDashboardView)
        llSessionsList = findViewById(R.id.llSessionsList)
        tvGeneratedCode = findViewById(R.id.tvGeneratedCode)

        val etMasterPassword = findViewById<EditText>(R.id.etMasterPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnGenerateCode = findViewById<Button>(R.id.btnGenerateCode)

        btnLogin.setOnClickListener {
            val password = etMasterPassword.text.toString()
            if (password == MASTER_PASSWORD) {
                llLoginView.visibility = View.GONE
                llDashboardView.visibility = View.VISIBLE
                loadSessions()
            } else {
                Toast.makeText(this, "Invalid Master Password", Toast.LENGTH_SHORT).show()
            }
        }

        btnGenerateCode.setOnClickListener {
            val newCode = UUID.randomUUID().toString().substring(0, 8)
            val dbRef = FirebaseDatabase.getInstance().getReference("access_codes").child(newCode)

            val codeData = hashMapOf(
                "secret" to MASTER_PASSWORD,
                "monitor_claimed" to false,
                "be_monitored_claimed" to false
            )

            dbRef.setValue(codeData).addOnSuccessListener {
                tvGeneratedCode.text = "Code Generated: $newCode"
                Toast.makeText(this, "Code Generated Successfully", Toast.LENGTH_SHORT).show()
            }.addOnFailureListener { e ->
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadSessions() {
        val codesRef = FirebaseDatabase.getInstance().getReference("access_codes")
        codesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Clear existing listeners to prevent memory leak
                for ((code, listener) in onlineStatusListeners) {
                    FirebaseDatabase.getInstance().getReference("sessions").child(code).child("state").child("isOnline").removeEventListener(listener)
                }
                onlineStatusListeners.clear()

                llSessionsList.removeAllViews()
                if (!snapshot.exists()) return

                for (codeSnapshot in snapshot.children) {
                    val code = codeSnapshot.key ?: continue
                    val monitorClaimed = codeSnapshot.child("monitor_claimed").getValue(Boolean::class.java) ?: false
                    val beMonitoredClaimed = codeSnapshot.child("be_monitored_claimed").getValue(Boolean::class.java) ?: false

                    addSessionToView(code, monitorClaimed, beMonitoredClaimed)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@AdminActivity, "Error loading sessions: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun addSessionToView(code: String, monitorClaimed: Boolean, beMonitoredClaimed: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_admin_session, llSessionsList, false)

        val tvSessionCode = view.findViewById<TextView>(R.id.tvSessionCode)
        val tvSessionDetails = view.findViewById<TextView>(R.id.tvSessionDetails)
        val ivOnlineStatus = view.findViewById<ImageView>(R.id.ivOnlineStatus)

        val btnMonitorDevice = view.findViewById<Button>(R.id.btnMonitorDevice)
        val btnDownloadLogs = view.findViewById<Button>(R.id.btnDownloadLogs)
        val btnClearData = view.findViewById<Button>(R.id.btnClearData)
        val btnKillSession = view.findViewById<Button>(R.id.btnKillSession)

        tvSessionCode.text = "Code: $code"
        tvSessionDetails.text = "Monitor: $monitorClaimed | Monitored: $beMonitoredClaimed"

        // Check online status
        val statusRef = FirebaseDatabase.getInstance().getReference("sessions").child(code).child("state").child("isOnline")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.getValue(Boolean::class.java) ?: false
                if (isOnline) {
                    ivOnlineStatus.setImageResource(R.drawable.bg_online)
                } else {
                    ivOnlineStatus.setImageResource(R.drawable.bg_offline)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        statusRef.addValueEventListener(listener)
        onlineStatusListeners[code] = listener

        btnMonitorDevice.setOnClickListener {
            val intent = Intent(this, MonitorActivity::class.java)
            intent.putExtra("SESSION_KEY", code)
            intent.putExtra("IS_ADMIN", true)
            startActivity(intent)
        }

        btnDownloadLogs.setOnClickListener {
            downloadLogs(code)
        }

        btnClearData.setOnClickListener {
            FirebaseDatabase.getInstance().getReference("sessions").child(code)
                .removeValue()
                .addOnSuccessListener {
                    Toast.makeText(this, "Cleared data for $code", Toast.LENGTH_SHORT).show()
                }
        }

        btnKillSession.setOnClickListener {
            FirebaseDatabase.getInstance().getReference("access_codes").child(code)
                .removeValue()
                .addOnSuccessListener {
                    Toast.makeText(this, "Session killed: $code", Toast.LENGTH_SHORT).show()
                }
        }

        llSessionsList.addView(view)
    }

    private fun downloadLogs(code: String) {
        val resultsRef = FirebaseDatabase.getInstance().getReference("sessions").child(code).child("results")

        Toast.makeText(this, "Fetching logs for $code...", Toast.LENGTH_SHORT).show()

        resultsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    Toast.makeText(this@AdminActivity, "No logs found for $code", Toast.LENGTH_SHORT).show()
                    return
                }

                try {
                    val logsJson = JSONObject()
                    for (resultSnapshot in snapshot.children) {
                        val key = resultSnapshot.key ?: continue
                        val value = resultSnapshot.value

                        val logEntry = JSONObject()
                        logEntry.put("data", value.toString())
                        logsJson.put(key, logEntry)
                    }

                    saveLogsToFile(code, logsJson.toString(4))
                } catch (e: Exception) {
                    Toast.makeText(this@AdminActivity, "Error formatting logs: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@AdminActivity, "Failed to download logs: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun saveLogsToFile(code: String, data: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "x24_logs_${code}_$timestamp.json"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(data.toByteArray())
                    }
                    Toast.makeText(this, "Logs saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to create file in MediaStore", Toast.LENGTH_LONG).show()
                }
            } else {
                // For older versions, this will still need permissions, but this is a fallback
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, fileName)
                file.writeText(data)
                Toast.makeText(this, "Logs saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        for ((code, listener) in onlineStatusListeners) {
            FirebaseDatabase.getInstance().getReference("sessions").child(code).child("state").child("isOnline").removeEventListener(listener)
        }
        onlineStatusListeners.clear()
    }
}
