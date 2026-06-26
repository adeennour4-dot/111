package com.gguf.zerocopy.domain.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiteRtEngine : InferenceEngine {
  override val engineType = EngineType.LITER_T
  override val engineName = "LiteRT-LM"
  override var isModelLoaded = false
    private set
  override var modelInfo: ModelInfo? = null
    private set
  override val loadedModelPath: String? get() = currentModelPath.ifEmpty { null }
  override var config = InferenceConfig()
  override var repeatPenalty = RepeatPenaltyConfig()
  override var systemPrompt = ""
  override var mmprojPath: String = ""

  private var engine: Engine? = null
  private var conversation: Conversation? = null
  private var currentModelPath = ""
  private var preferredBackend: Backend = Backend.CPU()
  private val inferenceDone = AtomicBoolean(true)
  private val tokensGenerated = AtomicInteger(0)
  private val partialStream = StringBuilder()
  private val fullResponse = StringBuilder()

  override suspend fun loadModel(path: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      currentModelPath = path
      val extConfig = EngineConfig(modelPath = path, backend = preferredBackend)
      engine = Engine(extConfig)
      engine!!.initialize()
      isModelLoaded = true
      modelInfo = ModelInfo(
        arch = "litert-lm",
        engineType = EngineType.LITER_T,
        modelPath = currentModelPath
      )
      Result.success(Unit)
    } catch (e: Exception) {
      tryFallbackLoad(path, e)
    }
  }

  private fun tryFallbackLoad(path: String, originalError: Exception): Result<Unit> = try {
    val legacyConfig = EngineConfig(modelPath = path, backend = Backend.CPU())
    engine = Engine(legacyConfig)
    engine!!.initialize()
    isModelLoaded = true
    modelInfo = ModelInfo(engineType = EngineType.LITER_T, modelPath = currentModelPath)
    Result.success(Unit)
  } catch (e: Exception) {
    Result.failure(Exception("LiteRT-LM load failed (primary: ${originalError.message}, fallback: ${e.message})"))
  }

  override fun unloadModel() {
    try { conversation?.close() } catch (_: Exception) {}
    try { engine?.close() } catch (_: Exception) {}
    engine = null
    conversation = null
    isModelLoaded = false
    modelInfo = null
    currentModelPath = ""
  }

  override suspend fun executeInferenceWithImage(prompt: String, imagePath: String, callback: TokenCallback) {
    executeInference("[Image: $imagePath]\n$prompt", callback)
  }

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    withContext(Dispatchers.IO) {
      synchronized(partialStream) {
        partialStream.clear()
        fullResponse.clear()
      }
      inferenceDone.set(false)
      tokensGenerated.set(0)

      // CountDownLatch ensures we suspend until the async callback fires onDone/onError.
      // Without this, executeInference() returned immediately and the ChatScreen
      // marked inference complete before any tokens were generated.
      val latch = CountDownLatch(1)

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
            synchronized(partialStream) {
              partialStream.append(text)
              fullResponse.append(text)
            }
            val count = tokensGenerated.incrementAndGet()
            callback.onToken(text)
            callback.onTokensGenerated(count)
          }

          override fun onDone() {
            inferenceDone.set(true)
            callback.onDone()
            latch.countDown()
          }

          override fun onError(t: Throwable) {
            inferenceDone.set(true)
            callback.onError(t.message ?: "LiteRT-LM error")
            latch.countDown()
          }
        }

        conversation?.sendMessageAsync(prompt, msgCallback, emptyMap())

        // Wait up to 5 minutes for inference to complete
        if (!latch.await(300, TimeUnit.SECONDS)) {
          inferenceDone.set(true)
          callback.onError("LiteRT-LM inference timed out")
        }
      } catch (e: Exception) {
        inferenceDone.set(true)
        callback.onError(e.message ?: "LiteRT-LM error")
        latch.countDown()
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
    synchronized(partialStream) {
      partialStream.clear()
      fullResponse.clear()
    }
    inferenceDone.set(true)
    tokensGenerated.set(0)
  }

  override suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult {
    if (!isModelLoaded) return BenchmarkResult(engine = engineName)
    return withContext(Dispatchers.IO) {
      try {
        val testPrompt = "The quick brown fox jumps over the lazy dog. ".repeat(maxOf(1, ppTokens / 10))
        val ppStart = System.currentTimeMillis()
        var firstTokenMs = -1L
        var tokenCount = 0
        val latch = CountDownLatch(1)
        val benchConv = engine?.createConversation()
        benchConv?.sendMessageAsync(testPrompt, object : MessageCallback {
          override fun onMessage(message: Message) {
            if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis()
            tokenCount++
            if (tokenCount >= tgTokens) benchConv.cancelProcess()
          }
          override fun onDone() { latch.countDown() }
          override fun onError(t: Throwable) { latch.countDown() }
        }, emptyMap())
        latch.await(120, TimeUnit.SECONDS)
        benchConv?.close()
        val totalMs = (System.currentTimeMillis() - ppStart).toFloat()
        val prefillMs = if (firstTokenMs > 0) (firstTokenMs - ppStart).toFloat() else totalMs
        val decodeMs = if (firstTokenMs > 0) (totalMs - prefillMs) else 0f
        val decodeTps = if (decodeMs > 0 && tokenCount > 1) (tokenCount - 1) / (decodeMs / 1000f) else 0f
        BenchmarkResult(
          engine = engineName,
          prefillMs = prefillMs,
          decodeMs = decodeMs,
          prefillTps = if (prefillMs > 0) ppTokens / (prefillMs / 1000f) else 0f,
          decodeTps = decodeTps,
          prefillTokens = ppTokens,
          decodeTokens = tokenCount
        )
      } catch (_: Exception) {
        BenchmarkResult(engine = engineName)
      }
    }
  }

  override fun supportsFormat(path: String): Boolean =
    path.endsWith(".tflite", true) || path.endsWith(".litertlm", true)

  override fun readPartialStream(): String = synchronized(partialStream) {
    partialStream.toString().also { partialStream.clear() }
  }

  override fun readTokenStream(): String = synchronized(partialStream) { fullResponse.toString() }

  override fun isInferenceDone(): Boolean = inferenceDone.get()

  override fun getTokensGenerated(): Int = tokensGenerated.get()

  override fun getKvUsage(): Int = 0
}
