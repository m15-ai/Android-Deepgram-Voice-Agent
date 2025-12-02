package com.m15.deepgramagent.net.openai

import kotlinx.coroutines.flow.Flow

interface RealtimeClient {
    fun connect(): Flow<Event>
    fun sendUserText(text: String)
    fun cancelResponse()
    fun close()

    sealed interface Event {
        data class Connected(val info: String) : Event
        data class TextDelta(val text: String) : Event
        data class TextCompleted(val text: String) : Event
        data class Error(val t: Throwable) : Event
    }
}


