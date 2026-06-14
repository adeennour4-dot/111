package com.gguf.zerocopy.domain.inference

import android.util.Log

object RustCore {
  private const val TAG = "RustCore"
  private var available: Boolean = false

  init {
    try {
      System.loadLibrary("zerocopy_core")
      available = true
      Log.i(TAG, "Rust optimization layer loaded")
    } catch (e: UnsatisfiedLinkError) {
      Log.i(TAG, "Rust core not available (optional): ${e.message}")
    }
  }

  data class ThreadConfig(
    val promptThreads: Int = 4,
    val decodeThreads: Int = 2,
    val useBigCores: Boolean = true,
    val gpuOffload: Boolean = true,
  )

  fun isAvailable(): Boolean = available

  fun init(totalRamMB: Long, cpuCores: Int) {
    if (available) {
      try {
        nativeInit(totalRamMB.toInt(), cpuCores)
      } catch (e: Exception) {
        Log.w(TAG, "Rust init failed: ${e.message}")
      }
    }
  }

  fun shouldReduceContext(): Boolean {
    if (!available) return false
    return try {
      nativeShouldReduceContext()
    } catch (e: Exception) { false }
  }

  private external fun nativeInit(totalRamMB: Int, cpuCores: Int)
  private external fun nativeShouldReduceContext(): Boolean
}
