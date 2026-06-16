# PROJECT MAP — ZeroCopy v8

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Kotlin | 2.3.x |
| UI | Jetpack Compose + Material3 | BOM 2026.05 |
| Android SDK | compileSdk | 36 |
| Min SDK | minSdk | 27 |
| Build | Gradle + AGP | 8.12 / 9.1.1 |
| Native | C++20 via CMake | NDK r29 |
| Rust (opt) | cargo-ndk | nightly |
| Engine: llama.cpp | ggml-org/llama.cpp | b9474 |
| Engine: MNN | alibaba/MNN | 3.5.0 |
| Engine: LiteRT-LM | Google AI Edge | latest |

## Architecture

```
ZeroCopyApp
├── EngineManager
│   ├── LlamaCppEngine → ipc-bridge (C++ JNI) → llama.cpp
│   ├── MnnEngine → mnn-bridge (C++ JNI) → MNN-LLM
│   └── LiteRtEngine → Google AAR → litert-lm-native
├── ModelRepository
│   ├── Local file management
│   └── Hugging Face downloader
├── ChatRepository
│   ├── Session management
│   └── Message persistence
├── DeviceUtils
│   ├── CPU/RAM/SoC detection
│   └── Auto-configuration
└── RustCore (optional)
    ├── InferenceScheduler
    └── MemoryMonitor
```

## File Layout

```
com.gguf.zerocopy/
├── ZeroCopyApp.kt          — Application class
├── MainActivity.kt         — Entry point + navigation
├── data/
│   ├── local/
│   │   └── SettingsManager.kt
│   └── repository/
│       ├── ModelRepository.kt  — Local + HF download
│       └── ChatRepository.kt   — Sessions + messages
├── domain/
│   ├── inference/
│   │   ├── InferenceConfig.kt
│   │   ├── InferenceEngine.kt  — Interface
│   │   ├── EngineManager.kt    — Engine selection
│   │   ├── NativeBridge.kt     — llama.cpp JNI
│   │   ├── LlamaCppEngine.kt   — GGUF engine
│   │   ├── MnnEngine.kt        — MNN engine
│   │   ├── LiteRtEngine.kt     — LiteRT-LM engine
│   │   └── RustCore.kt         — Rust bridge
│   └── device/
│       └── DeviceUtils.kt
└── ui/
    ├── theme/
    ├── chat/       — ChatScreen, bubbles, input
    ├── settings/   — SettingsScreen
    ├── models/     — ModelListScreen
    ├── download/   — DownloadScreen
    └── welcome/    — WelcomeScreen

cpp/
├── CMakeLists.txt
├── ipc-bridge.cpp     — llama.cpp JNI bridge
└── mnn-bridge.cpp     — MNN-LLM JNI bridge

rust_core/
├── Cargo.toml
├── build_android.sh
└── src/
    ├── lib.rs          — JNI entry points
    ├── scheduler.rs    — Thread/memory optimizer
    └── memory.rs       — Memory pressure monitor
```

## Key Differences from v7

1. **Model download** — built-in Hugging Face downloader for Zaya1 8B, Gemma 4, etc.
2. **Vision support** — image attachment directly in chat UI
3. **Low RAM mode** — configurable, limits context + optimized KV cache
4. **Flash attention** — on by default for faster inference
5. **Rust optimization layer** — optional smart scheduler and memory monitor
6. **Proper MVVM** — clean separation of concerns
7. **Error handling** — Result types instead of silent failures
8. **Benchmark** — all three engines benchmarkable
9. **Quantization detection** — model info shows quantization type
10. **n_batch persistence** — no longer hardcoded
