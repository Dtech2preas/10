package com.jonas.x24

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
        val savedRole = prefs.getString("ROLE", null)
        val savedKey = prefs.getString("SESSION_KEY", null)

        if (savedRole != null && savedKey != null) {
            if (savedRole == "MONITOR") {
                val intent = Intent(this, MonitorActivity::class.java)
                intent.putExtra("SESSION_KEY", savedKey)
                startActivity(intent)
                finish()
                return
            } else if (savedRole == "BE_MONITORED") {
                val intent = Intent(this, BeMonitoredActivity::class.java)
                intent.putExtra("SESSION_KEY", savedKey)
                startActivity(intent)
                finish()
                return
            }
        }

        setContentView(R.layout.activity_main)

        val etSessionKey = findViewById<EditText>(R.id.etSessionKey)
        val btnMonitor = findViewById<Button>(R.id.btnMonitor)
        val btnBeMonitored = findViewById<Button>(R.id.btnBeMonitored)

        btnMonitor.setOnClickListener {
            val key = etSessionKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter a session key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("ROLE", "MONITOR").putString("SESSION_KEY", key).apply()
            val intent = Intent(this, MonitorActivity::class.java)
            intent.putExtra("SESSION_KEY", key)
            startActivity(intent)
            finish()
        }

        btnBeMonitored.setOnClickListener {
            val key = etSessionKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter a session key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("ROLE", "BE_MONITORED").putString("SESSION_KEY", key).apply()
            val intent = Intent(this, BeMonitoredActivity::class.java)
            intent.putExtra("SESSION_KEY", key)
            startActivity(intent)
            finish()
        }
    }
}
