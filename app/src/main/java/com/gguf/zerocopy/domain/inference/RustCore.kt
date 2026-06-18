package com.gguf.zerocopy.domain.inference

import android.util.Log
import org.json.JSONObject

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
    val gpuOffload: Boolean = true
  )

  data class MemoryAdvice(
    val underPressure: Boolean = false,
    val pressurePercent: Double = 0.0,
    val availableMb: Long = 0,
    val recommendKvQuant: String = "q8_0",
    val recommendOffload: Boolean = false,
    val shouldReduceContext: Boolean = false
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

  fun optimizeThreads(modelSizeMB: Int, gpuLayers: Int): ThreadConfig {
    if (!available) return ThreadConfig()
    return try {
      val json = nativeOptimizeThreadConfig(modelSizeMB, gpuLayers)
      val j = JSONObject(json)
      ThreadConfig(
        promptThreads = j.optInt("prompt_threads", 4),
        decodeThreads = j.optInt("decode_threads", 2),
        useBigCores = j.optBoolean("use_big_cores", true),
        gpuOffload = j.optBoolean("gpu_offload", true)
      )
    } catch (e: Exception) {
      Log.w(TAG, "Thread optimization failed: ${e.message}")
      ThreadConfig()
    }
  }

  fun getMemoryAdvice(): MemoryAdvice {
    if (!available) return MemoryAdvice()
    return try {
      val json = nativeGetMemoryAdvice()
      val j = JSONObject(json)
      MemoryAdvice(
        underPressure = j.optBoolean("under_pressure", false),
        pressurePercent = j.optDouble("pressure_percent", 0.0),
        availableMb = j.optLong("available_mb", 0),
        recommendKvQuant = j.optString("recommend_kv_quant", "q8_0"),
        recommendOffload = j.optBoolean("recommend_offload", false),
        shouldReduceContext = j.optBoolean("should_reduce_context", false)
      )
    } catch (e: Exception) {
      MemoryAdvice()
    }
  }

  fun shouldReduceContext(): Boolean {
    if (!available) return false
    return try {
      nativeShouldReduceContext()
    } catch (e: Exception) {
      false
    }
  }

  private external fun nativeInit(totalRamMB: Int, cpuCores: Int)
  private external fun nativeOptimizeThreadConfig(modelSizeMB: Int, gpuLayers: Int): String
  private external fun nativeGetMemoryAdvice(): String
  private external fun nativeShouldReduceContext(): Boolean
}
