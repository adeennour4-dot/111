# ZeroCopy

Run LLMs entirely on your Android phone. No cloud, no account, no API key, nothing leaves your device.

Actively developed — expect rough edges, but the core works well across a wide range of devices including older Exynos chips.

---

## Quick overview

| | |
|---|---|
| **What it is** | Local LLM chat app + multi-agent project generator, all on-device |
| **Engines** | llama.cpp (GGUF), MNN, LiteRT-LM — auto-selected by file type |
| **Chipsets tested** | Snapdragon (8 Elite, 888+), Exynos (2200, 9825) |
| **Min Android** | 10 (API 29), arm64-v8a |
| **Network use** | None required for chat. Optional for web search / model downloads. |

---

## Core features

**Chat** — Standard streaming conversation. Sessions auto-save and auto-name from your first message.

**Web search** — Toggle the search icon in the input bar. The model can look things up before answering. Works with any model now — tokens stream live as the model generates, the earlier bug where generation appeared to stop after one token in search mode is fixed.

**Document Q&A (RAG)** — Attach a PDF or text file, ask questions about it. BM25 keyword search finds relevant chunks, no embedding model needed, fully offline. Tested up to ~100 pages.

**Vision input** — Attach photos if you've loaded a vision-capable model (LLaVA, Qwen2-VL, Gemma3 multimodal) with the matching `mmproj` file set in settings.

**Voice input / TTS** — Mic button dictates instead of typing. Speaker icon reads the last response aloud. Both use Android's built-in speech services.

**Local inference server** — Exposes an OpenAI-compatible API on your local network so other apps can query your model. WiFi-only mode and auto-start on boot are both available from the Cloud screen.

**Thinking mode** — Wraps your prompt to request step-by-step `<think>` reasoning. Best with models trained for chain-of-thought (Qwen3, DeepSeek R1).

**Export & Benchmark** — Share any chat as text or JSON. Measure prefill/decode tokens-per-second for your loaded model.

---

## Invent — new multi-agent project generator

A separate screen, accessible from the bottom nav, that turns a spoken idea into a real project file structure — entirely on-device, three small models taking turns so nothing exceeds your phone's RAM.

**How a session goes:**

1. **Setup** — Pick three GGUF models: a planner, a coder, and a small ~1B researcher. The app reads each model's context window directly from its file metadata, no model load needed for that step.
2. **Questioning** — The planner model asks you questions one at a time about your idea (platform, language, features, what makes it different) until it has everything it needs.
3. **Search** — Tap "Done talking" and the planner writes a structured blueprint (we call it ZCP) plus a list of things to look up. The researcher model loads, fetches from a small set of trusted domains, extracts what's relevant, then unloads. This repeats up to a few rounds if gaps remain.
4. **Planning** — The planner reloads with the research results, designs the full file tree, and chunks the implementation plan to fit inside the coder model's context window.
5. **Confirm** — The coder model loads, reads the blueprint, and tells you exactly what it understood. You hit **Sure** to generate the folder structure, or **Not Sure** to merge both attempts into a refined session and try again (capped at 2 merges).

Models never run simultaneously — only one is ever loaded in RAM at a time, so this works on 8–12 GB devices. Every step is saved to disk, so the app surviving a kill mid-session just resumes where it left off.

There's also an offline toggle: skip the web fetch entirely and let the researcher work from its own training knowledge instead, with results clearly flagged as unverified.

---

## Device compatibility

Specific fixes have gone into supporting lower-end and older chipsets, not just current flagships:

- **Samsung Note 10 Lite (Exynos 9825, ARMv8.2-a)** — downgraded CMake `-march` flags, fixed Exynos GPU detection, flash attention auto-disables when the CPU lacks `i8mm` support (checked at runtime from `/proc/cpuinfo`)
- **Samsung S25 Ultra (Snapdragon 8 Elite)** — verified full compatibility including flash attention
- **Crash recovery** — a sentinel file breaks model-load crash loops; if the app crashes mid-load, the next launch clears the broken model path automatically instead of retrying forever
- **KV cache handling** — fixed a corruption bug that could poison inference context across sessions

