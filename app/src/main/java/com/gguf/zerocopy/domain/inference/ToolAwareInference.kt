package com.gguf.zerocopy.domain.inference

import android.util.Log

/**
 * Shared tool-calling agentic loop used by ALL inference engines.
 *
 * Each engine provides:
 *  - [runInference] – a suspend function that runs a single inference round
 *    with the given prompt and streams results via the provided TokenCallback.
 *  - [setSystemPrompt] – sets the system prompt on the native/engine side.
 *
 * This utility handles the multi-round loop: inject tool definitions, run
 * inference, parse tool calls from the response, execute tools, and re-run
 * with results (up to [maxRounds] times).
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"
    private const val MAX_ROUNDS = 4

    /**
     * Execute the tool-aware agentic loop.
     *
     * @param userPrompt           The user's message text.
     * @param originalSystemPrompt The engine's current system prompt (will be
     *                             restored after the loop).
     * @param toolManager          The ToolManager with registered tools.
     * @param setSystemPrompt      Sets the system prompt on the engine.
     * @param runInference         Runs a single inference round with the given
     *                             prompt and streams tokens to the callback.
     * @param callback             The outer TokenCallback from ChatScreen.
     * @param isAborted            A lambda that returns true if inference was
     *                             aborted (e.g., by user pressing stop).
     */
    suspend fun execute(
        userPrompt: String,
        originalSystemPrompt: String,
        toolManager: ToolManager,
        setSystemPrompt: (String) -> Unit,
        runInference: suspend (String, TokenCallback) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean = { false }
    ) {
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

                // ── Run one inference round, buffer all tokens ────────────
                val responseBuf = StringBuilder()
                var turnError: String? = null

                val bufferCb = object : TokenCallback {
                    override fun onToken(token: String) {
                        responseBuf.append(token)
                    }

                    override fun onDone() {
                        // Nothing — we handle completion below
                    }

                    override fun onError(error: String) {
                        turnError = error
                    }

                    override fun onKvUsage(percent: Int) {
                        callback.onKvUsage(percent)
                    }

                    override fun onTokensGenerated(count: Int) {
                        callback.onTokensGenerated(count)
                    }

                    override fun onToolCall(toolName: String, toolArgs: String) {
                        callback.onToolCall(toolName, toolArgs)
                    }
                }

                try {
                    runInference(fullPrompt, bufferCb)
                } catch (e: Exception) {
                    Log.e(TAG, "Inference round $round failed: ${e.message}")
                    callback.onError("Inference error: ${e.message}")
                    callback.onDone()
                    return
                }

                if (turnError != null) {
                    callback.onError(turnError!!)
                    callback.onDone()
                    return
                }

                if (isAborted()) break

                val response = responseBuf.toString()
                val toolCall = toolManager.parseToolCall(response)

                if (toolCall == null) {
                    // ── No tool call — stream the final response ──────────
                    callback.onToken(response)
                    break
                }

                // ── Tool call detected — execute and continue ─────────────
                anyToolCall = true

                // Extract a human-readable query description
                val query = toolCall.arguments.optString(
                    "query",
                    toolCall.arguments.keys().asSequence().firstOrNull()
                        ?.let { toolCall.arguments.optString(it) }
                        ?: toolCall.name
                )
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

            if (!anyToolCall) {
                // If we never made a tool call, no need to restore system prompt
                // in a separate try/finally — we already streamed the response.
            }
        } finally {
            setSystemPrompt(originalSystemPrompt)
        }

        callback.onDone()
    }
}
