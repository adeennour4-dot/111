package com.gguf.zerocopy.domain.inference

import android.util.Log

object NativeBridge {
  private const val TAG = "NativeBridge"
  val nativeLibLoaded: Boolean

  init {
    var loaded = false
    try {
      System.loadLibrary("ipc-bridge")
      loaded = true
      android.util.Log.i(TAG, "Native library loaded successfully")
    } catch (e: UnsatisfiedLinkError) {
      android.util.Log.e(TAG, "Failed to load native library: ${e.message}")
    }
    nativeLibLoaded = loaded
  }

  interface TokenCallback {
    fun onToken(token: String)

    fun onDone()

    fun onError(error: String)

    fun onKvCacheUsage(percent: Int)

    fun onTokensGenerated(count: Int)

    fun onDiagnostic(info: String) {}
  }

  external fun loadGgufModelNative(filePath: String): Boolean

  external fun loadMmprojNative(mmprojPath: String): Boolean

  external fun executeWithCallbackNative(prompt: String, callback: TokenCallback)

  external fun executeWithImageNative(prompt: String, imagePath: String, callback: TokenCallback)

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

  external fun restoreHistoryNative(messagesJson: String)
}
