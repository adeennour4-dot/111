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
  override val loadedModelPath: String? get() = currentModelPath.ifEmpty { null }
  override var config = InferenceConfig()
  override var repeatPenalty = RepeatPenaltyConfig()
  override var systemPrompt = ""
  override var mmprojPath: String = ""

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
      // Resolve the model directory that MNN needs (the folder containing config.json)
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
        Result.failure(Exception("MNN model load failed for dir: $modelDir"))
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

  override suspend fun executeInferenceWithImage(prompt: String, imagePath: String, callback: TokenCallback) {
    executeInference("[Image: $imagePath]\n$prompt", callback)
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
          callback.onToken(token)
        }

        override fun onDone() {
          inferenceDone.set(true)
          callback.onDone()
        }

        override fun onError(error: String) {
          inferenceDone.set(true)
          callback.onError(error)
        }

        override fun onKvCacheUsage(percent: Int) {
          kvUsage = percent
          callback.onKvUsage(percent)
        }

        override fun onTokensGenerated(count: Int) {
          tokensGenerated.set(count)
          callback.onTokensGenerated(count)
        }
      }
    try {
      mnnExecuteInference(prompt, cb)
    } catch (e: Exception) {
      inferenceDone.set(true)
      callback.onError(e.message ?: "MNN inference failed")
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

  /**
   * Resolve the directory that MNN's Llm loader needs.
   *
   * MNN models are stored as a directory containing config.json (and weights/*.mnn files).
   * The user/system may pass:
   *   (a) the directory path itself  — already correct
   *   (b) a .mnn weight file inside the directory  — go up one level
   *   (c) a path whose name (minus .mnn suffix) is the directory  — try that
   *
   * Priority: check for config.json existence to confirm the correct directory.
   */
  private fun resolveModelDir(path: String): String {
    val file = File(path)

    // (a) Path IS the model directory
    if (file.isDirectory && File(file, "config.json").exists()) return file.absolutePath

    // (b) Path is a file inside the model directory (e.g. model.mnn weight shard)
    val parent = file.parentFile
    if (parent != null && File(parent, "config.json").exists()) return parent.absolutePath

    // (c) Path is something like /models/qwen/qwen.mnn — strip extension and check dir
    val nameWithoutExt = file.nameWithoutExtension
    val siblingDir = File(file.parent ?: "", nameWithoutExt)
    if (siblingDir.isDirectory && File(siblingDir, "config.json").exists()) return siblingDir.absolutePath

    // (d) Deeper search: scan up to 2 levels under the file's parent for any config.json
    val searchRoot = parent ?: file
    val found = searchRoot.walk()
      .maxDepth(2)
      .firstOrNull { it.name == "config.json" && it.parentFile?.isDirectory == true }
    if (found != null) return found.parentFile!!.absolutePath

    // Fallback: pass as-is and let the native layer decide
    android.util.Log.w("MnnEngine", "Could not find config.json near $path — passing path directly")
    return path
  }

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
      engineType = EngineType.MNN,
      modelPath = currentModelPath
    )
  } catch (_: Exception) {
    null
  }
}
