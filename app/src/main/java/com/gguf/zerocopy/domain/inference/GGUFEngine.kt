package com.gguf.zerocopy.domain.inference

import com.gguf.zerocopy.lib.GGMLEngine
import kotlinx.coroutines.flow.Flow

class GGUFEngine(
    private val engine: GGMLEngine = GGMLEngine()
) : InferenceEngine {

    @Volatile
    private var _loadedModelPath: String? = null

    override val isLoaded: Boolean get() = engine.isLoaded

    override val loadedModelPath: String? get() = _loadedModelPath

    override suspend fun load(path: String, config: EngineConfig): Result<Unit> = runCatching {
        val success = engine.load(
            path = path,
            contextSize = config.contextSize,
            threads = config.threads,
            batchSize = config.batchSize,
            flashAttn = config.flashAttn,
            useMmap = config.useMmap,
            useMlock = config.useMlock,
            cacheTypeK = config.cacheTypeK,
            cacheTypeV = config.cacheTypeV,
            opOffload = config.opOffload,
        )
        if (!success) error("Failed to load model: $path")
        _loadedModelPath = path
    }

    override suspend fun unload(): Result<Unit> = runCatching {
        engine.unload()
        _loadedModelPath = null
    }

    override fun generateFlow(prompt: String, maxTokens: Int): Flow<String> =
        engine.generateFlow(prompt, maxTokens)

    override fun stopGeneration() = engine.stopGeneration()

    override fun getModelInfoJson(): String? = engine.getModelInfoJson()

    override fun setSystemPrompt(prompt: String) = engine.setSystemPrompt(prompt)

    override fun setCacheDir(dir: String) = engine.setCacheDir(dir)

    override fun clearCache() = engine.clearCache()

    override fun setStreamingLLM(sinkTokens: Int, recentTokens: Int, threshold: Float) =
        engine.setStreamingLLM(sinkTokens, recentTokens, threshold)

    override val embeddingDim: Int get() = engine.embeddingDim

    override val numDocuments: Int get() = engine.numDocuments

    override suspend fun addDocument(
        text: String, source: String, chunkSize: Int, overlap: Int
    ): Boolean = engine.addDocument(text, source, chunkSize, overlap)

    override fun queryDocuments(query: String, topK: Int): String =
        engine.queryDocuments(query, topK)

    override fun clearDocuments() = engine.clearDocuments()

    override var ragEnabled: Boolean
        get() = engine.ragEnabled
        set(value) { engine.ragEnabled = value }

    override fun setRagParams(topK: Int, minScore: Float) =
        engine.setRagParams(topK, minScore)

    override val hasVision: Boolean
        get() {
            val info = engine.getModelInfoJson()
            if (info != null) {
                try {
                    val json = org.json.JSONObject(info)
                    if (json.optBoolean("has_vision", false)) return true
                } catch (_: Exception) {}
            }
            return com.gguf.zerocopy.data.local.SettingsManager.mmprojPath.isNotEmpty()
        }

    override val hasVoice: Boolean
        get() {
            val info = engine.getModelInfoJson()
            if (info != null) {
                try {
                    val json = org.json.JSONObject(info)
                    return json.optBoolean("has_voice", false)
                } catch (_: Exception) {}
            }
            return false
        }

    override val mmprojPath: String?
        get() = com.gguf.zerocopy.data.local.SettingsManager.mmprojPath.ifEmpty { null }
}
