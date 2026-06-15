package com.gguf.zerocopy.domain.inference

import kotlinx.serialization.Serializable

@Serializable
data class InferenceConfig(
  val nCtx: Int = 2048,
  val nBatch: Int = 512,
  val maxNewTokens: Int = 1024,
  val temperature: Float = 0.6f,
  val topP: Float = 0.9f,
  val minP: Float = 0.05f,
  val nGpuLayers: Int = 0,
  val nThreads: Int = 4,
  val seed: Int = -1,
  val lowRamMode: Boolean = true,
  val flashAttention: Boolean = true
)

@Serializable
data class RepeatPenaltyConfig(
  val repeatPenalty: Float = 1.1f,
  val freqPenalty: Float = 0.0f,
  val presPenalty: Float = 0.0f
)

enum class EngineType(val id: String, val formats: List<String>) {
  LLAMA_CPP("llama.cpp", listOf("gguf")),
  MNN("MNN", listOf("mnn")),
  LITER_T("LiteRT-LM", listOf("tflite", "litertlm"))
  ;

  companion object {
    fun fromFormat(path: String): EngineType = when {
      path.endsWith(".gguf", true) -> LLAMA_CPP
      path.endsWith(".mnn", true) -> MNN
      path.endsWith(".tflite", true) || path.endsWith(".litertlm", true) -> LITER_T
      else -> LLAMA_CPP
    }
  }
}

@Serializable
data class BenchmarkResult(
  val engine: String = "",
  val prefillTps: Float = 0f,
  val decodeTps: Float = 0f,
  val prefillMs: Float = 0f,
  val decodeMs: Float = 0f,
  val prefillTokens: Int = 0,
  val decodeTokens: Int = 0
)
