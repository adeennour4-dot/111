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
    topK: Int,
    nGpuLayers: Int,
    nThreads: Int,
    seed: Int,
    lowRamMode: Boolean,
    flashAttention: Boolean
  )

  external fun setSystemPromptNative(prompt: String)

  external fun setChatTemplateNative(template: String)

  external fun setRepeatPenaltyNative(repeatPenalty: Float, freqPenalty: Float, presPenalty: Float)

  external fun resetContextNative()

  external fun getModelInfoNative(): String

  external fun benchmarkNative(ppTokens: Int, tgTokens: Int): String

  external fun exportChatHistoryNative(): String

  external fun getKvCacheUsageNative(): Int

  external fun restoreHistoryNative(messagesJson: String)
  external fun formatWithChatTemplateNative(messagesJson: String): String
  external fun unloadModelNative()

  external fun getNativeDiagnosticsNative(): String

  /** Path where the native crash backtrace handler writes its trace file. */
  external fun setCrashLogPathNative(path: String)

  /** Point the native crash handler at <filesDir>/native_crash.txt. Call once
   *  at startup so Diagnostics can surface native crashes (e.g. GGUF SIGSEGV). */
  fun initCrashCapture(context: android.content.Context) {
    if (!nativeLibLoaded) return
    try {
      val dir = context.filesDir
      val file = java.io.File(dir, "native_crash.txt")
      setCrashLogPathNative(file.absolutePath)
    } catch (_: Exception) {
      android.util.Log.w(TAG, "initCrashCapture failed")
    }
  }
}
