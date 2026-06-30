package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared search-context injection helper used by ALL inference engines.
 *
 * This uses an **auto-detect + inject** strategy:
 *
 * 1. Check the user message for search-like keywords
 * 2. If triggered, run the web search via [ToolManager]
 * 3. If the search returns useful results, inject them into the system prompt
 * 4. Run a single normal inference round — the model uses the injected context
 *
 * No tool instruction is added to the prompt.  This works with EVERY model
 * regardless of tool-calling ability, which is exactly what the original
 * working code did in MnnEngine.
 *
 * If keywords are NOT detected, no augmentation happens and normal inference
 * proceeds as usual.
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"

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

    /**
     * Execute search-context injection if the user message implies a need for
     * real-time information.
     *
     * @param userPrompt The raw user message.
     * @param originalSystemPrompt The engine's current system prompt.
     * @param toolManager The ToolManager used to run the web search.
     * @param setSystemPrompt Callback to set the system prompt on the native side.
     * @param runInference Single-round inference runner (blocking).
     * @param callback Token callback to receive the generated response.
     * @param isAborted Function returning true if inference was aborted.
     */
    fun execute(
        userPrompt: String,
        originalSystemPrompt: String,
        toolManager: ToolManager,
        setSystemPrompt: (String) -> Unit,
        runInference: (prompt: String, tokenSink: TokenCallback, doneSignal: InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean = { false }
    ) {
        // ── Auto-detect search intent ────────────────────────────────────
        val searchResults = detectAndRunSearch(userPrompt, toolManager)

        if (searchResults != null && !isAborted()) {
            // Check whether the search genuinely returned useful data
            // or an error / empty result.
            val searchFailed = isEmptySearchResult(searchResults)
            val augmentedSysPrompt = if (searchFailed) {
                // Search failed — tell the model explicitly so it doesn't
                // see "here is up-to-date info" then nothing useful and go
                // silent. Instead, instruct it to answer from its own knowledge.
                val failureNote = "A web search was attempted for the user's question but returned no usable results (search may have failed, been rate-limited, or the query had nothing indexable). Do NOT say only \"okay\" or stop — answer the user's question as best you can from your own knowledge, and briefly mention that live search data wasn't available."
                if (originalSystemPrompt.isNotEmpty()) "$originalSystemPrompt\n\n$failureNote" else failureNote
            } else {
                // Search succeeded — inject results with a strong instruction
                // to actually use them.
                val instruction = "Here is up-to-date web search information:\n$searchResults\n\nUse the above information to answer the user's question directly and completely. Do not just acknowledge it — give the actual answer."
                if (originalSystemPrompt.isNotEmpty()) "$originalSystemPrompt\n\n$instruction" else instruction
            }
            setSystemPrompt(augmentedSysPrompt)

            try {
                runSingleRound(userPrompt, runInference, callback, isAborted)
            } finally {
                setSystemPrompt(originalSystemPrompt)
            }
        } else {
            // No search needed or search failed — run normal inference
            // with the original system prompt (no augmentation).
            runSingleRound(userPrompt, runInference, callback, isAborted)
        }

        callback.onDone()
    }

    // ── Single round runner ─────────────────────────────────────────────────

    /**
     * Run a single inference round and stream tokens LIVE to [callback] as
     * they arrive. Tokens are forwarded immediately so the UI never appears
     * stalled.
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

        runInference(prompt, tokenSink, roundDone)

        // Wait for the engine to signal completion via the doneSignal.
        val timedOut = !roundDone.await(5, TimeUnit.MINUTES)
        if (timedOut) {
            Log.e(TAG, "Inference timed out")
            callback.onError("Inference timed out")
            return
        }
        if (roundDone.error != null) {
            callback.onError(roundDone.error!!)
            return
        }
        // If aborted, the callback already received an onDone from the engine
        // or the abort path; do not call it again.
    }

    // ── Search detection and execution ──────────────────────────────────────

    /**
     * Detects whether a search result string represents a genuinely empty
     * or failed search (as opposed to real but short results).
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

    /**
     * Detect whether the user message implies a web search, run it, and
     * return formatted results (or null if no search needed or search failed).
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

        val args = org.json.JSONObject().apply {
            put("query", query)
            put("num_results", 5)
        }
        val toolCall = ToolCall("auto_${System.currentTimeMillis()}", "web_search", args)

        return try {
            val result = toolManager.executeTool(toolCall)
            val text = result.result.trim()
            // If the search returned an error message or "No results found.",
            // treat it as a failure.
            if (text.isBlank() ||
                text.startsWith("Error", ignoreCase = true) ||
                text.startsWith("Web search failed", ignoreCase = true) ||
                text.startsWith("No results found", ignoreCase = true)
            ) {
                Log.w(TAG, "Auto-search returned no useful results: $text")
                null
            } else {
                text
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-search failed: ${e.message}")
            null
        }
    }

    // ── InferenceDoneSignal ────────────────────────────────────────────────

    /**
     * A lightweight one-shot signal that an inference round has completed.
     * The engine's [runInference] lambda must call [signalDone] or [signalError]
     * exactly once before returning.  [await] blocks until one of those is called
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
