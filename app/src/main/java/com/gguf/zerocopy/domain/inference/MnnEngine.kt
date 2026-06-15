package com.gguf.zerocopy.domain.inference

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MnnEngine : InferenceEngine {
  override val engineType = EngineType.MNN
  override val engineName = "MNN"
  override var isModelLoaded = false
    private set
  override var modelInfo: ModelInfo? = null
    private set
  override var config = InferenceConfig()
  override var repeatPenalty = RepeatPenaltyConfig()
  override var systemPrompt = ""

  private val inferenceDone = AtomicBoolean(true)
  private val tokensGenerated = AtomicInteger(0)
  private val partialStream = StringBuilder()
  private val fullResponse = StringBuilder()
  private var kvUsage = 0
  private var currentModelPath = ""

  private val nativeLibLoaded: Boolean

  init {
    var loaded = false
    try {
      System.loadLibrary("mnn-bridge")
      loaded = true
    } catch (_: UnsatisfiedLinkError) {
    }
    nativeLibLoaded = loaded
  }

  private external fun mnnLoadModel(path: String): Boolean

  private external fun mnnExecuteInference(prompt: String, callback: NativeBridge.TokenCallback)

  private external fun mnnAbortInference()

  private external fun mnnResetContext()

  private external fun mnnGetModelInfo(): String

  private external fun mnnBenchmark(ppTokens: Int, tgTokens: Int): String

  private external fun mnnSetConfigNative(
    nCtx: Int,
    maxNewTokens: Int,
    temperature: Float,
    repeatPenalty: Float
  )

  private external fun mnnSetSystemPromptNative(prompt: String)

  private external fun mnnGetKvCacheUsage(): Int

  private external fun mnnGetTokensGenerated(): Int

  private external fun mnnIsInferenceDone(): Boolean

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (!nativeLibLoaded) {
      return@withContext Result.failure(
        Exception("MNN native library not available")
      )
    }
    try {
      currentModelPath = path
      val modelDir = resolveModelDir(path)
      mnnSetConfigNative(
        config.nCtx,
        config.maxNewTokens,
        config.temperature,
        repeatPenalty.repeatPenalty
      )
      mnnSetSystemPromptNative(systemPrompt)
      val ok = mnnLoadModel(modelDir)
      if (ok) {
        isModelLoaded = true
        modelInfo = parseModelInfo(mnnGetModelInfo())
        Result.success(Unit)
      } else {
        Result.failure(Exception("MNN model load failed"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  override fun unloadModel() {
    try {
      mnnResetContext()
    } catch (_: Exception) {
    }
    isModelLoaded = false
    modelInfo = null
    currentModelPath = ""
  }

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    synchronized(partialStream) {
      partialStream.clear()
      fullResponse.clear()
    }
    inferenceDone.set(false)
    tokensGenerated.set(0)

    val cb =
      object : NativeBridge.TokenCallback {
        override fun onToken(token: String) {
          synchronized(partialStream) {
            partialStream.append(token)
            fullResponse.append(token)
          }
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
      mnnExecuteInference(prompt, cb)
    } catch (e: Exception) {
      inferenceDone.set(true)
    }
  }

  override fun abortInference() {
    inferenceDone.set(true)
    try {
      mnnAbortInference()
    } catch (_: Exception) {
    }
  }

  override fun resetContext() {
    try {
      mnnResetContext()
    } catch (_: Exception) {
    }
    synchronized(partialStream) {
      partialStream.clear()
      fullResponse.clear()
    }
    inferenceDone.set(true)
    tokensGenerated.set(0)
    kvUsage = 0
  }

  override suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult =
    withContext(Dispatchers.IO) {
      try {
        val json = JSONObject(mnnBenchmark(ppTokens, tgTokens))
        BenchmarkResult(
          engine = engineName,
          prefillTps = json.optDouble("prefill_tps", 0.0).toFloat(),
          decodeTps = json.optDouble("decode_tps", 0.0).toFloat(),
          prefillMs = json.optDouble("prefill_ms", 0.0).toFloat(),
          decodeMs = json.optDouble("decode_ms", 0.0).toFloat(),
          prefillTokens = ppTokens,
          decodeTokens = tgTokens
        )
      } catch (e: Exception) {
        BenchmarkResult(engine = engineName)
      }
    }

  override fun supportsFormat(path: String): Boolean = path.endsWith(".mnn", true)

  override fun getTokensGenerated(): Int = tokensGenerated.get()

  override fun getKvUsage(): Int = kvUsage

  override fun isInferenceDone(): Boolean = inferenceDone.get()

  override fun readPartialStream(): String = synchronized(partialStream) {
    partialStream.toString().also { partialStream.clear() }
  }

  override fun readTokenStream(): String = synchronized(partialStream) { fullResponse.toString() }

  private fun resolveModelDir(path: String): String {
    val file = File(path)
    if (file.isDirectory && File(file, "config.json").exists()) return path
    val parent = file.parentFile
    if (parent != null && File(parent, "config.json").exists()) return parent.absolutePath
    val dir = File(file.absolutePath.removeSuffix(".mnn"))
    if (dir.isDirectory && File(dir, "config.json").exists()) return dir.absolutePath
    return path
  }

  private fun parseModelInfo(jsonStr: String): ModelInfo? = try {
    val j = JSONObject(jsonStr)
    ModelInfo(
      arch = j.optString("arch", ""),
      nParams = j.optLong("n_params", 0),
      engineType = EngineType.MNN
    )
  } catch (_: Exception) {
    null
  }
}
