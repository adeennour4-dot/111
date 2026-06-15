package com.gguf.zerocopy.domain.inference

sealed class InferenceResult {
  data object Idle : InferenceResult()

  data class Loading(val status: String) : InferenceResult()

  data class Ready(val info: ModelInfo) : InferenceResult()

  data class Error(val message: String) : InferenceResult()
}

data class ModelInfo(
  val arch: String = "",
  val nParams: Long = 0,
  val nLayers: Int = 0,
  val nEmbeds: Int = 0,
  val contextLength: Int = 0,
  val vocabSize: Int = 0,
  val quantization: String = "",
  val engineType: EngineType = EngineType.LLAMA_CPP
)

interface TokenCallback {
  fun onToken(token: String)

  fun onDone()

  fun onError(error: String)

  fun onKvUsage(percent: Int)

  fun onTokensGenerated(count: Int)
}

interface InferenceEngine {
  val engineType: EngineType
  val engineName: String
  val isModelLoaded: Boolean
  val modelInfo: ModelInfo?
  var config: InferenceConfig
  var repeatPenalty: RepeatPenaltyConfig
  var systemPrompt: String

  suspend fun loadModel(path: String): Result<Unit>

  fun unloadModel()

  suspend fun executeInference(prompt: String, callback: TokenCallback)

  fun abortInference()

  fun resetContext()

  suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult

  fun supportsFormat(path: String): Boolean

  fun readPartialStream(): String = ""
  fun readTokenStream(): String = ""
  fun isInferenceDone(): Boolean = true
  fun getTokensGenerated(): Int = 0
  fun getKvUsage(): Int = 0
}
