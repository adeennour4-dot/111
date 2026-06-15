package com.gguf.zerocopy.domain.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
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
      val extConfig = EngineConfig(modelPath = path, backend = preferredBackend)
      engine = Engine(extConfig)
      engine!!.initialize()
      isModelLoaded = true
      modelInfo =
        ModelInfo(
          arch = "litert-lm",
          engineType = EngineType.LITER_T
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
    modelInfo = ModelInfo(engineType = EngineType.LITER_T)
    Result.success(Unit)
  } catch (e: Exception) {
    Result.failure(
      Exception("LiteRT-LM load failed (primary: ${originalError.message}, fallback: ${e.message})")
    )
  }

  override fun unloadModel() {
    try {
      conversation?.close()
    } catch (_: Exception) {
    }
    try {
      engine?.close()
    } catch (_: Exception) {
    }
    engine = null
    conversation = null
    isModelLoaded = false
    modelInfo = null
    currentModelPath = ""
  }

  override suspend fun executeInference(prompt: String, callback: TokenCallback) {
    withContext(Dispatchers.IO) {
      synchronized(partialStream) {
        partialStream.clear()
        fullResponse.clear()
      }
      inferenceDone.set(false)
      tokensGenerated.set(0)

      try {
        if (conversation == null) {
          conversation = engine?.createConversation()
          if (systemPrompt.isNotEmpty()) {
            try {
              conversation?.sendMessage(Message.system(Contents.of(systemPrompt)), emptyMap())
            } catch (_: Exception) {
            }
          }
        }

        val msgCallback =
          object : MessageCallback {
            override fun onMessage(message: Message) {
              val text = message.toString()
              synchronized(partialStream) {
                partialStream.append(text)
                fullResponse.append(text)
              }
              tokensGenerated.incrementAndGet()
              callback.onToken(text)
            }

            override fun onDone() {
              inferenceDone.set(true)
              callback.onDone()
            }

            override fun onError(t: Throwable) {
              inferenceDone.set(true)
              callback.onError(t.message ?: "LiteRT-LM error")
            }
          }

        conversation?.sendMessageAsync(prompt, msgCallback, emptyMap())
      } catch (e: Exception) {
        inferenceDone.set(true)
        callback.onError(e.message ?: "LiteRT-LM error")
      }
    }
  }

  override fun abortInference() {
    inferenceDone.set(true)
    try {
      conversation?.cancelProcess()
    } catch (_: Exception) {
    }
  }

  override fun resetContext() {
    try {
      conversation?.close()
    } catch (_: Exception) {
    }
    conversation = null
    synchronized(partialStream) {
      partialStream.clear()
      fullResponse.clear()
    }
    inferenceDone.set(true)
    tokensGenerated.set(0)
  }

  override suspend fun benchmark(ppTokens: Int, tgTokens: Int): BenchmarkResult =
    BenchmarkResult(engine = engineName)

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
