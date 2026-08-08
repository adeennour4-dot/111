<div align="center">

# 🧠 ZeroCopy

### Run LLMs entirely on your Android phone — no cloud, no account, no API key. Nothing leaves your device.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android)]
[![Min SDK](https://img.shields.io/badge/Min%20SDK-29%20(Android%2010)-orange.svg)]
[![Build](https://img.shields.io/github/actions/workflow/status/adeennour4-dot/zerocopy-llm/build.yml?label=CI%20Build)]

**ZeroCopy** is a fully on-device LLM assistant for Android: streaming chat, RAG document Q&A, vision, voice, web search, a local OpenAI-compatible server — and **Invent**, a multi-agent project generator that plans and writes real projects from a spoken idea, using only local models.

</div>

---

## 📑 Table of Contents

- [Features](#-features)
- [The five tabs](#-the-five-tabs)
- [Invent — the multi-agent project generator](#-invent--the-multi-agent-project-generator)
- [Inference engines](#-inference-engines)
- [Getting started](#-getting-started)
- [RAM guide](#-ram-guide)
- [Settings reference](#-settings-reference)
- [Device compatibility](#-device-compatibility)
- [Building from source](#-building-from-source)
- [Project structure](#-project-structure)
- [Known limitations](#-known-limitations)
- [License](#-license)

---

## ✨ Features

### 💬 Chat & conversation
- **Streaming chat** with any local model — sessions auto-save and auto-name from your first message.
- **Thinking mode** — requests step-by-step `<think>` reasoning. Best with chain-of-thought models (Qwen3, DeepSeek R1, etc.).
- **Export & benchmark** — share any chat as text or JSON; measure prefill/decode tokens-per-second for the loaded model.

### 🔎 Web search
- Toggle the search icon in the input bar — the model can look things up before answering.
- Works with **any** model: search results are token-budgeted and injected as plain context, and tokens stream live during generation.

### 📄 Document Q&A (RAG)
- Attach a PDF or text file and ask questions about it.
- **BM25 keyword retrieval** — no embedding model required, fully offline. Tested up to ~100 pages.

### 👁️ Vision
- Attach photos when a vision-capable model is loaded (LLaVA, Qwen2-VL, Gemma3 multimodal) with the matching `mmproj` file — auto-detected next to the GGUF and applied on load.

### 🎙️ Voice input & TTS
- Mic button dictates instead of typing; speaker icon reads the last response aloud. Both use Android's built-in speech services.

### 🌐 Local inference server
- Exposes an **OpenAI-compatible API** on your local network so other apps can query your phone's model.
- WiFi-only mode and auto-start on boot are available from the Server tab.

### 🧩 Model manager
- Import models by file (`.gguf`, `.mnn`, `.tflite`, `.litertlm`), let the app pick the right engine automatically.
- Per-model settings: context, temperature, flash attention, GPU layers, threads, and more.

---

## 🧭 The five tabs

| Tab | What it does |
|---|---|
| **Chat** | Conversation, RAG attachments, vision, voice, web search, sessions. |
| **Models** | Browse imported models, load/unload, set the vision `mmproj`, per-model inference settings. |
| **Server** | Local OpenAI-compatible HTTP server: bind address, port, WiFi-only, auto-start on boot. |
| **Settings** | Global inference defaults, context window, system prompt, chat template, token budgeting. |
| **Invent** | The multi-agent project generator (below). |

---

## 🤖 Invent — the multi-agent project generator

Invent turns a spoken idea into a real, structured project — entirely on-device. Three small models take turns so **only one model is ever loaded in RAM at a time**, which means it runs comfortably on 8–12 GB devices.

### How a session works

```
Questioning → Search → Planning → Plan Review → Generation → Finalize → Done
```

1. **Setup** — pick three GGUF models: a *planner*, a *coder*, and a small *researcher*. ZeroCopy reads each model's context window directly from its file metadata — no model load needed for this step. Pick the same model for all roles to skip model-swapping entirely.
2. **Questioning** — the planner interviews you one question at a time (platform, language, features, architecture, edge cases) until your idea is fully specified.
3. **Search** — tap **Done Gathering Info** and the planner writes a structured blueprint (the *ZCP protocol*) plus research targets. The researcher model loads, fetches from a curated set of official domains (or works offline from its own knowledge, with results flagged as unverified), extracts versions and API changes, then unloads.
4. **Planning** — the planner reloads with the research, designs the full file tree, and splits the implementation so every file fits in the coder's context window.
5. **🛑 Plan Review** — the proposed file tree is shown for approval. **Approve & Generate** starts code generation; **Regenerate** asks the planner to revise the breakdown; **Cancel** returns to questioning. No more discovering a bad plan after an hour of generation.
6. **Generation** — the coder generates each file topologically (dependencies first), with per-file web lookups, sanity checks, retry-on-failure, and skip-not-abort error handling. Progress, tokens, and per-file status are shown live.
7. **Finalize** — a README with build instructions is written, and the project is ready to **export as a ZIP**.
8. **Afterwards** — browse the file tree, **Debug** any file (the model diagnoses and fixes it), ask the coder questions about a file, or export.

### Why it works on phones
- Models run **sequentially**, never simultaneously — peak RAM = your largest single model.
- Every step is **persisted to disk**: the app can be killed mid-session and resumes where it left off.
- Sessions are saved and switchable from the **Sessions** popup; project files are stored per-session (never clobbered by name collisions).
- Context is **token-budgeted** everywhere — including the JNI chat template — so small models don't overflow.

### Invent model suggestions
- **Planner:** 3–4B instruct (e.g. Qwen3 4B, Gemma 3 4B Q4_K_M)
- **Coder:** your strongest model (same 3–4B, or larger if RAM allows)
- **Researcher:** ~1B (tiny, cheap, only used for extraction)

---

## ⚙️ Inference engines

| Engine | Format | Notes |
|--------|--------|-------|
| llama.cpp | `.gguf` | Widest compatibility, most HuggingFace models. Required for Invent. |
| MNN | `.mnn` (folder with `config.json`) | Alibaba's framework — strong on Exynos. |
| LiteRT-LM | `.tflite` / `.litertlm` | Google's on-device runtime. |

The engine is selected automatically from the file you load. On top of the JNI bridges, a small Rust core (`rust_core`) provides additional on-device optimizations.

---

## 🚀 Getting started

1. **Build the app** (see [Building from source](#building-from-source)) or grab the latest APK from [Releases](https://github.com/adeennour4-dot/zerocopy-llm/releases).
2. **Get a model** — a `.gguf` from HuggingFace is the easiest start (Q4_K_M quantization is the sweet spot).
3. **Copy it to your phone**, open ZeroCopy, tap the model name in the header, and pick the file.
4. Wait for the load (a few seconds for KV-cache warm-up), then chat.

**Good starting models for 6–8 GB RAM phones:** Qwen3 4B · Llama 3.2 3B · Gemma 3 4B — all available as GGUF Q4_K_M.

---

## 🧠 RAM guide

| Model size | Free RAM needed |
|---|---|
| 1B | ~1 GB |
| 3B | ~2.5 GB |
| 7–8B | ~5–6 GB |
| Invent (3 models, sequential) | Same as your largest single model — models never coexist in RAM |

---

## ⚙️ Settings reference

| Setting | What it does |
|---|---|
| Context window | Tokens the model remembers. Larger = more RAM. Start at 2048. |
| Max new tokens | Longest single response. |
| Temperature | 0.1 = factual, 0.8 = creative. |
| Top-K | Token candidate limit — 40 is a safe default. |
| Flash Attention | Faster on ARMv8.2+ chips; auto-disabled on CPUs without `i8mm` support. |
| GPU layers | Leave at 0 unless you know your device benefits. |
| Threads | Match your device's performance-core count, usually 4. |
| System prompt | Custom instructions prepended to every chat. |
| mmproj | Vision-encoder path for multimodal models (auto-detected next to the GGUF). |

---

## 📱 Device compatibility

ZeroCopy targets **Android 10+ (API 29), arm64-v8a**, with specific fixes for lower-end and older chipsets, not just current flagships:

- **Samsung Note 10 Lite (Exynos 9825, ARMv8.2-a)** — downgraded CMake `-march` flags; fixed Exynos GPU detection; flash attention auto-disables when the CPU lacks `i8mm` support (checked at runtime from `/proc/cpuinfo`).
- **Samsung S25 Ultra (Snapdragon 8 Elite)** — verified full compatibility including flash attention.
- **Crash recovery** — a sentinel file breaks model-load crash loops: if the app crashes mid-load, the next launch clears the broken model path automatically instead of retrying forever.
- **KV-cache handling** — fixed a corruption bug that could poison inference context across sessions.

---

## 🛠️ Building from source

```bash
git clone https://github.com/adeennour4-dot/zerocopy-llm
cd 111
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Requirements**

- Android Studio Hedgehog+ (or a CLI build with JDK 17)
- NDK **27.0.12077973**, CMake **3.22.1**
- Rust toolchain (for the optional `rust_core` layer; the build script builds it via `rust_core/build_android.sh`)

llama.cpp and MNN sources are fetched automatically via CMake `FetchContent` — the first build takes a while.

**CI** — a GitHub Actions workflow (`build.yml`) runs on every push to `master`/`main` and on `v*` tags, producing both a **standard** and a **compatibility** (`-compat`) APK and attaching the debug APK to Releases.

---

## 🗂️ Project structure

```
app/src/main/java/com/gguf/zerocopy/
├── ui/
│   ├── chat/          Chat screen, input bar, message bubbles, RAG/vision/voice
│   ├── invent/        Multi-agent project generator (screen + view-model + plan review)
│   ├── models/        Model list, file picker, mmproj auto-detect
│   ├── server/        Local OpenAI-compatible HTTP server controls
│   ├── settings/      Inference settings
│   └── theme/         Material theme + palette
├── domain/
│   ├── inference/     Engine abstraction + LlamaCpp / MNN / LiteRT implementations
│   ├── invent/        GGUF metadata reader (context-aware planning)
│   ├── rag/           BM25 document retrieval
│   ├── ocr/           PDF text extraction
│   └── server/        Local HTTP server implementation
└── data/
    ├── repository/    Chat and model storage
    ├── local/         Settings persistence
    └── invent/        ZCP protocol, session state, telemetry

rust_core/             Optional Rust optimization layer (JNI crate)
cpp/                   ipc-bridge (llama.cpp) + mnn-bridge JNI bridges
```

---

## ⚠️ Known limitations

- **Invent requires GGUF** — MNN and LiteRT models aren't supported in the Invent pipeline yet (normal chat works with all engines).
- **STT/TTS** — uses Android's built-in services, not local neural models; speech recognition needs internet on most devices.
- **In-app HuggingFace download** — UI exists but isn't fully wired; copy model files manually for now.
- **Vision** — only llama.cpp supports multimodal currently.
- **GPU acceleration** — compiled out for now; the GPU-layers setting exists but won't help on most Android GPUs.
- **Web search** — depends on DuckDuckGo's HTML endpoints; can break if they change markup or rate-limit.

---

## 📜 License

Apache 2.0. Underlying libraries — llama.cpp, MNN, LiteRT-LM, Jetpack Compose — are open source under their own licenses (MIT, Apache 2.0).
