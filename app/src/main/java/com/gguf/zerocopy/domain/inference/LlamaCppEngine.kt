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

  /**
   * Snapshot of the last history JSON passed by ChatScreen via restoreHistory().
   * runWithTools() uses this to properly re-seed the context on round > 0
   * WITHOUT wiping the context on round 0 (which caused the immediate crash).
   */
  private var lastRestoredHistoryJson: String = "[]"

  override fun getToolManager() = _toolManager
  override fun setToolManager(tm: ToolManager?) { _toolManager = tm }

  // ── Model load ────────────────────────────────────────────────────────────

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (!nativeLibLoaded) return@withContext Result.failure(Exception("llama.cpp native library not available"))
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
    result
  }

  override fun unloadModel() {
    NativeBridge.resetContextNative()
    isModelLoaded = false; modelInfo = null; currentModelPath = ""
    lastRestoredHistoryJson = "[]"
    _toolManager = null  // clear tool manager so it doesn't leak into Invent
  }

  override fun loadMmproj(path: String): Boolean {
    mmprojPath = path
    return runCatching { NativeBridge.loadMmprojNative(path) }.getOrDefault(false)
  }

  // ── Tool-aware agentic loop ───────────────────────────────────────────────
  //
  // Design rules (to avoid the immediate-crash bug):
  //
  // Round 0:
  //   ChatScreen already called restoreHistory() → native context is primed.
  //   We DO NOT touch restoreHistoryNative here.  We call executeWithCallbackNative
  //   directly with a tool-augmented version of the user prompt.
  //
  // Round N > 0 (tool follow-up):
  //   We MUST reset the context first (resetContextNative), then re-seed it
  //   with the saved history plus the tool exchange, then call
  //   executeWithCallbackNative with the continuation prompt.
  //
  // This avoids:
  //   • Calling restoreHistoryNative("[]") which wiped the ChatScreen context → crash
  //   • Calling executeWithCallbackNative twice on the same live context → undefined behaviour
  //
  private fun runWithTools(userPrompt: String, tm: ToolManager, callback: TokenCallback) {
    val toolDefs = tm.getToolDefinitionsJson()

    // Compact tool instruction appended to the user's message on round 0.
    // Kept short so it doesn't overflow small context windows.
    val toolPreamble = buildString {
      appendLine("You have tools. If you need live info, output EXACTLY (nothing else on that line):")
      appendLine("<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"YOUR QUERY\"}}</tool_call>")
      appendLine("Then stop. After you see [Tool Result] continue your answer.")
      appendLine("Tools: ${toolDefs.take(600)}")   // truncate for small contexts
    }

    // The prompt for round 0 — tool instruction + original user message
    val round0Prompt = "$toolPreamble\n\nUser: $userPrompt"

    // Accumulated tool exchanges for context re-seeding on round > 0
    data class Exchange(val assistantMsg: String, val toolResultMsg: String)
    val exchanges = mutableListOf<Exchange>()

    val MAX_ROUNDS = 4

    for (round in 0 until MAX_ROUNDS) {
      if (inferenceAborted.get()) break

      // ── Context setup ────────────────────────────────────────────────────
      if (round == 0) {
        // Context already primed by ChatScreen's restoreHistory call — do NOT reset it.
        // Just proceed with the augmented prompt.
      } else {
        // Reset the native context cleanly before re-seeding.
        NativeBridge.resetContextNative()

        // Re-build history: original chat history + all tool exchanges so far
        val fullHistory = JSONArray().apply {
          // Original chat history entries
          val origArr = try { JSONArray(lastRestoredHistoryJson) } catch (_: Exception) { JSONArray() }
          for (i in 0 until origArr.length()) put(origArr.getJSONObject(i))
          // Tool exchanges from previous rounds
          exchanges.forEach { ex ->
            put(JSONObject().apply { put("role", "assistant"); put("content", ex.assistantMsg) })
            put(JSONObject().apply { put("role", "user");      put("content", ex.toolResultMsg) })
          }
        }
        NativeBridge.restoreHistoryNative(fullHistory.toString())
      }

      // ── Pick the prompt for this round ───────────────────────────────────
      val currentPrompt = if (round == 0) round0Prompt
                          else "Continue answering the user based on the tool result above."

      // ── Run one inference turn ───────────────────────────────────────────
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
      try {
        NativeBridge.executeWithCallbackNative(currentPrompt, innerCb)
      } catch (e: Exception) {
        callback.onError("Inference error: ${e.message}")
        activeCallback = null
        return
      }
      activeCallback = null

      if (turnErr != null) { callback.onError(turnErr!!); callback.onDone(); return }

      val response = responseBuf.toString().trim()
      val toolCall = tm.parseToolCall(response)

      if (toolCall == null) {
        // No tool call — stream the final answer
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

      // ── Tool call detected ───────────────────────────────────────────────
      val queryPreview = toolCall.arguments.optString("query",
        toolCall.arguments.keys().asSequence().firstOrNull()
          ?.let { toolCall.arguments.optString(it) } ?: toolCall.name)
      val statusMsg = "\n🔍 *Searching: \"$queryPreview\"…*\n\n"
      for (ch in statusMsg) callback.onToken(ch.toString())
      callback.onToolCall(toolCall.name, toolCall.arguments.toString())

      val toolResult = try {
        tm.executeTool(toolCall)
      } catch (e: Exception) {
        callback.onError("Tool execution failed: ${e.message}")
        callback.onDone()  // must call onDone or caller hangs
        return
      }

      // Record this exchange for context re-seeding on the next round
      exchanges.add(Exchange(
        assistantMsg = response,
        toolResultMsg = "[Tool Result for ${toolCall.name}]:\n${toolResult.result}"
      ))
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
    if (!nativeLibLoaded) { callback.onError("llama.cpp native library not available"); callback.onDone(); return }
    if (!isModelLoaded) { callback.onError("No model is loaded — load a GGUF file first"); callback.onDone(); return }
    // Reset the abort flag so a previously-aborted inference doesn't
    // kill this new one.  The other state fields are reset below.
    inferenceAborted.set(false)
    withContext(Dispatchers.IO) {
      synchronized(lock) { partialStream.clear(); fullResponse.clear() }
      inferenceDone.set(false)
      tokensGenerated.set(0)

      val tm = _toolManager
      if (tm != null) {
        // Only use runWithTools if the prompt doesn't already contain chat-template
        // markup (e.g. system/user/assistant tokens).  Invent builds its own
        // fully-formatted prompt and must NOT be re-wrapped with tool preamble.
        val alreadyWrapped = prompt.startsWith("<|system|") ||
          prompt.startsWith("<|im_start|") ||
          prompt.startsWith("<|begin_of_text|") ||
          prompt.contains("\n<|user|>\n") ||
          prompt.contains("\n<|im_start|>user\n")
        if (!alreadyWrapped) {
          runWithTools(prompt, tm, callback)
          inferenceDone.set(true)
          return@withContext
        }
        // fall through — prompt is already fully formatted, run directly
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
    if (!nativeLibLoaded) { callback.onError("llama.cpp native library not available"); callback.onDone(); return }
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
    val json = buildHistoryJson(messages)
    lastRestoredHistoryJson = json          // save snapshot for runWithTools round > 0
    NativeBridge.restoreHistoryNative(json)
  }

  override fun resetContext() {
    NativeBridge.resetContextNative()
    synchronized(lock) { partialStream.clear(); fullResponse.clear() }
    inferenceDone.set(true); tokensGenerated.set(0); kvUsage = 0
    lastRestoredHistoryJson = "[]"
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
