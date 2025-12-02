package com.m15.deepgramagent.net.flux

import kotlinx.coroutines.flow.Flow

interface FluxClient {
    sealed interface Event {
        data class UserStart(val tsMs: Long): Event
        data class Partial(val text: String, val isFinal: Boolean): Event
        data class UserStop(val tsMs: Long): Event
        data class Error(val t: Throwable): Event
    }
    fun connect(): Flow<Event>
    fun sendPcm(pcm: ShortArray)
    fun close()
}
