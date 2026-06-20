package com.gguf.zerocopy.lib

import android.util.Log

object NativeBridge {
    private const val TAG = "NativeBridge"
    val nativeLibLoaded: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("zerocopy_engine")
            loaded = true
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
        nativeLibLoaded = loaded
    }

    interface StreamCallback {
        fun onToken(token: String)
        fun onDone()
        fun onError(message: String)
        fun onKvCacheUsage(percent: Int)
        fun onTokensGenerated(count: Int)
    }

    // Model lifecycle
    external fun nativeLoadModel(
        filePath: String, nCtx: Int, nThreads: Int, nBatch: Int,
        flashAttn: Boolean, useMmap: Boolean, useMlock: Boolean,
        cacheTypeK: String, cacheTypeV: String, opOffload: Boolean,
    ): Boolean

    external fun nativeRelease()
    external fun nativeGenerateStream(prompt: String, maxTokens: Int, callback: StreamCallback)
    external fun nativeSetThreadMode(mode: Int)
    external fun nativeSetSystemPrompt(prompt: String)
    external fun nativeAbortInference()
    external fun nativeGetModelInfo(): String

    // Prompt cache
    external fun nativeSetCacheDir(dir: String)
    external fun nativeClearCache()

    // StreamingLLM
    external fun nativeSetStreamingLLM(sinkTokens: Int, recentTokens: Int, threshold: Float)

    // RAG
    external fun nativeGetEmbeddingDim(): Int
    external fun nativeAddDocument(
        text: String, source: String, chunkSize: Int, overlap: Int
    ): Boolean
    external fun nativeQueryDocuments(query: String, topK: Int): String
    external fun nativeClearDocuments()
    external fun nativeNumDocuments(): Int
    external fun nativeSetRagEnabled(enabled: Boolean)
    external fun nativeSetRagParams(topK: Int, minScore: Float)
}
