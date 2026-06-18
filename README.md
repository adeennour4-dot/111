# ZeroCopy - On-Device AI for Android

Your private, offline AI assistant that runs entirely on your phone. No internet needed, no data leaves your device.

## Features

### Multi-Engine AI
| Engine | Format | Best For |
|--------|--------|----------|
| **llama.cpp** | `.gguf` | GPU acceleration, widest model compatibility |
| **MNN** | `.mnn` | CPU-optimized inference |
| **LiteRT-LM** | `.tflite` / `.litertlm` | Google models, NPU access |

### 🎤 Voice Input
Speak your questions instead of typing. Uses Android's built-in speech recognition.

### 🔊 Text-to-Speech
Listen to AI responses read aloud with Android TTS.

### 📤 Conversation Export
Share your chats as text with one tap.

### 🖼️ Vision Support (Experimental)
Attach images to your messages. Works with multimodal models (LLaVA, Qwen-VL, etc.) when you load a matching mmproj file.

### 💡 Prompt Suggestions
Quick-access prompt templates for common tasks.

### Performance Optimizations
- **Big core pinning** — `sched_setaffinity()` to ARM big cores
- **Process priority boost** — maximum throughput priority
- **RAM locking** — prevents page faults during inference
- **ThinLTO + ARMv8.6** — optimized native compilation
- **Context shifting** — KV cache reuse across conversations
- **Flash Attention** — faster long-context inference

### Device-Aware Auto-Configuration
- Auto-detects CPU cores, SoC, RAM
- Suggests optimal GPU layers and threads
- Auto-sizes context window based on available RAM
- Recommends compatible models from HuggingFace

## Architecture

```
MainActivity.kt           (Compose UI, state management)
├── ChatScreen            (Chat UI + voice/vision/export)
├── SettingsScreen        (Sampling, generation, theme)
├── ModelListScreen       (Model management)
├── DownloadScreen        (HuggingFace model store)
├── SessionListScreen     (Chat history)
└── WelcomeScreen         (Onboarding)

EngineManager            (Engine selection & management)
├── LlamaCppEngine       (GGUF, multimodal vision)
├── MnnEngine            (MNN, CPU-optimized)
└── LiteRtEngine         (TFLite/LiteRT-LM)

InferenceEngine (interface)
├── loadModel()          Load model
├── loadMmproj()         Load vision encoder
├── executeInference()   Run inference (streaming)
├── executeWithImage()   Vision inference
├── abortInference()     Cancel generation
└── benchmark()          Performance measurement

Voice/TTS                (Speech recognition + synthesis)
Export                   (Chat as text sharing)
Prompt Library           (Quick templates)
Rust Core                (Performance scheduler + memory monitor)
```

## Building

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements
- Android 8.0+ (API 27)
- arm64-v8a device
- 4GB+ RAM (8GB+ for 7B models)
- Model files from HuggingFace or local .gguf/.mnn/.tflite

## Supported Models

| Model | Format | Engine |
|-------|--------|--------|
| Qwen3 / Qwen3.5 | GGUF / MNN | llama.cpp / MNN |
| Llama 3.2 | GGUF / MNN | llama.cpp / MNN |
| DeepSeek R1 | GGUF / MNN | llama.cpp / MNN |
| Phi-4 | GGUF / LiteRT-LM | llama.cpp / LiteRT-LM |
| Gemma 4 | GGUF | llama.cpp |
| LLaVA / Qwen-VL | GGUF + mmproj | llama.cpp (vision) |

## Settings

| Setting | Range | Description |
|---------|-------|-------------|
| Context Window | 512-32768 | KV cache size |
| Max Tokens | 64-8192 | Max generation length |
| GPU Layers | 0-999 | GPU offloading |
| Threads | 0-16 | CPU threads (0=auto) |
| Temperature | 0-2 | Sampling creativity |
| Top-P | 0-1 | Nucleus sampling |
| Min-P | 0-1 | Token probability filter |
| Repeat Penalty | 1.0+ | Repetition reduction |
| Presence Penalty | 0+ | Topic diversity |
| Batch Size | 512-8192 | Prefill batch size |
| Low RAM Mode | on/off | Memory optimization |
| mmproj | file picker | Vision encoder model |

## License

Apache 2.0. All dependencies (llama.cpp, MNN, LiteRT-LM, Compose) are open source. You can use this app commercially.

## Version History

### v8.0.0 - Public Release
- 🎤 Voice input via Android SpeechRecognizer
- 🔊 Text-to-speech for AI responses
- 📤 One-tap conversation export as text
- 🖼️ Experimental vision/multimodal support (mmproj)
- 💡 Quick prompt suggestion chips
- Fixed model deletion (directory models support)
- Improved default sampling parameters (less nonsense)
- Better error handling throughout
