package com.m15.deepgramagent.net.openai

import android.util.Log
import com.m15.deepgramagent.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*

class RealtimeClientImpl(
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build(),

    //private val model: String = "gpt-4o-mini-realtime-preview"
    private val model: String = "gpt-realtime"
) : RealtimeClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableSharedFlow<RealtimeClient.Event>(extraBufferCapacity = 128)
    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var systemInjected = false
    private val TAG = "RealtimeClientImpl"
    
    override fun connect() = run {
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        ws = okHttp.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ready = true
                systemInjected = false
                _events.tryEmit(RealtimeClient.Event.Connected("ok"))
                Log.i(TAG, "OpenAI Realtime WS opened ${response.code}")

                // Send system prompt
                if (!systemInjected) {
                    ws?.send(systemItemJson())
                    systemInjected = true
                    Log.i(TAG, "Injected system prompt")
                }
                // Send the real output-token configuration
                sendDefaultResponseConfig()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val eventType = json.decodeFromString<EventTypeWrapper>(text).type
                    when (eventType) {
                        "response.text.delta" -> {
                            // No debug log here – deltas are too chatty
                            val deltaEvent = json.decodeFromString<TextDeltaEvent>(text)
                            if (deltaEvent.delta.isNotEmpty()) {
                                _events.tryEmit(RealtimeClient.Event.TextDelta(deltaEvent.delta))
                            }
                        }
                        "response.text.done" -> {
                            Log.d(TAG, "OpenAI ← ${text.take(100)}")
                            val doneEvent = json.decodeFromString<TextDoneEvent>(text)
                            if (doneEvent.text.isNotEmpty()) {
                                _events.tryEmit(RealtimeClient.Event.TextCompleted(doneEvent.text))
                            }
                        }
                        "response.created" -> {
                            Log.d(TAG, "OpenAI ← ${text.take(100)}")
                            active = true
                        }
                        "response.done" -> {
                            Log.d(TAG, "OpenAI ← ${text.take(100)}")
                            active = false
                        }
                        else -> {
                            // Log unexpected stuff once, and highlight errors
                            Log.d(TAG, "OpenAI ← ${text.take(100)}")
                            if (text.contains("\"error\"")) {
                                Log.w(TAG, "OpenAI Realtime error: ${text.take(100)}")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Parse fail: ${text.take(100)}", t)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                _events.tryEmit(RealtimeClient.Event.Error(t))
                Log.e(TAG, String.format("OpenAI Realtime WS failure %s", response?.code))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                webSocket.close(1000, null)
            }
        })
        _events.asSharedFlow()
    }

    @Volatile private var active = false
    override fun cancelResponse() {
        if (!ready) return
        if (!active) { Log.d(TAG,"Cancel ignored: no active response"); return }
        ws?.send("""{"type":"response.cancel"}""")
    }

    override fun sendUserText(text: String) {
        if (!ready) {
            Log.w(TAG,"OpenAI WS not ready; drop text")
            return
        }

        // Append user message
        val userItem = """
              {
                "type": "conversation.item.create",
                "item": {
                  "type": "message",
                  "role": "user",
                  "content": [
                    { "type": "input_text", "text": ${text.escapeJson()} }
                  ]
                }
              }
            """.trimIndent()
        ws?.send(userItem)

        // Ask for TEXT ONLY (no audio)
        val respCreate = """
              {
                "type": "response.create",
                "response": {
                  "modalities": ["text"],
                  "instructions": "Stay fun, friendly, and VERY brief — 1–2 sentences max. Casual tone. No Emojis"
                }
              }
            """.trimIndent()

        val ok = ws?.send(respCreate) == true
        Log.i(TAG,String.format("OpenAI request → %s (send=%s)", text.take(80), ok))
    }

    private fun sendDefaultResponseConfig() {
        val cfg = """
      {
        "type": "session.update",
        "session": {
          "response": {
            "max_output_tokens": 300,
            "modalities": ["text"]
          }
        }
      }
    """.trimIndent()

        ws?.send(cfg)
        Log.i(TAG, "Injected response.max_output_tokens")
    }

    override fun close() {
        ready = false
        systemInjected = false
        ws?.close(1000, null)
        ws = null
    }
}

// Data classes for JSON parsing
@Serializable
private data class EventTypeWrapper(val type: String)

@Serializable
private data class TextDeltaEvent(
    val type: String,
    val delta: String
)

@Serializable
private data class TextDoneEvent(
    val type: String,
    val text: String
)

private fun systemItemJson(): String = """
  {
    "type": "conversation.item.create",
    "item": {
      "type": "message",
      "role": "system",
      "max_output_tokens": 300,
      "content": [
        {
          "type": "input_text",
          "text": "You are a fun, witty, friendly, emotional assistant. Keep EVERY reply VERY short (1–2 sentences). Speak casually, like a cheerful friend. Avoid long explanations. No emojis in the response."
        }
      ]
    }
  }
""".trimIndent()

// tiny helper so we can inline JSON safely
private fun String.escapeJson(): String = buildString {
    append('"')
    for (ch in this@escapeJson) {
        when (ch) {
            '\\' -> append("\\\\")
            '"'  -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}