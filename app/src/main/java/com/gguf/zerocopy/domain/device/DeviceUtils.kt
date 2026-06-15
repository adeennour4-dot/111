package com.gguf.zerocopy.domain.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.gguf.zerocopy.domain.inference.EngineType
import com.gguf.zerocopy.domain.inference.InferenceConfig
import java.io.File

data class DeviceInfo(
  val socModel: String = "",
  val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
  val cpuMaxFreq: Int = 0,
  val bigCores: List<Int> = emptyList(),
  val totalRamMB: Long = 0,
  val availableRamMB: Long = 0,
  val isSnapdragon: Boolean = false,
  val isExynos: Boolean = false,
  val isMediaTek: Boolean = false,
  val isTensor: Boolean = false,
  val hasVulkan: Boolean = false,
  val hasOpenCL: Boolean = false
) {
  fun suggestConfig(modelSizeB: Float = 7f): InferenceConfig {
    val suggestedThreads =
      if (bigCores.isNotEmpty()) {
        bigCores.size.coerceIn(1, 4)
      } else {
        (cpuCores / 2).coerceIn(1, 4)
      }

    val suggestedGpuLayers = if (hasVulkan) 99 else 0

    val estimatedModelRAM = modelSizeB * 1024 * 0.6f
    val availableForContext = (availableRamMB - estimatedModelRAM).coerceAtLeast(256f)
    val suggestedCtx =
      when {
        modelSizeB <= 1f -> 4096
        modelSizeB <= 3f -> 2048
        modelSizeB <= 7f -> 1024
        else -> 512
      }.coerceAtMost((availableForContext / 2).toInt()).coerceAtLeast(512)

    return InferenceConfig(
      nCtx = suggestedCtx,
      nBatch = 512,
      maxNewTokens = suggestedCtx.coerceAtMost(1024),
      nGpuLayers = suggestedGpuLayers,
      nThreads = suggestedThreads,
      lowRamMode = true
    )
  }

  fun suggestEngine(): EngineType = when {
    isSnapdragon -> EngineType.LLAMA_CPP
    isMediaTek -> EngineType.MNN
    isExynos -> EngineType.LLAMA_CPP
    else -> EngineType.LLAMA_CPP
  }

  fun canFitModel(modelSizeGB: Float, safetyMargin: Float = 0.8f): Boolean {
    val requiredMB = modelSizeGB * 1024 * 1.2f
    return availableRamMB > requiredMB / safetyMargin
  }
}

class DeviceUtils(private val context: Context) {
  fun detect(): DeviceInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    val totalRamMB = memInfo.totalMem / (1024 * 1024)
    val availableRamMB = memInfo.availMem / (1024 * 1024)
    val cpuCores = Runtime.getRuntime().availableProcessors()
    val cpuMaxFreq = readCpuMaxFreq()
    val bigCores = detectBigCores()
    val socModel =
      Build.SOC_MODEL
        .ifEmpty { Build.HARDWARE }
        .ifEmpty { "unknown" }
        .lowercase()

    return DeviceInfo(
      socModel = socModel,
      cpuCores = cpuCores,
      cpuMaxFreq = cpuMaxFreq,
      bigCores = bigCores,
      totalRamMB = totalRamMB,
      availableRamMB = availableRamMB,
      isSnapdragon =
      socModel.contains("snapdragon") ||
        socModel.contains("qcom") ||
        Build.MANUFACTURER.lowercase().contains("qualcomm"),
      isExynos = socModel.contains("exynos") || Build.MANUFACTURER.lowercase().contains("samsung"),
      isMediaTek =
      socModel.contains("mt") ||
        socModel.contains("dimensity") ||
        Build.MANUFACTURER.lowercase().contains("mediatek"),
      isTensor = socModel.contains("tensor") || Build.MANUFACTURER.lowercase().contains("google"),
      hasVulkan = hasVulkanDevice(),
      hasOpenCL = hasOpenCLDevice()
    )
  }

  fun readCpuFreq(cpu: Int): Int = try {
    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
      .readText()
      .trim()
      .toIntOrNull() ?: 0
  } catch (_: Exception) {
    0
  }

  private fun readCpuMaxFreq(): Int {
    var max = 0
    for (cpu in 0 until Runtime.getRuntime().availableProcessors()) {
      val f = readCpuFreq(cpu)
      if (f > max) max = f
    }
    return max
  }

  private fun detectBigCores(): List<Int> {
    val coreFreqs = mutableListOf<Pair<Int, Int>>()
    for (cpu in 0 until Runtime.getRuntime().availableProcessors()) {
      val f = readCpuFreq(cpu)
      if (f > 0) coreFreqs.add(cpu to f)
    }
    if (coreFreqs.isEmpty()) return emptyList()
    val maxFreq = coreFreqs.maxOf { it.second }
    val threshold = maxFreq * 80 / 100
    return coreFreqs.filter { it.second >= threshold }.map { it.first }
  }

  private fun hasVulkanDevice(): Boolean {
    return try {
      Class.forName("android.hardware.vulkan.VulkanDevice")
      true
    } catch (_: Exception) {
      try {
        val clazz = Class.forName("android.os.Build")
        val field = clazz.getDeclaredField("SUPPORTED_64_BIT_ABIS")
        val abis = field.get(null) as? Array<*> ?: return false
        true
      } catch (_: Exception) {
        false
      }
    }
  }

  private fun hasOpenCLDevice(): Boolean = try {
    System.loadLibrary("OpenCL")
    true
  } catch (_: UnsatisfiedLinkError) {
    false
  }
}
