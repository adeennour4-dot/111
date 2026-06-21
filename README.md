# ZeroCopy - On-Device AI for Android

Your private, offline AI assistant that runs entirely on your phone. No internet needed, no data leaves your device.

## Features

### Multi-Engine AI
| Engine | Format | Best For |
|--------|--------|----------|
| **llama.cpp** (GGUF) | `.gguf` / `.ggml` | GPU acceleration, widest model compatibility, RAG |
| **LiteRT-LM** | `.tflite` / `.litertlm` | Google models, NPU access |
| **MNN** | `.mnn` | CPU-optimized inference |
| **ONNX Runtime** | `.onnx` | Cross-platform models |
| **ExecuTorch** | `.pte` | Meta's edge-optimized format |

### 🎤 Voice Input
Speak your questions instead of typing. Uses Android's built-in speech recognition.

### 🔊 Text-to-Speech
Listen to AI responses read aloud with Android TTS.

### 📤 Conversation Export/Import
Share your chats as text or JSON. Import conversations from JSON backup.

### 📥 Download Models
Browse and download models directly from HuggingFace inside the app.

### 🖼️ Vision Support (Auto-Detected)
Attach images to your messages. Vision capability is auto-detected from model metadata — no separate mmproj file needed.

### 📚 RAG (Retrieval-Augmented Generation)
Index documents (PDF, text, markdown) and query them during chat. OCR fallback via ML Kit for scanned PDFs.

### 💡 Prompt Suggestions
Quick-access prompt templates for common tasks.

### ⚙️ StreamingLLM
Efficient KV cache management using sink tokens + rolling window for long conversations without OOM.

### Performance Optimizations
- **Big core pinning** — `sched_setaffinity()` to ARM big cores
- **Process priority boost** — maximum throughput priority
- **RAM locking** — prevents page faults during inference
- **ThinLTO + ARMv8.6** — optimized native compilation
- **Context shifting** — KV cache reuse across conversations
- **Flash Attention** — faster long-context inference
- **Prompt caching** — disk-backed prompt cache for repeat queries

### Device-Aware Auto-Configuration
- Auto-detects CPU cores, SoC, RAM
- Suggests optimal GPU layers and threads
- Auto-sizes context window based on available RAM
- Recommends compatible models from HuggingFace

## Architecture

Compose UI screens in `app/src/main/java/com/gguf/zerocopy/ui/`:
- **ChatScreen** — Chat UI with conversation history, attachment handling, RAG toggle, reasoning toggle
- **SettingsScreen** — Collapsible cards for Sampling/Generation and System/Advanced settings
- **ModelListScreen** — Model management with download dialog and import
- **CloudScreen** — Dedicated server start/stop/configuration (consolidated from SettingsScreen)
- **RagScreen** — Document management, embedding model selection, RAG stats
- **SessionListScreen** — Chat history management

Engines in `domain/inference/`:
```
InferenceEngine (interface)
├── GGUFEngine       — Wraps GGMLEngine (llama.cpp via JNI, full RAG + StreamingLLM)
├── LiteRtEngine     — Google AI Edge LiteRT-LM, vision auto-detect
├── MnnEngine        — MNN via :mnn-lib module (MNN 3.5.0)
├── OnnxEngine       — ONNX Runtime Android
└── ExecutorchEngine — ExecuTorch Android
```

Other modules:
- `zerocopy-lib` — JNI bridge to llama.cpp (prompt cache, RAG, StreamingLLM, token batching)
- `RustCore` — Optional native optimization layer via `libzerocopy_core.so`
- `ToolManager` — Function-calling tools (time, date, calculate)
- `SimpleTokenizer` — Shared tokenizer: HuggingFace JSON, vocab.json, SentencePiece .model

## Building

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements
- Android 8.0+ (API 27)
- arm64-v8a device
- 4GB+ RAM (8GB+ for 7B models)
- Model files: .gguf, .ggml, .mnn, .tflite, .litertlm, .onnx, .pte

## Supported Models

| Model | Format | Engine |
|-------|--------|--------|
| Qwen3 / Qwen3.5 | GGUF / MNN | llama.cpp / MNN |
| Llama 3.2 | GGUF / MNN | llama.cpp / MNN |
| DeepSeek R1 | GGUF / MNN | llama.cpp / MNN |
| Phi-4 | GGUF / LiteRT-LM | llama.cpp / LiteRT-LM |
| Gemma 4 | GGUF | llama.cpp |
| LLaVA / Qwen-VL | GGUF | llama.cpp (vision, auto-detected) |
| Any .onnx model | ONNX | OnnxEngine |
| Any .pte model | PTE | ExecutorchEngine |

## Settings (collapsible cards)

| Card | Settings |
|------|----------|
| **Sampling & Generation** | Context Window, Max Tokens, Batch, GPU Layers, Threads, Temperature, Top-P, Min-P, Repeat/Freq/Presence Penalties, Low RAM Mode |
| **System & Advanced** | System Prompt, Reasoning toggle, Dark Theme, StreamingLLM (sink/recent/evict), Prompt Cache clear, Reset Context, Unload Model, Device Defaults |

## Server (dedicated CloudScreen tab)
- Start/stop HTTP server for web UI access
- Port, authentication token, WiFi-only, auto-start on boot
- Select which model the server uses independently of the chat engine

## License

Apache 2.0. All dependencies (llama.cpp, MNN, LiteRT-LM, ONNX Runtime, ExecuTorch, Compose) are open source.

## Version History

### v8.1.0 - Phase 1 Rework
- 🔌 5 engines: GGUF/llama.cpp, LiteRT-LM, MNN, ONNX Runtime, ExecuTorch
- 🔍 Vision auto-detected from model metadata (no mmproj)
- 📚 RAG with document indexing + ML Kit OCR for scanned PDFs
- 💬 Full conversation history passed to generation (models see previous turns)
- 📥 Direct model download from HuggingFace
- 📤 Chat export (text/JSON) + import
- 🧩 Collapsible settings cards (no server/RAG duplication)
- ⚡ StreamingLLM for long context management
