package com.gguf.zerocopy.domain.inference

import android.util.Log
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

  // Tool support — MNN uses same agentic loop pattern as LlamaCppEngine
  private var _toolManager: ToolManager? = null
  override fun getToolManager() = _toolManager
  override fun setToolManager(tm: ToolManager?) { _toolManager = tm }

  private val nativeLibLoaded: Boolean

  init {
    var loaded = false
    try {
      System.loadLibrary("mnn-bridge")
      loaded = true
    } catch (_: UnsatisfiedLinkError) {}
    nativeLibLoaded = loaded
  }

  private external fun mnnLoadModel(path: String): Boolean
  private external fun mnnExecuteInference(prompt: String, callback: NativeBridge.TokenCallback)
  private external fun mnnAbortInference()
  private external fun mnnResetContext()
  private external fun mnnGetModelInfo(): String
  private external fun mnnBenchmark(ppTokens: Int, tgTokens: Int): String
  private external fun mnnSetConfigNative(nCtx: Int, maxNewTokens: Int, temperature: Float, repeatPenalty: Float)
  private external fun mnnSetSystemPromptNative(prompt: String)
  private external fun mnnGetKvCacheUsage(): Int
  private external fun mnnGetTokensGenerated(): Int
  private external fun mnnIsInferenceDone(): Boolean

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (!nativeLibLoaded) return@withContext Result.failure(Exception("MNN native library not available"))
    try {
      currentModelPath = path
      val modelDir = resolveModelDir(path)
      mnnSetConfigNative(config.nCtx, config.maxNewTokens, config.temperature, repeatPenalty.repeatPenalty)
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
    try { mnnResetContext() } catch (_: Exception) {}
    isModelLoaded = false
    modelInfo = null
    currentModelPath = ""
  }

  override suspend fun executeInferenceWithImage(prompt: String, imagePath: String, callback: TokenCallback) {
    executeInference("[Image: $imagePath]\n$prompt", callback)
  }

  // ── Low-level single native turn ──────────────────────────────────────────
  private fun runNativeTurn(
    prompt: String,
    onToken: (String) -> Unit,
    onKv: (Int) -> Unit,
    onTokCount: (Int) -> Unit
  ): Pair<String, String?> {
    val buf = StringBuilder()
    var err: String? = null
    val cb = object : NativeBridge.TokenCallback {
      override fun onToken(token: String) { buf.append(token); onToken(token) }
      override fun onDone() {}
      override fun onError(e: String) { err = e }
      override fun onKvCacheUsage(p: Int) { onKv(p) }
      override fun onTokensGenerated(c: Int) { onTokCount(c) }
    }
    mnnExecuteInference(prompt, cb)
    return buf.toString() to err
  }

  // ── Tool-aware agentic loop (mirrors LlamaCppEngine) ──────────────────────
  private fun runWithTools(prompt: String, tm: ToolManager, callback: TokenCallback) {
    val toolInstruction = buildString {
      appendLine("You have access to tools. When you need real-time information, use a tool by")
      appendLine("outputting ONLY a JSON block (no other text) in this exact format and then stop:")
      appendLine("```json")
      appendLine("{\"name\": \"tool_name\", \"arguments\": {\"key\": \"value\"}}")
      appendLine("```")
      appendLine("Available tools:")
      appendLine(tm.getToolDefinitionsJson())
      appendLine("After receiving a [Tool Result], answer the user using that information.")
    }
    val origSystemPrompt = systemPrompt
    mnnSetSystemPromptNative(
      if (origSystemPrompt.isNotEmpty()) "$origSystemPrompt\n\n$toolInstruction" else toolInstruction
    )

    var promptSuffix = ""
    val MAX_ROUNDS = 4

    try {
      for (round in 0 until MAX_ROUNDS) {
        val fullPrompt = if (promptSuffix.isEmpty()) prompt else "$prompt\n$promptSuffix"
        val responseBuf = StringBuilder()
        var turnErr: String? = null

        val innerCb = object : NativeBridge.TokenCallback {
          override fun onToken(t: String) { responseBuf.append(t) }
          override fun onDone() {}
          override fun onError(e: String) { turnErr = e }
          override fun onKvCacheUsage(p: Int) { callback.onKvUsage(p); kvUsage = p }
          override fun onTokensGenerated(c: Int) { callback.onTokensGenerated(c); tokensGenerated.set(c) }
        }
        mnnExecuteInference(fullPrompt, innerCb)

        if (turnErr != null) { callback.onError(turnErr!!); return }

        val response = responseBuf.toString()
        val toolCall = tm.parseToolCall(response)

        if (toolCall == null) {
          for (ch in response) callback.onToken(ch.toString())
          break
        }

        val query = toolCall.arguments.optString("query",
          toolCall.arguments.keys().asSequence().firstOrNull()
            ?.let { toolCall.arguments.optString(it) } ?: toolCall.name)
        val status = "\n🔍 *Searching: \"$query\"…*\n\n"
        for (ch in status) callback.onToken(ch.toString())
        callback.onToolCall(toolCall.name, toolCall.arguments.toString())

        val result = tm.executeTool(toolCall)
        promptSuffix += "\n[Tool Call]:\n" + response.trim() +
          "\n[Tool Result for ${toolCall.name}]:\n" + result.result.trim() + "\n"
      }
    } finally {
      mnnSetSystemPromptNative(origSystemPrompt)
    }
    callback.onDone()
  }

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    if (!nativeLibLoaded) { callback.onError("MNN native library not available"); return }
    withContext(Dispatchers.IO) {
      synchronized(partialStream) { partialStream.clear(); fullResponse.clear() }
      inferenceDone.set(false)
      tokensGenerated.set(0)

      val tm = _toolManager
      if (tm != null) {
        runWithTools(prompt, tm, callback)
        inferenceDone.set(true)
        return@withContext
      }

      val cb = object : NativeBridge.TokenCallback {
        override fun onToken(token: String) {
          synchronized(partialStream) { partialStream.append(token); fullResponse.append(token) }
          callback.onToken(token)
        }
        override fun onDone() { inferenceDone.set(true); callback.onDone() }
        override fun onError(error: String) { inferenceDone.set(true); callback.onError(error) }
        override fun onKvCacheUsage(percent: Int) { kvUsage = percent; callback.onKvUsage(percent) }
        override fun onTokensGenerated(count: Int) { tokensGenerated.set(count); callback.onTokensGenerated(count) }
      }
      try {
        mnnExecuteInference(prompt, cb)
      } catch (e: Exception) {
        inferenceDone.set(true)
        callback.onError(e.message ?: "MNN inference failed")
      }
    }
  }

  override fun abortInference() {
    inferenceDone.set(true)
    try { mnnAbortInference() } catch (_: Exception) {}
  }

  override fun resetContext() {
    try { mnnResetContext() } catch (_: Exception) {}
    synchronized(partialStream) { partialStream.clear(); fullResponse.clear() }
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
      } catch (_: Exception) { BenchmarkResult(engine = engineName) }
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
    if (file.isDirectory && File(file, "config.json").exists()) return file.absolutePath
    val parent = file.parentFile
    if (parent != null && File(parent, "config.json").exists()) return parent.absolutePath
    val siblingDir = File(file.parent ?: "", file.nameWithoutExtension)
    if (siblingDir.isDirectory && File(siblingDir, "config.json").exists()) return siblingDir.absolutePath
    val found = (parent ?: file).walk().maxDepth(2)
      .firstOrNull { it.name == "config.json" && it.parentFile?.isDirectory == true }
    if (found != null) return found.parentFile!!.absolutePath
    Log.w("MnnEngine", "Could not find config.json near $path — passing path directly")
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
  } catch (_: Exception) { null }
}
