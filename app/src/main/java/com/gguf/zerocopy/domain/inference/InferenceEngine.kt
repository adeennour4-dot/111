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
) {
  val isVisionModel: Boolean get() {
    val lower = arch.lowercase()
    return lower.contains("clip") || lower.contains("llava") ||
      lower.contains("vision") || lower.contains("mmproj") ||
      lower.contains("multimodal") || lower.contains("qwen2-vl") ||
      lower.contains("gemma3") || lower.contains("paligemma")
  }
}

interface TokenCallback {
  fun onToken(token: String)

  fun onDone()

  fun onError(error: String)

  fun onKvUsage(percent: Int)

  fun onTokensGenerated(count: Int)

  fun onToolCall(toolName: String, toolArgs: String) {}
}

interface InferenceEngine {
  val engineType: EngineType
  val engineName: String
  val isModelLoaded: Boolean
  val modelInfo: ModelInfo?
  var config: InferenceConfig
  var repeatPenalty: RepeatPenaltyConfig
  var systemPrompt: String
  var mmprojPath: String
  val loadedModelPath: String?
  val hasVisionCapability: Boolean
    get() = modelInfo?.isVisionModel == true || mmprojPath.isNotEmpty()

  suspend fun loadModel(path: String): Result<Unit>

  fun unloadModel()

  suspend   fun executeInference(prompt: String, callback: TokenCallback)

  suspend fun executeInferenceWithImage(prompt: String, imagePath: String, callback: TokenCallback)

  fun getToolManager(): ToolManager? = null
  fun setToolManager(tm: ToolManager?) {}
  val toolsEnabled: Boolean
    get() = getToolManager() != null

  fun abortInference()

  fun resetContext()

  suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult

  fun supportsFormat(path: String): Boolean

  fun loadMmproj(path: String): Boolean = false

  fun readPartialStream(): String = ""
  fun readTokenStream(): String = ""
  fun isInferenceDone(): Boolean = true
  fun getTokensGenerated(): Int = 0
  fun getKvUsage(): Int = 0
  fun restoreHistory(messages: List<Pair<String, String>>) {}
}
