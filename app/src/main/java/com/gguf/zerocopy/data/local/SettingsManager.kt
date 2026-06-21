package com.gguf.zerocopy.data.local

import android.content.Context
import android.content.SharedPreferences
import com.gguf.zerocopy.domain.device.DeviceInfo
import com.gguf.zerocopy.domain.inference.EngineConfig
import kotlinx.serialization.Serializable

@Serializable
data class InferenceConfig(
  val nCtx: Int = 4096,
  val nBatch: Int = 512,
  val maxNewTokens: Int = 2048,
  val temperature: Float = 0.5f,
  val topP: Float = 0.9f,
  val minP: Float = 0.1f,
  val nGpuLayers: Int = 0,
  val nThreads: Int = 4,
  val seed: Int = -1,
  val lowRamMode: Boolean = true,
  val flashAttention: Boolean = true,
  val presencePenalty: Float = 0.1f,
  val mmprojPath: String = ""
)

@Serializable
data class RepeatPenaltyConfig(
  val repeatPenalty: Float = 1.1f,
  val freqPenalty: Float = 0.0f,
  val presPenalty: Float = 0.0f
)

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
    get() = prefs?.getInt("max_tokens", 2048) ?: 2048
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

  var lowRamMode: Boolean
    get() = prefs?.getBoolean("low_ram", true) ?: true
    set(v) {
      prefs?.edit()?.putBoolean("low_ram", v)?.apply()
    }

  var mmprojPath: String
    get() = prefs?.getString("mmproj_path", "") ?: ""
    set(v) {
      prefs?.edit()?.putString("mmproj_path", v)?.apply()
    }

  var currentSessionId: String
    get() = prefs?.getString("current_session_id", "") ?: ""
    set(v) {
      prefs?.edit()?.putString("current_session_id", v)?.apply()
    }

  var reasoningEnabled: Boolean
    get() = prefs?.getBoolean("reasoning_enabled", false) ?: false
    set(v) {
      prefs?.edit()?.putBoolean("reasoning_enabled", v)?.apply()
    }

  var serverEnabled: Boolean
    get() = prefs?.getBoolean("server_enabled", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("server_enabled", v)?.apply() }

  var serverIp: String
    get() = prefs?.getString("server_ip", "127.0.0.1") ?: "127.0.0.1"
    set(v) { prefs?.edit()?.putString("server_ip", v)?.apply() }

  var serverPort: Int
    get() = prefs?.getInt("server_port", 8080) ?: 8080
    set(v) { prefs?.edit()?.putInt("server_port", v)?.apply() }

  var serverAuthEnabled: Boolean
    get() = prefs?.getBoolean("server_auth", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("server_auth", v)?.apply() }

  var serverAuthToken: String
    get() = prefs?.getString("server_auth_token", "") ?: ""
    set(v) { prefs?.edit()?.putString("server_auth_token", v)?.apply() }

  var serverWifiOnly: Boolean
    get() = prefs?.getBoolean("server_wifi_only", true) ?: true
    set(v) { prefs?.edit()?.putBoolean("server_wifi_only", v)?.apply() }

  var serverModelPath: String
    get() = prefs?.getString("server_model_path", "") ?: ""
    set(v) { prefs?.edit()?.putString("server_model_path", v)?.apply() }

  var serverModelName: String
    get() = prefs?.getString("server_model_name", "") ?: ""
    set(v) { prefs?.edit()?.putString("server_model_name", v)?.apply() }

  var lastModelPath: String
    get() = prefs?.getString("last_model_path", "") ?: ""
    set(v) { prefs?.edit()?.putString("last_model_path", v)?.apply() }

  var lastModelName: String
    get() = prefs?.getString("last_model_name", "") ?: ""
    set(v) { prefs?.edit()?.putString("last_model_name", v)?.apply() }

  var welcomeDone: Boolean
    get() = prefs?.getBoolean("welcome_done", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("welcome_done", v)?.apply() }

  var isDarkTheme: Boolean
    get() = prefs?.getBoolean("dark_theme", true) ?: true
    set(v) {
      prefs?.edit()?.putBoolean("dark_theme", v)?.apply()
      com.gguf.zerocopy.ui.theme.ThemeState.isDark = v
    }

  // RAG
  var ragEnabled: Boolean
    get() = prefs?.getBoolean("rag_enabled", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("rag_enabled", v)?.apply() }

  var ragTopK: Int
    get() = prefs?.getInt("rag_top_k", 3) ?: 3
    set(v) { prefs?.edit()?.putInt("rag_top_k", v)?.apply() }

  var ragMinScore: Float
    get() = prefs?.getFloat("rag_min_score", 0.3f) ?: 0.3f
    set(v) { prefs?.edit()?.putFloat("rag_min_score", v)?.apply() }

  var ragChunkSize: Int
    get() = prefs?.getInt("rag_chunk_size", 512) ?: 512
    set(v) { prefs?.edit()?.putInt("rag_chunk_size", v)?.apply() }

  var ragOverlap: Int
    get() = prefs?.getInt("rag_overlap", 64) ?: 64
    set(v) { prefs?.edit()?.putInt("rag_overlap", v)?.apply() }

  var embeddingModelPath: String
    get() = prefs?.getString("embedding_model_path", "") ?: ""
    set(v) { prefs?.edit()?.putString("embedding_model_path", v)?.apply() }

  var embeddingModelName: String
    get() = prefs?.getString("embedding_model_name", "") ?: ""
    set(v) { prefs?.edit()?.putString("embedding_model_name", v)?.apply() }

  // StreamingLLM
  var kvSinkTokens: Int
    get() = prefs?.getInt("kv_sink_tokens", 4) ?: 4
    set(v) { prefs?.edit()?.putInt("kv_sink_tokens", v)?.apply() }

  var kvRecentTokens: Int
    get() = prefs?.getInt("kv_recent_tokens", 512) ?: 512
    set(v) { prefs?.edit()?.putInt("kv_recent_tokens", v)?.apply() }

  var kvEvictThreshold: Float
    get() = prefs?.getFloat("kv_evict_threshold", 0.85f) ?: 0.85f
    set(v) { prefs?.edit()?.putFloat("kv_evict_threshold", v)?.apply() }

  fun toConfig() = InferenceConfig(
    nCtx = nCtx,
    nBatch = nBatch.coerceIn(512, 8192),
    maxNewTokens = maxTokens.coerceAtMost((nCtx - 512).coerceAtLeast(64)),
    temperature = temperature.coerceIn(0f, 2f),
    topP = topP.coerceIn(0f, 1f),
    minP = minP.coerceIn(0f, 1f),
    nGpuLayers = gpuLayers.coerceIn(0, 999),
    nThreads = threads.coerceIn(0, 16),
    lowRamMode = lowRamMode,
    mmprojPath = mmprojPath
  )

  fun toRepeatPenalty() = RepeatPenaltyConfig(
    repeatPenalty = repeatPenalty,
    freqPenalty = freqPenalty,
    presPenalty = presPenalty
  )

  fun toEngineConfig() = EngineConfig(
    contextSize = nCtx,
    threads = threads.coerceIn(0, 16),
    batchSize = nBatch,
    flashAttn = true,
    useMmap = true,
    useMlock = false,
    cacheTypeK = "q8_0",
    cacheTypeV = "q8_0",
    opOffload = false,
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
    mmprojPath = config.mmprojPath
    repeatPenalty = rp.repeatPenalty
    freqPenalty = rp.freqPenalty
    presPenalty = rp.presPenalty
  }
}
