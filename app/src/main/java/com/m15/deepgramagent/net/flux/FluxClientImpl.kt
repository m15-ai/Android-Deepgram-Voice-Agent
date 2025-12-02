package com.m15.deepgramagent.net.flux

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString.Companion.toByteString

class FluxClientImpl(
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build(),
    private val useMocks: Boolean = false,
    private val model: String = "flux-general-en",

    private val url: String =
        "wss://api.deepgram.com/v2/listen" +
                "?model=$model" +                  // ← official Flux model id
                "&encoding=linear16" +             // ← raw PCM 16-bit LE
                "&sample_rate=16000" +
                // tune these:
                "&eot_threshold=0.85" +            // conservative end-of-turn
                "&eager_eot_threshold=0.75" +      // start LLM sooner; you can raise/lower
                "&eot_timeout_ms=8000",            // safety net

    private val apiKey: String = com.m15.deepgramagent.BuildConfig.DEEPGRAM_API_KEY
) : FluxClient {

    @kotlinx.serialization.Serializable data class DGAlt(val transcript: String = "")
    @kotlinx.serialization.Serializable data class DGChannel(val alternatives: List<DGAlt> = emptyList())
    @kotlinx.serialization.Serializable data class DGResults(
        val channels: List<DGChannel> = emptyList(),
        val is_final: Boolean? = null
    )
    @kotlinx.serialization.Serializable data class DGMessage(
        val type: String? = null,            // "Connected", "TurnInfo", "Results", ...
        val event: String? = null,           // TurnInfo: "Started"|"Update"|"Stopped"
        val results: DGResults? = null,
        val transcript: String? = null,      // TurnInfo sometimes echoes text here
        val speech_started: Boolean? = null, // v1/Nova
        val speech_final: Boolean? = null    // v1/Nova
    )

    private val _events = MutableSharedFlow<FluxClient.Event>(extraBufferCapacity = 128)
    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null

    @Volatile private var wsReady = false
    private var bytesSent = 0L
    private var lastLogAt = System.currentTimeMillis()

    private val TAG = "FluxClientImpl"

    override fun connect(): Flow<FluxClient.Event> {
        if (useMocks) {
            scope?.cancel()
            scope = CoroutineScope(Dispatchers.Default)
            scope!!.launch {
                while (isActive) {
                    delay(2500)
                    _events.emit(FluxClient.Event.UserStart(System.currentTimeMillis()))
                    delay(400)
                    _events.emit(FluxClient.Event.Partial("hello", true))
                    _events.emit(FluxClient.Event.UserStop(System.currentTimeMillis()))
                }
            }
            return _events.asSharedFlow()
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .addHeader("Accept", "application/json")
            .build()

        ws = okHttp.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, resp: Response) {
                wsReady = true
                bytesSent = 0
                Log.i(TAG,String.format("Flux WS opened %d", resp.code))
            }

            private var msgCount = 0

            private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

            private var currentTurnText = StringBuilder()


            override fun onMessage(ws: WebSocket, text: String) {
                if (msgCount++ < 5) Log.d("Flux ← %s", text.take(400))
                try {
                    val m = json.decodeFromString(DGMessage.serializer(), text)

                    if (m.type == "TurnInfo") {
                        when (m.event) {
                            "StartOfTurn" -> {
                                currentTurnText.clear()
                                _events.tryEmit(FluxClient.Event.UserStart(System.currentTimeMillis()))
                            }
                            "Update" -> {
                                // Some Flux builds put rolling text in TurnInfo.transcript
                                m.transcript?.takeIf { it.isNotBlank() }?.let {
                                    // replace or append; Flux updates are usually cumulative,
                                    // but to be safe we overwrite with the latest snapshot:
                                    currentTurnText.clear()
                                    currentTurnText.append(it)
                                    _events.tryEmit(FluxClient.Event.Partial(it, false))
                                }
                            }
                            "EagerEndOfTurn", "EndOfTurn", "Stopped" -> {
                                val finalText = (m.transcript?.takeIf { it.isNotBlank() } ?: currentTurnText.toString()).trim()
                                _events.tryEmit(FluxClient.Event.UserStop(System.currentTimeMillis()))
                                if (finalText.isNotEmpty()) {
                                    _events.tryEmit(FluxClient.Event.Partial(finalText, true))
                                } else {
                                    Log.d(TAG,"Flux turn ended with empty transcript")
                                }
                                currentTurnText.clear()
                            }
                        }
                    }

                    if (m.type == "Results" && m.results != null) {
                        val alt = m.results.channels.firstOrNull()?.alternatives?.firstOrNull()?.transcript.orEmpty()
                        if (alt.isNotBlank()) {
                            val fin = m.results.is_final == true
                            if (fin) currentTurnText.clear()
                            _events.tryEmit(FluxClient.Event.Partial(alt, fin))
                        }
                    }

                    if (m.speech_started == true)  _events.tryEmit(FluxClient.Event.UserStart(System.currentTimeMillis()))
                    if (m.speech_final == true)    _events.tryEmit(FluxClient.Event.UserStop(System.currentTimeMillis()))

                } catch (t: Throwable) {
                    Log.w(TAG, String.format("DG parse fail: %s", text.take(300)))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _events.tryEmit(FluxClient.Event.Error(t))
                Log.e(TAG, String.format("Flux WS failure code=%s body=%s",
                    response?.code, response?.body?.string()))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.w(TAG,String.format("Flux WS closing code=%d reason=%s", code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG,String.format("Flux WS closed code=%d reason=%s", code, reason))
            }
        })
        return _events.asSharedFlow()
    }

    override fun sendPcm(pcm: ShortArray) {
        if (!wsReady) return
        val arr = ByteArray(pcm.size * 2)
        // little-endian
        var j = 0
        for (s in pcm) {
            arr[j++] = (s.toInt() and 0xFF).toByte()
            arr[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        if (ws?.send(arr.toByteString(0, arr.size)) == true) {
            bytesSent += arr.size
            val now = System.currentTimeMillis()
            if (now - lastLogAt >= 1000) {
                //Log.d(TAG,String.format("Flux audio ↑ %d KB/s", bytesSent / 1024))
                bytesSent = 0; lastLogAt = now
            }
        }
    }

    override fun close() {
        scope?.cancel()
        ws?.close(1000, null)
        ws = null
    }

}