---

## Engines

| Engine | Format | Notes |
|--------|--------|-------|
| llama.cpp | `.gguf` | Most models on HuggingFace. Widest compatibility, required for Invent. |
| MNN | `.mnn` (folder with `config.json`) | Alibaba's framework, strong on Exynos. |
| LiteRT-LM | `.tflite` / `.litertlm` | Google's on-device runtime. |

The app picks the engine automatically from the file you load.

---

## Known limitations

- **Invent requires GGUF** — MNN and LiteRT models aren't supported in the Invent pipeline yet, only normal chat.
- **STT/TTS** — Both rely on Android's built-in services, not local neural models. Speech recognition needs internet on most devices.
- **In-app HuggingFace download** — UI exists in the Cloud screen but isn't fully wired yet. Copy model files manually for now.
- **Vision** — Only llama.cpp supports multimodal currently.
- **GPU acceleration** — Compiled out. The GPU layers setting exists but won't help on most Android GPUs right now.
- **Web search** — Depends on DuckDuckGo's HTML endpoints; can break if they change markup or rate-limit.

---

## RAM guide

| Model size | Free RAM needed |
|---|---|
| 1B | ~1 GB |
| 3B | ~2.5 GB |
| 7–8B | ~5–6 GB |
| Invent (3 models, sequential) | Same as your largest single model — they never coexist in RAM |

---

## Getting started

1. Build the app or grab the latest APK from [Releases](../../releases)
2. Get a model file — `.gguf` from HuggingFace is the easiest start
3. Copy it to your phone
4. Open ZeroCopy → tap the model name at the top → pick your file
5. Wait for load (a few seconds for KV cache warm-up), then chat

Good starting models for 6–8 GB RAM phones: Qwen3 4B, Llama 3.2 3B, Gemma 3 4B — all available as GGUF Q4_K_M.

---

## Settings reference

| Setting | What it does |
|---|---|
| Context window | Tokens the model remembers. Larger = more RAM. Start at 2048. |
| Max new tokens | Longest single response. |
| Temperature | 0.1 = factual, 0.8 = creative. |
| Top-K | Token candidate limit, 40 is a safe default. |
| Flash Attention | Faster on ARMv8.2+ chips. Auto-disabled on devices that don't support it (e.g. Exynos 9825). |
| GPU layers | Leave at 0 unless you know your device benefits. |
| Threads | Match your device's performance core count, usually 4. |
| System prompt | Custom instructions prepended to every chat. |
| mmproj | Vision encoder path for multimodal models. |

---

## Building from source

```bash
git clone https://github.com/adeennour4-dot/111
cd 111
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires Android Studio Hedgehog+, NDK 27.0.12077973, CMake 3.22.1. llama.cpp and MNN sources are fetched automatically via CMake `FetchContent` — first build takes a while.

CI builds run automatically on every push to `master`/`main` and on version tags (`v*`), which also publish a debug APK to [Releases](../../releases).

---

## Project structure

```
ui/
  chat/          Chat screen, input bar, message bubbles
  invent/        Multi-agent project generator screen
  settings/      Inference settings
  models/        Model list and file picker
  cloud/         Local server controls
  sessions/      Chat history
  welcome/       First-run screen

domain/
  inference/     Engine abstraction + LlamaCpp/MNN/LiteRT implementations
  invent/        GGUF metadata reader for Invent's context-aware planning
  rag/           BM25 document retrieval
  ocr/           PDF text extraction
  server/        Local OpenAI-compatible HTTP server

data/
  repository/    Chat and model storage
  local/         Settings persistence
  invent/        ZCP protocol, session state, domain registry

cpp/
  ipc-bridge.cpp     JNI bridge for llama.cpp
  mnn-bridge.cpp     JNI bridge for MNN
```

---

## License

Apache 2.0. Underlying libraries — llama.cpp, MNN, LiteRT-LM, Jetpack Compose — are open source under their own licenses (MIT, Apache 2.0).
