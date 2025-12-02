package com.m15.deepgramagent.tts

import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import kotlin.coroutines.coroutineContext

class DeepgramTtsClient(
    private val context: Context,
    private val okHttp: OkHttpClient,
    private val apiKey: String,
    private val model: String = "aura-2-amalthea-en",
    private val sampleRate: Int = 48_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TtsClient, com.m15.deepgramagent.SupportsSpeakerphone {
    private val TAG = "DeepgramTts"
    private val wsUrl =
        "wss://api.deepgram.com/v1/speak?model=$model&encoding=linear16&sample_rate=$sampleRate&container=none"
    private var ws: WebSocket? = null
    private val readyToSpeak = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    // Text queue so we don't lose deltas before onOpen
    private val outbox = Channel<Outgoing>(capacity = Channel.BUFFERED)
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var opened: CompletableDeferred<Unit>? = null
    private val connectMutex = Mutex()
    // Audio
    @Volatile private var audioTrack: AudioTrack? = null
    // Audio focus members
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    companion object {
        private const val TAG = "DeepgramTtsClient"
    }
    private var focusGranted = false
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var squelched = false          // hard gate for barge-in
    @Volatile private var acceptPcm = true           // quick guard for onMessage
    @Volatile private var lastBargeAt = 0L
    @Volatile private var speakerphoneEnabled: Boolean = false
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        scope.launch { senderLoop() }
    }

    private suspend fun senderLoop() {
        var lastSentAt = 0L
        val minGapMs = 0L
        while (coroutineContext.isActive) {
            val msg = outbox.receive()
            try {
                ensureConnected()
                opened?.await()
                while (!readyToSpeak.get()) delay(2)
                when (msg) {
                    is Outgoing.Speak -> {
                        val now = System.currentTimeMillis()
                        if (now - lastSentAt < minGapMs) delay(minGapMs - (now - lastSentAt))
                        lastSentAt = now
                        val payload = """{"type":"Speak","text":${JSONObject.quote(msg.text)}}"""
                        val ok = ws?.send(payload) ?: false
                        Log.d(TAG,"TTS → Speak len=${msg.text.length} send=$ok")
                        if (ok) isSpeaking.set(true)
                    }
                    Outgoing.Flush -> {
                        val ok = ws?.send("""{"type":"Flush"}""") ?: false
                        Log.d(TAG,"TTS → Flush send=$ok")
                    }
                    Outgoing.Clear -> {
                        val ok = ws?.send("""{"type":"Clear"}""") ?: false
                        Log.d(TAG,"TTS → Clear send=$ok")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Sender failed for msg; dropping and continuing")
                // Drop the message; next messages will retry connection if needed
            }
        }
    }

    private suspend fun ensureConnected() {
        if (connected.get() || connecting.get()) return
        connectMutex.withLock {
            if (connected.get() || connecting.get()) return
            connecting.set(true)
            opened = CompletableDeferred()
            requestFocusIfNeeded()
            if (audioTrack == null) audioTrack = buildAudioTrack()
            val req = Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Token $apiKey")
                .build()
            Log.i(TAG,"TTS WS CONNECT → $wsUrl")
            ws = okHttp.newWebSocket(req, object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, resp: Response) {
                    Log.i(TAG,"TTS WS OPEN ${resp.code} ${resp.message}")
                    connected.set(true)
                    readyToSpeak.set(true)
                    audioTrack?.play()
                    connecting.set(false)
                    opened?.complete(Unit)
                }
                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (squelched || !acceptPcm) {
                        // Drop any in-flight PCM while barge-in is active
                        Log.d(TAG, String.format("PCM ← dropped (%d bytes) squelched=%s accept=%s", bytes.size, squelched, acceptPcm))
                        return
                    }
                    val at = audioTrack ?: return
                    val data = bytes.toByteArray()
                    val wrote = if (Build.VERSION.SDK_INT >= 23)
                        at.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
                    else at.write(data, 0, data.size)
                    //Log.d(TAG,"PCM ← ${data.size} bytes wrote=$wrote")
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG,"TTS ← $text")
                    if (text.contains("\"type\":\"Flushed\"")) {
                        isSpeaking.set(false)
                    }
                }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG,"TTS WS closing $code $reason")
                    connected.set(false)
                    readyToSpeak.set(false)
                    connecting.set(false)
                }
                override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                    Log.e(TAG, "TTS WS failure")
                    connected.set(false)
                    readyToSpeak.set(false)
                    connecting.set(false)
                }
            })
        }
    }

    private fun applyRouting() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Throwable) { /* no-op */ }
    }

    // ----- TtsClient -----
    private fun resumeIfSquelched() {
        if (squelched) {
            Log.d(TAG,"TTS(resume) after barge-in")
            squelched = false
            acceptPcm = true
            // Ensure track exists and is playing
            if (audioTrack == null) audioTrack = buildAudioTrack()
            try { audioTrack?.play() } catch (_: Throwable) {}
        }
    }

    override fun speak(text: String) {
        resumeIfSquelched()
        scope.launch(sendDispatcher) {
            outbox.send(Outgoing.Clear)   // << prevents bleed-over
            outbox.send(Outgoing.Speak(text))
            outbox.send(Outgoing.Flush)
        }
    }

    override fun streamDelta(text: String) {
        resumeIfSquelched()
        Log.d(TAG,"TTS → enqueue '${text.take(60)}'")
        scope.launch(sendDispatcher) {
            outbox.send(Outgoing.Speak(text))
        }
    }

    override fun flush() {
        Log.d(TAG, "TTS(Flush) queued")
        // Always enqueue flush — senderLoop will apply it once WS is connected
        scope.launch(sendDispatcher) {
            outbox.send(Outgoing.Flush)
        }
    }

    override fun stop() { // barge-in
        Log.d(TAG,"TTS(BargeIn) → squelch + Clear")
        lastBargeAt = System.currentTimeMillis()
        squelched = true
        acceptPcm = false
        // Tell server to drop anything queued
        outbox.trySend(Outgoing.Clear)
        // Immediately silence local output
        audioTrack?.let {
            try {
                it.pause()
                it.flush()     // discard local buffer
            } catch (_: Throwable) {}
        }
        // Keep the WS + AudioTrack alive; we’ll resume on the next Speak
        isSpeaking.set(false)
    }

    override fun close() {
        try { ws?.send("""{"type":"Close"}""") } catch (_: Throwable) {}
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        ws = null
        audioTrack?.let {
            try { it.pause(); it.flush(); it.stop(); it.release() } catch (_: Throwable) {}
        }
        audioTrack = null
        connected.set(false)
        readyToSpeak.set(false)
        connecting.set(false)
        abandonFocusIfNeeded()
    }

    override fun isSpeaking(): Boolean = isSpeaking.get()

    private fun buildAudioTrack(): AudioTrack {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        //val targetBuf = max(minBuf, sampleRate / 10 * 2) // ~100ms @ 16-bit mono
        val targetBuf = max(minBuf, sampleRate / 5 * 2)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(targetBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
        return builder.build()
    }
    // ----- Audio focus -----
    private fun requestFocusIfNeeded() {
        if (focusGranted) return
        focusGranted = try {
            if (Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setOnAudioFocusChangeListener { }
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusRequest = r
                audioManager.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) { false }
        if (focusGranted) applyRouting()
    }
    override fun setSpeakerphoneEnabled(enabled: Boolean) { // NEW
        speakerphoneEnabled = enabled
        applyRouting()
    }

    private fun abandonFocusIfNeeded() {
        if (!focusGranted) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
            }
        } catch (_: Throwable) { /* no-op */ }
        focusGranted = false
        focusRequest = null
    }
}
/** New TtsClient interface used by the app. */
interface TtsClient {
    fun speak(text: String)
    fun streamDelta(delta: String)
    fun flush()
    fun stop()
    fun close() {}
    fun isSpeaking(): Boolean
    //fun setAudioSessionId(id: Int)
}
private sealed interface Outgoing {
    data class Speak(val text: String) : Outgoing
    data object Flush : Outgoing
    data object Clear : Outgoing
}