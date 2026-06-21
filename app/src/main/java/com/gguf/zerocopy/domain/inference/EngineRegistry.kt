package com.gguf.zerocopy.domain.inference

class EngineRegistry {
    private val engines = mutableMapOf<String, InferenceEngine>()

    fun register(extension: String, engine: InferenceEngine) {
        engines[extension.lowercase()] = engine
    }

    fun resolve(path: String): InferenceEngine {
        val ext = path.substringAfterLast('.').lowercase()
        return engines[ext] ?: throw IllegalArgumentException("No engine registered for .$ext files")
    }

    fun find(path: String): InferenceEngine? {
        val ext = path.substringAfterLast('.').lowercase()
        return engines[ext]
    }

    fun findByFormat(format: String): InferenceEngine? = engines[format.lowercase()]

    val registered: Map<String, InferenceEngine> get() = engines.toMap()
}
