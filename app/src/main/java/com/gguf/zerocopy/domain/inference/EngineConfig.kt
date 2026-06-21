package com.gguf.zerocopy.domain.inference

data class EngineConfig(
    val contextSize: Int = 4096,
    val threads: Int = 4,
    val batchSize: Int = 512,
    val flashAttn: Boolean = false,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val cacheTypeK: String = "q8_0",
    val cacheTypeV: String = "q8_0",
    val opOffload: Boolean = false,
)
