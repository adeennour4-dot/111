package com.gguf.zerocopy.domain.inference

import android.content.Context

class EngineManager(context: Context) {
  private val engines = mutableMapOf<EngineType, InferenceEngine>()
  private var activeEngine: InferenceEngine? = null

  val llamaCpp: LlamaCppEngine = LlamaCppEngine()
  val mnn: MnnEngine = MnnEngine()
  val liteRt: InferenceEngine = try {
    LiteRtEngine()
  } catch (e: Throwable) {
    // LiteRT-LM may not be available on all devices/architectures.
    // Provide a silent stub that reports errors when actually used.
    android.util.Log.w("EngineManager", "LiteRT-LM unavailable: ${e.message}")
    UnavailableLiteRtEngine()
  }

  init {
    engines[EngineType.LLAMA_CPP] = llamaCpp
    engines[EngineType.MNN] = mnn
    engines[EngineType.LITER_T] = liteRt
  }

  fun selectEngine(type: EngineType): InferenceEngine {
    activeEngine = engines[type]
    return activeEngine!!
  }

  fun selectEngineForFormat(path: String): InferenceEngine {
    val type = EngineType.fromFormat(path)
    return selectEngine(type)
  }

  fun getActiveEngine(): InferenceEngine? = activeEngine

  fun getEngine(type: EngineType): InferenceEngine = engines[type]!!

  fun isAnyModelLoaded(): Boolean = engines.values.any { it.isModelLoaded }

  fun getSupportedExtensions(): Set<String> = setOf("gguf", "mnn", "tflite", "litertlm", "lite")

  fun unloadAll() {
    engines.values.forEach { it.unloadModel() }
    activeEngine = null
  }
}

/**
 * Stub engine that replaces LiteRT-LM when the native library is not
 * available.  Reports clear errors so the user knows TFLite models won't work
 * instead of crashing silently.
 */
private class UnavailableLiteRtEngine : InferenceEngine {
  override val engineType = EngineType.LITER_T
  override val engineName = "LiteRT-LM (unavailable)"
  override var isModelLoaded = false
  override var modelInfo: ModelInfo? = null
  override val loadedModelPath: String? get() = null
  override var config = InferenceConfig()
  override var repeatPenalty = RepeatPenaltyConfig()
  override var systemPrompt = ""
  override var mmprojPath = ""

  override suspend fun loadModel(path: String): Result<Unit> =
    Result.failure(IllegalStateException("LiteRT-LM native library is not available on this device"))

  override fun unloadModel() {}
  override fun resetContext() {}

  override suspend fun executeInference(prompt: String, callback: TokenCallback, searchQuery: String?) {
    callback.onError("LiteRT-LM is not available on this device")
    callback.onDone()
  }

  override suspend fun executeInferenceWithImage(prompt: String, imagePath: String, callback: TokenCallback) =
    executeInference(prompt, callback)

  override fun abortInference() {}
  override fun readPartialStream(): String = ""
  override fun readTokenStream(): String = ""
  override fun isInferenceDone(): Boolean = true
  override fun getTokensGenerated(): Int = 0
  override fun getKvUsage(): Int = 0

  override suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult =
    BenchmarkResult(engine = engineName)

  override fun supportsFormat(path: String): Boolean = false
}
