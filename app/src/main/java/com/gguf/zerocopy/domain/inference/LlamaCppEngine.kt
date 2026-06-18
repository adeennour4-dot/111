package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LlamaCppEngine : InferenceEngine {
  private val nativeLibLoaded: Boolean by lazy {
    try {
      System.loadLibrary("ipc-bridge")
      true
    } catch (e: UnsatisfiedLinkError) {
      android.util.Log.e("LlamaCppEngine", "Failed to load native library: ${e.message}")
      false
    }
  }
  override val engineType = EngineType.LLAMA_CPP
  override val engineName = "llama.cpp"
  override var isModelLoaded = false
    private set
  override var modelInfo: ModelInfo? = null
  override val loadedModelPath: String? get() = currentModelPath.ifEmpty { null }
  override var config = InferenceConfig()
  override var repeatPenalty = RepeatPenaltyConfig()
  override var systemPrompt = ""
    set(v) {
      field = v
      if (isModelLoaded) NativeBridge.setSystemPromptNative(v)
    }
  override var mmprojPath: String = ""

  private val lock = Any()
  private var partialStream = StringBuilder()
  private var fullResponse = StringBuilder()
  private val inferenceDone = AtomicBoolean(true)
  private val tokensGenerated = AtomicInteger(0)
  private var kvUsage = 0
  private var currentModelPath = ""
  private var _toolManager: ToolManager? = null
  // Strong reference to prevent garbage collection of callback
  private var activeCallback: NativeBridge.TokenCallback? = null
  override fun getToolManager() = _toolManager
  override fun setToolManager(tm: ToolManager?) { _toolManager = tm }

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (!nativeLibLoaded) {
      return@withContext Result.failure(
        Exception("llama.cpp native library not available")
      )
    }
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
        if (mmprojPath.isNotEmpty()) {
          try {
            NativeBridge.loadMmprojNative(mmprojPath)
          } catch (_: Exception) { }
        }
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

  override fun loadMmproj(path: String): Boolean {
    mmprojPath = path
    return try {
      NativeBridge.loadMmprojNative(path)
    } catch (_: Exception) {
      false
    }
  }

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    if (!nativeLibLoaded) {
      callback.onError("llama.cpp native library not available")
      return
    }
    withContext(Dispatchers.IO) {
      synchronized(lock) {
        partialStream.clear()
        fullResponse.clear()
      }
      inferenceDone.set(false)
      tokensGenerated.set(0)

      // Create callback and hold strong reference to prevent GC
      val cb =
        object : NativeBridge.TokenCallback {
          override fun onToken(token: String) {
            synchronized(lock) {
              partialStream.append(token)
              fullResponse.append(token)
            }
            android.util.Log.d("LlamaCppEngine", "onToken: ${token.take(50)}")
          }

          override fun onDone() {
            android.util.Log.d("LlamaCppEngine", "onDone")
            callback.onDone()
            inferenceDone.set(true)
          }

          override fun onError(error: String) {
            android.util.Log.e("LlamaCppEngine", "onError: $error")
            callback.onError(error)
            inferenceDone.set(true)
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

      // Store strong reference to prevent garbage collection
      activeCallback = cb
      android.util.Log.d("LlamaCppEngine", "Starting inference with prompt length: ${prompt.length}")

      try {
        NativeBridge.executeWithCallbackNative(prompt, cb)
      } catch (e: Exception) {
        android.util.Log.e("LlamaCppEngine", "Exception during inference: ${e.message}")
        inferenceDone.set(true)
      } finally {
        // Keep reference alive until inference completes
        // Don't clear here - let onDone handle it
      }
    }
  }

  override suspend fun executeInferenceWithImage(prompt: String, imagePath: String, callback: TokenCallback) {
    if (!nativeLibLoaded) {
      callback.onError("llama.cpp native library not available")
      return
    }
    withContext(Dispatchers.IO) {
      synchronized(lock) {
        partialStream.clear()
        fullResponse.clear()
      }
      inferenceDone.set(false)
      tokensGenerated.set(0)

      // Create callback and hold strong reference to prevent GC
      val cb =
        object : NativeBridge.TokenCallback {
          override fun onToken(token: String) {
            synchronized(lock) {
              partialStream.append(token)
              fullResponse.append(token)
            }
            android.util.Log.d("LlamaCppEngine", "onToken (image): ${token.take(50)}")
          }

          override fun onDone() {
            android.util.Log.d("LlamaCppEngine", "onDone (image)")
            callback.onDone()
            inferenceDone.set(true)
          }

          override fun onError(error: String) {
            android.util.Log.e("LlamaCppEngine", "onError (image): $error")
            callback.onError(error)
            inferenceDone.set(true)
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

      // Store strong reference to prevent garbage collection
      activeCallback = cb
      android.util.Log.d("LlamaCppEngine", "Starting image inference with prompt length: ${prompt.length}")

      try {
        NativeBridge.executeWithImageNative(prompt, imagePath, cb)
      } catch (e: Exception) {
        android.util.Log.e("LlamaCppEngine", "Exception during image inference: ${e.message}")
        inferenceDone.set(true)
      } finally {
        // Keep reference alive until inference completes
      }
    }
  }

  override fun abortInference() {
    android.util.Log.d("LlamaCppEngine", "abortInference called")
    NativeBridge.abortInferenceNative()
  }

  override fun restoreHistory(messages: List<Pair<String, String>>) {
    val jsonArr = org.json.JSONArray()
    messages.forEach { (role, content) ->
      jsonArr.put(org.json.JSONObject().apply {
        put("role", role)
        put("content", content)
      })
    }
    NativeBridge.restoreHistoryNative(jsonArr.toString())
  }

  override fun resetContext() {
    NativeBridge.resetContextNative()
    synchronized(lock) {
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

  override fun readPartialStream(): String = synchronized(lock) {
    val text = partialStream.toString()
    partialStream = StringBuilder()
    text
  }

  override fun readTokenStream(): String = synchronized(lock) {
    fullResponse.toString()
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
      engineType = EngineType.LLAMA_CPP,
      modelPath = currentModelPath
    )
  } catch (_: Exception) {
    null
  }
}
