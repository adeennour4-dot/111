package com.gguf.zerocopy

import android.app.Application
import android.content.Intent
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.inference.EngineConfig
import com.gguf.zerocopy.domain.inference.EngineRegistry
import com.gguf.zerocopy.domain.inference.GGUFEngine
import com.gguf.zerocopy.domain.inference.InferenceEngine
import com.gguf.zerocopy.domain.inference.LiteRtEngine
import com.gguf.zerocopy.domain.inference.MnnEngine
import com.gguf.zerocopy.domain.inference.OnnxEngine
import com.gguf.zerocopy.domain.inference.ExecutorchEngine
import com.gguf.zerocopy.domain.server.ModelServerService
import com.gguf.zerocopy.data.repository.ChatRepository
import com.gguf.zerocopy.data.repository.ModelRepository
import com.gguf.zerocopy.domain.device.DeviceUtils
import com.gguf.zerocopy.domain.inference.ToolManager
import com.gguf.zerocopy.domain.server.ModelServer
import com.gguf.zerocopy.lib.GGMLEngine
import kotlinx.coroutines.runBlocking

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

  lateinit var activeEngine: InferenceEngine
    private set
  val engineRegistry: EngineRegistry = EngineRegistry()
  private val ggmlEngine = GGMLEngine()

  override fun onCreate() {
    super.onCreate()
    instance = this

    SettingsManager.init(this)
    deviceUtils = DeviceUtils(this)
    modelRepository = ModelRepository(this)
    chatRepository = ChatRepository(this)
    modelServer = ModelServer(initialPort = SettingsManager.serverPort.coerceIn(1024, 65535))

    activeEngine = GGUFEngine(ggmlEngine)
    engineRegistry.register("gguf", activeEngine)
    engineRegistry.register("ggml", activeEngine)

    val litertEngine = LiteRtEngine()
    engineRegistry.register("tflite", litertEngine)
    engineRegistry.register("litertlm", litertEngine)

    val mnnEngine = MnnEngine()
    engineRegistry.register("mnn", mnnEngine)

    val onnxEngine = OnnxEngine()
    engineRegistry.register("onnx", onnxEngine)

    val executorchEngine = ExecutorchEngine()
    engineRegistry.register("pte", executorchEngine)

    if (SettingsManager.serverEnabled && SettingsManager.lastModelPath.isNotEmpty()) {
      modelServer.setAutoModel(SettingsManager.lastModelPath, SettingsManager.lastModelName)
      startService(Intent(this, ModelServerService::class.java))
    }
  }

  fun loadModelFromSettings() {
    val path = SettingsManager.lastModelPath
    if (path.isNotEmpty()) {
      runBlocking {
        activeEngine.load(path, SettingsManager.toEngineConfig())
      }
    }
  }

  fun switchEngine(newEngine: InferenceEngine) {
    activeEngine = newEngine
  }

  companion object {
    lateinit var instance: ZeroCopyApp
      private set
  }
}
