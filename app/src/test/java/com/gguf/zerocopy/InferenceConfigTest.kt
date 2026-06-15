package com.gguf.zerocopy

import com.gguf.zerocopy.domain.inference.EngineType
import com.gguf.zerocopy.domain.inference.InferenceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceConfigTest {
  @Test
  fun defaultConfigIsMobileFriendly() {
    val cfg = InferenceConfig()
    assertTrue("Context should fit mobile", cfg.nCtx <= 4096)
    assertTrue("GPU layers should be 0 by default", cfg.nGpuLayers == 0)
    assertTrue("Threads should be reasonable", cfg.nThreads in 0..8)
    assertTrue("Low RAM mode should be on", cfg.lowRamMode)
  }

  @Test
  fun engineTypeFromFormat() {
    assertEquals(EngineType.LLAMA_CPP, EngineType.fromFormat("model.gguf"))
    assertEquals(EngineType.LLAMA_CPP, EngineType.fromFormat("model.GGUF"))
    assertEquals(EngineType.MNN, EngineType.fromFormat("model.mnn"))
    assertEquals(EngineType.LITER_T, EngineType.fromFormat("model.tflite"))
    assertEquals(EngineType.LITER_T, EngineType.fromFormat("model.litertlm"))
    assertEquals(EngineType.LLAMA_CPP, EngineType.fromFormat("model.unknown"))
  }

  @Test
  fun engineTypeIds() {
    assertEquals("llama.cpp", EngineType.LLAMA_CPP.id)
    assertEquals("MNN", EngineType.MNN.id)
    assertEquals("LiteRT-LM", EngineType.LITER_T.id)
  }
}
