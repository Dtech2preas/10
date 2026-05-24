package com.jonas.x24

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.jonas.x24.services.FirebaseCommandService

class BeMonitoredActivity : AppCompatActivity() {

    private lateinit var sessionKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_be_monitored)

        sessionKey = intent.getStringExtra("SESSION_KEY") ?: return finish()

        findViewById<Button>(R.id.btnRequestPermissions).setOnClickListener {
            val permissions = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_CALL_LOG
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }

        findViewById<Button>(R.id.btnOpenOverlaySettings).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnOpenA11ySettings).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable 'x24 Accessibility' service", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnOpenNotificationSettings).setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
            Toast.makeText(this, "Allow notification access for 'x24'", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            startMonitoringService()
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            logout()
        }
    }

    private fun logout() {
        val prefs = getSharedPreferences("x24_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun startMonitoringService() {
        val intent = Intent(this, FirebaseCommandService::class.java)
        intent.putExtra("SESSION_KEY", sessionKey)
        startForegroundService(intent)
        Toast.makeText(this, "Monitoring Service Started", Toast.LENGTH_SHORT).show()
    }
}
