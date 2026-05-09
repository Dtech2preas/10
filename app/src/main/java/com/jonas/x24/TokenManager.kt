package com.jonas.x24

import android.content.Context
import android.content.SharedPreferences
import com.jonas.x24.network.AllTokensFailedException
import com.jonas.x24.network.GroqRequest
import com.jonas.x24.network.RetrofitClient
import com.jonas.x24.network.GroqResponse
import okhttp3.ResponseBody
import retrofit2.HttpException

object TokenManager {
    private var tokens: List<String> = emptyList()
    private var currentIndex = 0

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("x24_prefs", Context.MODE_PRIVATE)
        loadTokens(prefs)
    }

    fun loadTokens(prefs: SharedPreferences) {
        val tokensString = prefs.getString("groq_token", "") ?: ""
        tokens = tokensString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isNotEmpty() && currentIndex >= tokens.size) {
            currentIndex = 0
        }
    }

    fun getTokens(): List<String> {
        return tokens
    }

    suspend fun chatCompletions(request: GroqRequest): GroqResponse {
        if (tokens.isEmpty()) {
            throw Exception("No Groq API tokens configured.")
        }

        var attempts = 0
        val maxAttempts = tokens.size

        while (attempts < maxAttempts) {
            val token = tokens[currentIndex]
            try {
                val response = RetrofitClient.groqApi.chatCompletions("Bearer $token", request)
                // Advance index on success to round-robin load
                currentIndex = (currentIndex + 1) % tokens.size
                return response
            } catch (e: HttpException) {
                if (e.code() == 429) {
                    attempts++
                    currentIndex = (currentIndex + 1) % tokens.size
                } else {
                    throw e
                }
            } catch (e: Exception) {
                throw e
            }
        }

        throw AllTokensFailedException("Cooling off")
    }

    suspend fun chatCompletionsStream(request: GroqRequest): ResponseBody {
        if (tokens.isEmpty()) {
            throw Exception("No Groq API tokens configured.")
        }

        var attempts = 0
        val maxAttempts = tokens.size

        while (attempts < maxAttempts) {
            val token = tokens[currentIndex]
            try {
                val response = RetrofitClient.groqApi.chatCompletionsStream("Bearer $token", request)
                // We advance on success to rotate
                currentIndex = (currentIndex + 1) % tokens.size
                return response
            } catch (e: HttpException) {
                if (e.code() == 429) {
                    attempts++
                    currentIndex = (currentIndex + 1) % tokens.size
                } else {
                    throw e
                }
            } catch (e: Exception) {
                throw e
            }
        }

        throw AllTokensFailedException("Cooling off")
    }
}
