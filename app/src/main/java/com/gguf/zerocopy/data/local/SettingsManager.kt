package com.gguf.zerocopy.data.local

import android.content.Context
import android.content.SharedPreferences
import com.gguf.zerocopy.domain.device.DeviceInfo
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.RepeatPenaltyConfig

object SettingsManager {
  private const val PREFS_NAME = "zerocopy_settings"

  private var prefs: SharedPreferences? = null

  fun init(context: Context) {
    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  var nCtx: Int
    get() = prefs?.getInt("n_ctx", 2048) ?: 2048
    set(v) {
      prefs?.edit()?.putInt("n_ctx", v)?.apply()
    }

  var maxTokens: Int
    get() = prefs?.getInt("max_tokens", 1024) ?: 1024
    set(v) {
      prefs?.edit()?.putInt("max_tokens", v)?.apply()
    }

  var nBatch: Int
    get() = prefs?.getInt("n_batch", 512) ?: 512
    set(v) {
      prefs?.edit()?.putInt("n_batch", v)?.apply()
    }

  var temperature: Float
    get() = prefs?.getFloat("temperature", 0.6f) ?: 0.6f
    set(v) {
      prefs?.edit()?.putFloat("temperature", v)?.apply()
    }

  var topP: Float
    get() = prefs?.getFloat("top_p", 0.9f) ?: 0.9f
    set(v) {
      prefs?.edit()?.putFloat("top_p", v)?.apply()
    }

  var minP: Float
    get() = prefs?.getFloat("min_p", 0.05f) ?: 0.05f
    set(v) {
      prefs?.edit()?.putFloat("min_p", v)?.apply()
    }

  var gpuLayers: Int
    get() = prefs?.getInt("gpu_layers", 0) ?: 0
    set(v) {
      prefs?.edit()?.putInt("gpu_layers", v)?.apply()
    }

  var threads: Int
    get() = prefs?.getInt("threads", 4) ?: 4
    set(v) {
      prefs?.edit()?.putInt("threads", v)?.apply()
    }

  var repeatPenalty: Float
    get() = prefs?.getFloat("repeat_penalty", 1.1f) ?: 1.1f
    set(v) {
      prefs?.edit()?.putFloat("repeat_penalty", v)?.apply()
    }

  var freqPenalty: Float
    get() = prefs?.getFloat("freq_penalty", 0.0f) ?: 0.0f
    set(v) {
      prefs?.edit()?.putFloat("freq_penalty", v)?.apply()
    }

  var presPenalty: Float
    get() = prefs?.getFloat("pres_penalty", 0.0f) ?: 0.0f
    set(v) {
      prefs?.edit()?.putFloat("pres_penalty", v)?.apply()
    }

  var systemPrompt: String
    get() =
      prefs?.getString(
        "system_prompt",
        "You are a helpful, concise assistant running on-device. Respond clearly and directly."
      )
        ?: "You are a helpful, concise assistant running on-device. Respond clearly and directly."
    set(v) {
      prefs?.edit()?.putString("system_prompt", v)?.apply()
    }

  var autoDetectDevice: Boolean
    get() = prefs?.getBoolean("auto_detect", true) ?: true
    set(v) {
      prefs?.edit()?.putBoolean("auto_detect", v)?.apply()
    }

  var lowRamMode: Boolean
    get() = prefs?.getBoolean("low_ram", true) ?: true
    set(v) {
      prefs?.edit()?.putBoolean("low_ram", v)?.apply()
    }

  var isDarkTheme: Boolean
    get() = prefs?.getBoolean("dark_theme", true) ?: true
    set(v) {
      prefs?.edit()?.putBoolean("dark_theme", v)?.apply()
      com.gguf.zerocopy.ui.theme.ThemeState.isDark = v
    }

  fun toConfig() = InferenceConfig(
    nCtx = nCtx,
    nBatch = nBatch.coerceIn(512, 8192),
    maxNewTokens = maxTokens.coerceAtMost((nCtx - 512).coerceAtLeast(64)),
    temperature = temperature.coerceIn(0f, 2f),
    topP = topP.coerceIn(0f, 1f),
    minP = minP.coerceIn(0f, 1f),
    nGpuLayers = gpuLayers.coerceIn(0, 999),
    nThreads = threads.coerceIn(0, 16),
    lowRamMode = lowRamMode
  )

  fun toRepeatPenalty() = RepeatPenaltyConfig(
    repeatPenalty = repeatPenalty,
    freqPenalty = freqPenalty,
    presPenalty = presPenalty
  )

  fun applyDeviceDefaults(info: DeviceInfo) {
    val suggestion = info.suggestConfig()
    nCtx = suggestion.nCtx
    maxTokens = suggestion.maxNewTokens
    nBatch = suggestion.nBatch
    gpuLayers = suggestion.nGpuLayers
    threads = suggestion.nThreads
  }

  fun save(config: InferenceConfig, rp: RepeatPenaltyConfig) {
    nCtx = config.nCtx
    nBatch = config.nBatch
    maxTokens = config.maxNewTokens
    temperature = config.temperature
    topP = config.topP
    minP = config.minP
    gpuLayers = config.nGpuLayers
    threads = config.nThreads
    lowRamMode = config.lowRamMode
    repeatPenalty = rp.repeatPenalty
    freqPenalty = rp.freqPenalty
    presPenalty = rp.presPenalty
  }
}
