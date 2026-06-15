package com.gguf.zerocopy.data.repository

import android.content.Context
import android.net.Uri
import com.gguf.zerocopy.domain.inference.EngineType
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private val VALID_EXTENSIONS = setOf("gguf", "mnn", "tflite", "litertlm")

private fun engineForExt(ext: String): EngineType = when (ext) {
  "gguf" -> EngineType.LLAMA_CPP
  "mnn" -> EngineType.MNN
  else -> EngineType.LITER_T
}

private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"
private val GGML_MAGIC = byteArrayOf(0x47, 0x47, 0x4D, 0x4C) // "GGML"
private val TFLITE1_MAGIC = byteArrayOf(0x1A, 0x00, 0x00, 0x00)
private val TFLITE2_MAGIC = byteArrayOf(0x54, 0x46, 0x4C, 0x33) // "TFL3"

fun isValidModelFile(file: File): Boolean {
  if (!file.isFile || file.length() < 8) return false
  return try {
    RandomAccessFile(file, "r").use { raf ->
      val header = ByteArray(4)
      raf.readFully(header)
      header.contentEquals(GGUF_MAGIC) ||
        header.contentEquals(GGML_MAGIC) ||
        header.contentEquals(TFLITE1_MAGIC) ||
        header.contentEquals(TFLITE2_MAGIC) ||
        file.extension.lowercase() == "litertlm"
    }
  } catch (_: Exception) {
    false
  }
}

data class LocalModel(
  val id: String,
  val name: String,
  val path: String,
  val format: String,
  val engine: EngineType,
  val sizeBytes: Long = 0,
  val addedAt: Long = System.currentTimeMillis(),
  val lastUsed: Long = 0
) {
  val sizeFormatted: String get() =
    when {
      sizeBytes >= 1L shl 30 -> "%.1f GB".format(sizeBytes.toDouble() / (1 shl 30))
      sizeBytes >= 1L shl 20 -> "%.1f MB".format(sizeBytes.toDouble() / (1 shl 20))
      sizeBytes >= 1L shl 10 -> "%.1f KB".format(sizeBytes.toDouble() / (1 shl 10))
      else -> "$sizeBytes B"
    }
}

data class DownloadableModel(
  val id: String,
  val name: String,
  val hfRepo: String,
  val hfFile: String,
  val format: String,
  val engine: EngineType,
  val sizeBytes: Long,
  val description: String,
  val requires: String = ""
)

object ModelDownloads {
  val models =
    listOf(
      DownloadableModel(
        "zaya1-8b-q4",
        "Zaya1 8B Q4_K_M",
        "Zaya-AI/Zaya1-8B-GGUF",
        "zaya1-8b-q4_k_m.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        4_600_000_000L,
        "Latest Zaya1 8B model, optimized for on-device"
      ),
      DownloadableModel(
        "zaya1-8b-q8",
        "Zaya1 8B Q8_0",
        "Zaya-AI/Zaya1-8B-GGUF",
        "zaya1-8b-q8_0.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        8_000_000_000L,
        "Zaya1 8B at higher quality, needs 8GB+ RAM"
      ),
      DownloadableModel(
        "gemma-4-2b",
        "Gemma 4 2B",
        "google/gemma-4-2b-it-GGUF",
        "gemma-4-2b-it-q4_k_m.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        1_200_000_000L,
        "Google's latest compact model"
      ),
      DownloadableModel(
        "gemma-4-9b",
        "Gemma 4 9B",
        "google/gemma-4-9b-it-GGUF",
        "gemma-4-9b-it-q4_k_m.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        5_200_000_000L,
        "Google's flagship on-device model"
      ),
      DownloadableModel(
        "qwen3-1b",
        "Qwen3 1B",
        "Qwen/Qwen3-1B-GGUF",
        "qwen3-1b-q4_k_m.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        700_000_000L,
        "Lightweight, fast for low-end devices"
      ),
      DownloadableModel(
        "qwen3-8b",
        "Qwen3 8B",
        "Qwen/Qwen3-8B-GGUF",
        "qwen3-8b-q4_k_m.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        4_800_000_000L,
        "Best quality/speed balance"
      ),
      DownloadableModel(
        "llama-3.2-1b",
        "Llama 3.2 1B",
        "lmstudio-community/Llama-3.2-1B-Instruct-GGUF",
        "Llama-3.2-1B-Instruct-q4_k_m.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        700_000_000L,
        "Meta's efficient 1B model"
      ),
      DownloadableModel(
        "llama-3.2-3b",
        "Llama 3.2 3B",
        "lmstudio-community/Llama-3.2-3B-Instruct-GGUF",
        "Llama-3.2-3B-Instruct-q4_k_m.gguf",
        "gguf",
        EngineType.LLAMA_CPP,
        2_000_000_000L,
        "Best for phones with 4GB+ RAM"
      )
    )

  fun recommendForRam(availableRamMB: Long): List<DownloadableModel> = when {
    availableRamMB < 2000 -> models.filter { it.sizeBytes < 1_000_000_000L }
    availableRamMB < 4000 -> models.filter { it.sizeBytes < 3_000_000_000L }
    availableRamMB < 6000 -> models.filter { it.sizeBytes < 5_000_000_000L }
    else -> models
  }
}

