# PROJECT MAP — ZeroCopy v8.1.0

## [TECH_STACK]

| Layer | Technology | Version | Status |
|-------|-----------|---------|--------|
| Language | Kotlin | 2.4.x | ✅ |
| Android SDK | compileSdk | 36 | ✅ |
| Min SDK | minSdk | 27 | ✅ |
| Build | Gradle + AGP | 9.x | ✅ |
| UI | Jetpack Compose + Material3 | BOM 2026.05.00 | ✅ |
| Coroutines | kotlinx-coroutines | 1.10.1 | ✅ |
| **Engine: llama.cpp** | ggml-org/llama.cpp | b9581 (pinned) | ✅ |
| **Engine: MNN** | alibaba/MNN | 3.5.0 (pinned) | ✅ |
| **Engine: LiteRT-LM** | com.google.ai.edge.litertlm | 0.13.0 | ✅ |
| **Rust core** | jni + serde | as needed | ✅ |
| CI | GitHub Actions | ubuntu-24.04 + NDK r28c | ✅ |

## [FEATURES]

### Core AI
- Multi-engine: llama.cpp (GGUF), MNN, LiteRT-LM (TFLite)
- Streaming token generation via JNI callbacks
- Chunked prefill (uBatch=512)
- Context shifting (KV cache reuse)
- Flash Attention support
- Big core pinning, priority boost, RAM locking

### New in v8.1.0
- 🎤 **Voice Input** — Android SpeechRecognizer integration
- 🔊 **Text-to-Speech** — Read responses aloud via Android TTS
- 📤 **Conversation Export** — One-tap share as text
- 🖼️ **Vision Support** — mmproj loading for multimodal models (LLaVA, Qwen-VL)
- 💡 **Prompt Suggestions** — Quick template chips
- Fixed model deletion (handles directories for MNN)

### Performance
- ThinLTO + ARMv8.6-a+dotprod+i8mm+fp16
- Device-aware auto-configuration
- Rust-based performance scheduler & memory monitor

## [SYSTEM_FLOW]

```
User opens app → Splash → ChatScreen (no model)
                    ↓ [tap robot icon → ModelSelectionSheet]
              Select existing or import/download model
                    ↓
              EngineManager.selectEngineForFormat()
                    ↓
              setConfig() + loadModel() (+ optional mmproj)
                    ↓
              ChatScreen ← modelLoaded = true
                    ↓ [type message → tap send]
              executeInference(prompt) on Dispatchers.IO
                    ↓ (JNI callback per token)
              readPartialStream() → streamedText
                    ↓ [isInferenceDone]
              readTokenStream() → chat.add(ChatMessage)
                    ↓
              [Optional] Voice input / TTS / Export
```

## [ARCHITECTURE]

```
MainActivity.kt           (Compose UI, state management)
├── ChatScreen            (Chat + voice/TTS/vision/export)
├── SettingsScreen        (Sampling, generation, mmproj, theme)
├── ModelListScreen       (Model management with delete)
├── DownloadScreen        (HuggingFace model store)
├── SessionListScreen     (Chat history)
└── SplashScreen          (App branding)

EngineManager            (Engine selection & management)
├── LlamaCppEngine       (GGUF + mmproj vision)
├── MnnEngine            (MNN)
└── LiteRtEngine         (TFLite/LiteRT-LM)

InferenceEngine (interface)
├── loadModel()          Load model from file path
├── loadMmproj()         Load vision encoder (mmproj)
├── executeInference()   Run inference (streaming)
├── executeWithImage()   Vision inference
├── abortInference()     Cancel current generation
└── benchmark()          Performance measurement

Native C++ (JNI)
├── ipc-bridge.cpp       (llama.cpp + CLIP multimodal)
├── mnn-bridge.cpp       (MNN-LLM)
└── CMakeLists.txt       (Build config)

Rust Core (jni)
├── lib.rs               (Scheduler + memory monitor)
├── scheduler.rs         (Thread/thermal optimization)
└── memory.rs            (Memory pressure tracking)
```

## [v8.1.0 CHANGES]

### Bug Fixes
1. **Model deletion** — Now handles directories (MNN models), returns success/failure
2. **GGUF nonsense** — Better defaults: temp=0.5, min_p=0.1, pres_penalty=0.1, ctx=4096
3. **Vision not working** — Full mmproj pipeline added (load, encode, infer)

### New Features
1. **Voice Input** — SpeechRecognizer intent with RECORD_AUDIO permission
2. **Text-to-Speech** — Android TTS for assistant responses  
3. **Conversation Export** — Share chat via Intent.ACTION_SEND
4. **Prompt Suggestions** — Quick chip templates above input
5. **mmproj Loading** — Vision encoder selection in Settings

### Infrastructure
- Version bumped to 8.1.0 (code 9)
- CLIP/llava build enabled in CMake
- New JNI functions: loadMmprojNative, executeWithImageNative
- Settings persistence for mmproj path
