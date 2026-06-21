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
import com.gguf.zerocopy.domain.server.ModelServer
import com.gguf.zerocopy.lib.GGMLEngine

class ZeroCopyApp : Application() {
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
  val ggmlEngine: GGMLEngine = GGMLEngine()
  val engineManager: EngineManager = EngineManager()

  override fun onCreate() {
    super.onCreate()
    instance = this

    SettingsManager.init(this)
    deviceUtils = DeviceUtils(this)
    modelRepository = ModelRepository(this)
    chatRepository = ChatRepository(this)
    modelServer = ModelServer()

    if (SettingsManager.serverEnabled && SettingsManager.lastModelPath.isNotEmpty()) {
      modelServer.setAutoModel(SettingsManager.lastModelPath, SettingsManager.lastModelName)
      startService(Intent(this, ModelServerService::class.java))
    }
  }

  companion object {
    lateinit var instance: ZeroCopyApp
      private set
  }
}
