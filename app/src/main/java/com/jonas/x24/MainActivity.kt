package com.jonas.x24

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val intent = Intent(this, MonitorActivity::class.java)
            intent.putExtra("SESSION_KEY", key)
            startActivity(intent)
        }

        btnBeMonitored.setOnClickListener {
            val key = etSessionKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter a session key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, BeMonitoredActivity::class.java)
            intent.putExtra("SESSION_KEY", key)
            startActivity(intent)
        }
    }
}
