package com.jonas.x24

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogManager {
    private val logs = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val formattedLog = "[$timestamp] $message"
        logs.add(formattedLog)

        listeners.forEach { it.invoke(formattedLog) }
    }

    fun getLogs(): List<String> {
        return logs.toList()
    }

    fun getAllLogsString(): String {
        return logs.joinToString("\n")
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        logs.clear()
        listeners.forEach { it.invoke("--- Logs Cleared ---") }
    }
}
