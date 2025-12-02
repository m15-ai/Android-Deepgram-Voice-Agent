package com.m15.deepgramagent.data.model

data class ChatMessage(
    val role: String, // "user" | "assistant"
    val text: String,
    val ts: Long = System.currentTimeMillis()
)
