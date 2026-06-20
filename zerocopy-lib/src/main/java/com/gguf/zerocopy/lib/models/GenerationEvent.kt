package com.gguf.zerocopy.lib.models

sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    object Done : GenerationEvent
    data class Error(val message: String) : GenerationEvent
}
