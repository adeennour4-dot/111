package com.gguf.zerocopy.domain.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
  fun suggestConfig(modelSizeB: Float = 7f, isMoE: Boolean = false, expertCount: Int = 0, expertUsedCount: Int = 0): InferenceConfig {
    val suggestedThreads =
      if (bigCores.isNotEmpty()) {
        bigCores.size.coerceIn(1, 8)
      } else {
        // If freq-based detection failed, assume 4 threads max for old devices
        (cpuCores / 2).coerceIn(2, 8)
      }

    // Exynos chips (Note 10 Lite = 9825, S23 FE = 2200) have Vulkan but
    // the NDK cross-compilation for llama.cpp Vulkan is broken on Exynos Mali.
    // Only suggest GPU layers on verified Snapdragon / Tensor with Vulkan.
    // MoE models: force CPU+mmap unless VRAM can hold ALL experts,
    // because GPU offload loads every expert into VRAM at once.
    val suggestedGpuLayers = when {
        isMoE -> 0  // mmap + CPU lets the OS page in only active experts
        hasVulkan && (isSnapdragon || isTensor) -> 99
        else -> 0
    }

    // Context window based on ACTIVE model size:
    //   - 1B  models → 4096 (room for search results)
    //   - 4B  models → 2048
    //   - 7B  models → 1024
    //   - 12B+ models → 512
    // For MoE models, the active parameter count is
    // total × (expert_used / expert_total), so the model can afford
    // a larger context than its file size suggests.
    val effectiveModelSizeB = if (isMoE && expertCount > 0 && expertUsedCount > 0)
        modelSizeB * (expertUsedCount.toFloat() / expertCount)
    else modelSizeB
    // Capped by total RAM so the system doesn't OOM.
    val maxCtxByRam = (totalRamMB * 0.6f).toInt().coerceIn(256, 16384)
    val suggestedCtx = when {
      effectiveModelSizeB <= 1f -> 4096
      effectiveModelSizeB <= 4f -> 2048
      effectiveModelSizeB <= 7f -> 1024
      else             -> 512
    }.coerceAtMost(maxCtxByRam)

    // Max new tokens = same as context (user expects room for long answers)
    val suggestedMaxNewTokens = suggestedCtx

    return InferenceConfig(
      nCtx = suggestedCtx,
      nBatch = suggestedCtx.coerceAtMost(2048).coerceAtLeast(128),
      maxNewTokens = suggestedMaxNewTokens,
      nGpuLayers = suggestedGpuLayers,
      nThreads = suggestedThreads,
      // Only enable low RAM mode on devices with ≤ 8 GB total RAM
      lowRamMode = totalRamMB <= 8000
    )
  }

  fun suggestEngine(): EngineType = when {
    // Snapdragon → llama.cpp with GPU layers (Vulkan on 8xx series)
    isSnapdragon -> EngineType.LLAMA_CPP
    // MediaTek → MNN (optimized for MediaTek's NeuroPilot / Edge AI)
    isMediaTek -> EngineType.MNN
    // Exynos → llama.cpp (MNN Mali backend is limited, llama.cpp works on CPU)
    isExynos -> EngineType.LLAMA_CPP
    // Tensor → llama.cpp (Google's TPU not exposed, CPU inference)
    isTensor -> EngineType.LLAMA_CPP
    // Fallback: llama.cpp on any other ARM CPU
    else -> EngineType.LLAMA_CPP
  }

  fun canFitModel(modelSizeGB: Float, safetyMargin: Float = 0.8f): Boolean {
    val requiredMB = modelSizeGB * 1024 * 1.2f
    return availableRamMB > requiredMB / safetyMargin
  }
}

class DeviceUtils(private val context: Context) {
  companion object {
    private const val TAG = "DeviceUtils"
  }

  fun detect(): DeviceInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    val totalRamMB = memInfo.totalMem / (1024 * 1024)
    val availableRamMB = memInfo.availMem / (1024 * 1024)
    val cpuCores = Runtime.getRuntime().availableProcessors()
    val cpuMaxFreq = readCpuMaxFreq()
    val bigCores = detectBigCores()
    val socModel = detectSocModel()

