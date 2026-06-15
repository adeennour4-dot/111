package com.gguf.zerocopy.domain.inference

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LlamaCppEngine : InferenceEngine {
  override val engineType = EngineType.LLAMA_CPP
  override val engineName = "llama.cpp"
  override var isModelLoaded = false
    private set
  override var modelInfo: ModelInfo? = null
    private set
  override var config = InferenceConfig()
  override var repeatPenalty = RepeatPenaltyConfig()
  override var systemPrompt = ""

  private val partialStream = AtomicReference(StringBuilder())
  private val fullResponse = AtomicReference(StringBuilder())
  private val inferenceDone = AtomicBoolean(true)
  private val tokensGenerated = AtomicInteger(0)
  private var kvUsage = 0
  private var currentModelPath = ""

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      currentModelPath = path
      NativeBridge.setEngineConfigNative(
        config.nCtx,
        config.nBatch,
        config.maxNewTokens,
        config.temperature,
        config.topP,
        config.minP,
        config.nGpuLayers,
        config.nThreads,
        config.seed,
        config.lowRamMode,
        config.flashAttention
      )
      NativeBridge.setRepeatPenaltyNative(
        repeatPenalty.repeatPenalty,
        repeatPenalty.freqPenalty,
        repeatPenalty.presPenalty
      )
      if (systemPrompt.isNotEmpty()) {
        NativeBridge.setSystemPromptNative(systemPrompt)
      }
      val ok = NativeBridge.loadGgufModelNative(path)
      if (ok) {
        isModelLoaded = true
        modelInfo = parseModelInfo(NativeBridge.getModelInfoNative())
        Result.success(Unit)
      } else {
        Result.failure(Exception("Failed to load GGUF model"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override fun unloadModel() {
    NativeBridge.resetContextNative()
    isModelLoaded = false
    modelInfo = null
    currentModelPath = ""
  }

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    withContext(Dispatchers.IO) {
      partialStream.get().clear()
      fullResponse.get().clear()
      inferenceDone.set(false)
      tokensGenerated.set(0)

      val cb =
        object : NativeBridge.TokenCallback {
          override fun onToken(token: String) {
            partialStream.get().append(token)
            fullResponse.get().append(token)
          }

          override fun onDone() {
            inferenceDone.set(true)
          }

          override fun onError(error: String) {
            inferenceDone.set(true)
          }

          override fun onKvCacheUsage(percent: Int) {
            kvUsage = percent
          }

          override fun onTokensGenerated(count: Int) {
            tokensGenerated.set(count)
          }
        }

      try {
        NativeBridge.executeWithCallbackNative(prompt, cb)
      } catch (e: Exception) {
        inferenceDone.set(true)
      }
    }
  }

  override fun abortInference() {
    NativeBridge.abortInferenceNative()
  }

  override fun resetContext() {
    NativeBridge.resetContextNative()
    partialStream.get().clear()
    fullResponse.get().clear()
    inferenceDone.set(true)
    tokensGenerated.set(0)
    kvUsage = 0
  }

  override suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult =
    withContext(Dispatchers.IO) {
      try {
        val json = JSONObject(NativeBridge.benchmarkNative(ppTokens, tgTokens))
        BenchmarkResult(
          engine = engineName,
          prefillTps = json.optDouble("pp_tps", 0.0).toFloat(),
          decodeTps = json.optDouble("tg_tps", 0.0).toFloat(),
          prefillMs = json.optDouble("pp_ms", 0.0).toFloat(),
          decodeMs = json.optDouble("tg_ms", 0.0).toFloat(),
          prefillTokens = ppTokens,
          decodeTokens = tgTokens
        )
      } catch (e: Exception) {
        BenchmarkResult(engine = engineName)
      }
    }

  override fun supportsFormat(path: String): Boolean = path.endsWith(".gguf", true)

  override fun getTokensGenerated(): Int = tokensGenerated.get()

  override fun getKvUsage(): Int = kvUsage

  override fun isInferenceDone(): Boolean = inferenceDone.get()

  override fun readPartialStream(): String = partialStream.getAndSet(StringBuilder()).toString()

  override fun readTokenStream(): String = fullResponse.get().toString()

  private fun parseModelInfo(jsonStr: String): ModelInfo? = try {
    val j = JSONObject(jsonStr)
    ModelInfo(
      arch = j.optString("arch", ""),
      nParams = j.optLong("n_params", 0),
      nLayers = j.optInt("n_layer", 0),
      nEmbeds = j.optInt("n_embd", 0),
      contextLength = j.optInt("ctx_train", 0),
      vocabSize = j.optInt("n_vocab", 0),
      quantization = j.optString("quantization", ""),
      engineType = EngineType.LLAMA_CPP
    )
  } catch (_: Exception) {
    null
  }
}