class ModelRepository(private val context: Context) {
  private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
  private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
  val models: Flow<List<LocalModel>> = _models.asStateFlow()

  init {
    scanModels()
  }

  fun scanModels() {
    val files = modelsDir.listFiles() ?: emptyArray()
    _models.value =
      (
        files.filter {
          it.isFile &&
            it.extension.lowercase() in VALID_EXTENSIONS &&
            isValidModelFile(it)
        } +
          files.filter { it.isDirectory && File(it, "config.json").exists() }
            .map { dir ->
              val entries = dir.listFiles() ?: emptyArray()
              val dataFile = entries.find { it.extension.lowercase() in VALID_EXTENSIONS }
              dataFile ?: dir
            }
        )
        .map { file ->
          val ext = if (file.isDirectory) "mnn" else file.extension.lowercase()
          LocalModel(
            id = "${file.name}_${file.lastModified()}",
            name = file.name,
            path = file.absolutePath,
            format = ext,
            engine = engineForExt(ext),
            sizeBytes =
            if (file.isDirectory) {
              file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
              file.length()
            },
            addedAt = file.lastModified(),
            lastUsed = file.lastModified()
          )
        }.sortedByDescending { it.lastUsed }
  }

  suspend fun importUri(uri: Uri, filename: String): Result<LocalModel> =
    withContext(Dispatchers.IO) {
      try {
        val file = File(modelsDir, filename)
        context.contentResolver.openInputStream(uri)?.use { input ->
          FileOutputStream(file).use { output ->
            input.copyTo(output, bufferSize = 8 * 1024 * 1024)
          }
        }
        val ext = filename.substringAfterLast('.').lowercase()
        val model =
          LocalModel(
            id = "${filename}_${System.currentTimeMillis()}",
            name = filename,
            path = file.absolutePath,
            format = ext,
            engine = engineForExt(ext),
            sizeBytes = file.length()
          )
        scanModels()
        Result.success(model)
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  fun downloadFromHf(
    repo: String,
    filename: String,
    onProgress: (Float) -> Unit,
    onCancel: () -> Boolean = { false }
  ): DownloadTask {
    val task = DownloadTask(modelsDir, repo, filename, onProgress, onCancel, ::scanModels)
    task.start()
    return task
  }

  class DownloadTask internal constructor(
    private val modelsDir: File,
    private val repo: String,
    private val filename: String,
    private val onProgress: (Float) -> Unit,
    private val onCancel: () -> Boolean,
    private val onComplete: () -> Unit
  ) {
    @Volatile
    var isRunning = false
      private set

    @Volatile
    var result: Result<LocalModel>? = null
      private set

    private val partFile: File get() = File(modelsDir, "$filename.part")

    fun start() {
      isRunning = true
      Thread {
        try {
          result = download()
        } catch (e: Exception) {
          result = Result.failure(e)
        }
        isRunning = false
      }.apply { isDaemon = true }.start()
    }

    fun cancel() {
      isRunning = false
    }

    private fun download(): Result<LocalModel> {
      val url = URL("https://huggingface.co/$repo/resolve/main/$filename")
      var totalBytes = -1L
      var bytesRead = 0L

      val existingPart = if (partFile.exists()) partFile.length() else 0L

      try {
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
          connectTimeout = 30000
          readTimeout = 60000
          setRequestProperty("User-Agent", "ZeroCopy-Android/8.0")
          if (existingPart > 0) {
            setRequestProperty("Range", "bytes=$existingPart-")
          }
        }
        conn.connect()

        val contentLength = conn.contentLengthLong
        totalBytes = if (contentLength > 0 && existingPart > 0) {
          contentLength + existingPart
        } else if (contentLength > 0) {
          contentLength
        } else {
          -1
        }

        bytesRead = existingPart

        val outputStream = FileOutputStream(partFile, existingPart > 0)
        conn.inputStream.use { input ->
          outputStream.use { output ->
            val buffer = ByteArray(8 * 1024)
            var bytes = input.read(buffer)
            while (bytes >= 0 && isRunning && !onCancel()) {
              output.write(buffer, 0, bytes)
              bytesRead += bytes
              if (totalBytes > 0) onProgress(bytesRead.toFloat() / totalBytes)
              bytes = input.read(buffer)
            }
          }
        }

        if (!isRunning || onCancel()) {
          return Result.failure(Exception("Download cancelled"))
        }

        val finalFile = File(modelsDir, filename)
        partFile.renameTo(finalFile)

        val ext = filename.substringAfterLast('.').lowercase()
        val model = LocalModel(
          id = "${filename}_${System.currentTimeMillis()}",
          name = filename,
          path = finalFile.absolutePath,
          format = ext,
          engine = engineForExt(ext),
          sizeBytes = finalFile.length()
        )
        onComplete()
        return Result.success(model)
      } catch (e: Exception) {
        if (e.message == "Download cancelled") throw e
        return Result.failure(e)
      }
    }
  }

  fun deleteModel(id: String) {
    val model = _models.value.find { it.id == id } ?: return
    File(model.path).delete()
    scanModels()
  }

  fun markUsed(id: String) {
    _models.value =
      _models.value.map {
        if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it
      }
  }

  fun getModel(id: String): LocalModel? = _models.value.find { it.id == id }

  fun getModelByPath(path: String): LocalModel? = _models.value.find { it.path == path }
}
