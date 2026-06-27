# ZeroCopy

An Android app for running LLMs locally on your phone. No cloud, no account, no data sent anywhere.

Still early — version 0.5, actively developed. Expect rough edges.

---

## What it does

You load a model file onto your phone and chat with it. That's the core. Everything runs on-device using one of three inference engines depending on the model format you're using.

**Three engines, three formats:**

| Engine | Format | Notes |
|--------|--------|-------|
| llama.cpp | `.gguf` | Most models on HuggingFace are this format. Widest compatibility. |
| MNN | `.mnn` (directory) | Alibaba's framework. Needs a folder with `config.json` inside. |
| LiteRT-LM | `.tflite` / `.litertlm` | Google's on-device runtime. Fewer models available. |

The app picks the engine automatically based on file extension.

---

## Features that actually work

**Chat** — Standard back-and-forth conversation with streaming output. Sessions are saved and named automatically from your first message.

**Document Q&A (RAG)** — Attach a PDF or text file and ask questions about it. Uses BM25 keyword search to find relevant chunks — no embedding model required, so it's fast and works offline. Tested up to ~100 pages; larger files get capped at 2000 chunks to avoid OOM.

**Web search** — Toggle the search icon in the input bar. When enabled, the model can call DuckDuckGo to look things up before answering. Works with most instruction-tuned models that support tool calling (Qwen3, Llama 3.1+, etc). Hit or miss with smaller models.

**Vision / image input** — Attach photos to messages. Only works if you've loaded a vision-capable model (LLaVA, Qwen2-VL, Gemma3 multimodal) and set the matching `mmproj` file in settings. If your model isn't multimodal, the camera button is disabled.

**Voice input** — Tap the mic to dictate instead of type. Uses Android's built-in speech recognition.

**Text-to-speech** — Tap the speaker icon to have the last response read aloud. Uses Android TTS.

**Local inference server** — Exposes a basic OpenAI-compatible API on your local network so other apps or scripts can query the model. Toggle it from the Cloud screen. WiFi-only mode available. Auto-start on boot if you want.

**Thinking / reasoning mode** — Toggle in the input bar. Wraps your prompt to ask the model to use `<think>` tags for step-by-step reasoning. Works best with models trained for chain-of-thought (Qwen3, DeepSeek R1).

**Export** — Share any conversation as plain text or JSON.

**Benchmark** — Measure prefill and decode speed (tokens/sec) for your loaded model.

---

## What doesn't work yet / known issues

- **STT/TTS** — Voice input uses Android's recognizer (requires internet on most devices). The in-app TTS is Android system TTS, not a local neural voice.
- **Download from HuggingFace in-app** — The model list in CloudScreen is there but the download UI isn't fully wired. For now, copy model files manually to the app's files directory or use a file manager.
- **Vision on MNN/LiteRT** — Only llama.cpp supports multimodal right now.
- **GPU acceleration** — Vulkan is compiled out. GPU layers setting exists but only helps on devices where the driver supports OpenCL via llama.cpp's CPU path. Most Android GPUs won't see improvement here.
- **Rust performance layer** — `RustCore.kt` exists but the native `.so` isn't included in this build. It falls back gracefully to defaults.
- **Web search reliability** — Depends on DuckDuckGo's HTML endpoints. Breaks if DDG changes their markup or rate-limits you.

---

## Requirements

- Android 10 (API 29) or newer
- arm64-v8a device (all modern Android phones)
- Enough RAM for your model — rough guide:
  - 1B models: ~1 GB free
  - 3B models: ~2.5 GB free
  - 7–8B models: ~5–6 GB free
  - Anything bigger will OOM on most phones

---

## Getting started

1. Build the app or install the APK
2. Get a model file — `.gguf` from HuggingFace is the easiest starting point
3. Copy it to your phone (any folder you can open with a file picker)
4. Open ZeroCopy → tap the model name at the top → pick your file
5. Wait for it to load (first load takes a few seconds for KV cache warm-up)
6. Start chatting

Good starting models for phones with 6–8 GB RAM: Qwen3 4B, Llama 3.2 3B, Gemma 3 4B — all available as GGUF Q4_K_M on HuggingFace.

---

## Settings worth knowing

| Setting | What it does |
|---------|-------------|
| Context window | How many tokens the model remembers. Larger = more RAM. Start with 2048. |
| Max new tokens | Longest response the model can generate. |
| Temperature | How creative/random responses are. 0.1 = factual, 0.8 = creative. |
| Top-K | Limits token candidates. 40 is a reasonable default. |
| Flash Attention | Faster inference on ARMv8.2+ chips (Snapdragon 888 and newer, most 2021+ flagships). Keep on unless you see errors. |
| GPU layers | How many transformer layers to offload. Leave at 0 unless you know your device supports it. |
| Threads | CPU threads for inference. Match your device's big core count (usually 4). |
| System prompt | Custom instructions prepended to every conversation. |
| mmproj | Path to the vision encoder file for multimodal models. |

---

## Building from source

```bash
git clone https://github.com/adeennour4-dot/111
cd 111
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires Android Studio Hedgehog or newer, NDK 27.0.12077973, CMake 3.22.1. The llama.cpp and MNN sources are fetched automatically by CMake at build time (`FetchContent`), so the first build takes a while.

---

## Project structure (roughly)

```
ui/
  chat/          Chat screen, input bar, message bubbles
  settings/      All inference settings
  models/        Model list and file picker
  cloud/         Local server controls
  sessions/      Chat history
  welcome/       First-run screen

domain/
  inference/     Engine abstraction + LlamaCpp/MNN/LiteRT implementations
  rag/           BM25 document retrieval
  ocr/           PDF text extraction
  server/        Local OpenAI-compatible HTTP server

data/
  repository/    Chat and model storage
  local/         Settings persistence (SharedPreferences)

cpp/
  ipc-bridge.cpp     JNI bridge for llama.cpp
  mnn-bridge.cpp     JNI bridge for MNN
```

---

## License

Apache 2.0. The underlying libraries — llama.cpp, MNN, LiteRT-LM, Jetpack Compose — are all open source with their own licenses (MIT, Apache 2.0).
