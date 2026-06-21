package com.gguf.zerocopy.domain.inference

import kotlinx.coroutines.flow.Flow

interface InferenceEngine {
    val isLoaded: Boolean
    val loadedModelPath: String?

    suspend fun load(path: String, config: EngineConfig): Result<Unit>
    suspend fun unload(): Result<Unit>

    fun generateFlow(prompt: String, maxTokens: Int = 4096): Flow<String>
    fun stopGeneration()

    fun getModelInfoJson(): String?
    fun setSystemPrompt(prompt: String)

    fun setCacheDir(dir: String)
    fun clearCache()
    fun setStreamingLLM(sinkTokens: Int, recentTokens: Int, threshold: Float)

    val embeddingDim: Int
    val numDocuments: Int
    suspend fun addDocument(
        text: String, source: String = "", chunkSize: Int = 512, overlap: Int = 64
    ): Boolean
    fun queryDocuments(query: String, topK: Int = 3): String
    fun clearDocuments()
    var ragEnabled: Boolean
    fun setRagParams(topK: Int, minScore: Float)

    val hasVision: Boolean
    val hasVoice: Boolean
    val mmprojPath: String?
}
