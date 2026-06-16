# ZeroCopy v8

Ultra-optimized on-device LLM inference for Android. Runs GGUF, MNN, and LiteRT-LM models with vision and file support.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  UI Layer (Compose)              │
│  Welcome  →  Chat  →  Models  →  Settings  → DL │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│           Domain Layer (Kotlin)                  │
│  ┌────────────┐ ┌──────────┐ ┌────────────────┐ │
│  │ LlamaCpp   │ │   MNN    │ │   LiteRT-LM    │ │
│  │ Engine     │ │  Engine  │ │    Engine      │ │
│  └─────┬──────┘ └────┬─────┘ └───────┬────────┘ │
└────────┼─────────────┼───────────────┼───────────┘
         │             │               │
┌────────▼─────┐ ┌─────▼───────┐ ┌────▼───────────┐
│  ipc-bridge  │ │ mnn-bridge  │ │  LiteRT-LM AAR │
│  (C++ JNI)   │ │ (C++ JNI)   │ │  (Google)      │
│  llama.cpp   │ │ MNN-LLM     │ │                 │
│  ggml        │ │ libMNN.so   │ │                 │
│  Vulkan/CPU  │ │ CPU-opt     │ │                 │
└──────────────┘ └─────────────┘ └─────────────────┘
         │
┌────────▼─────────────────────────────────────────┐
│  Rust Core (optional optimization layer)         │
│  • Smart thread scheduling                       │
│  • Memory pressure monitoring                    │
│  • Thermal-aware throttling                      │
│  • Dynamic batch/context sizing                  │
└──────────────────────────────────────────────────┘
```

## Supported Models

| Model | Format | Engine | Size (Q4) |
|-------|--------|--------|-----------|
| Zaya1 8B | GGUF | llama.cpp | ~4.6 GB |
| Gemma 4 2B | GGUF | llama.cpp | ~1.2 GB |
| Gemma 4 9B | GGUF | llama.cpp | ~5.2 GB |
| Qwen3 1B | GGUF | llama.cpp | ~700 MB |
| Qwen3 8B | GGUF | llama.cpp | ~4.8 GB |
| Llama 3.2 1B/3B | GGUF | llama.cpp | ~700 MB / ~2 GB |
| Any .mnn model | MNN | MNN | Varies |
| Any .litertlm | LiteRT-LM | LiteRT-LM | Varies |

### Vision Models
| Model | Format | Engine |
|-------|--------|--------|
| LLaVA 1.5/1.6 | GGUF + mmproj | llama.cpp |
| Gemma 3 Vision | GGUF + mmproj | llama.cpp |
| Qwen3-VL | GGUF / MNN | llama.cpp / MNN |

## Building

```bash
# Standard build
./gradlew assembleDebug

# With Rust optimization layer
cd rust_core
./build_android.sh
cd ..
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Key Improvements in v8

- **MVVM architecture** with ViewModels and state management
- **Model download** from Hugging Face (Zaya1, Gemma 4, Qwen, Llama)
- **Vision integration** — send images directly in chat
- **Low RAM mode** — limits context and uses optimized KV cache
- **Flash attention** enabled by default for faster inference
- **Proper error handling** throughout all engine layers
- **Rust optimization layer** — intelligent thread/memory/thermal management
- **Benchmark** for all three engines
- **Quantization detection** in model info
- **n_batch persistence** and proper clamping

## Requirements

- Android API ≥ 27 (Oreo)
- arm64-v8a device
- 4GB+ RAM (8GB+ recommended for 7B models)

## License

Apache 2.0 — see LICENSE
