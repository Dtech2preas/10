package com.jonas.x24

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class AppsActivity : AppCompatActivity() {

    private lateinit var btnRefreshApps: Button
    private lateinit var tvWatchedApp: TextView
    private lateinit var lvApps: ListView

    private lateinit var sessionKey: String
    private val database = FirebaseDatabase.getInstance()

    private var appsList: List<String> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
        sessionKey = prefs.getString("SESSION_KEY", "") ?: ""

        btnRefreshApps = findViewById(R.id.btnRefreshApps)
        tvWatchedApp = findViewById(R.id.tvWatchedApp)
        lvApps = findViewById(R.id.lvApps)

        btnRefreshApps.setOnClickListener {
            requestAppsList()
        }

        lvApps.setOnItemClickListener { _, _, position, _ ->
            val selectedApp = appsList[position]
            confirmWatchApp(selectedApp)
        }

        listenForAppsResult()
        listenForWatchedApp()
    }

    private fun requestAppsList() {
        if (sessionKey.isEmpty()) return
        val commandsRef = database.getReference("sessions").child(sessionKey).child("commands").push()
        val data = mapOf(
            "command" to "GET_INSTALLED_APPS",
            "timestamp" to System.currentTimeMillis()
        )
        commandsRef.setValue(data).addOnSuccessListener {
            Toast.makeText(this, "Requested apps list...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listenForAppsResult() {
        if (sessionKey.isEmpty()) return
        val resultsRef = database.getReference("sessions").child(sessionKey).child("results")
        resultsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val command = child.child("command").getValue(String::class.java)
                    if (command == "GET_INSTALLED_APPS") {
                        val appsString = child.child("data").getValue(String::class.java) ?: ""
                        if (appsString.isNotEmpty()) {
                            appsList = appsString.split("\n").filter { it.isNotBlank() }
                            val adapter = ArrayAdapter(this@AppsActivity, android.R.layout.simple_list_item_1, appsList)
                            lvApps.adapter = adapter
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForWatchedApp() {
        if (sessionKey.isEmpty()) return
        val stateRef = database.getReference("sessions").child(sessionKey).child("state").child("watchedApp")
        stateRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val watchedApp = snapshot.getValue(String::class.java)
                if (watchedApp != null && watchedApp.isNotEmpty()) {
                    tvWatchedApp.text = "Currently Watched App: $watchedApp"
                } else {
                    tvWatchedApp.text = "Currently Watched App: None"
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun confirmWatchApp(appInfo: String) {
        val packageName = appInfo.substringAfterLast("(").removeSuffix(")")
        AlertDialog.Builder(this)
            .setTitle("Watch App")
            .setMessage("Do you want to receive alerts when '$packageName' is opened?")
            .setPositiveButton("Yes") { _, _ ->
                setWatchedApp(packageName)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun setWatchedApp(packageName: String) {
        if (sessionKey.isEmpty()) return
        // We set the state directly in Firebase for both devices to see
        database.getReference("sessions").child(sessionKey).child("state").child("watchedApp").setValue(packageName)
        Toast.makeText(this, "Watching: $packageName", Toast.LENGTH_SHORT).show()
    }
}
