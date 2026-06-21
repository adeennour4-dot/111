package com.gguf.zerocopy.domain.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

class OnnxEngine : InferenceEngine {

    @Volatile private var ortEnv: OrtEnvironment? = null
    @Volatile private var ortSession: OrtSession? = null
    @Volatile private var _loadedModelPath: String? = null
    @Volatile private var _systemPrompt: String = ""
    @Volatile private var stopRequested = false

    private var tokenizer: SimpleTokenizer? = null
    private var eosTokenId = 0
    private val vocabSize: Int get() = tokenizer?.vocabSize ?: 0

    override val isLoaded: Boolean get() = ortSession != null
    override val loadedModelPath: String? get() = _loadedModelPath

    override suspend fun load(path: String, config: EngineConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val modelFile = resolveModelFile(path)
                val modelDir = modelFile.parentFile ?: File(path)

                // Init ONNX Runtime
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                opts.addCPU(true)
                val session = env.createSession(modelFile.absolutePath, opts)

                // Load tokenizer
                val tok = SimpleTokenizer.fromDir(modelDir)
                val eos = tok?.findEosId() ?: 0

                ortEnv = env
                ortSession = session
                tokenizer = tok
                eosTokenId = eos
                _loadedModelPath = path
                stopRequested = false
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun unload(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ortSession?.close()
        } catch (_: Exception) {}
        ortSession = null
        ortEnv = null
        tokenizer = null
        _loadedModelPath = null
        Result.success(Unit)
    }

    override fun generateFlow(prompt: String, maxTokens: Int): Flow<String> = callbackFlow {
        val env = ortEnv ?: throw IllegalStateException("ONNX engine not loaded")
        val session = ortSession ?: throw IllegalStateException("ONNX engine not loaded")
        val tok = tokenizer

        stopRequested = false

        val fullPrompt = if (_systemPrompt.isNotEmpty()) "$_systemPrompt\n$prompt" else prompt
        val inputIds = mutableListOf<Long>()

        if (tok != null) {
            inputIds.addAll(tok.encode(fullPrompt))
        } else {
            fullPrompt.forEach { ch -> inputIds.add(ch.code.toLong()) }
        }

        if (inputIds.isEmpty()) inputIds.add(0L)

        try {
            for (i in 0 until maxTokens) {
                if (stopRequested) break

                val seqLen = inputIds.size
                val tensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(inputIds.toLongArray()),
                    longArrayOf(1L, seqLen.toLong())
                )

                val inputs = mutableMapOf<String, OnnxTensor>("input_ids" to tensor)

                val attentionMask = LongArray(seqLen) { 1L }
                val maskTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(attentionMask),
                    longArrayOf(1L, seqLen.toLong())
                )
                inputs["attention_mask"] = maskTensor

                val result = session.run(inputs)
                val logitsTensor = run {
                    val v = result.get("logits")
                    if (v.isPresent) v.get() as? OnnxTensor
                    else result.get(0) as? OnnxTensor
                }

                val logitsBuf = logitsTensor?.floatBuffer ?: continue
                val logitsCapacity = logitsBuf.capacity()
                val lastPosStart = logitsCapacity - vocabSize.coerceAtLeast(logitsCapacity)
                val lastLogits = FloatArray(vocabSize.coerceAtMost(logitsCapacity - lastPosStart)) { idx ->
                    logitsBuf[lastPosStart + idx]
                }

                tensor.close()
                maskTensor.close()
                result.close()

                val nextToken = sampleToken(lastLogits)
                if (nextToken == eosTokenId.toLong() && i > 0) break

                inputIds.add(nextToken)

                val tokenText = tok?.decode(nextToken.toInt()) ?: nextToken.toInt().toChar().toString()
                if (tokenText.isNotEmpty()) {
                    trySend(tokenText)
                }
            }
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        close()
    }

    override fun stopGeneration() {
        stopRequested = true
    }

    override fun getModelInfoJson(): String? {
        val session = ortSession ?: return null
        return try {
            """{"arch":"onnx","vocab_size":$vocabSize}"""
        } catch (_: Exception) {
            """{"arch":"onnx"}"""
        }
    }

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

    override val hasVision: Boolean = false
    override val hasVoice: Boolean = false
    override val mmprojPath: String? = null

    private fun resolveModelFile(path: String): File {
        val file = File(path)
        if (file.isFile) return file
        if (file.isDirectory) {
            file.listFiles()?.forEach { f ->
                if (f.extension.lowercase() == "onnx") return f
            }
        }
        return file
    }

    private fun sampleToken(logits: FloatArray): Long {
        var maxIdx = 0
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (logits[i] > maxVal) {
                maxVal = logits[i]
                maxIdx = i
            }
        }
        return maxIdx.toLong()
    }

}
