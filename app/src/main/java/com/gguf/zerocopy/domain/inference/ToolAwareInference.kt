package com.gguf.zerocopy.domain.inference

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tool-aware inference loop.
 *
 * For models that support tool calling (hasToolCallingCapability = true),
 * this injects tool definitions into the system prompt before inference,
 * then after generation parses the output via ToolManager.parseToolCall().
 * If a tool call is detected, it executes the tool and re-runs inference
 * with the result appended as a tool-response turn (max 3 rounds).
 *
 * For models that do NOT support tool calling, inference runs normally
 * with no keyword sniffing or search injection — the model simply answers
 * from its training data.
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"
    private const val MAX_TOOL_ROUNDS = 3

    /**
     * Execute inference with model-directed tool calling.
     *
     * 1. If the model supports tool calling, inject tool definitions into
     *    the system prompt using <tools>...</tools> convention.
     * 2. Run inference normally (no pre-decision based on keywords).
     * 3. After generation, parse output for tool calls.
     * 4. If tool call found → execute → append result → loop (max 3).
     * 5. If no tool call → return generated text as-is.
     *
     * @param supportsToolCalling Whether the loaded model can emit tool-call syntax.
     *        When false, inference runs as plain chat with no tool injection.
     */
    fun execute(
        userPrompt: String,
        originalSystemPrompt: String,
        toolManager: ToolManager,
        setSystemPrompt: (String) -> Unit,
        runInference: (prompt: String, tokenSink: TokenCallback, doneSignal: InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean = { false },
        searchQuery: String? = null,
        supportsToolCalling: Boolean = false
    ) {
        if (!supportsToolCalling || toolManager.getToolCount() == 0) {
            // Plain chat — no tool injection, no keyword sniffing
            runSingleRound(userPrompt, runInference, callback, isAborted)
            return
        }

        // ── Inject tool definitions into the system prompt ──
        val toolDefs = toolManager.getToolDefinitionsJson()
        val augmentedSystemPrompt = if (originalSystemPrompt.isNotBlank()) {
            "$originalSystemPrompt\n\nYou have access to the following tools:\n<tools>\n$toolDefs\n</tools>\n\n" +
            "To use a tool, respond with:\n<tool_call>\n{\"name\": \"tool_name\", \"arguments\": {...}}\n</tool_call>\n" +
            "After you receive the tool result, provide your final answer.\n" +
            "If you don't need to use any tools, answer normally without the <tool_call> tags."
        } else {
            "You have access to the following tools:\n<tools>\n$toolDefs\n</tools>\n\n" +
            "To use a tool, respond with:\n<tool_call>\n{\"name\": \"tool_name\", \"arguments\": {...}}\n</tool_call>\n" +
            "After you receive the tool result, provide your final answer.\n" +
            "If you don't need to use any tools, answer normally without the <tool_call> tags."
        }
        setSystemPrompt(augmentedSystemPrompt)

        // ── Tool-calling loop ──
        var currentPrompt = userPrompt
        var totalRounds = 0
        var finalText = ""

        while (totalRounds < MAX_TOOL_ROUNDS) {
            if (isAborted()) {
                callback.onDone()
                return
            }

            val roundOutput = StringBuilder()
            val roundDone = InferenceDoneSignal()

            val roundSink = object : TokenCallback {
                override fun onToken(token: String) {
                    if (isAborted()) { roundDone.signalDone(); return }
                    roundOutput.append(token)
                    // Forward tokens to the outer callback only on the final round
                    if (totalRounds == 0) callback.onToken(token)
                }
                override fun onDone() {}
                override fun onError(error: String) { roundDone.signalError(error) }
                override fun onKvUsage(percent: Int) { callback.onKvUsage(percent) }
                override fun onTokensGenerated(count: Int) { callback.onTokensGenerated(count) }
                override fun onToolCall(toolName: String, toolArgs: String) {}
            }

            runInference(currentPrompt, roundSink, roundDone)

            val timedOut = !roundDone.await(5, TimeUnit.MINUTES)
            val aborted = isAborted()

            if (timedOut) {
                if (!aborted) callback.onError("Inference timed out")
                else callback.onDone()
                return
            }
            if (roundDone.error != null) {
                if (!aborted) callback.onError(roundDone.error!!)
                else callback.onDone()
                return
            }

            val output = roundOutput.toString()
            finalText = output

            // ── Try to parse a tool call from the model output ──
            val toolCall = toolManager.parseToolCall(output)

            if (toolCall == null) {
                // No tool call → model answer is the final output
                break
            }

            // ── Execute the tool ──
            Log.d(TAG, "Tool call: ${toolCall.name}(${toolCall.arguments})")
            val toolResult = toolManager.executeTool(toolCall)

            // ── Append tool result as a user/tool turn for the next round ──
            val toolResultText = if (toolResult.result.length > 2000) {
                toolResult.result.take(2000) + "\n[... truncated]"
            } else toolResult.result

            // Use a simple format: the model sees the tool result as context
            currentPrompt = "$output\n\nTool result (${toolCall.name}):\n$toolResultText\n\n" +
                "Please provide your final answer based on this result."
            totalRounds++
        }

        // ── Forward final output ──
        // If there was a tool-calling loop, the tokens were NOT forwarded in
        // earlier rounds.  Now push the complete final text.
        if (totalRounds > 0) {
            callback.onToken(finalText)
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
        if (isAborted()) {
            callback.onDone()
            return
        }

        val roundDone = InferenceDoneSignal()

        val tokenSink = object : TokenCallback {
            override fun onToken(token: String) {
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
        // Successful completion — signal the outer callback
        callback.onDone()
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
