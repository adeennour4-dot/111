package com.gguf.zerocopy.domain.inference

import android.util.Log
import com.gguf.zerocopy.ZeroCopyApp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LlamaCppEngine : InferenceEngine {
  private val nativeLibLoaded: Boolean by lazy {
    try { System.loadLibrary("ipc-bridge"); true }
    catch (e: UnsatisfiedLinkError) {
      Log.e("LlamaCppEngine", "Failed to load native library: ${e.message}"); false
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
    set(v) { field = v; if (isModelLoaded) NativeBridge.setSystemPromptNative(v) }
  override var mmprojPath: String = ""

  private val lock = Any()
  private var partialStream = StringBuilder()
  private var fullResponse = StringBuilder()
  private val inferenceDone = AtomicBoolean(true)
  private val inferenceAborted = AtomicBoolean(false)
  private val tokensGenerated = AtomicInteger(0)
  private var kvUsage = 0
  private var currentModelPath = ""
  private var _toolManager: ToolManager? = null
  private var activeCallback: NativeBridge.TokenCallback? = null
  override fun getToolManager() = _toolManager
  override fun setToolManager(tm: ToolManager?) { _toolManager = tm }

  // ── Model load ────────────────────────────────────────────────────────────

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (!nativeLibLoaded) return@withContext Result.failure(Exception("llama.cpp native library not available"))
    val sentinel = runCatching {
      java.io.File(ZeroCopyApp.instance.filesDir, ".loading_sentinel").also { it.createNewFile() }
    }.getOrNull()
    val result = try {
      currentModelPath = path
      NativeBridge.setEngineConfigNative(
        config.nCtx, config.nBatch, config.maxNewTokens, config.temperature,
        config.topP, config.minP, config.nGpuLayers, config.nThreads,
        config.seed, config.lowRamMode, config.flashAttention
      )
      NativeBridge.setRepeatPenaltyNative(repeatPenalty.repeatPenalty, repeatPenalty.freqPenalty, repeatPenalty.presPenalty)
      if (systemPrompt.isNotEmpty()) NativeBridge.setSystemPromptNative(systemPrompt)
      val ok = NativeBridge.loadGgufModelNative(path)
      if (ok) {
        isModelLoaded = true
        modelInfo = parseModelInfo(NativeBridge.getModelInfoNative())
        if (mmprojPath.isNotEmpty()) runCatching { NativeBridge.loadMmprojNative(mmprojPath) }
        Result.success(Unit)
      } else {
        Result.failure(Exception("Failed to load GGUF model"))
      }
    } catch (e: Exception) { Result.failure(e) }
    runCatching { sentinel?.delete() }
    result
  }

  override fun unloadModel() {
    NativeBridge.resetContextNative()
    isModelLoaded = false; modelInfo = null; currentModelPath = ""
  }

  override fun loadMmproj(path: String): Boolean {
    mmprojPath = path
    return runCatching { NativeBridge.loadMmprojNative(path) }.getOrDefault(false)
  }

  // ── Tool-aware agentic loop ───────────────────────────────────────────────

  /**
   * Full rewrite of runWithTools to fix all four search failure root causes:
   *
   * 1. Tool instruction is injected as a FIRST USER MESSAGE (not via setSystemPromptNative)
   *    so it doesn't fight the KV-cached system prompt from loadModel().
   *
   * 2. Tool results are fed back through NativeBridge.restoreHistoryNative() as proper
   *    assistant + user role pairs, matching the chat template the model was trained on.
   *
   * 3. parseToolCall() now handles all common formats:
   *    <tool_call>{...}</tool_call>, ```json{...}```, bare {...}, {"function":...}
   *
   * 4. After each round the context is NOT reset — we use history accumulation so the
   *    model sees the full conversation including all tool exchanges.
   */
  private fun runWithTools(userPrompt: String, tm: ToolManager, callback: TokenCallback) {
    val toolDefs = tm.getToolDefinitionsJson()

    // Build tool instruction as a system-level prefix that survives KV cache
    // by including it in the very first user message content, not via setSystemPromptNative.
    val toolInstruction = buildString {
      appendLine("You have access to the following tools. Use them when you need real-time or external information.")
      appendLine()
      appendLine("TOOLS:")
      appendLine(toolDefs)
      appendLine()
      appendLine("To use a tool, output EXACTLY this format on its own line (nothing before or after):")
      appendLine("<tool_call>{\"name\": \"TOOL_NAME\", \"arguments\": {\"KEY\": \"VALUE\"}}</tool_call>")
      appendLine()
      appendLine("After you see a <tool_result>, use that information to answer the user.")
      appendLine("If you don't need a tool, answer directly.")
      appendLine()
      appendLine("User request: $userPrompt")
    }

    // History accumulates across rounds as proper role pairs
    val history = mutableListOf<Pair<String, String>>() // (role, content)
    val MAX_ROUNDS = 5

    try {
      var currentInput = toolInstruction

      for (round in 0 until MAX_ROUNDS) {
        if (inferenceAborted.get()) break

        // Restore history before each turn so the model has full context
        NativeBridge.restoreHistoryNative(buildHistoryJson(history))

        val responseBuf = StringBuilder()
        var turnErr: String? = null

        val innerCb = object : NativeBridge.TokenCallback {
          override fun onToken(t: String) { responseBuf.append(t) }
          override fun onDone() {}
          override fun onError(e: String) { turnErr = e }
          override fun onKvCacheUsage(p: Int) { callback.onKvUsage(p); kvUsage = p }
          override fun onTokensGenerated(c: Int) { callback.onTokensGenerated(c); tokensGenerated.set(c) }
        }
        activeCallback = innerCb
        NativeBridge.executeWithCallbackNative(currentInput, innerCb)
        activeCallback = null

        if (turnErr != null) { callback.onError(turnErr!!); return }

        val response = responseBuf.toString().trim()
        val toolCall = tm.parseToolCall(response)

        if (toolCall == null) {
          // No tool call — this is the final answer, stream it to the user
          val clean = response
            .replace(Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
          val toStream = clean.ifEmpty { response }
          for (ch in toStream) {
            if (inferenceAborted.get()) break
            callback.onToken(ch.toString())
          }
          break
        }

        // Tool call detected — show live status to user
        val queryPreview = toolCall.arguments.optString("query",
          toolCall.arguments.keys().asSequence().firstOrNull()
            ?.let { toolCall.arguments.optString(it) } ?: toolCall.name)
        val statusMsg = "\n🔍 *Searching: \"$queryPreview\"…*\n\n"
        for (ch in statusMsg) callback.onToken(ch.toString())
        callback.onToolCall(toolCall.name, toolCall.arguments.toString())

        // Execute the tool
        val toolResult = tm.executeTool(toolCall)

        // Add this exchange to history as proper role pairs
        // assistant role = what the model said (the tool call)
        history.add("assistant" to response)
        // user/tool role = the tool result fed back
        history.add("user" to "<tool_result>${toolResult.result}</tool_result>")

        // Next round: ask model to continue with the tool result in context
        currentInput = "Based on the tool result above, please answer the original request."
      }
    } finally {
      // Restore history to just what was there before (caller handles full history)
      NativeBridge.restoreHistoryNative("[]")
    }

    callback.onDone()
  }

  private fun buildHistoryJson(history: List<Pair<String, String>>): String {
    val arr = JSONArray()
    history.forEach { (role, content) ->
      arr.put(JSONObject().apply { put("role", role); put("content", content) })
    }
    return arr.toString()
  }

  // ── executeInference ──────────────────────────────────────────────────────

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    if (!nativeLibLoaded) { callback.onError("llama.cpp native library not available"); return }
    withContext(Dispatchers.IO) {
      synchronized(lock) { partialStream.clear(); fullResponse.clear() }
      inferenceDone.set(false)
      inferenceAborted.set(false)
      tokensGenerated.set(0)

      val tm = _toolManager
      if (tm != null) {
        runWithTools(prompt, tm, callback)
        inferenceDone.set(true)
        return@withContext
      }

      val cb = object : NativeBridge.TokenCallback {
        override fun onToken(token: String) {
          synchronized(lock) { partialStream.append(token); fullResponse.append(token) }
          callback.onToken(token)
        }
        override fun onDone() { callback.onDone(); inferenceDone.set(true); activeCallback = null }
        override fun onError(error: String) { callback.onError(error); inferenceDone.set(true); activeCallback = null }
        override fun onKvCacheUsage(percent: Int) { kvUsage = percent; callback.onKvUsage(percent) }
        override fun onTokensGenerated(count: Int) { tokensGenerated.set(count); callback.onTokensGenerated(count) }
      }
      activeCallback = cb
      try {
        NativeBridge.executeWithCallbackNative(prompt, cb)
      } catch (e: Exception) {
        Log.e("LlamaCppEngine", "Exception during inference: ${e.message}")
        inferenceDone.set(true); activeCallback = null
      } finally {
        if (!inferenceDone.get()) activeCallback = null
      }
    }
  }

  override suspend fun executeInferenceWithImage(prompt: String, imagePath: String, callback: TokenCallback) {
    if (!nativeLibLoaded) { callback.onError("llama.cpp native library not available"); return }
    withContext(Dispatchers.IO) {
      synchronized(lock) { partialStream.clear(); fullResponse.clear() }
      inferenceDone.set(false); tokensGenerated.set(0)
      val cb = object : NativeBridge.TokenCallback {
        override fun onToken(token: String) {
          synchronized(lock) { partialStream.append(token); fullResponse.append(token) }
          callback.onToken(token)
        }
        override fun onDone() { callback.onDone(); inferenceDone.set(true); activeCallback = null }
        override fun onError(error: String) { callback.onError(error); inferenceDone.set(true); activeCallback = null }
        override fun onKvCacheUsage(percent: Int) { kvUsage = percent; callback.onKvUsage(percent) }
        override fun onTokensGenerated(count: Int) { tokensGenerated.set(count); callback.onTokensGenerated(count) }
      }
      activeCallback = cb
      try {
        NativeBridge.executeWithImageNative(prompt, imagePath, cb)
      } catch (e: Exception) {
        Log.e("LlamaCppEngine", "Exception during image inference: ${e.message}")
        inferenceDone.set(true); activeCallback = null
      } finally {
        if (!inferenceDone.get()) activeCallback = null
      }
    }
  }

  override fun abortInference() {
    inferenceAborted.set(true)
    NativeBridge.abortInferenceNative()
  }

  override fun restoreHistory(messages: List<Pair<String, String>>) {
    NativeBridge.restoreHistoryNative(buildHistoryJson(messages))
  }

  override fun resetContext() {
    NativeBridge.resetContextNative()
    synchronized(lock) { partialStream.clear(); fullResponse.clear() }
    inferenceDone.set(true); tokensGenerated.set(0); kvUsage = 0
  }

  override suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult = withContext(Dispatchers.IO) {
    try {
      val json = JSONObject(NativeBridge.benchmarkNative(ppTokens, tgTokens))
      BenchmarkResult(
        engine = engineName,
        prefillTps = json.optDouble("pp_tps", 0.0).toFloat(),
        decodeTps  = json.optDouble("tg_tps", 0.0).toFloat(),
        prefillMs  = json.optDouble("pp_ms",  0.0).toFloat(),
        decodeMs   = json.optDouble("tg_ms",  0.0).toFloat(),
        prefillTokens = ppTokens, decodeTokens = tgTokens
      )
    } catch (_: Exception) { BenchmarkResult(engine = engineName) }
  }

  override fun supportsFormat(path: String): Boolean = path.endsWith(".gguf", true)
  override fun getTokensGenerated(): Int = tokensGenerated.get()
  override fun getKvUsage(): Int = kvUsage
  override fun isInferenceDone(): Boolean = inferenceDone.get()
  override fun readPartialStream(): String = synchronized(lock) {
    partialStream.toString().also { partialStream = StringBuilder() }
  }
  override fun readTokenStream(): String = synchronized(lock) { fullResponse.toString() }

  private fun parseModelInfo(jsonStr: String): ModelInfo? = try {
    val j = JSONObject(jsonStr)
    ModelInfo(
      arch = j.optString("arch", ""), nParams = j.optLong("n_params", 0),
      nLayers = j.optInt("n_layer", 0), nEmbeds = j.optInt("n_embd", 0),
      contextLength = j.optInt("ctx_train", 0), vocabSize = j.optInt("n_vocab", 0),
      quantization = j.optString("quantization", ""),
      engineType = EngineType.LLAMA_CPP, modelPath = currentModelPath
    )
  } catch (_: Exception) { null }
}
