package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared search-context injection helper used by ALL inference engines.
 *
 * When search is ON, this auto-detects search intent from keywords
 * and prepends results to the user prompt. This works with ALL chat
 * templates (Gemma, Llama, ChatML, etc.) because the search results
 * are inside a user message, which every template supports.
 *
 * The model never needs to understand tool calling — it just sees
 * search results as context and answers using them.
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
        "recent", "update", "status of",
        "can you", "do you know", "explain"
    )

    /**
     * Execute with search context injection.
     *
     * 1. Check if user message implies search (keyword detection)
     * 2. If yes → run search → prepend results to user prompt
     * 3. If no → run normal inference
     *
     * Search results are prepended to the user prompt (NOT the system prompt)
     * because some chat templates (Gemma, etc.) don't support system messages
     * and silently discard them. User messages are always supported.
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
        // Auto-detect search intent from keywords
        val lower = userPrompt.lowercase()
        val needsSearch = SEARCH_TRIGGERS.any { lower.contains(it) }

        if (needsSearch) {
            // Run search and prepend results to user prompt
            val searchResults = runSearch(userPrompt, toolManager)
            val augmentedPrompt = if (searchResults != null) {
                "Search results:\n$searchResults\n\n---\n\nUser question:\n$userPrompt"
            } else {
                // Search failed — tell model to answer from knowledge
                "(Search was attempted but no usable results were found. Answer from your own knowledge.)\n\n$userPrompt"
            }

            // Restore original system prompt (in case a previous round changed it)
            setSystemPrompt(originalSystemPrompt)
            runSingleRound(augmentedPrompt, runInference, callback, isAborted)
        } else {
            // No search needed — run normal inference
            runSingleRound(userPrompt, runInference, callback, isAborted)
        }

        // IMPORTANT: onDone() is already called inside runSingleRound via the
        // inner callback's onDone/onError. DO NOT call it again here or the
        // ChatScreen will receive a duplicate completion signal, corrupting state.
    }

    // ── Single round runner ─────────────────────────────────────────────────

    private fun runSingleRound(
        prompt: String,
        runInference: (String, TokenCallback, InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean
    ) {
        // Check if inference was aborted before starting
        if (isAborted()) {
            callback.onDone()
            return
        }

        val roundDone = InferenceDoneSignal()

        val tokenSink = object : TokenCallback {
            override fun onToken(token: String) {
                // Check abort on each token — if aborted, stop forwarding tokens
                if (isAborted()) {
                    roundDone.signalDone()
                    return
                }
                callback.onToken(token)
            }
            override fun onDone() {}
            override fun onError(error: String) {}
            override fun onKvUsage(percent: Int) { callback.onKvUsage(percent) }
            override fun onTokensGenerated(count: Int) { callback.onTokensGenerated(count) }
            override fun onToolCall(toolName: String, toolArgs: String) {}
        }

        runInference(prompt, tokenSink, roundDone)

        val timedOut = !roundDone.await(5, TimeUnit.MINUTES)
        if (timedOut) { 
            if (!isAborted()) callback.onError("Inference timed out")
            else callback.onDone()
            return 
        }
        if (roundDone.error != null) { 
            if (!isAborted()) callback.onError(roundDone.error!!)
            else callback.onDone()
            return 
        }
    }

    // ── Search execution ────────────────────────────────────────────────────

    private fun runSearch(userPrompt: String, toolManager: ToolManager): String? {
        // Build search query from user message
        val query = userPrompt.trim()
            .replace(Regex("^(?:search\\s+(?:for\\s+)?|find\\s+|look\\s+up\\s+|google\\s+)", RegexOption.IGNORE_CASE), "")
            .trim()
            .take(200)
            .ifEmpty { userPrompt.take(200) }

        if (query.length < 2) return null

        val args = org.json.JSONObject().apply {
            put("query", query)
            put("num_results", 5)
        }
        val toolCall = ToolCall("auto_${System.currentTimeMillis()}", "web_search", args)

        return try {
            val result = toolManager.executeTool(toolCall)
            val text = result.result.trim()
            if (text.isBlank() ||
                text.startsWith("Error", ignoreCase = true) ||
                text.startsWith("Web search failed", ignoreCase = true) ||
                text.startsWith("No results found", ignoreCase = true)
            ) {
                Log.w(TAG, "Search returned no useful results: $text")
                null
            } else {
                Log.d(TAG, "Search returned ${text.length} chars")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}")
            null
        }
    }

    // ── InferenceDoneSignal ────────────────────────────────────────────────

    class InferenceDoneSignal {
        private val latch = CountDownLatch(1)

        @Volatile
        var error: String? = null
            private set

        fun signalDone() { latch.countDown() }

        fun signalError(msg: String) {
            error = msg
            latch.countDown()
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean {
            return latch.await(timeout, unit)
        }
    }
}
