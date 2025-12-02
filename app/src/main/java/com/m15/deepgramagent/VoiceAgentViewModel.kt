package com.m15.deepgramagent

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m15.deepgramagent.audio.AudioCapture
import com.m15.deepgramagent.net.flux.FluxClient
import com.m15.deepgramagent.net.openai.RealtimeClient
import com.m15.deepgramagent.tts.TtsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.media.AudioDeviceCallback
import android.util.Log
import com.m15.deepgramagent.util.areSimilar

interface SupportsSpeakerphone {
    fun setSpeakerphoneEnabled(enabled: Boolean)
}
data class AgentUiState(
    val sessionId: String? = null,
    val sessionActive: Boolean = false,
    val livePartial: String? = null,       // user live transcription from Flux
    val assistantLive: String? = null,     // assistant streaming text
    val isThinking: Boolean = false,
    val error: String? = null,
    val messages: List<Pair<String, String>> = emptyList(),
    val speakerOn: Boolean = false
)
class VoiceAgentViewModel(
    private val flux: FluxClient = ServiceLocator.flux,
    private val realtime: RealtimeClient = ServiceLocator.realtime,
    private val tts: TtsClient = ServiceLocator.tts,
    private val audio: AudioCapture = ServiceLocator.audio,
    private val barge: BargeInController = ServiceLocator.barge
) : ViewModel() {
    private val am: AudioManager = ServiceLocator.audioManager
    private val _ui = MutableStateFlow(AgentUiState())
    val ui: StateFlow<AgentUiState> = _ui
    private var micStarted = false
    private var sttJob: Job? = null
    private var llmJob: Job? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    companion object {
        private const val TAG = "VoiceAgentViewModel"
    }

    // ---- Speakerphone control API (bind to your FAB) ----
    fun toggleSpeaker() = setSpeaker(!ui.value.speakerOn)
    fun setSpeaker(enabled: Boolean) {
        _ui.update { it.copy(speakerOn = enabled) }
        applyRouting()
        (tts as? SupportsSpeakerphone)?.setSpeakerphoneEnabled(enabled)
    }
    private fun applyRouting() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val speakerOn = ui.value.speakerOn
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val preferredTypes = if (speakerOn) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
                    listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    )
                }
                val devices = am.availableCommunicationDevices
                for (type in preferredTypes) {
                    val dev = devices.firstOrNull { it.type == type }
                    if (dev != null) {
                        am.setCommunicationDevice(dev)
                        Log.i(TAG,"Routed to type $type (speakerOn=$speakerOn)")
                        return
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                if (speakerOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = true
                    Log.i(TAG,"Routed to speaker (pre-31, speakerOn=$speakerOn)")
                } else {
                    val allDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    if (allDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                        am.isSpeakerphoneOn = false
                        Log.i(TAG,"Routed to Bluetooth SCO (pre-31, speakerOn=$speakerOn)")
                    } else {
                        am.isSpeakerphoneOn = false
                        Log.i(TAG,"Routed to wired headset or earpiece (pre-31, speakerOn=$speakerOn)")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyRouting failed (speakerOn=$speakerOn)")
        }
    }

    fun startSession() {
        if (ui.value.sessionActive) return
        // Register audio device callback for dynamic routing changes
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                applyRouting()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                applyRouting()
            }
        }
        am.registerAudioDeviceCallback(audioDeviceCallback, null)
        // Apply initial routing
        applyRouting()
        viewModelScope.launch {
            val sid = ServiceLocator.repo.newSession("Voice Chat")
            _ui.update { it.copy(sessionId = sid, sessionActive = true, error = null) }
            // ---- OpenAI Realtime: collect deltas; pipe to TTS ----
            llmJob?.cancel()
            llmJob = viewModelScope.launch {
                Log.i(TAG,"Realtime: connecting…")
                realtime.connect().collect { ev ->
                    when (ev) {
                        is RealtimeClient.Event.Connected -> {
                            Log.i(TAG,"Realtime: connected")
                            _ui.update { it.copy(isThinking = false) }
                        }
                        is RealtimeClient.Event.TextDelta -> {
                            val delta = ev.text
                            if (delta.isNotEmpty()) {
                                _ui.update { st ->
                                    st.copy(
                                        isThinking = true,
                                        assistantLive = (st.assistantLive ?: "") + delta
                                    )
                                }
                                runCatching { tts.streamDelta(delta) }
                                    .onFailure { Log.w(TAG, "TTS streamDelta failed") }
                            }
                        }
                        is RealtimeClient.Event.TextCompleted -> {
                            val finalText = ev.text.ifBlank { ui.value.assistantLive.orEmpty() }.trim()
                            Log.i(TAG,"Realtime: completed → ${finalText.take(80)}")
                            if (finalText.isNotEmpty()) {
                                ui.value.sessionId?.let { ServiceLocator.repo.addAssistantText(it, finalText) }
                                _ui.update { st ->
                                    st.copy(
                                        isThinking = false,
                                        assistantLive = null,
                                        messages = st.messages + ("assistant" to finalText)
                                    )
                                }
                            } else {
                                _ui.update { it.copy(isThinking = false, assistantLive = null) }
                            }
                            runCatching { tts.flush() }
                                .onFailure { Log.w(TAG, "TTS flush failed") }
                        }
                        is RealtimeClient.Event.Error -> {
                            Log.w(TAG, "Realtime error")
                            _ui.update { it.copy(isThinking = false, error = ev.t.message) }
                            runCatching { tts.flush() }
                        }
                    }
                }
            }
            // Deepgram Flux STT: partial + final; barge-in integration
            sttJob?.cancel()
            sttJob = viewModelScope.launch {
                Log.i(TAG,"Flux: connecting…")
                flux.connect().collect { e ->
                    barge.onFluxEvent(e) // handles barge-in (tts.stop + realtime.cancel)
                    when (e) {
                        is FluxClient.Event.Partial -> {
                            if (e.isFinal) {
                                val text = e.text.trim()
                                val lastUser = ui.value.messages.lastOrNull { it.first == "user" }?.second.orEmpty()
                                if (text.isNotEmpty() && text != lastUser && !areSimilar(text, lastUser)) {  // Add !areSimilar
                                    _ui.value.sessionId?.let { ServiceLocator.repo.addUserText(it, text) }
                                    _ui.update { st ->
                                        st.copy(
                                            livePartial = null,
                                            messages = st.messages + ("user" to text)
                                        )
                                    }
                                    Log.i(TAG, "LLM ⇢ sending user text: %s, $text")
                                    realtime.sendUserText(text)
                                } else {
                                    _ui.update { it.copy(livePartial = null) }
                                    if (areSimilar(text, lastUser)) {
                                        Log.d(TAG, "Skipped duplicate/similar user text: $text (similar to $lastUser)")
                                    }
                                }
                            } else {
                                _ui.update { it.copy(livePartial = e.text) }
                            }
                        }
                        else -> Unit
                    }
                }
            }
            // ---- Start mic AFTER collectors are live ----
            if (!micStarted) {
                runCatching {
                    audio.start { pcm -> flux.sendPcm(pcm) }
                }.onSuccess {
                    micStarted = true
                    Log.i(TAG,"Mic started → streaming PCM to Flux")
                    // Share session ID with TTS for better AEC
                    //val sessionId = audio.getAudioSessionId()
                    //if (sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE) {
                    //    tts.setAudioSessionId(sessionId)
                    //}
                }.onFailure {
                    Log.e(TAG, "Failed to start mic")
                    _ui.update { s -> s.copy(error = it.message) }
                }
            }
        }
    }
    fun stopSession() {
        if (!ui.value.sessionActive) return
        _ui.update { it.copy(sessionActive = false, isThinking = false, livePartial = null, assistantLive = null) }
        runCatching { tts.stop() }
        runCatching { realtime.close() }
        runCatching { flux.close() }
        runCatching { audio.stop() }
        micStarted = false
        sttJob?.cancel(); sttJob = null
        llmJob?.cancel(); llmJob = null
        am.mode = AudioManager.MODE_NORMAL
        // Unregister audio device callback
        audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        Log.i(TAG,"Session stopped")
    }
}