package com.gguf.zerocopy.domain.inference

import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LiteRtEngine : InferenceEngine {
  override val engineType = EngineType.LITER_T
  override val engineName = "LiteRT-LM"
  override var isModelLoaded = false
  private set
  override var modelInfo: ModelInfo? = null
  private set
  override var config = InferenceConfig()
  override var repeatPenalty = RepeatPenaltyConfig()
  override var systemPrompt = ""

  private var engine: Engine? = null
  private var conversation: Conversation? = null
  private var currentModelPath = ""
  private var preferredBackend: Backend = Backend.CPU(null)
  private val inferenceDone = AtomicBoolean(true)
  private val tokensGenerated = AtomicInteger(0)
  private val partialStream = StringBuilder()
  private val fullResponse = StringBuilder()

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      currentModelPath = path
      val extConfig = EngineConfig(path, preferredBackend, null, null, null, null, null)
      engine = Engine(extConfig)
      engine!!.initialize()
      isModelLoaded = true
      modelInfo = ModelInfo(
        arch = "litert-lm",
        engineType = EngineType.LITER_T
      )
      Result.success(Unit)
    } catch (e: Exception) {
      tryFallbackLoad(path)
    }
  }

  private fun tryFallbackLoad(path: String): Result<Unit> {
    return try {
      val legacyConfig = EngineConfig(path, Backend.CPU(null), null, null, null, null, null)
      engine = Engine(legacyConfig)
      engine!!.initialize()
      isModelLoaded = true
      modelInfo = ModelInfo(engineType = EngineType.LITER_T)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(Exception("LiteRT-LM load failed: ${e.message}"))
    }
  }

  override fun unloadModel() {
    try { conversation?.close() } catch (_: Exception) {}
    try { engine?.close() } catch (_: Exception) {}
    engine = null; conversation = null
    isModelLoaded = false; modelInfo = null; currentModelPath = ""
  }

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    withContext(Dispatchers.IO) {
      synchronized(partialStream) { partialStream.clear(); fullResponse.clear() }
      inferenceDone.set(false)
      tokensGenerated.set(0)

      try {
        if (conversation == null) {
          conversation = engine?.createConversation()
          if (systemPrompt.isNotEmpty()) {
            try {
              conversation?.sendMessage(Message.system(Contents.of(systemPrompt)), emptyMap())
            } catch (_: Exception) {}
          }
        }

        val msgCallback = object : MessageCallback {
          override fun onMessage(message: Message) {
            val text = message.toString()
            synchronized(partialStream) { partialStream.clear(); partialStream.append(text); fullResponse.append(text) }
            tokensGenerated.incrementAndGet()
          }
          override fun onDone() { inferenceDone.set(true) }
          override fun onError(t: Throwable) { inferenceDone.set(true) }
        }

        conversation?.sendMessageAsync(prompt, msgCallback, emptyMap())
      } catch (e: Exception) {
        inferenceDone.set(true)
      }
    }
  }

  override fun abortInference() {
    inferenceDone.set(true)
    try { conversation?.cancelProcess() } catch (_: Exception) {}
  }

  override fun resetContext() {
    try { conversation?.close() } catch (_: Exception) {}
    conversation = null
    synchronized(partialStream) { partialStream.clear(); fullResponse.clear() }
    inferenceDone.set(true)
    tokensGenerated.set(0)
  }

  override suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult {
    return BenchmarkResult(engine = engineName)
  }

  override fun supportsFormat(path: String): Boolean =
  path.endsWith(".tflite", true) || path.endsWith(".litertlm", true)

  fun readPartialStream(): String = synchronized(partialStream) { partialStream.toString().also { partialStream.clear() } }
  fun readTokenStream(): String = synchronized(partialStream) { fullResponse.toString() }
  fun isInferenceDone(): Boolean = inferenceDone.get()
  fun getTokensGenerated(): Int = tokensGenerated.get()
  fun getKvUsage(): Int = 0
}







