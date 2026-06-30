package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared search-context injection helper used by ALL inference engines.
 *
 * When the search toggle is ON, EVERY user input is treated as a search query:
 * 1. Run the web search via [ToolManager] with the user's input
 * 2. If results come back, inject them into the system prompt
 * 3. If search fails, inject a "search failed" note
 * 4. Run inference — the model uses the injected context
 *
 * No tool instruction is added. Works with EVERY model.
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"

    /**
     * Execute search-context injection for every user message.
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
        // Always run search with the user's input as the query
        val searchResults = runSearch(userPrompt, toolManager)

        val augmentedSysPrompt = if (searchResults != null) {
            // Search succeeded — inject results
            val instruction = "Here is up-to-date web search information:\n$searchResults\n\nUse the above information to answer the user's question directly and completely. Do not just acknowledge it — give the actual answer."
            if (originalSystemPrompt.isNotEmpty()) "$originalSystemPrompt\n\n$instruction" else instruction
        } else {
            // Search failed — tell the model to answer from its own knowledge
            val failureNote = "A web search was attempted for the user's question but returned no usable results. Do NOT say only \"okay\" or stop — answer the user's question as best you can from your own knowledge, and briefly mention that live search data wasn't available."
            if (originalSystemPrompt.isNotEmpty()) "$originalSystemPrompt\n\n$failureNote" else failureNote
        }

        setSystemPrompt(augmentedSysPrompt)
        try {
            runSingleRound(userPrompt, runInference, callback, isAborted)
        } finally {
            setSystemPrompt(originalSystemPrompt)
        }

        callback.onDone()
    }

    // ── Single round runner ─────────────────────────────────────────────────

    private fun runSingleRound(
        prompt: String,
        runInference: (String, TokenCallback, InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean
    ) {
        val roundDone = InferenceDoneSignal()

        val tokenSink = object : TokenCallback {
            override fun onToken(token: String) {
                callback.onToken(token)
            }
            override fun onDone() { /* completion handled via roundDone */ }
            override fun onError(error: String) { /* errors handled via roundDone */ }
            override fun onKvUsage(percent: Int) { callback.onKvUsage(percent) }
            override fun onTokensGenerated(count: Int) { callback.onTokensGenerated(count) }
            override fun onToolCall(toolName: String, toolArgs: String) {}
        }

        runInference(prompt, tokenSink, roundDone)

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
    }

    // ── Search execution ────────────────────────────────────────────────────

    /**
     * Run web search with the user's input as the query.
     * Returns search results as a string, or null if search failed.
     */
    private fun runSearch(userPrompt: String, toolManager: ToolManager): String? {
        val query = userPrompt.trim().take(200)
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
