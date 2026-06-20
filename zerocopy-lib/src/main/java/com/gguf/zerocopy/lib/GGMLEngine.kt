package com.gguf.zerocopy.lib

import android.content.Context
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
}
