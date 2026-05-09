package com.jonas.x24.network

import retrofit2.http.Body
import retrofit2.http.Header
import okhttp3.ResponseBody
import retrofit2.http.Streaming
import retrofit2.http.POST

data class GroqMessage(
    val role: String,
    val content: String
)

data class GroqRequest(
    val model: String = "llama3-8b-8192", // or llama3-70b-8192
    val messages: List<GroqMessage>,
    val stream: Boolean = true
)

data class GroqChoice(
    val message: GroqMessage
)

data class GroqResponse(
    val choices: List<GroqChoice>
)

interface GroqApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authHeader: String,
        @Body request: GroqRequest
    ): GroqResponse
    @Streaming
    @POST("chat/completions")
    suspend fun chatCompletionsStream(
        @Header("Authorization") authHeader: String,
        @Body request: GroqRequest
    ): ResponseBody
}
