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
    applyCompatBuildDefaultsIfFirstRun(context)
  }

  /**
   * On the very first launch of the "compatibility" build flavor (old
   * Samsung devices like the Note 10 Lite / Exynos 9825 and similar
   * low-RAM ARM64 phones), seed conservative runtime defaults BEFORE the
   * user ever loads a model. This prevents an out-of-the-box OOM or crash
   * on first model load — the user can still raise these later in Settings
   * if their device handles it fine, but they won't crash before getting
   * the chance to.
   *
   * Uses BuildConfig.IS_COMPAT_BUILD / SAFE_DEFAULT_CTX / SAFE_DEFAULT_THREADS
   * which are generated per product-flavor in app/build.gradle.kts.
   * Reflection is used here (rather than a direct BuildConfig import) to
   * keep SettingsManager flavor-agnostic and avoid a hard compile
   * dependency that would break if BuildConfig fields are renamed.
   */
  private fun applyCompatBuildDefaultsIfFirstRun(context: Context) {
    val alreadySeeded = prefs?.getBoolean("compat_defaults_seeded", false) ?: true
    if (alreadySeeded) return
    try {
      val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
      val isCompat = buildConfigClass.getField("IS_COMPAT_BUILD").getBoolean(null)
      if (isCompat) {
        val safeCtx = buildConfigClass.getField("SAFE_DEFAULT_CTX").getInt(null)
        val safeThreads = buildConfigClass.getField("SAFE_DEFAULT_THREADS").getInt(null)
        nCtx = safeCtx
        maxTokens = (safeCtx / 2).coerceAtMost(1024)
        threads = safeThreads
        flashAttention = false   // compatibility build: never assume i8mm/dotprod codegen paths
        gpuLayers = 0
        lowRamMode = true
      }
    } catch (_: Exception) {
      // Standard flavor (or BuildConfig fields missing) — nothing to do,
      // fall through to the normal hardcoded defaults above.
    }
    prefs?.edit()?.putBoolean("compat_defaults_seeded", true)?.apply()
  }

  // ── Per-Model Token Configs (stored as JSON map) ───────────────────────
  // Each entry: path -> {"ctx": N, "maxNew": N}
  // Global nCtx/maxTokens kept as defaults for models without per-model config.
  var nCtx: Int
    get() = prefs?.getInt("n_ctx", 2048) ?: 2048
    set(v) { prefs?.edit()?.putInt("n_ctx", v)?.apply() }

  var maxTokens: Int
    get() = prefs?.getInt("max_tokens", 2048) ?: 2048
    set(v) { prefs?.edit()?.putInt("max_tokens", v)?.apply() }

  /** Per-model config overrides for ALL inference parameters.
   *  Null fields mean "use global default from Settings". */
  data class ModelTokenConfig(
    val ctx: Int,
    val maxNew: Int,
    val gpuLayers: Int,
    val temperature: Float? = null,
    val topP: Float? = null,
    val minP: Float? = null,
    val topK: Int? = null,
    val repeatPenalty: Float? = null,
    val freqPenalty: Float? = null,
    val presPenalty: Float? = null,
    val seed: Int? = null,
    val flashAttention: Boolean? = null,
    val lowRamMode: Boolean? = null,
    val threads: Int? = null,
    val nBatch: Int? = null
  )

  private var _modelConfigsCache: MutableMap<String, ModelTokenConfig>? = null

  private fun loadModelConfigs(): MutableMap<String, ModelTokenConfig> {
    if (_modelConfigsCache != null) return _modelConfigsCache!!
    val raw = prefs?.getString("model_token_configs", "{}") ?: "{}"
    val map = mutableMapOf<String, ModelTokenConfig>()
    try {
      val json = org.json.JSONObject(raw)
      json.keys().forEach { key ->
        val obj = json.getJSONObject(key)
        val ctx = obj.optInt("ctx", 1024)
        val maxNew = obj.optInt("maxNew", 1024)
        val gpuLayers = obj.optInt("gpuLayers", 0)
        map[key] = ModelTokenConfig(
          ctx = ctx, maxNew = maxNew, gpuLayers = gpuLayers,
          temperature = if (obj.has("temperature")) obj.getDouble("temperature").toFloat() else null,
          topP = if (obj.has("topP")) obj.getDouble("topP").toFloat() else null,
          minP = if (obj.has("minP")) obj.getDouble("minP").toFloat() else null,
          topK = if (obj.has("topK")) obj.optInt("topK") else null,
          repeatPenalty = if (obj.has("repeatPenalty")) obj.getDouble("repeatPenalty").toFloat() else null,
          freqPenalty = if (obj.has("freqPenalty")) obj.getDouble("freqPenalty").toFloat() else null,
          presPenalty = if (obj.has("presPenalty")) obj.getDouble("presPenalty").toFloat() else null,
          seed = if (obj.has("seed")) obj.optInt("seed") else null,
          flashAttention = if (obj.has("flashAttention")) obj.optBoolean("flashAttention") else null,
          lowRamMode = if (obj.has("lowRamMode")) obj.optBoolean("lowRamMode") else null,
          threads = if (obj.has("threads")) obj.optInt("threads") else null,
          nBatch = if (obj.has("nBatch")) obj.optInt("nBatch") else null
        )
      }
    } catch (_: Exception) {}
    _modelConfigsCache = map
    return map
  }

  private fun saveModelConfigs(map: Map<String, ModelTokenConfig>) {
    try {
      val json = org.json.JSONObject()
      map.forEach { (path, cfg) ->
        json.put(path, org.json.JSONObject().apply {
          put("ctx", cfg.ctx)
          put("maxNew", cfg.maxNew)
          put("gpuLayers", cfg.gpuLayers)
          cfg.temperature?.let { put("temperature", it.toDouble()) }
          cfg.topP?.let { put("topP", it.toDouble()) }
          cfg.minP?.let { put("minP", it.toDouble()) }
          cfg.topK?.let { put("topK", it) }
          cfg.repeatPenalty?.let { put("repeatPenalty", it.toDouble()) }
          cfg.freqPenalty?.let { put("freqPenalty", it.toDouble()) }
          cfg.presPenalty?.let { put("presPenalty", it.toDouble()) }
          cfg.seed?.let { put("seed", it) }
          cfg.flashAttention?.let { put("flashAttention", it) }
          cfg.lowRamMode?.let { put("lowRamMode", it) }
          cfg.threads?.let { put("threads", it) }
          cfg.nBatch?.let { put("nBatch", it) }
        })
      }
      prefs?.edit()?.putString("model_token_configs", json.toString())?.apply()
    } catch (_: Exception) {}
  }

  /** Get per-model token config, or null if not set (use global defaults). */
  fun getModelTokenConfig(path: String): ModelTokenConfig? {
    val map = loadModelConfigs()
    return map[path]
  }

  /** Set per-model token config with full overrides. */
  fun setModelTokenConfig(path: String, cfg: ModelTokenConfig) {
    val map = loadModelConfigs()
    map[path] = cfg
    saveModelConfigs(map)
  }

  /** Remove per-model config (revert to global defaults). */
  fun removeModelTokenConfig(path: String) {
    val map = loadModelConfigs()
    map.remove(path)
    saveModelConfigs(map)
  }

  // ── Invent-specific model configs (separate from main settings) ───────────

  private fun loadInventConfigs(): MutableMap<String, ModelTokenConfig> {
    val raw = prefs?.getString("invent_model_token_configs", "{}") ?: "{}"
    val map = mutableMapOf<String, ModelTokenConfig>()
    try {
      val json = org.json.JSONObject(raw)
      json.keys().forEach { key ->
        val obj = json.getJSONObject(key)
        map[key] = ModelTokenConfig(
          ctx = obj.optInt("ctx", 1024),
          maxNew = obj.optInt("maxNew", 1024),
          gpuLayers = obj.optInt("gpuLayers", 0),
          temperature = if (obj.has("temperature")) obj.getDouble("temperature").toFloat() else null,
          topP = if (obj.has("topP")) obj.getDouble("topP").toFloat() else null,
          minP = if (obj.has("minP")) obj.getDouble("minP").toFloat() else null,
          topK = if (obj.has("topK")) obj.optInt("topK") else null,
          repeatPenalty = if (obj.has("repeatPenalty")) obj.getDouble("repeatPenalty").toFloat() else null,
          freqPenalty = if (obj.has("freqPenalty")) obj.getDouble("freqPenalty").toFloat() else null,
          presPenalty = if (obj.has("presPenalty")) obj.getDouble("presPenalty").toFloat() else null,
          seed = if (obj.has("seed")) obj.optInt("seed") else null,
          flashAttention = if (obj.has("flashAttention")) obj.optBoolean("flashAttention") else null,
          lowRamMode = if (obj.has("lowRamMode")) obj.optBoolean("lowRamMode") else null,
          threads = if (obj.has("threads")) obj.optInt("threads") else null,
          nBatch = if (obj.has("nBatch")) obj.optInt("nBatch") else null
        )
      }
    } catch (_: Exception) {}
    return map
  }

  private fun saveInventConfigs(map: Map<String, ModelTokenConfig>) {
    try {
      val json = org.json.JSONObject()
      map.forEach { (key, cfg) ->
        json.put(key, org.json.JSONObject().apply {
          put("ctx", cfg.ctx)
          put("maxNew", cfg.maxNew)
          put("gpuLayers", cfg.gpuLayers)
          cfg.temperature?.let { put("temperature", it.toDouble()) }
          cfg.topP?.let { put("topP", it.toDouble()) }
          cfg.minP?.let { put("minP", it.toDouble()) }
          cfg.topK?.let { put("topK", it) }
          cfg.repeatPenalty?.let { put("repeatPenalty", it.toDouble()) }
          cfg.freqPenalty?.let { put("freqPenalty", it.toDouble()) }
          cfg.presPenalty?.let { put("presPenalty", it.toDouble()) }
          cfg.seed?.let { put("seed", it) }
          cfg.flashAttention?.let { put("flashAttention", it) }
          cfg.lowRamMode?.let { put("lowRamMode", it) }
          cfg.threads?.let { put("threads", it) }
          cfg.nBatch?.let { put("nBatch", it) }
        })
      }
      prefs?.edit()?.putString("invent_model_token_configs", json.toString())?.apply()
    } catch (_: Exception) {}
  }

  /** Get invent-specific per-model config for a role (e.g. "Planner"). */
  fun getInventModelConfig(role: String): ModelTokenConfig? {
    return loadInventConfigs()[role]
  }

  /** Set invent-specific per-model config for a role. */
  fun setInventModelConfig(role: String, cfg: ModelTokenConfig) {
    val map = loadInventConfigs()
    map[role] = cfg
    saveInventConfigs(map)
  }

  /** Remove invent-specific per-model config for a role. */
  fun removeInventModelConfig(role: String) {
    val map = loadInventConfigs()
    map.remove(role)
    saveInventConfigs(map)
  }



  var nBatch: Int
    get() = prefs?.getInt("n_batch", 512) ?: 512
    set(v) { prefs?.edit()?.putInt("n_batch", v)?.apply() }

  var temperature: Float
    get() = prefs?.getFloat("temperature", 0.6f) ?: 0.6f
    set(v) { prefs?.edit()?.putFloat("temperature", v)?.apply() }

  var topP: Float
    get() = prefs?.getFloat("top_p", 0.9f) ?: 0.9f
    set(v) { prefs?.edit()?.putFloat("top_p", v)?.apply() }

  var minP: Float
    get() = prefs?.getFloat("min_p", 0.05f) ?: 0.05f
    set(v) { prefs?.edit()?.putFloat("min_p", v)?.apply() }

  var topK: Int
    get() = prefs?.getInt("top_k", 40) ?: 40
    set(v) { prefs?.edit()?.putInt("top_k", v)?.apply() }

  var gpuLayers: Int
    get() = prefs?.getInt("gpu_layers", 0) ?: 0
    set(v) { prefs?.edit()?.putInt("gpu_layers", v)?.apply() }

  var threads: Int
    get() = prefs?.getInt("threads", 4) ?: 4
    set(v) { prefs?.edit()?.putInt("threads", v)?.apply() }

  var repeatPenalty: Float
    get() = prefs?.getFloat("repeat_penalty", 1.1f) ?: 1.1f
    set(v) { prefs?.edit()?.putFloat("repeat_penalty", v)?.apply() }

  var freqPenalty: Float
    get() = prefs?.getFloat("freq_penalty", 0.0f) ?: 0.0f
    set(v) { prefs?.edit()?.putFloat("freq_penalty", v)?.apply() }

  var presPenalty: Float
    get() = prefs?.getFloat("pres_penalty", 0.0f) ?: 0.0f
    set(v) { prefs?.edit()?.putFloat("pres_penalty", v)?.apply() }

  var systemPrompt: String
    get() = prefs?.getString("system_prompt",
        "You are a helpful, concise assistant running on-device. Respond clearly and directly.")
        ?: "You are a helpful, concise assistant running on-device. Respond clearly and directly."
    set(v) { prefs?.edit()?.putString("system_prompt", v)?.apply() }

  var lowRamMode: Boolean
    get() = prefs?.getBoolean("low_ram", true) ?: true
    set(v) { prefs?.edit()?.putBoolean("low_ram", v)?.apply() }

  // Flash attention: user can toggle; native code will further gate on i8mm detection
  var flashAttention: Boolean
    get() = prefs?.getBoolean("flash_attention", true) ?: true
    set(v) { prefs?.edit()?.putBoolean("flash_attention", v)?.apply() }

  var mmprojPath: String
    get() = prefs?.getString("mmproj_path", "") ?: ""
    set(v) { prefs?.edit()?.putString("mmproj_path", v)?.apply() }

  var currentSessionId: String
    get() = prefs?.getString("current_session_id", "") ?: ""
    set(v) { prefs?.edit()?.putString("current_session_id", v)?.apply() }

  var reasoningEnabled: Boolean
    get() = prefs?.getBoolean("reasoning_enabled", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("reasoning_enabled", v)?.apply() }

  var webSearchEnabled: Boolean
    get() = prefs?.getBoolean("web_search_enabled", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("web_search_enabled", v)?.apply() }

  var ragEnabled: Boolean
    get() = prefs?.getBoolean("rag_enabled", true) ?: true
    set(v) { prefs?.edit()?.putBoolean("rag_enabled", v)?.apply() }

  var ragFileLimit: Int
    get() = prefs?.getInt("rag_file_limit", 5) ?: 5
    set(v) { prefs?.edit()?.putInt("rag_file_limit", v.coerceIn(1, 50))?.apply() }

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

  // ── Chat template selection ──────────────────────────────────────────
  var chatTemplate: String
    get() = prefs?.getString("chat_template", "auto") ?: "auto"
    set(v) { prefs?.edit()?.putString("chat_template", v)?.apply() }

  // ── RAG settings ─────────────────────────────────────────────────────
  var ragMaxChunks: Int
    get() = prefs?.getInt("rag_max_chunks", 5) ?: 5
    set(v) { prefs?.edit()?.putInt("rag_max_chunks", v.coerceIn(1, 20))?.apply() }

  var ragMaxChars: Int
    get() = prefs?.getInt("rag_max_chars", 3000) ?: 3000
    set(v) { prefs?.edit()?.putInt("rag_max_chars", v.coerceIn(500, 10000))?.apply() }

  var ragMinScore: Float
    get() = prefs?.getFloat("rag_min_score", 0.05f) ?: 0.05f
    set(v) { prefs?.edit()?.putFloat("rag_min_score", v.coerceIn(0.01f, 1f))?.apply() }

  var welcomeDone: Boolean
    get() = prefs?.getBoolean("welcome_done", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("welcome_done", v)?.apply() }

  var isDarkTheme: Boolean
    get() = prefs?.getBoolean("dark_theme", true) ?: true
    set(v) {
      prefs?.edit()?.putBoolean("dark_theme", v)?.apply()
      com.gguf.zerocopy.ui.theme.ThemeState.isDark = v
    }


  // ── Invent settings ──────────────────────────────────────────────────────
  var inventOfflineMode: Boolean
    get() = prefs?.getBoolean("invent_offline", false) ?: false
    set(v) { prefs?.edit()?.putBoolean("invent_offline", v)?.apply() }

  var inventModel1Path: String
    get() = prefs?.getString("invent_model1_path", "") ?: ""
    set(v) { prefs?.edit()?.putString("invent_model1_path", v)?.apply() }

  var inventModel1Name: String
    get() = prefs?.getString("invent_model1_name", "") ?: ""
    set(v) { prefs?.edit()?.putString("invent_model1_name", v)?.apply() }

  var inventModel2Path: String
    get() = prefs?.getString("invent_model2_path", "") ?: ""
    set(v) { prefs?.edit()?.putString("invent_model2_path", v)?.apply() }

  var inventModel2Name: String
    get() = prefs?.getString("invent_model2_name", "") ?: ""
    set(v) { prefs?.edit()?.putString("invent_model2_name", v)?.apply() }

  var inventResearcherPath: String
    get() = prefs?.getString("invent_researcher_path", "") ?: ""
    set(v) { prefs?.edit()?.putString("invent_researcher_path", v)?.apply() }

  var inventResearcherName: String
    get() = prefs?.getString("invent_researcher_name", "") ?: ""
    set(v) { prefs?.edit()?.putString("invent_researcher_name", v)?.apply() }

  /** Build InferenceConfig, applying per-model overrides if available.
   *  Uses SettingsManager defaults for fields not covered by per-model config. */
  fun toConfig(modelPath: String? = null): InferenceConfig {
    val pm = if (modelPath != null) getModelTokenConfig(modelPath) else null
    return InferenceConfig(
      nCtx = pm?.ctx ?: nCtx,
      nBatch = (pm?.nBatch ?: nBatch).coerceIn(512, 8192),
      maxNewTokens = (pm?.maxNew ?: maxTokens).coerceAtMost(
        ((pm?.ctx ?: nCtx) - 64).coerceAtLeast(64)),
      temperature = (pm?.temperature ?: temperature).coerceIn(0f, 2f),
      topP = (pm?.topP ?: topP).coerceIn(0f, 1f),
      minP = (pm?.minP ?: minP).coerceIn(0f, 1f),
      topK = (pm?.topK ?: topK).coerceIn(0, 200),
      nGpuLayers = (pm?.gpuLayers ?: gpuLayers).coerceIn(0, 999),
      nThreads = (pm?.threads ?: threads).coerceIn(0, 16),
      seed = pm?.seed ?: -1,
      lowRamMode = pm?.lowRamMode ?: lowRamMode,
      flashAttention = pm?.flashAttention ?: flashAttention,
      mmprojPath = mmprojPath
    )
  }

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
    topK = config.topK
    gpuLayers = config.nGpuLayers
    threads = config.nThreads
    lowRamMode = config.lowRamMode
    flashAttention = config.flashAttention
    mmprojPath = config.mmprojPath
    repeatPenalty = rp.repeatPenalty
    freqPenalty = rp.freqPenalty
    presPenalty = rp.presPenalty
  }
}

