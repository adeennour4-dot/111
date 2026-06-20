package com.gguf.zerocopy.lib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GGMLEngine {
    @Volatile private var loaded = false

    suspend fun load(
        path: String,
        contextSize: Int = 4096,
        threads: Int = 0,
        batchSize: Int = 0,
        flashAttn: Boolean = false,
        useMmap: Boolean = true,
        useMlock: Boolean = false,
        cacheTypeK: String = "q8_0",
        cacheTypeV: String = "q8_0",
        opOffload: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        loaded = NativeBridge.nativeLoadModel(
            path, contextSize, threads, batchSize,
            flashAttn, useMmap, useMlock, cacheTypeK, cacheTypeV, opOffload,
        )
        loaded
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        if (loaded) { NativeBridge.nativeRelease(); loaded = false }
    }

    val isLoaded: Boolean get() = loaded

    fun generateFlow(prompt: String, maxTokens: Int = 4096): Flow<String> = callbackFlow {
        val cb = object : NativeBridge.StreamCallback {
            override fun onToken(token: String) { trySend(token) }
            override fun onDone() { close() }
            override fun onError(message: String) { close() }
            override fun onKvCacheUsage(percent: Int) {}
            override fun onTokensGenerated(count: Int) {}
        }
        val job = launch(Dispatchers.IO) {
            NativeBridge.nativeGenerateStream(prompt, maxTokens, cb)
        }
        awaitClose { job.cancel(); NativeBridge.nativeAbortInference() }
    }

    fun setThreadMode(mode: Int) = NativeBridge.nativeSetThreadMode(mode)
    fun setSystemPrompt(prompt: String) = NativeBridge.nativeSetSystemPrompt(prompt)
    fun getModelInfoJson(): String? = if (loaded) NativeBridge.nativeGetModelInfo() else null
    fun stopGeneration() = NativeBridge.nativeAbortInference()

    // Prompt cache
    fun setCacheDir(dir: String) = NativeBridge.nativeSetCacheDir(dir)
    fun clearCache() = NativeBridge.nativeClearCache()

    // StreamingLLM
    fun setStreamingLLM(sinkTokens: Int = 4, recentTokens: Int = 512, threshold: Float = 0.85f) =
        NativeBridge.nativeSetStreamingLLM(sinkTokens, recentTokens, threshold)

    // RAG
    val embeddingDim: Int get() = NativeBridge.nativeGetEmbeddingDim()
    val numDocuments: Int get() = NativeBridge.nativeNumDocuments()

    suspend fun addDocument(
        text: String, source: String = "", chunkSize: Int = 512, overlap: Int = 64
    ): Boolean = withContext(Dispatchers.IO) {
        NativeBridge.nativeAddDocument(text, source, chunkSize, overlap)
    }

    fun queryDocuments(query: String, topK: Int = 3): String =
        NativeBridge.nativeQueryDocuments(query, topK)

    fun clearDocuments() = NativeBridge.nativeClearDocuments()

    var ragEnabled: Boolean = false
        set(value) {
            field = value
            NativeBridge.nativeSetRagEnabled(value)
        }

    fun setRagParams(topK: Int = 3, minScore: Float = 0.3f) =
        NativeBridge.nativeSetRagParams(topK, minScore)
}
