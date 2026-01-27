package com.jonas.x24.network

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

interface WorkerApi {
    @POST("/")
    suspend fun chat(@Body request: ChatRequest): ChatResponse
}
