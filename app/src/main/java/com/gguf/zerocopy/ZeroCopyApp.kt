package com.gguf.zerocopy

import android.app.Application
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.ChatRepository
import com.gguf.zerocopy.data.repository.ModelRepository
import com.gguf.zerocopy.domain.device.DeviceUtils
import com.gguf.zerocopy.domain.inference.EngineManager

class ZeroCopyApp : Application() {
  lateinit var engineManager: EngineManager
    private set
  lateinit var modelRepository: ModelRepository
    private set
  lateinit var chatRepository: ChatRepository
    private set
  lateinit var deviceUtils: DeviceUtils
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this

    SettingsManager.init(this)
    deviceUtils = DeviceUtils(this)
    engineManager = EngineManager(this)
    modelRepository = ModelRepository(this)
    chatRepository = ChatRepository(this)

    if (SettingsManager.autoDetectDevice) {
      val info = deviceUtils.detect()
      SettingsManager.applyDeviceDefaults(info)
    }
    syncSettingsToEngines()
  }

  private fun syncSettingsToEngines() {
    val config = SettingsManager.toConfig()
    val rp = SettingsManager.toRepeatPenalty()
    val prompt = SettingsManager.systemPrompt
    engineManager.llamaCpp.config = config
    engineManager.llamaCpp.repeatPenalty = rp
    engineManager.llamaCpp.systemPrompt = prompt
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
