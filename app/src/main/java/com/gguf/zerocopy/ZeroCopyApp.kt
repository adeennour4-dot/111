package com.gguf.zerocopy

import android.app.Application
import android.content.Intent
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.server.ModelServerService
import com.gguf.zerocopy.data.repository.ChatRepository
import com.gguf.zerocopy.data.repository.ModelRepository
import com.gguf.zerocopy.domain.device.DeviceUtils
import com.gguf.zerocopy.domain.inference.EngineManager
import com.gguf.zerocopy.domain.inference.ToolManager
import com.gguf.zerocopy.domain.rag.RagEngine
import com.gguf.zerocopy.domain.server.ModelServer

class ZeroCopyApp : Application() {
  lateinit var engineManager: EngineManager
    private set
  lateinit var modelRepository: ModelRepository
    private set
  lateinit var chatRepository: ChatRepository
    private set
  lateinit var deviceUtils: DeviceUtils
    private set
  var toolManager: ToolManager = ToolManager()
    private set
  lateinit var modelServer: ModelServer
    private set
  lateinit var ragEngine: RagEngine
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this

    SettingsManager.init(this)
    deviceUtils = DeviceUtils(this)
    engineManager = EngineManager(this)
    modelRepository = ModelRepository(this)
    chatRepository = ChatRepository(this)
    modelServer = ModelServer()
    ragEngine = RagEngine(this)

    syncSettingsToEngines()

    if (SettingsManager.serverEnabled && SettingsManager.lastModelPath.isNotEmpty()) {
      modelServer.setAutoModel(SettingsManager.lastModelPath, SettingsManager.lastModelName)
      startService(Intent(this, ModelServerService::class.java))
    }
  }

  private fun syncSettingsToEngines() {
    val config = SettingsManager.toConfig()
    val rp = SettingsManager.toRepeatPenalty()
    val prompt = SettingsManager.systemPrompt
    engineManager.llamaCpp.config = config
    engineManager.llamaCpp.repeatPenalty = rp
    engineManager.llamaCpp.systemPrompt = prompt
    engineManager.llamaCpp.mmprojPath = config.mmprojPath
    engineManager.mnn.config = config
    engineManager.mnn.repeatPenalty = rp
    engineManager.mnn.systemPrompt = prompt
    engineManager.liteRt.config = config
    engineManager.liteRt.repeatPenalty = rp
    engineManager.liteRt.systemPrompt = prompt
  }

  companion object {
    lateinit var instance: ZeroCopyApp
      private set
  }
}
