package com.gguf.zerocopy.domain.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

class ExecutorchEngine : InferenceEngine {

    @Volatile private var module: Module? = null
    @Volatile private var _loadedModelPath: String? = null
    @Volatile private var _systemPrompt: String = ""
    @Volatile private var stopRequested = false

    private var tokenizer: SimpleTokenizer? = null
    private var eosTokenId = 0
    private val vocabSize: Int get() = tokenizer?.vocabSize ?: 0

    override val isLoaded: Boolean get() = module != null
    override val loadedModelPath: String? get() = _loadedModelPath

    override suspend fun load(path: String, config: EngineConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val modelFile = resolveModelFile(path)
                val modelDir = modelFile.parentFile ?: File(path)

                val mod = Module.load(modelFile.absolutePath)
                val tok = SimpleTokenizer.fromDir(modelDir)
                val eos = tok?.findEosId() ?: 0

                module = mod
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
            module?.destroy()
        } catch (_: Exception) {}
        module = null
        tokenizer = null
        _loadedModelPath = null
        Result.success(Unit)
    }

    override fun generateFlow(prompt: String, maxTokens: Int): Flow<String> = callbackFlow {
        val mod = module ?: throw IllegalStateException("ExecuTorch engine not loaded")
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
                val data = inputIds.map { it.toFloat() }.toFloatArray()
                val inputTensor = Tensor.fromBlob(data, longArrayOf(1L, seqLen.toLong()))
                val outputEValues = mod.forward(EValue.from(inputTensor))
                val outputTensor = outputEValues[0].toTensor()
                val scores = outputTensor.getDataAsFloatArray()

                val vSize = vocabSize.coerceIn(1, scores.size)
                val lastLogits = scores.copyOfRange(scores.size - vSize, scores.size)

                val nextToken = greedySample(lastLogits)
                if (nextToken == eosTokenId.toLong() && i > 0) break

                inputIds.add(nextToken)

                val tokenText = tok?.decode(nextToken.toInt())
                    ?: nextToken.toInt().toChar().toString()
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
        return if (isLoaded) """{"arch":"executorch","vocab_size":$vocabSize}""" else null
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

    private fun resolveModelFile(path: String): File {
        val file = File(path)
        if (file.isFile) return file
        if (file.isDirectory) {
            file.listFiles()?.forEach { f ->
                if (f.extension.lowercase() == "pte") return f
            }
        }
        return file
    }

    private fun greedySample(logits: FloatArray): Long {
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
