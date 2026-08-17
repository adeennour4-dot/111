# Task: Auto-detect vision/multimodal capability across 3 inference backends

> **Adjusted to the actual ZeroCopy codebase.** All file paths, class names, and
> existing helpers below are real (verified against the current `master`).
> Detection is for *vision understanding* (image → text), not image generation.

## Context
ZeroCopy is a multi-engine Android LLM app (Kotlin + C++/JNI + Rust). It supports
three backends selected by file extension via `ModelRepository.engineForExt()`
(`app/src/main/java/com/gguf/zerocopy/data/repository/ModelRepository.kt`):

| Engine | Format(s) | EngineType | Loader |
|--------|-----------|------------|--------|
| llama.cpp | `.gguf` | `LLAMA_CPP` | `domain/inference/LlamaCppEngine.kt` |
| MNN | `.mnn` | `MNN` | `domain/inference/MnnEngine.kt` |
| LiteRT-LM | `.litertlm`, `.lite` | `LITER_T` | `domain/inference/LiteRtEngine.kt` |

Today vision is only loosely inferred at **runtime** by two heuristics:
- `ModelInfo.isVisionModel` (`domain/inference/InferenceEngine.kt:24`) — substring
  match on `arch` / `modelPath` (`gemma3`, `llava`, `qwen2-vl`, `paligemma`, …).
- `InferenceEngine.hasVisionCapability` (`InferenceEngine.kt:90`) — true if
  `modelInfo?.isVisionModel == true || mmprojPath.isNotEmpty()` (plus filename hacks).

There is no per-model stored capability flag, and nothing is detected at import time.
Goal: detect capability **once at import/discovery time**, store it on the model
entry, and let the UI + routing react to it.

## Unified interface (NEW)
Add a `ModelCapability` enum and a detector. Suggested location:
`app/src/main/java/com/gguf/zerocopy/domain/inference/ModelCapabilityDetector.kt`
(next to `InferenceConfig.kt` / `InferenceEngine.kt`).

```kotlin
enum class ModelCapability { TEXT_ONLY, VISION, AUDIO, VISION_AUDIO }

object ModelCapabilityDetector {
  fun detect(modelFile: File, engineType: EngineType): ModelCapability
}
```

`detect()` must run **without a full model load** (parse headers/metadata only) and
be called from the import + discovery paths (see Storage).

## Per-backend detection logic

### 1. llama.cpp (`.gguf`)
Two independent signals, OR them:

**(a) GGUF metadata `general.architecture`** — parse the GGUF header at import time
(no load). Known vision arches (case-insensitive substring):
`llava`, `llava-llama`, `qwen2vl` / `qwen2-vl`, `minicpmv` / `minicpm-v`, `glm4v`,
`phi3v` / `phi-3-vision`, `gemma3` (vision variant), `idefics`, `internvl`,
`paligemma`, `pixtral`, `mistral3`, `llama3.2-vision`, `smolvlm`, `molmo`, `ember`.

**(b) Sibling `mmproj` projector** — vision GGUFs need a companion
`mmproj-*.gguf` (CLIP/SigLIP). Reuse the existing candidate list already in
`ui/models/ModelListScreen.kt:142-145`:
```kotlin
java.io.File(modelFile.parentFile, modelFile.nameWithoutExtension + ".mmproj")
java.io.File(modelFile.parentFile, "mmproj-model-f16.gguf")
java.io.File(modelFile.parentFile, "mmproj-${modelFile.name}")
```
If found → VISION, **and** record the mmproj path so it can be wired in.

**Wiring the projector:** `LlamaCppEngine.loadModel()` already calls
`NativeBridge.loadMmprojNative(mmprojPath)` when `mmprojPath.isNotEmpty()`
(`LlamaCppEngine.kt:69`, `NativeBridge.loadMmprojNative` at `NativeBridge.kt:35`).
So storing the detected mmproj path on the model (→ `LlamaCppEngine.mmprojPath`)
is enough to enable the `llama_decode` vision path; no native change required
unless you want auto-discovery of the mmproj passed to the engine load.

### 2. MNN (`.mnn`)
MNN multimodal models expose a vision tower via `config.json` (resolved by the
existing `MnnEngine.resolveModelDir()`, `MnnEngine.kt:229`). Detect by:
- `config.json` containing `is_visual == true`, **or** a non-empty `vit_path`
  (absolute or sibling), **or**
- a sibling vision-tower `.mnn` file in the model dir (e.g. `vit.mnn`,
  `*vision*.mnn`).

