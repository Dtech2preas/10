package com.jonas.x24

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jonas.x24.services.FirebaseCommandService

class BeMonitoredActivity : AppCompatActivity() {

    private lateinit var sessionKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_be_monitored)

        sessionKey = intent.getStringExtra("SESSION_KEY") ?: return finish()

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            startMonitoringService()
        }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, FirebaseCommandService::class.java)
        intent.putExtra("SESSION_KEY", sessionKey)
        startForegroundService(intent)
        Toast.makeText(this, "Monitoring Service Started", Toast.LENGTH_SHORT).show()
    }
}
