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
  private fun runWithTools(userPrompt: String, callback: TokenCallback) {
    // ── Auto-detect search intent and run search ──────────────────────────
    // Small models (Gemma 3 1B, etc.) can't reliably output structured
    // <tool_call> XML. Instead we detect search-like queries automatically
    // and prepend results to the prompt as context — no tool call needed.
    val searchContext = detectAndRunSearch(userPrompt)

    // ── Augment system prompt with search results ─────────────────────────
    val origSystemPrompt = systemPrompt
    val augmentedSysPrompt = buildString {
      if (origSystemPrompt.isNotEmpty()) appendLine(origSystemPrompt)
      if (searchContext != null) {
        appendLine()
        appendLine("Here is up-to-date web search information:")
        appendLine(searchContext)
        appendLine()
        appendLine("Use the above information to answer the user if relevant.")
      }
    }
    NativeBridge.setSystemPromptNative(augmentedSysPrompt)

    // ── Single inference round ────────────────────────────────────────────
    try {
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
        NativeBridge.executeWithCallbackNative(userPrompt, innerCb)
      } catch (e: Exception) {
        callback.onError("Inference error: ${e.message}")
        callback.onDone()
        return
      } finally {
        activeCallback = null
      }

      if (turnErr != null) { callback.onError(turnErr!!); callback.onDone(); return }

      // Stream the response
      val response = responseBuf.toString().trim()
      for (ch in response) {
        if (inferenceAborted.get()) break
        callback.onToken(ch.toString())
      }
    } finally {
      NativeBridge.setSystemPromptNative(origSystemPrompt)
    }
    callback.onDone()
  }

  /**
   * Detects whether the user message implies a web search, runs it,
   * and returns a formatted results string (or null if no search needed).
   */
  private fun detectAndRunSearch(userPrompt: String): String? {
    val lower = userPrompt.lowercase()
    val triggers = listOf(
      "search", "find", "look up", "google",
      "what is", "what are", "who is", "who are",
      "when", "where is", "where are",
      "latest", "current", "news", "weather",
      "price", "prices", "stock", "stocks",
      "forecast", "today", "now",
      "how much", "how many", "tell me about"
    )
    if (!triggers.any { lower.contains(it) }) return null

    // Extract a concise query from the message
    var query = userPrompt.trim()
      .replace(Regex("^(?:search\\s+(?:for\\s+)?|find\\s+|look\\s+up\\s+|google\\s+)", RegexOption.IGNORE_CASE), "")
      .trim()
      .take(200)
    if (query.length < 3) query = userPrompt.take(200)

    return try {
      val encoded = java.net.URLEncoder.encode(query, "UTF-8")
      val results = fetchDuckDuckGo(encoded)
      if (results.isNotBlank()) results else null
    } catch (_: Exception) { null }
  }

  /**
   * Fetches web results from DuckDuckGo (lite API first, HTML fallback).
   * Called on background thread (from within runWithTools which is on Dispatchers.IO).
   */
  private fun fetchDuckDuckGo(encoded: String): String {
    val lite = fetchDdgLite(encoded)
    if (lite.isNotBlank()) return lite
    return fetchDdgHtml(encoded)
  }

  private fun openDdgConn(url: String): java.net.HttpURLConnection =
    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 10_000
      readTimeout = 10_000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
      setRequestProperty("Accept", "text/html,*/*;q=0.8")
      setRequestProperty("Connection", "close")
    }

  private fun fetchDdgLite(encoded: String): String {
    val conn = openDdgConn("https://lite.duckduckgo.com/lite/?q=$encoded")
    if (conn.responseCode != 200) { conn.disconnect(); return "" }
    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    conn.disconnect()
    return formatDdgResults(parseDdgLinks(html, "result-link", "result-snippet"), 5)
  }

  private fun fetchDdgHtml(encoded: String): String {
    val conn = openDdgConn("https://html.duckduckgo.com/html/?q=$encoded")
    if (conn.responseCode != 200) { conn.disconnect(); return "" }
    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    conn.disconnect()
    return formatDdgResults(parseDdgLinks(html, "result__a", "result__snippet"), 5)
  }

  private data class DdgResult(val title: String, val url: String, val snippet: String)

  private fun parseDdgLinks(html: String, linkClass: String, snipClass: String): List<DdgResult> {
    val linkPat = Regex("""<a[^>]+class="$linkClass"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    val snipPat = Regex("""<[^>]+class="$snipClass"[^>]*>(.*?)</[^>]+>""", RegexOption.DOT_MATCHES_ALL)
    val links = linkPat.findAll(html).toList()
    val snips = snipPat.findAll(html).toList()
    val results = mutableListOf<DdgResult>()
    for (i in links.indices) {
      val title = stripHtml(links[i].groupValues[2]).trim()
      if (title.isEmpty()) continue
      var url = decodeHtml(links[i].groupValues[1])
      if ("uddg=" in url) {
        url = try { java.net.URLDecoder.decode(url.substringAfter("uddg=").substringBefore("&"), "UTF-8") }
        catch (_: Exception) { url }
      }
      val snip = snips.getOrNull(i)?.let { stripHtml(it.groupValues[1]).trim() } ?: ""
      results.add(DdgResult(title, url, snip))
    }
    return results
  }

  private fun formatDdgResults(results: List<DdgResult>, max: Int): String {
    if (results.isEmpty()) return ""
    return results.take(max).joinToString("\n---\n") { r ->
      buildString {
        appendLine(r.title)
        appendLine("URL: ${r.url}")
        if (r.snippet.isNotEmpty()) appendLine(r.snippet)
      }
    }
  }

  private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "").let { decodeHtml(it) }

  private fun decodeHtml(s: String) = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }

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

      val webSearchEnabled = com.gguf.zerocopy.data.local.SettingsManager.webSearchEnabled
      if (webSearchEnabled) {
        // Auto-search strategy: detect search intent from user message,
        // run web search if needed, feed results as system prompt context.
        // No tool call output required from the model.
        val alreadyWrapped = prompt.startsWith("<|system|") ||
          prompt.startsWith("<|im_start|") ||
          prompt.startsWith("<|begin_of_text|") ||
          prompt.contains("\n<|user|>\n") ||
          prompt.contains("\n<|im_start|>user\n")
        if (!alreadyWrapped) {
          runWithTools(prompt, callback)
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
        callback.onDone()
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
        callback.onDone()
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
