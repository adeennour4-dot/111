package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared search-context injection helper used by ALL inference engines.
 *
 * When search is ON, the system prompt is augmented to tell the model
 * it has web search available. The model decides when to search by
 * outputting a simple marker like [SEARCH: query]. If the model doesn't
 * search, it answers normally from its own knowledge.
 *
 * This works with small models because the instruction is minimal.
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"

    /** Simple search marker that even small models can output. */
    private val SEARCH_MARKER_REGEX = Regex("""\[SEARCH:\s*(.+?)]""", RegexOption.IGNORE_CASE)

    /**
     * Execute with search awareness. The system prompt tells the model
     * about search capability. After inference, if the model output
     * contains a search marker, we run the search and re-run inference
     * with the results injected.
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
        // Add search capability instruction to system prompt
        val searchInstruction = buildString {
            appendLine()
            appendLine("You have access to web search for real-time information.")
            appendLine("When the user asks about current prices, news, weather, facts that may have changed, or anything you're not sure about, you MUST search the web.")
            appendLine("To search, output EXACTLY this format and nothing else:")
            appendLine("[SEARCH: your search query here]")
            appendLine("After you see search results, use them to answer the user.")
            appendLine("If you already know the answer and it doesn't need current data, answer directly without searching.")
        }
        val augmentedSysPrompt = if (originalSystemPrompt.isNotEmpty()) {
            "$originalSystemPrompt\n$searchInstruction"
        } else {
            searchInstruction.trimStart()
        }

        setSystemPrompt(augmentedSysPrompt)
        try {
            // Round 1: Run inference — model may output a search request
            val responseBuf = StringBuilder()
            val roundDone = InferenceDoneSignal()

            val tokenSink = object : TokenCallback {
                override fun onToken(token: String) {
                    responseBuf.append(token)
                    callback.onToken(token)
                }
                override fun onDone() {}
                override fun onError(error: String) {}
                override fun onKvUsage(percent: Int) { callback.onKvUsage(percent) }
                override fun onTokensGenerated(count: Int) { callback.onTokensGenerated(count) }
                override fun onToolCall(toolName: String, toolArgs: String) {}
            }

            runInference(userPrompt, tokenSink, roundDone)

            val timedOut = !roundDone.await(5, TimeUnit.MINUTES)
            if (timedOut) { callback.onError("Inference timed out"); return }
            if (roundDone.error != null) { callback.onError(roundDone.error!!); return }
            if (isAborted()) return

            val response = responseBuf.toString()

            // Check if model wants to search
            val searchMatch = SEARCH_MARKER_REGEX.find(response)
            if (searchMatch != null) {
                // Model requested a search — run it
                val query = searchMatch.groupValues[1].trim()
                Log.d(TAG, "Model requested search: $query")

                val searchResults = runSearch(query, toolManager)
                if (searchResults != null) {
                    // Re-run inference with search results in system prompt
                    val resultsPrompt = "Here is up-to-date web search information:\n$searchResults\n\nUse the above information to answer the user's question directly and completely."
                    val newSysPrompt = if (originalSystemPrompt.isNotEmpty()) {
                        "$originalSystemPrompt\n\n$resultsPrompt"
                    } else {
                        resultsPrompt
                    }
                    setSystemPrompt(newSysPrompt)
                    try {
                        runSingleRound(userPrompt, runInference, callback, isAborted)
                    } finally {
                        setSystemPrompt(originalSystemPrompt)
                    }
                } else {
                    // Search failed — tell model to answer from knowledge
                    val failPrompt = "Web search failed or returned no results. Answer the user's question from your own knowledge and mention that live search data wasn't available."
                    val newSysPrompt = if (originalSystemPrompt.isNotEmpty()) {
                        "$originalSystemPrompt\n\n$failPrompt"
                    } else {
                        failPrompt
                    }
                    setSystemPrompt(newSysPrompt)
                    try {
                        runSingleRound(userPrompt, runInference, callback, isAborted)
                    } finally {
                        setSystemPrompt(originalSystemPrompt)
                    }
                }
            }
            // If no search marker, model already answered — nothing more to do
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
            override fun onToken(token: String) { callback.onToken(token) }
            override fun onDone() {}
            override fun onError(error: String) {}
            override fun onKvUsage(percent: Int) { callback.onKvUsage(percent) }
            override fun onTokensGenerated(count: Int) { callback.onTokensGenerated(count) }
            override fun onToolCall(toolName: String, toolArgs: String) {}
        }

        runInference(prompt, tokenSink, roundDone)

        val timedOut = !roundDone.await(5, TimeUnit.MINUTES)
        if (timedOut) { callback.onError("Inference timed out"); return }
        if (roundDone.error != null) { callback.onError(roundDone.error!!); return }
    }

    // ── Search execution ────────────────────────────────────────────────────

    private fun runSearch(query: String, toolManager: ToolManager): String? {
        val q = query.trim().take(200)
        if (q.length < 2) return null

        val args = org.json.JSONObject().apply {
            put("query", q)
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
