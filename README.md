# **Deepgram Android Voice Agent**

*A realtime voice AI agent for Android, built for ultra-low latency.*

This project demonstrates a **full end-to-end mobile voice AI stack** using:

- **Deepgram Flux** for realtime STT
- **Deepgram Aura** for streaming TTS
- **OpenAI Realtime API** as the LLM layer
- **Native Android audio pipeline** (AEC, NS, AGC, Bluetooth routing, AudioRecord/AudioTrack)
- **Full barge-in support** (interrupt the AI mid-response)

The entire flow is designed for **near-instant conversation** with round-trip latencies typically between **500–700ms**, even on mediocre networks.

------

## Why Build It This Way?

Most “voice agent” demos rely on SDK wrappers.
 This project takes the harder route:

**Everything is split and controlled inside the Android app.**

- You control STT, LLM, TTS independently
- You can plug in **any LLM provider**
- You get native access to Android’s audio stack
- You can tune latency, routing, thresholds, buffers
- You can enforce your own barge-in rules
- You can deeply debug what's happening in real time

This gives significantly more flexibility and performance when building **production-grade mobile agents**.

------

## Features

- **Realtime STT** using Deepgram Flux
- **Text deduplication** with fuzzy similarity checks
- **Realtime OpenAI LLM** with streamed deltas
- **Delta TTS playback** using Deepgram Aura
- **Full barge-in interrupt support**
- **AEC, NS, AGC** via Android hardware when available
- **Routing:** speakerphone, wired headset, Bluetooth
- **Compose UI:** chat bubbles, live partials, “thinking…” indicator
- **Room DB:** session + message history

------

## Architecture Overview

**MVVM pattern**

- `VoiceAgentViewModel` orchestrates STT → LLM → TTS
- Repositories handle persistence
- WebSocket clients handle audio/LLM streams

**Audio pipeline**

1. `AudioCapture.kt` → sends PCM to Flux
2. Flux → partial & final transcripts
3. Similarity check → push final text to OpenAI
4. OpenAI → streamed deltas
5. `DeepgramTtsClient.kt` → delta TTS → AudioTrack playback
6. Barge-in cancels all outgoing audio instantly

------

## Key Files

- `VoiceAgentViewModel.kt` — core logic, routing, barge-in
- `AudioCapture.kt` — mic, AEC/NS/AGC
- `FluxClientImpl.kt` — STT WebSocket
- `RealtimeClientImpl.kt` — OpenAI Realtime
- `DeepgramTtsClient.kt` — TTS streaming
- `BargeInController.kt` — interruption logic
- `VoiceAgentScreen.kt` — Compose UI
- `StringUtils.kt` — Levenshtein similarity dedupe

(See full file list in repo.)

------

## API Keys

Add this to **local.properties** (never committed to GitHub):

```
DEEPGRAM_API_KEY=your_key
OPENAI_API_KEY=your_key
```

Gradle injects them as `BuildConfig.DEEPGRAM_API_KEY` and `BuildConfig.OPENAI_API_KEY`.

------

## Build & Run

- **Android 8.0+** (API 26+)
- Physical device required for audio routing + echo cancellation
- Press start → talk → interrupt → talk again
- Toggle speakerphone for AEC

------

## Debugging & Performance Tuning

Verbose logging helps track:

- WebSocket state
- AEC availability and attach
- Audio routing changes
- STT partials & final timing
- LLM delta timing
- TTS buffer behavior

Latency can be improved via:

- Smaller PCM chunks
- Tweaked Flux EOT thresholds
- Parallel coroutine processing
- More aggressive dedupe logic

------

## Contributions Welcome

PRs, issues, and feature suggestions are encouraged—especially around:

- Lower-latency audio routing
- Additional LLM integrations
- Better barge-in heuristics
- Multi-turn conversation memory