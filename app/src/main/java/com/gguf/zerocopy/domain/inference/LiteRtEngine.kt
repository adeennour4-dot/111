package com.gguf.zerocopy.domain.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

class LiteRtEngine : InferenceEngine {

    @Volatile private var engine: Engine? = null
    @Volatile private var currentConversation: Conversation? = null
    @Volatile private var _loadedModelPath: String? = null
    @Volatile private var _systemPrompt: String = ""

    override val isLoaded: Boolean get() = engine != null
    override val loadedModelPath: String? get() = _loadedModelPath

    override suspend fun load(path: String, config: EngineConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val extConfig = com.google.ai.edge.litertlm.EngineConfig(modelPath = path, backend = Backend.CPU(null))
                engine = Engine(extConfig)
                engine!!.initialize()
                _loadedModelPath = path
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun unload(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            currentConversation?.close()
        } catch (_: Exception) {}
        try {
            engine?.close()
        } catch (_: Exception) {}
        engine = null
        currentConversation = null
        _loadedModelPath = null
        Result.success(Unit)
    }

    override fun generateFlow(prompt: String, maxTokens: Int): Flow<String> = callbackFlow {
        val conv = engine?.createConversation()
            ?: throw IllegalStateException("LiteRT-LM engine not loaded")
        currentConversation = conv

        if (_systemPrompt.isNotEmpty()) {
            try {
                conv.sendMessage(Message.system(Contents.of(_systemPrompt)), emptyMap())
            } catch (_: Exception) {}
        }

        val cb = object : MessageCallback {
            override fun onMessage(message: Message) {
                trySend(message.toString())
            }

            override fun onDone() {
                close()
            }

            override fun onError(t: Throwable) {
                close(t)
            }
        }

        try {
            conv.sendMessageAsync(prompt, cb, emptyMap())
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            try {
                conv.cancelProcess()
                conv.close()
            } catch (_: Exception) {}
            currentConversation = null
        }
    }

    override fun stopGeneration() {
        try {
            currentConversation?.cancelProcess()
        } catch (_: Exception) {}
    }

    override fun getModelInfoJson(): String? = null

    override fun setSystemPrompt(prompt: String) {
        _systemPrompt = prompt
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
}
