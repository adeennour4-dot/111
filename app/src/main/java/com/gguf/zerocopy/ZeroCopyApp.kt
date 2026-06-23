package com.gguf.zerocopy

import android.app.Application
import android.content.Intent
import android.util.Log
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

    // Install a crash guard BEFORE loading any native libraries.
    // If a native crash (SIGSEGV/SIGILL) occurs during model load, Android
    // restarts the app. On the next cold start we clear lastModelPath so the
    // app doesn't attempt to auto-reload the same model that caused the crash,
    // breaking the persistent crash loop seen on Exynos 9825 (Note 10 Lite).
    installNativeCrashGuard()

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

  /**
   * Installs a crash sentinel file mechanism.
   * On every cold start we check for a sentinel file written by the previous
   * launch. If it exists, the previous session crashed before completing model
   * load, so we clear the saved model path to break the crash loop.
   */
  private fun installNativeCrashGuard() {
    val sentinel = java.io.File(filesDir, ".loading_sentinel")
    if (sentinel.exists()) {
      // Previous launch wrote sentinel but never deleted it → it crashed.
      Log.w("ZeroCopyApp", "Crash sentinel found — previous launch crashed during model load. Clearing lastModelPath.")
      SettingsManager.lastModelPath = ""
      SettingsManager.lastModelName = ""
      sentinel.delete()
    }
    // Also install an UncaughtExceptionHandler to write the sentinel when
    // a JVM-visible crash happens (e.g. OOM, bad JNI return type).
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      Log.e("ZeroCopyApp", "Uncaught exception — writing crash sentinel", throwable)
      try { sentinel.createNewFile() } catch (_: Exception) {}
      defaultHandler?.uncaughtException(thread, throwable)
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
