package com.jonas.x24

import android.content.SharedPreferences

object TokenManager {
    private const val PREFS_KEY = "groq_tokens"
    private var currentIndex = 0

    fun getTokens(prefs: SharedPreferences): List<String> {
        val str = prefs.getString(PREFS_KEY, "") ?: ""
        if (str.isEmpty()) {
            val oldToken = prefs.getString("groq_token", "") ?: ""
            if (oldToken.isNotEmpty()) {
                saveTokens(prefs, listOf(oldToken))
                return listOf(oldToken)
            }
            return emptyList()
        }
        return str.split(",").filter { it.isNotBlank() }
    }

    fun saveTokens(prefs: SharedPreferences, tokens: List<String>) {
        prefs.edit().putString(PREFS_KEY, tokens.joinToString(",")).apply()
    }

    @Synchronized
    fun getNextToken(prefs: SharedPreferences): String? {
        val tokens = getTokens(prefs)
        if (tokens.isEmpty()) return null

        if (currentIndex >= tokens.size) {
            currentIndex = 0
        }

        val token = tokens[currentIndex]
        currentIndex = (currentIndex + 1) % tokens.size
        return token
    }
}
