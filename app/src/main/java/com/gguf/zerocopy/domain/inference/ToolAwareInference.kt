package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared tool-calling agentic loop used by ALL inference engines.
 *
 * Strategy (two-phase):
 *
 * **Phase 1 — Auto-detect (works for ALL models):**
 * Before adding any tool instruction, check the user message for search-like
 * intent using keyword triggers. If found, run the web search immediately
 * via [ToolManager], inject results into the system prompt (NO tool
 * instruction), and run a single inference round. This is the "implicit
 * search" path that works with every model regardless of tool-calling ability.
 *
 * **Phase 2 — Tool-calling loop (for models that support it):**
 * If Phase 1 did NOT trigger (no search intent detected via keywords), add
 * the tool instruction to the system prompt and run the multi-round agentic
 * loop. The model may output a `<tool_call>` JSON to trigger a search, or
 * answer directly.
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"
    private const val MAX_ROUNDS = 4

    /** Keywords that signal a search / real-time-info query. */
    private val SEARCH_TRIGGERS = listOf(
        "search", "find", "look up", "google",
        "what is", "what are", "who is", "who are",
        "when did", "when was", "where is", "where are",
        "latest", "current", "news", "weather",
        "price", "prices", "stock", "stocks",
        "forecast", "today", "now",
        "how much", "how many", "tell me about",
        "recent", "update", "status of"
    )

    fun execute(
        userPrompt: String,
        originalSystemPrompt: String,
        toolManager: ToolManager,
        setSystemPrompt: (String) -> Unit,
        runInference: (prompt: String, tokenSink: TokenCallback, doneSignal: InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean = { false }
    ) {
        // ── Phase 1: Auto-detect search intent ─────────────────────────────
        val searchResults = detectAndRunSearch(userPrompt, toolManager)
        if (searchResults != null) {
            // Inject search results directly into the system prompt —
            // no tool instruction needed, works with EVERY model.
            //
            // FIX: when the search came back genuinely empty ("No results
            // found.", a fetch error, etc.) we must NOT tell the model
            // "here is up-to-date info" followed by nothing useful — small
            // models read that literally, conclude there's nothing to say,
            // and reply "okay" then stop. Instead, explicitly tell the model
            // the search failed/was empty and instruct it to answer from its
            // own knowledge with a disclaimer, so it never just goes silent.
            val searchFailed = isEmptySearchResult(searchResults)
            val augmentedSysPrompt = if (searchFailed) {
                val failureNote = "A web search was attempted for the user's question but returned no usable results (search may have failed, been rate-limited, or the query had nothing indexable). Do NOT say only \"okay\" or stop — answer the user's question as best you can from your own knowledge, and briefly mention that live search data wasn't available."
                if (originalSystemPrompt.isNotEmpty()) "$originalSystemPrompt\n\n$failureNote" else failureNote
            } else {
                val instruction = "Here is up-to-date web search information:\n$searchResults\n\nUse the above information to answer the user's question directly and completely. Do not just acknowledge it — give the actual answer."
                if (originalSystemPrompt.isNotEmpty()) "$originalSystemPrompt\n\n$instruction" else instruction
            }
            setSystemPrompt(augmentedSysPrompt)

            try {
                runSingleRound(userPrompt, runInference, callback, isAborted)
            } finally {
                setSystemPrompt(originalSystemPrompt)
            }
            // runSingleRound already calls callback.onDone() via the live signal,
            // but guard here too in case of early-return paths.
            return
        }

        // ── Phase 2: Tool-calling agentic loop ────────────────────────────
        val toolInstruction = buildString {
            appendLine("You have access to tools. When you need real-time information, use a tool by")
            appendLine("outputting ONLY a JSON block (no other text) in this exact format and then stop:")
            appendLine("```json")
            appendLine("{\"name\": \"tool_name\", \"arguments\": {\"key\": \"value\"}}")
            appendLine("```")
            appendLine("Available tools:")
            appendLine(toolManager.getToolDefinitionsJson())
            appendLine("After receiving a [Tool Result], answer the user using that information.")
        }

        val augmentedSysPrompt = if (originalSystemPrompt.isNotEmpty()) {
            "$originalSystemPrompt\n\n$toolInstruction"
        } else {
            toolInstruction
        }
        setSystemPrompt(augmentedSysPrompt)

        var promptSuffix = ""
        var anyToolCall = false

        try {
            for (round in 0 until MAX_ROUNDS) {
                if (isAborted()) break

                val fullPrompt = if (promptSuffix.isEmpty()) {
                    userPrompt
                } else {
                    "$userPrompt\n$promptSuffix"
                }

                val responseBuf = StringBuilder()
                val roundDone = InferenceDoneSignal()

                // Stream tokens live to the UI as they arrive — buffering
                // happens IN ADDITION TO live forwarding, not instead of it.
                // This is the fix: the UI sees every token immediately,
                // it is never silently swallowed until a round completes.
                val tokenSink = object : TokenCallback {
                    override fun onToken(token: String) {
                        responseBuf.append(token)
                        callback.onToken(token)
                    }
                    override fun onDone() { /* completion handled via roundDone */ }
                    override fun onError(error: String) { /* errors handled via roundDone */ }
                    override fun onKvUsage(percent: Int) { callback.onKvUsage(percent) }
                    override fun onTokensGenerated(count: Int) { callback.onTokensGenerated(count) }
                    override fun onToolCall(toolName: String, toolArgs: String) { callback.onToolCall(toolName, toolArgs) }
                }

                runRound(fullPrompt, tokenSink, roundDone, runInference, callback, round)
                if (roundDone.error != null) { callback.onError(roundDone.error!!); callback.onDone(); return }
                if (isAborted()) break

                val response = responseBuf.toString()
                val toolCall = toolManager.parseToolCall(response)

                if (toolCall == null) {
                    // Already streamed live above — do not re-emit the full
                    // response again, that would duplicate every token.
                    break
                }

                anyToolCall = true
                val query = toolCall.arguments.optString("query",
                    toolCall.arguments.keys().asSequence().firstOrNull()
                        ?.let { toolCall.arguments.optString(it) } ?: toolCall.name)
                val statusMsg = "\n🔍 *Searching: \"$query\"…*\n\n"
                callback.onToken(statusMsg)
                callback.onToolCall(toolCall.name, toolCall.arguments.toString())

                try {
                    val result = toolManager.executeTool(toolCall)
                    promptSuffix += buildString {
                        appendLine()
                        appendLine("[Tool Call]:")
                        appendLine(response.trim())
                        appendLine("[Tool Result for ${toolCall.name}]:")
                        appendLine(result.result.trim())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Tool execution failed: ${e.message}")
                    callback.onError("Tool execution failed: ${e.message}")
                    callback.onDone()
                    return
                }
            }
        } finally {
            setSystemPrompt(originalSystemPrompt)
        }
        callback.onDone()
    }

    /**
     * Detects whether a search result string represents a genuinely empty
     * or failed search (as opposed to real but short results). This is what
     * prevents the model from being told "here is info" when there's none.
     */
    private fun isEmptySearchResult(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return true
        if (t.length < 12) return true // too short to be a real result block
        val failureMarkers = listOf(
            "no results found",
            "web search failed",
            "search timed out",
            "error executing tool",
            "empty search query"
        )
        return failureMarkers.any { t.contains(it) }
    }

    // ── Phase 1 helpers ────────────────────────────────────────────────────

    /**
     * Run a SINGLE inference round and stream tokens LIVE to [callback] as
     * they arrive. No tool instruction is injected — this is the "implicit
     * search" path where search results are already in the system prompt.
     *
     * FIX: previously this buffered every token silently and only emitted
     * the full response once at the very end via callback.onToken(full).
     * That meant the UI received zero tokens during generation and a single
     * giant dump after — which any "is still generating" timeout / stall
     * detector in the UI interpreted as the model stopping after one token.
     * Now every token is forwarded immediately as it streams from the engine.
     */
    private fun runSingleRound(
        prompt: String,
        runInference: (String, TokenCallback, InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean
    ) {
        val roundDone = InferenceDoneSignal()

        val tokenSink = object : TokenCallback {
            override fun onToken(token: String) {
                // Forward immediately — no buffering, no delay.
                callback.onToken(token)
            }
            override fun onDone() { /* completion handled via roundDone */ }
            override fun onError(error: String) { /* errors handled via roundDone */ }
            override fun onKvUsage(percent: Int) { callback.onKvUsage(percent) }
            override fun onTokensGenerated(count: Int) { callback.onTokensGenerated(count) }
            override fun onToolCall(toolName: String, toolArgs: String) {}
        }

        runRound(prompt, tokenSink, roundDone, runInference, callback, 0)
        if (roundDone.error != null) { callback.onError(roundDone.error!!); callback.onDone(); return }
        if (!isAborted()) {
            callback.onDone()
        } else {
            callback.onDone()
        }
    }

    /**
     * Detect whether the user message implies a web search, run it, and
     * return formatted results (or null if no search needed).
     */
    private fun detectAndRunSearch(userPrompt: String, toolManager: ToolManager): String? {
        val lower = userPrompt.lowercase()
        if (!SEARCH_TRIGGERS.any { lower.contains(it) }) return null

        // Build a search query from the user message
        val query = userPrompt.trim()
            .replace(Regex("^(?:search\\s+(?:for\\s+)?|find\\s+|look\\s+up\\s+|google\\s+)", RegexOption.IGNORE_CASE), "")
            .trim()
            .take(200)
            .ifEmpty { userPrompt.take(200) }

        if (query.length < 3) return null

        // Execute the web_search tool via ToolManager
        val args = org.json.JSONObject().apply {
            put("query", query)
            put("num_results", 5)
        }
        val toolCall = ToolCall("auto_${System.currentTimeMillis()}", "web_search", args)
        return try {
            val result = toolManager.executeTool(toolCall)
            val text = result.result.trim()
            text.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-search failed: ${e.message}")
            null
        }
    }

    // ── Shared round runner ────────────────────────────────────────────────

    /**
     * Call [runInference], wait for the [doneSignal], and handle timeouts.
     */
    private fun runRound(
        prompt: String,
        tokenSink: TokenCallback,
        doneSignal: InferenceDoneSignal,
        runInference: (String, TokenCallback, InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        round: Int
    ) {
        try {
            runInference(prompt, tokenSink, doneSignal)
        } catch (e: Exception) {
            Log.e(TAG, "Inference round $round failed: ${e.message}")
            callback.onError("Inference error: ${e.message}")
            callback.onDone()
            return
        }

        val timedOut = !doneSignal.await(5, TimeUnit.MINUTES)
        if (timedOut) {
            Log.e(TAG, "Inference round $round timed out")
            callback.onError("Inference timed out")
            callback.onDone()
            return
        }
    }

    // ── InferenceDoneSignal ────────────────────────────────────────────────

    /**
     * A lightweight one-shot signal that an inference round has completed.
     * The engine's [runInference] lambda must call [signalDone] or [signalError]
     * exactly once before returning. [await] blocks until one of those is called
     * (or the timeout expires).
     */
    class InferenceDoneSignal {
        private val latch = CountDownLatch(1)

        @Volatile
        var error: String? = null
            private set

        fun signalDone() {
            latch.countDown()
        }

        fun signalError(msg: String) {
            error = msg
            latch.countDown()
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean {
            return latch.await(timeout, unit)
        }
    }
}