If any → VISION. **Note:** `MnnEngine.hasVisionCapability` is currently hard-coded
`false` (`MnnEngine.kt:28`) and `executeInferenceWithImage` returns a "not
supported" error (`MnnEngine.kt:100`). Detection here is *preparatory* — the actual
MNN CLIP/vision inference path is a separate implementation task; for now just set
the flag so the UI can show it and routing can avoid sending images until the
engine supports them.

### 3. LiteRT-LM (`.litertlm` / `.lite`)
The LiteRT-LM bundle format (`EngineType.LITER_T`, `LiteRtEngine.kt`) embeds a
manifest/metadata header declaring supported input modalities. Parse it for a
modalities list (`TEXT`, `IMAGE`, `AUDIO`):
- `IMAGE` present → VISION
- `AUDIO` present (without IMAGE) → AUDIO
- both → VISION_AUDIO
- absent/unknown → default `TEXT_ONLY` (safe).

**Note:** `LiteRtEngine.executeInferenceWithImage` (`LiteRtEngine.kt:84`) is a text
stub that only prepends `"[Image: $path]"` to the prompt — it does not truly
process images. Detection marks capability; real vision handling is separate.

## Storage
- Add a field to `LocalModel` (`ModelRepository.kt:50`):
  ```kotlin
  val capability: ModelAlias = ModelCapability.TEXT_ONLY
  ```
- Compute it in **both** import paths:
  - `ModelRepository.importUri(uri, filename)` (`ModelRepository.kt:331`)
  - `ModelRepository.importPath(path, name)` (`ModelRepository.kt:492`)
  - and in the startup **discovery** path (`_models.value = discovered…`
    around `ModelRepository.kt:328`) so already-imported models get detected on
    next launch.
- If detection is expensive/fallible, cache the result. The in-memory
  `ModelRepository._models` `StateFlow` (`ModelRepository.kt:239`) is the source of
  truth at runtime; optionally persist per-model in the `SettingsManager`
  model-config JSON (alongside `ModelTokenConfig`, `SettingsManager.kt:70`) so it
  survives reinstall. For GGUF, also feed a detected mmproj path into
  `SettingsManager.mmprojPath` / `LlamaCppEngine.mmprojPath`.

## Downstream usage of the flag
- **Chat UI image-attach button** — `ChatScreen` currently gates on
  `val hasVision = engine?.hasVisionCapability == true` (`ChatScreen.kt:151`),
  used at `:579` and `:924`. Seed `hasVisionCapability` from the loaded model's
  `LocalModel.capability` (VISION or VISION_AUDIO) when the engine loads, instead
  of the current arch+filename heuristics. Keep `hasVisionCapability` as the
  runtime guard but derive its default from the stored capability.
- **Inference routing** — only call `executeInferenceWithImage(...)` when the
  capability includes vision; for GGUF ensure the mmproj projector is loaded first.
- **Model list / benchmark screens** — `ModelListScreen.kt:140-160` already
  hand-rolls an mmproj warning; replace that with a capability-driven vision icon
  (👁) and drop the manual warning. `BenchmarkDialog` (benchmark screen) should
  skip/label vision-only runs for non-vision models.
- **`InferenceEngine.hasVisionCapability`** (`InferenceEngine.kt:90`) — refactor to
  consult `LocalModel.capability` (set at import) rather than re-deriving from
  `arch` + `mmprojPath` filename heuristics.

## Suggested files to touch
| Concern | File |
|---------|------|
| Enum + detector | `domain/inference/ModelCapabilityDetector.kt` (new) |
| Per-model entry field | `data/repository/ModelRepository.kt` (`LocalModel`, `importUri`, `importPath`, discovery) |
| GGUF arch + mmproj constants | reuse `ModelInfo.isVisionModel` + `ModelListScreen.kt:142` candidates |
| MNN config.json parse | `domain/inference/MnnEngine.kt` (`resolveModelDir`) |
| LiteRT manifest parse | `domain/inference/LiteRtEngine.kt` |
| mmproj JNI wiring (already exists) | `NativeBridge.loadMmprojNative`, `LlamaCppEngine.loadModel` |
| Runtime guard refactor | `domain/inference/InferenceEngine.kt` (`hasVisionCapability`) |
| Chat attach button | `ui/chat/ChatScreen.kt` (`:151`, `:579`, `:924`) |
| Model list icon + benchmark | `ui/models/ModelListScreen.kt`, `ui/settings/BenchmarkDialog.kt` |
| Optional persistence | `data/local/SettingsManager.kt` (model-config JSON) |
