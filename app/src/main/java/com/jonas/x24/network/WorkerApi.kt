package com.jonas.x24.network

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

data class ChatRequest(
    val messages: List<Message>
)

data class Message(
    val role: String,
    val content: String
)

data class ChatResponse(
    val reply: String
)

data class TtsRequest(
    val tts_text: String,
    val voice: String = "en-US-AndrewMultilingualNeural"
)

interface WorkerApi {
    @POST("/")
    suspend fun chat(@Body request: ChatRequest): ChatResponse

    @POST("/")
    suspend fun tts(@Body request: TtsRequest): ResponseBody
}
