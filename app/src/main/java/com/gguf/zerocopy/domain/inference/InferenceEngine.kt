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
  val engineType: EngineType = EngineType.LLAMA_CPP,
  val modelPath: String = ""
) {
  val isVisionModel: Boolean get() {
    val lower = arch.lowercase()
    val pathLower = modelPath.lowercase()
    return lower.contains("clip") || lower.contains("llava") ||
      lower.contains("vision") || lower.contains("mmproj") ||
      lower.contains("multimodal") || lower.contains("qwen2-vl") ||
      lower.contains("qwen-vl") || lower.contains("gemma3") ||
      lower.contains("paligemma") || lower.contains("florence") ||
      lower.contains("phi-3-vision") || lower.contains("phi-4-vision") ||
      lower.contains("internvl") || lower.contains("intern-vl") ||
      lower.contains("cogvlm") || lower.contains("idefics") ||
      lower.contains("fuyu") || lower.contains("kosmos") ||
      lower.contains("blip") || lower.contains("git") ||
      lower.contains("img") || lower.contains("imagebind") ||
      pathLower.contains("mmproj") || pathLower.contains("vision") ||
      pathLower.contains("clip")
  }

  val hasSTTCapability: Boolean get() = true

  val hasTTSCapability: Boolean get() = true

  val hasToolCallingCapability: Boolean
    get() {
      // Tool calling requires a model that can emit structured JSON/tool-call
      // syntax.  Small/base models (<3B params) almost never support this.
      // Heuristic: models with >=3B params and recent architecture.
      val nParams = modelInfo?.nParams ?: return false
      if (nParams < 3_000_000_000) return false
      val path = loadedModelPath?.lowercase() ?: return false
      // Known tool-calling capable families
      if (path.contains("command-r") || path.contains("c4ai")) return true
      if (path.contains("llama-3") || path.contains("llama3")) return true
      if (path.contains("qwen-2") || path.contains("qwen2")) return true
      if (path.contains("mistral") || path.contains("mixtral")) return true
      if (path.contains("phi-4") || path.contains("phi4")) return true
      if (path.contains("gemma-2") || path.contains("gemma2")) return true
      if (path.contains("deepseek") || path.contains("hermes")) return true
      // Default: assume capable if >=5B params, conservative for <5B
      return nParams >= 5_000_000_000
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
    get() {
      if (modelInfo?.isVisionModel == true || mmprojPath.isNotEmpty()) return true
      // .litertlm multimodal models expose vision through the LiteRT-LM engine;
      // detect by common name patterns since arch metadata may not be populated
      val path = loadedModelPath?.lowercase() ?: return false
      return path.contains("vision") || path.contains("vl") ||
        path.contains("multimodal") || path.contains("mmproj") ||
        path.contains("llava") || path.contains("clip") ||
        path.contains("gemma3") || path.contains("paligemma") ||
        path.contains("qwen2-vl") || path.contains("internvl") ||
        path.contains("phi-4-v") || path.contains("phi-3-v") ||
        path.contains("minicpm-v") || path.contains("smolvlm") ||
        path.contains("img") || path.contains("image")
    }

  suspend fun loadModel(path: String): Result<Unit>

  fun unloadModel()

  suspend   fun executeInference(prompt: String, callback: TokenCallback, searchQuery: String? = null)

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
