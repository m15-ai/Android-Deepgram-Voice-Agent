package com.m15.deepgramagent.audio

import android.Manifest
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import java.nio.ShortBuffer
import kotlin.math.max

interface AudioCapture {
    fun start(onPcm: (ShortArray) -> Unit)
    fun stop()
}

class DefaultAudioCapture(private val ctx: Context) : AudioCapture {
    private val TAG = "AudioCapture"
    private var job: Job? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat  = AudioFormat.ENCODING_PCM_16BIT

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(onPcm: (ShortArray) -> Unit) {
        stop()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = max(minBuf, sampleRate / 20 * 2) // ~50ms, 16-bit mono
        record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)  // Enables AEC
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .build()
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG,"AudioRecord initialization failed")
            return
        }
        val sessionId = record!!.audioSessionId
        // Attach AcousticEchoCanceler if available
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)
            aec?.enabled = true
            Log.i(TAG,"AEC attached and enabled")
        } else {
            Log.w(TAG,"AEC not available on this device")
        }
        // Attach NoiseSuppressor if available
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)
            ns?.enabled = true
            Log.i(TAG,"NoiseSuppressor attached and enabled")
        } else {
            Log.w(TAG,"NoiseSuppressor not available")
        }
        // Attach AutomaticGainControl if available
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)
            agc?.enabled = true
            Log.i(TAG,"AutomaticGainControl attached and enabled")
        } else {
            Log.w(TAG,"AutomaticGainControl not available")
        }
        record?.startRecording()
        job = CoroutineScope(Dispatchers.Default).launch {
            val frameMs = 20
            val frameSamples = sampleRate * frameMs / 1000
            val frame = ShortArray(frameSamples)
            while (isActive) {
                val n = record?.read(frame, 0, frame.size) ?: 0
                if (n > 0) {
                    // If short read, only send filled part
                    if (n == frame.size) onPcm(frame)
                    else onPcm(ShortArray(n).also { ShortBuffer.wrap(it).put(frame, 0, n) })
                } else if (n < 0) {
                    Log.w(TAG,"AudioRecord read error: $n")
                }
            }
        }
    }
    override fun stop() {
        job?.cancel(); job = null
        record?.stop(); record?.release(); record = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        agc?.release(); agc = null
    }
}
