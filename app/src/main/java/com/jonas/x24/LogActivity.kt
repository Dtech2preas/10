package com.jonas.x24

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var tvLogConsole: TextView
    private lateinit var btnCopyLogs: Button
    private lateinit var svLogs: ScrollView

    private val logListener: (String) -> Unit = { newLog ->
        runOnUiThread {
            tvLogConsole.append("\n$newLog")
            scrollToBottom()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tvLogConsole = findViewById(R.id.tvLogConsole)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        svLogs = findViewById(R.id.svLogs)

        // Load existing logs
        tvLogConsole.text = LogManager.getAllLogsString()

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("x24 Logs", LogManager.getAllLogsString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        LogManager.addListener(logListener)
        scrollToBottom()
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.removeListener(logListener)
    }

    private fun scrollToBottom() {
        svLogs.post { svLogs.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
