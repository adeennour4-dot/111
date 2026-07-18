package com.gguf.zerocopy.domain.inference

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Orchestrates tool-augmented inference.
 *
 * Injects tool definitions into the system prompt so the model can decide
 * whether to call a tool, then parses the output for structured tool calls.
 * If a tool is called, it executes the tool, feeds the result back, and runs
 * another round for the model to produce the final answer.
 *
 * For models that don't explicitly support tool calling (based on
 * [supportsToolCalling]), tool definitions are still injected — many models
 * can follow the `<tool_call>` syntax without being fine-tuned for it — but
 * the multi-round tool loop is skipped and the raw output is forwarded.
 */
object ToolAwareInference {

    private const val TAG = "ToolAwareInference"
    private const val MAX_TOOL_ROUNDS = 5

    fun execute(
        userPrompt: String,
        originalSystemPrompt: String,
        toolManager: ToolManager,
        setSystemPrompt: (String) -> Unit,
        runInference: (String, TokenCallback, InferenceDoneSignal) -> Unit,
        callback: TokenCallback,
        isAborted: () -> Boolean = { false },
        searchQuery: String? = null
    ) {
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

        // ── Single round when no tools exist ──
        if (toolManager.getToolCount() == 0) {
            runSingleRound(userPrompt, runInference, callback, isAborted)
            return
        }

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
                    // Buffer all tokens during the tool-calling loop.
                    // Final output is forwarded as one chunk after the loop
                    // completes, so the UI never sees intermediate tool-call
                    // markup or partial results.
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

            // Use the original clean user query for web_search instead of
            // whatever the model extracted (which may include reasoning
            // prompts, RAG context, or other prefixes).
            val augmentedArgs = if (toolCall.name == "web_search" && searchQuery != null) {
                val args = JSONObject()
                args.put("query", searchQuery)
                toolCall.arguments.keys().forEachRemaining { k -> if (k != "query") args.put(k, toolCall.arguments.get(k)) }
                args
            } else toolCall.arguments

            val toolResult = if (augmentedArgs !== toolCall.arguments) {
                toolManager.executeTool(ToolCall(toolCall.id, toolCall.name, augmentedArgs))
            } else {
                toolManager.executeTool(toolCall)
            }

            // ── Append tool result as a user/tool turn for the next round ──
            val toolResultText = if (toolResult.result.length > 2000) {
                toolResult.result.take(2000) + "\n[... truncated]"
            } else toolResult.result

            // Use a simple format: the model sees the tool result as context
            // Include a step-by-step reasoning instruction so that when
            // both search and reasoning are enabled, the model reasons
            // about the tool result rather than just regurgitating it.
            currentPrompt = "$output\n\nTool result (${toolCall.name}):\n$toolResultText\n\n" +
                "Think step by step about this information and provide your final answer."
            totalRounds++
        }

        // ── Forward final output ──
        // When totalRounds == 0 the model answered directly (no tool call), so
        // the buffered tokens must be forwarded.  When totalRounds > 0 a tool
        // was called and only the final round's complete text is pushed.
        // In either case, never silently drop the model's output.
        if (finalText.isNotEmpty()) {
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
        callback.onDone()
    }
}

class InferenceDoneSignal {
    private val latch = CountDownLatch(1)
    @Volatile var error: String? = null; private set

    fun signalDone() { latch.countDown() }
    fun signalError(msg: String) { error = msg; latch.countDown() }
    fun await(timeout: Long, unit: TimeUnit): Boolean = latch.await(timeout, unit)
}
