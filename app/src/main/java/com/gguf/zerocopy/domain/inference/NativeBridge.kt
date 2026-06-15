package com.gguf.zerocopy.domain.inference

object NativeBridge {
  init {
    System.loadLibrary("ipc-bridge")
  }

  interface TokenCallback {
    fun onToken(token: String)

    fun onDone()

    fun onError(error: String)

    fun onKvCacheUsage(percent: Int)

    fun onTokensGenerated(count: Int)
  }

  external fun loadGgufModelNative(filePath: String): Boolean

  external fun executeWithCallbackNative(prompt: String, callback: TokenCallback)

  external fun abortInferenceNative()

  external fun setEngineConfigNative(
    nCtx: Int,
    nBatch: Int,
    maxNewTokens: Int,
    temperature: Float,
    topP: Float,
    minP: Float,
    nGpuLayers: Int,
    nThreads: Int,
    seed: Int,
    lowRamMode: Boolean,
    flashAttention: Boolean
  )

  external fun setSystemPromptNative(prompt: String)

  external fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)

  external fun resetContextNative()

  external fun getModelInfoNative(): String

  external fun benchmarkNative(ppTokens: Int, tgTokens: Int): String

  external fun exportChatHistoryNative(): String

  external fun getKvCacheUsageNative(): Int
}
