package com.gguf.zerocopy.domain.inference

import com.gguf.zerocopy.data.local.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MnnEngine : InferenceEngine {

    private val nativeLibLoaded: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary("mnn-bridge")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
        }
        nativeLibLoaded = loaded
    }

    private external fun mnnLoadModel(path: String): Boolean
    private external fun mnnExecuteInference(prompt: String, callback: TokenCallback)
    private external fun mnnAbortInference()
    private external fun mnnResetContext()
    private external fun mnnGetModelInfo(): String
    private external fun mnnBenchmark(ppTokens: Int, tgTokens: Int): String
    private external fun mnnSetConfigNative(
        nCtx: Int, maxNewTokens: Int, temperature: Float, repeatPenalty: Float
    )
    private external fun mnnSetSystemPromptNative(prompt: String)
    private external fun mnnGetKvCacheUsage(): Int
    private external fun mnnGetTokensGenerated(): Int
    private external fun mnnIsInferenceDone(): Boolean

    @Volatile private var _loadedModelPath: String? = null
    @Volatile override var isLoaded: Boolean = false
        private set
    override val loadedModelPath: String? get() = _loadedModelPath

    private val inferenceDone = AtomicBoolean(true)
    private val tokensGenerated = AtomicInteger(0)
    private var kvUsage = 0
    private var _systemPrompt = ""

    private var config = EngineConfig()

    override suspend fun load(path: String, config: EngineConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!nativeLibLoaded) {
                return@withContext Result.failure(
                    Exception("MNN native library not available")
                )
            }
            try {
                this@MnnEngine.config = config
                val modelDir = resolveModelDir(path)
                mnnSetConfigNative(
                    config.contextSize,
                    SettingsManager.maxTokens,
                    SettingsManager.temperature,
                    SettingsManager.repeatPenalty
                )
                mnnSetSystemPromptNative(_systemPrompt)
                val ok = mnnLoadModel(modelDir)
                if (ok) {
                    isLoaded = true
                    _loadedModelPath = path
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("MNN model load failed"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun unload(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mnnResetContext()
        } catch (_: Exception) {}
        isLoaded = false
        _loadedModelPath = null
        Result.success(Unit)
    }

    override fun generateFlow(prompt: String, maxTokens: Int): Flow<String> = callbackFlow {
        if (!isLoaded) {
            close(IllegalStateException("MNN model not loaded"))
            return@callbackFlow
        }

        val cb = object : TokenCallback {
            override fun onToken(token: String) {
                trySend(token)
            }

            override fun onDone() {
                close()
            }

            override fun onError(error: String) {
                close(Exception(error))
            }

            override fun onKvCacheUsage(percent: Int) {
                kvUsage = percent
            }

            override fun onTokensGenerated(count: Int) {
                tokensGenerated.set(count)
            }
        }

        inferenceDone.set(false)
        tokensGenerated.set(0)
        try {
            mnnExecuteInference(prompt, cb)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            mnnAbortInference()
            inferenceDone.set(true)
        }
    }

    override fun stopGeneration() {
        inferenceDone.set(true)
        try {
            mnnAbortInference()
        } catch (_: Exception) {}
    }

    override fun getModelInfoJson(): String? {
        return if (isLoaded) mnnGetModelInfo() else null
    }

    override fun setSystemPrompt(prompt: String) {
        _systemPrompt = prompt
        if (isLoaded) {
            try {
                mnnSetSystemPromptNative(prompt)
            } catch (_: Exception) {}
        }
    }

    override fun setCacheDir(dir: String) {}

    override fun clearCache() {}

    override fun setStreamingLLM(sinkTokens: Int, recentTokens: Int, threshold: Float) {}

    override val embeddingDim: Int get() = 0

    override val numDocuments: Int get() = 0

    override suspend fun addDocument(
        text: String, source: String, chunkSize: Int, overlap: Int
    ): Boolean = false

    override fun queryDocuments(query: String, topK: Int): String = ""

    override fun clearDocuments() {}

    override var ragEnabled: Boolean = false

    override fun setRagParams(topK: Int, minScore: Float) {}

    private fun resolveModelDir(path: String): String {
        val file = File(path)
        if (file.isDirectory && File(file, "config.json").exists()) return path
        val parent = file.parentFile
        if (parent != null && File(parent, "config.json").exists()) return parent.absolutePath
        val dir = File(file.absolutePath.removeSuffix(".mnn"))
        if (dir.isDirectory && File(dir, "config.json").exists()) return dir.absolutePath
        return path
    }

    interface TokenCallback {
        fun onToken(token: String)
        fun onDone()
        fun onError(error: String)
        fun onKvCacheUsage(percent: Int)
        fun onTokensGenerated(count: Int)
    }
}
