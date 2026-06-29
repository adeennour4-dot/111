package com.gguf.zerocopy.domain.inference

import android.content.Context

class EngineManager(context: Context) {
  private val engines = mutableMapOf<EngineType, InferenceEngine>()
  private var activeEngine: InferenceEngine? = null

  val llamaCpp: LlamaCppEngine = LlamaCppEngine()
  val mnn: MnnEngine = MnnEngine()

  /**
   * LiteRT-LM engine. May be null if the native library is not available on this
   * device/architecture.  Always check for null before using.
   */
  val liteRt: LiteRtEngine? = try {
    LiteRtEngine()
  } catch (e: UnsatisfiedLinkError) {
    android.util.Log.w("EngineManager", "LiteRT-LM not available: ${e.message}")
    null
  } catch (e: Exception) {
    android.util.Log.w("EngineManager", "LiteRT-LM init failed: ${e.message}")
    null
  }

  init {
    engines[EngineType.LLAMA_CPP] = llamaCpp
    engines[EngineType.MNN] = mnn
    if (liteRt != null) engines[EngineType.LITER_T] = liteRt
  }

  fun selectEngine(type: EngineType): InferenceEngine? {
    activeEngine = engines[type]
    return activeEngine
  }

  fun selectEngineForFormat(path: String): InferenceEngine? {
    val type = EngineType.fromFormat(path)
    return selectEngine(type)
  }

  fun getActiveEngine(): InferenceEngine? = activeEngine

  fun getEngine(type: EngineType): InferenceEngine? = engines[type]

  fun isAnyModelLoaded(): Boolean = engines.values.any { it.isModelLoaded }

  fun getSupportedExtensions(): Set<String> = buildSet {
    add("gguf")
    add("mnn")
    if (liteRt != null) {
      add("tflite")
      add("litertlm")
      add("lite")
    }
  }

  fun unloadAll() {
    engines.values.forEach { it.unloadModel() }
    activeEngine = null
  }
}
