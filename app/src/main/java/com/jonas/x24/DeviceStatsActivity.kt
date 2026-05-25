package com.jonas.x24

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class DeviceStatsActivity : AppCompatActivity() {

    private lateinit var btnRefreshStats: Button
    private lateinit var tvStatsContent: TextView

    private lateinit var sessionKey: String
    private val database = FirebaseDatabase.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_stats)

        val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
        sessionKey = prefs.getString("SESSION_KEY", "") ?: ""

        btnRefreshStats = findViewById(R.id.btnRefreshStats)
        tvStatsContent = findViewById(R.id.tvStatsContent)

        btnRefreshStats.setOnClickListener {
            sendCommand("GET_DEVICE_STATS")
        }

        listenForResults()
    }

    private fun sendCommand(command: String) {
        if (sessionKey.isEmpty()) return
        val commandsRef = database.getReference("sessions").child(sessionKey).child("commands").push()
        val data = mapOf(
            "command" to command,
            "timestamp" to System.currentTimeMillis()
        )
        commandsRef.setValue(data).addOnSuccessListener {
            Toast.makeText(this, "Requested $command...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listenForResults() {
        if (sessionKey.isEmpty()) return
        val resultsRef = database.getReference("sessions").child(sessionKey).child("results")
        resultsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // To display the latest matching result correctly, we iterate through children
                for (child in snapshot.children) {
                    val command = child.child("command").getValue(String::class.java)
                    if (command == "GET_DEVICE_STATS") {
                        val data = child.child("data").getValue(String::class.java) ?: "No data"
                        tvStatsContent.text = "Result for $command:\n\n$data"
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
