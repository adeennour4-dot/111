package com.gguf.zerocopy.domain.inference

import android.content.Context

class EngineManager(context: Context) {
  private val engines = mutableMapOf<EngineType, InferenceEngine>()
  private var activeEngine: InferenceEngine? = null

  val llamaCpp: LlamaCppEngine
  val mnn: MnnEngine
  val liteRt: LiteRtEngine

  init {
    llamaCpp = LlamaCppEngine()
    mnn = MnnEngine()
    liteRt = LiteRtEngine()
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

  fun getSupportedExtensions(): Set<String> = setOf("gguf", "mnn", "tflite", "litertlm")

  fun unloadAll() {
    engines.values.forEach { it.unloadModel() }
    activeEngine = null
  }
}
