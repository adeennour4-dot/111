package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared tool-calling agentic loop used by ALL inference engines.
 *
 * Each engine provides:
 *  - [runInference] – runs a single inference round with the given prompt.
 *    The lambda MUST call `onDone()` or `onError()` on the provided callback
 *    when inference completes, and should return only after the callback has
 *    fired (i.e., it is responsible for its own synchronization).
 *  - [setSystemPrompt] – sets the system prompt on the native/engine side.
 *
 * This utility handles the multi-round loop: inject tool definitions, run
 * inference, parse tool calls from the response, execute tools, and re-run
 * with results (up to [MAX_ROUNDS] times).
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"
    private const val MAX_ROUNDS = 4

    /**
     * Execute the tool-aware agentic loop.
     *
     * The [runInference] lambda receives:
     *  - `prompt` — the text to feed to the model
     *  - `tokenSink` — a [TokenCallback] the engine MUST forward each token
     *    to as they are generated (this buffers them inside the loop for
     *    tool call parsing).
     *  - `doneSignal` — an [InferenceDoneSignal] the engine MUST call
     *    `signalDone()` or `signalError()` on exactly once when inference
     *    completes. The lambda MUST NOT return before calling one of those.
     *
     * The [tokenSink] does NOT forward tokens to the outer [callback]; it
     * buffers them internally for tool call parsing. The final response is
     * delivered to [callback] after the loop ends.
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
                val roundDone = InferenceDoneSignal()

                val tokenSink = object : TokenCallback {
                    override fun onToken(token: String) {
                        responseBuf.append(token)
                    }

                    override fun onDone() {
                        // completion is signalled via roundDone, not here
                    }

                    override fun onError(error: String) {
                        // errors are signalled via roundDone, not here
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
                    runInference(fullPrompt, tokenSink, roundDone)
                } catch (e: Exception) {
                    Log.e(TAG, "Inference round $round failed: ${e.message}")
                    callback.onError("Inference error: ${e.message}")
                    callback.onDone()
                    return
                }

                // Wait for the engine to signal completion via the doneSignal.
                // The engine's runInference lambda MUST call signalDone() or
                // signalError() on the doneSignal and MUST NOT return before
                // doing so.  If the engine fails to signal within 5 minutes,
                // we time out.
                val timedOut = !roundDone.await(5, TimeUnit.MINUTES)
                if (timedOut) {
                    Log.e(TAG, "Inference round $round timed out")
                    callback.onError("Inference timed out")
                    callback.onDone()
                    return
                }

                if (roundDone.error != null) {
                    callback.onError(roundDone.error!!)
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
        } finally {
            setSystemPrompt(originalSystemPrompt)
        }

        callback.onDone()
    }

    /**
     * A lightweight one-shot signal that an inference round has completed.
     * The engine's [runInference] lambda must call [signalDone] or [signalError]
     * exactly once before returning.  [await] blocks until one of those is called
     * (or the timeout expires).
     */
    class InferenceDoneSignal {
        private val latch = CountDownLatch(1)
        @Volatile var error: String? = null
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
