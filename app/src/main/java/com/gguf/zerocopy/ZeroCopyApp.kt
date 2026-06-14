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
  }

  companion object {
    lateinit var instance: ZeroCopyApp
    private set
  }
}