    return DeviceInfo(
      socModel = socModel,
      cpuCores = cpuCores,
      cpuMaxFreq = cpuMaxFreq,
      bigCores = bigCores,
      totalRamMB = totalRamMB,
      availableRamMB = availableRamMB,
      isSnapdragon = isSnapdragon(socModel),
      isExynos = isExynos(socModel),
      isMediaTek = isMediaTek(socModel),
      isTensor = isTensor(socModel),
      hasVulkan = hasVulkanDevice(),
      hasOpenCL = hasOpenCLDevice()
    )
  }

  /**
   * Recommended thread count for CPU inference, derived from the device's
   * high-frequency ("big") core count read via
   * /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq. Falls back to half
   * the available processors when frequency data is unavailable. No root needed.
   * This is a lightweight path (no Vulkan/OpenCL native-lib probing) so it is
   * safe to call during app startup.
   */
  fun recommendedThreads(): Int {
    val big = detectBigCores()
    return if (big.isNotEmpty()) {
      big.size.coerceIn(1, 8)
    } else {
      (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
    }
  }

  fun readCpuFreq(cpu: Int): Int = try {
    val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
    if (f.exists()) {
      f.readText().trim().toIntOrNull() ?: 0
    } else {
      // On emulators and some kernels, cpufreq directory might not exist.
      // Fall back to /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq
      val alt = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq")
      if (alt.exists()) alt.readText().trim().toIntOrNull() ?: 0 else 0
    }
  } catch (e: Exception) {
    Log.w(TAG, "Failed to read CPU freq for core $cpu: ${e.message}")
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
    // If we couldn't read any CPU frequencies (emulator, restricted kernel, etc.),
    // assume all cores are equal and return nothing (caller uses default threads).
    if (coreFreqs.isEmpty()) {
      Log.d(TAG, "Could not detect CPU frequencies — assuming homogeneous cores")
      return emptyList()
    }
    val maxFreq = coreFreqs.maxOf { it.second }
    val threshold = maxFreq * 80 / 100
    val big = coreFreqs.filter { it.second >= threshold }.map { it.first }
    Log.d(TAG, "Detected ${big.size} big cores out of ${coreFreqs.size}")
    return big
  }

  /**
   * Detect SoC model with robust fallback for older API levels.
   * Build.SOC_MODEL was added in API 31 (Android 12).
   * On API 29-30, fall back to Build.HARDWARE, then Build.BOARD.
   */
  private fun detectSocModel(): String {
    return if (Build.VERSION.SDK_INT >= 31) {
      Build.SOC_MODEL.ifEmpty { Build.HARDWARE }.ifEmpty { Build.BOARD }.lowercase()
    } else {
      Build.HARDWARE.ifEmpty { Build.BOARD }.ifEmpty { "unknown" }.lowercase()
    }
  }

  private fun isSnapdragon(soc: String): Boolean =
    soc.contains("snapdragon") ||
      soc.contains("qcom") ||
      soc.contains("kryo") ||
      soc.contains("sm8") || // Snapdragon 8xx (e.g. sm8750 = 8 Elite)
      soc.contains("sm7") || // Snapdragon 7xx
      soc.contains("sdm") || // Snapdragon 6xx/8xx (e.g. sdm845)
      soc.contains("sun") || // Snapdragon 8 Gen 1 (sun = taro)
      soc.contains("lahaina") || // Snapdragon 888
      soc.contains("waipio") // Snapdragon 8 Gen 1

  private fun isExynos(soc: String): Boolean =
    soc.contains("exynos") ||
      soc.contains("s5e") || // Exynos chip IDs (e.g. s5e9925 = Exynos 2200)
      soc.contains("universal")

  private fun isMediaTek(soc: String): Boolean =
    soc.contains("mt") ||
      soc.contains("dimensity") ||
      soc.contains("helio") ||
      soc.contains("kompanio")

  private fun isTensor(soc: String): Boolean =
    soc.contains("tensor") ||
      soc.contains("gs1") || // Google Tensor
      soc.contains("gs2") || // Google Tensor G2
      soc.contains("gs3") || // Google Tensor G3
      soc.contains("zuma") || // Google Tensor G4
      soc.contains("zumapro") // Google Tensor G5

  private fun hasVulkanDevice(): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= 29) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
      } else {
        // Fallback: check for Vulkan by trying to dlopen libvulkan.so
        try {
          System.loadLibrary("vulkan")
          true
        } catch (_: UnsatisfiedLinkError) {
          false
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Vulkan detection failed: ${e.message}")
      false
    }
  }

  /**
   * Detect OpenCL support by checking for the libOpenCL.so system library
   * and /dev/mali0 (Mali GPU) or /sys/class/opencl presence.
   */
  private fun hasOpenCLDevice(): Boolean {
    return try {
      // Check for the shared library
      System.loadLibrary("OpenCL")
      // Also verify /dev/mali0 exists for Mali GPUs (common on Exynos/MediaTek)
      if (File("/dev/mali0").exists()) return true
      // Check sysfs for OpenCL presence
      if (File("/sys/class/opencl").exists()) return true
      // On Snapdragon, check for Adreno OpenCL
      if (File("/system/vendor/lib/libOpenCL.so").exists() ||
          File("/vendor/lib/libOpenCL.so").exists()) return true
      false
    } catch (_: UnsatisfiedLinkError) {
      // Try alternate detection: check known OpenCL library paths
      try {
        System.loadLibrary("libOpenCL")
        true
      } catch (_: UnsatisfiedLinkError) {
        false
      }
    }
  }
}
