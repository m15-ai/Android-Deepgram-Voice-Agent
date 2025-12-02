package com.m15.deepgramagent

import android.content.Context
import android.media.AudioManager
import okhttp3.OkHttpClient

import com.m15.deepgramagent.audio.DefaultAudioCapture
import com.m15.deepgramagent.data.db.AppDatabase
import com.m15.deepgramagent.data.repo.ConversationRepository
import com.m15.deepgramagent.net.flux.FluxClient
import com.m15.deepgramagent.net.flux.FluxClientImpl
import com.m15.deepgramagent.net.openai.RealtimeClient
import com.m15.deepgramagent.net.openai.RealtimeClientImpl
import com.m15.deepgramagent.tts.TtsClient

object ServiceLocator {

    private var initialized = false

    lateinit var repo: ConversationRepository
    lateinit var flux: FluxClient
    lateinit var realtime: RealtimeClient
    lateinit var tts: TtsClient
    lateinit var audio: DefaultAudioCapture
    lateinit var barge: BargeInController
    lateinit var audioManager: AudioManager

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true

        audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val db = AppDatabase.get(ctx)
        repo = ConversationRepository(db)

        val ok = OkHttpClient()

        flux = FluxClientImpl(okHttp = ok, useMocks = false)

        // OpenAI Realtime (real)
        //   "gpt-4o-mini-realtime-preview"  or  "gpt-realtime"
        realtime = RealtimeClientImpl(okHttp = ok, model = "gpt-4o-mini-realtime-preview")

        // Deepgram TTS (real)
        val dgTts = com.m15.deepgramagent.tts.DeepgramTtsClient(
            context = ctx,
            okHttp = ok,
            apiKey = BuildConfig.DEEPGRAM_API_KEY,
            model = "aura-2-amalthea-en",
            sampleRate = 48_000
        )
        tts = dgTts
        audio = DefaultAudioCapture(ctx)
        barge = BargeInController(realtime = realtime, tts = dgTts)
    }
}