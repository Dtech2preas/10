package com.jonas.x24

import com.jonas.x24.network.GroqMessage
import java.util.concurrent.CopyOnWriteArrayList

object ChatHistoryManager {
    private const val MAX_HISTORY = 10
    private val history = CopyOnWriteArrayList<GroqMessage>()

    fun addMessage(role: String, content: String) {
        history.add(GroqMessage(role, content))
        if (history.size > MAX_HISTORY) {
            history.removeAt(0)
        }
    }

    fun getHistory(): List<GroqMessage> {
        return history.toList()
    }

    fun clear() {
        history.clear()
    }
}
