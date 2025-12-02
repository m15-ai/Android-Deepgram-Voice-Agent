package com.m15.deepgramagent

import com.m15.deepgramagent.net.flux.FluxClient
import com.m15.deepgramagent.net.openai.RealtimeClient
import com.m15.deepgramagent.tts.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BargeInController(
    private val realtime: RealtimeClient,
    private val tts: TtsClient
) {
    private val userSpeaking = AtomicBoolean(false)
    private var pendingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onFluxEvent(e: FluxClient.Event) {
        when (e) {
            is FluxClient.Event.UserStart -> {
                if (userSpeaking.getAndSet(true)) return
                // Debounce: wait briefly; if the user is STILL speaking, preempt.
                pendingJob?.cancel()
                pendingJob = scope.launch {
                    delay(150)
                    if (userSpeaking.get() && tts.isSpeaking()) {
                        realtime.cancelResponse()
                        tts.stop()
                    }
                }
            }
            is FluxClient.Event.UserStop -> {
                userSpeaking.set(false)
                pendingJob?.cancel()
                pendingJob = null
            }
            else -> Unit
        }
    }
}