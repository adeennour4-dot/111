package com.gguf.zerocopy.ui.models

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.domain.inference.BenchmarkResult
import com.gguf.zerocopy.domain.inference.EngineType
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.RustCore
import com.gguf.zerocopy.ui.components.IdentityBorderBrush
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelListScreen(
  onModelSelected: (path: String, name: String) -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val colors = currentPalette()
  val models by app.modelRepository.models.collectAsState(initial = emptyList())
  var loading by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  var modelToDelete by remember { mutableStateOf<LocalModel?>(null) }
  var modelToDetail by remember { mutableStateOf<LocalModel?>(null) }
  var engineSwitchWarningModel by remember { mutableStateOf<LocalModel?>(null) }
  var longPressModel by remember { mutableStateOf<LocalModel?>(null) }
  var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }
  var benchmarking by remember { mutableStateOf(false) }
  var tokenConfigModel by remember { mutableStateOf<LocalModel?>(null) }
  var reloading by remember { mutableStateOf(false) }
  var loadingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
  var loadCancelRequested by remember { mutableStateOf(false) }
  var loadingStep by remember { mutableStateOf("") }
  var pendingImport by remember { mutableStateOf(false) }
  var importWarning by remember { mutableStateOf<String?>(null) }
  var loadError by remember { mutableStateOf<String?>(null) }
  var loadErrorModel by remember { mutableStateOf<LocalModel?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }

  /** Validate an imported model and set importWarning if issues found. */
  fun validateImportedModel(model: com.gguf.zerocopy.data.repository.LocalModel) {
    val warnings = mutableListOf<String>()

    // 1. Check for .sha256 checksum file alongside the model
    val modelFile = java.io.File(model.path)
    val checksumFile = java.io.File(modelFile.parentFile, modelFile.name + ".sha256")
    if (checksumFile.exists()) {
      try {
        val expected = checksumFile.readText().trim().split(" ").firstOrNull() ?: ""
        if (expected.length == 64) {
          val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(modelFile.readBytes())
            .joinToString("") { "%02x".format(it) }
          if (!actual.equals(expected, ignoreCase = true)) {
            warnings.add("⚠ Checksum mismatch — file may be corrupted or tampered")
          }
        }
      } catch (_: Exception) {}
    }

    // 2. Check for mmproj file for vision-capable GGUF models
    if (model.format.equals("gguf", ignoreCase = true)) {
      val mmprojCandidates = listOf(
        java.io.File(modelFile.parentFile, modelFile.nameWithoutExtension + ".mmproj"),
        java.io.File(modelFile.parentFile, "mmproj-model-f16.gguf"),
        java.io.File(modelFile.parentFile, "mmproj-${modelFile.name}")
      )
      val mmprojFound = mmprojCandidates.any { it.exists() }
      if (!mmprojFound) {
        // Check if there's a global mmproj path set — if not, it might be a vision model
        val globalMmproj = com.gguf.zerocopy.data.local.SettingsManager.mmprojPath
        if (globalMmproj.isNotEmpty() && !java.io.File(globalMmproj).exists()) {
          warnings.add("⚠ Configured mmproj file not found at: $globalMmproj")
        }
      }
    }

    // 3. Check file size sanity
    if (model.sizeBytes > 0 && model.sizeBytes < 1_000_000) {
      warnings.add("⚠ Model file is very small (< 1MB) — may be invalid or truncated")
    }

    importWarning = if (warnings.isNotEmpty()) warnings.joinToString("\n") else null
  }

  val activeEngine = app.engineManager.getActiveEngine()
  val isModelLoaded = activeEngine?.isModelLoaded == true
  val loadedModelPath = if (isModelLoaded) activeEngine?.loadedModelPath else null

  val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data?.data?.let { uri ->
        val name = getFileName(context, uri)
        loading = true
        scope.launch {
          app.modelRepository.importUri(uri, name)
            .onSuccess { model ->
              loading = false
              // Validate imported model
              validateImportedModel(model)
              // Show settings dialog before loading
              tokenConfigModel = model
              pendingImport = true
            }
            .onFailure { error ->
              loading = false
              scope.launch {
                snackbarHostState.showSnackbar("Import failed: ${error.message?.take(100) ?: "Unknown error"}")
              }
            }
        }
      }
    }
  }

  fun handleModelTap(model: LocalModel) {
    // Abort any running inference before touching the model.
    // If inference is still active when we free the C++ globals
    // (model, context, sampler) via unloadModel/loadModel, the
    // inference thread will crash with a use-after-free.
    val runningEngine = app.engineManager.getActiveEngine()
    if (runningEngine != null && !runningEngine.isInferenceDone()) {
      runningEngine.abortInference()
    }
    if (loadedModelPath == model.path && activeEngine != null) {
      // Already loaded — just focus without reloading or unloading.
      onModelSelected(model.path, model.name)
      return
    }
    val targetEngine = app.engineManager.selectEngineForFormat(model.path)
    if (isModelLoaded && activeEngine != targetEngine) {
      engineSwitchWarningModel = model
      return
    }
    // Show settings dialog if no per-model config exists yet
    val existingCfg = SettingsManager.getModelTokenConfig(model.path)
    if (existingCfg == null) {
      tokenConfigModel = model
      pendingImport = true
      return
    }
    isLoading = true
    loadCancelRequested = false
    loadingStep = "Loading model…"
    val jobId = app.jobManager.register(
      label = "Load ${model.name}",
      category = com.gguf.zerocopy.domain.inference.JobManager.JobCategory.MODEL_LOAD
    )
    loadingJob = scope.launch {
      // Wait for inference to actually stop before freeing C++ globals
      // (unloadModel inside loadModel).  The abort signal is async.
      val eng = app.engineManager.getActiveEngine()
      if (eng != null && !eng.isInferenceDone()) {
        var waited = 0
        while (!eng.isInferenceDone() && waited < 2000) {
          delay(50); waited += 50
        }
      }
      val err = loadModel(model, onModelSelected)
      isLoading = false
      loadingJob = null
      loadingStep = ""
      app.jobManager.unregister(jobId)
      if (err != null) { loadError = err; loadErrorModel = model }
    }
  }

  fun confirmEngineSwitch(model: LocalModel) {
    engineSwitchWarningModel = null
    isLoading = true
    loadCancelRequested = false
    loadingStep = "Switching engine…"
    val jobId = app.jobManager.register(
      label = "Switch to ${model.name}",
      category = com.gguf.zerocopy.domain.inference.JobManager.JobCategory.MODEL_LOAD
    )
    loadingJob = scope.launch {
      // Abort running inference inside the coroutine so we don't block the
      // main thread with a spin-wait (would cause ANR).
      val runningEngine = app.engineManager.getActiveEngine()
      if (runningEngine != null && !runningEngine.isInferenceDone()) {
        runningEngine.abortInference()
        var waited = 0
        while (!runningEngine.isInferenceDone() && waited < 2000) {
          delay(50); waited += 50
        }
      }
      app.engineManager.unloadAll()
      val err = loadModel(model, onModelSelected)
      isLoading = false
      loadingJob = null
      loadingStep = ""
      app.jobManager.unregister(jobId)
      if (err != null) { loadError = err; loadErrorModel = model }
    }
  }

  fun confirmDelete(model: LocalModel) {
    scope.launch {
      if (loadedModelPath == model.path) {
        val engine = app.engineManager.getActiveEngine()
        if (engine != null && !engine.isInferenceDone()) {
          engine.abortInference()
          var waited = 0
          while (!engine.isInferenceDone() && waited < 2000) {
            delay(50); waited += 50
          }
        }
        engine?.unloadModel()
      }
      app.modelRepository.deleteModel(model.id)
      modelToDelete = null
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Models", fontWeight = FontWeight.Bold, color = colors.Text) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
          }
        },
        actions = {
          IconButton(onClick = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE)
              type = "*/*"
              putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
            }
            filePicker.launch(intent)
          }) {
            Icon(Icons.Filled.Add, "Import", tint = colors.Accent)
          }
          IconButton(onClick = { app.modelRepository.scanModels() }) {
            Icon(Icons.Filled.Refresh, "Scan", tint = colors.Accent2)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = colors.Bg
  ) { pad ->
    Box(modifier = Modifier.padding(pad).fillMaxSize()) {
      if (models.isEmpty() && !loading) {
        Column(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            Icons.Outlined.SmartToy,
            null,
            modifier = Modifier.size(48.dp),
            tint = colors.Text3
          )
          Spacer(Modifier.height(16.dp))
          Text("No models found. Import a model file to get started.", color = colors.Text3, fontSize = 14.sp)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(models, key = { it.id }) { model ->
            val isThisLoaded = loadedModelPath == model.path
            ModelCard(
              model = model,
              isLoaded = isThisLoaded,
              onClick = { handleModelTap(model) },
              onLongClick = { longPressModel = model },
              onGearClick = { tokenConfigModel = model }
            )
          }
        }
      }

      // ── Accessibility announcement for loading state ──
      if (loading || isLoading || reloading) {
        com.gguf.zerocopy.ui.common.AccessibilityAnnouncement(
          if (loadingStep.isNotEmpty()) loadingStep else "Loading model"
        )
      }

      // ── Loading overlay with progress and cancel ──
      if (loading || isLoading || reloading) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(colors.Bg.copy(alpha = 0.85f)),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(
              modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(4.dp),
              color = colors.Accent,
              trackColor = colors.Border
            )
            Spacer(Modifier.height(12.dp))
            Text(
              loadingStep.ifEmpty { "Loading…" },
              fontSize = 12.sp,
              color = colors.Text2,
              fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(16.dp))
            TextButton(shape = RoundedCornerShape(50), onClick = {
              loadCancelRequested = true
              loadingJob?.cancel()
              loadingJob = null
              isLoading = false
              reloading = false
              loadingStep = ""
              // If model was partially loaded, unload it
              val engine = app.engineManager.getActiveEngine()
              if (engine?.loadedModelPath != null) {
                try { engine.unloadModel() } catch (_: Exception) {}
              }
            }) {
              Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel loading",
                modifier = Modifier.size(16.dp),
                tint = colors.Red
              )
              Spacer(Modifier.width(4.dp))
              Text(
                "Cancel",
                fontSize = 12.sp,
                color = colors.Red,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      }

      // ── Token config dialog ────────────────────────────────────────────
      tokenConfigModel?.let { model ->
        val deviceInfo = remember { app.deviceUtils.detect() }
        val curCfg = model.path.let { path ->
          SettingsManager.getModelTokenConfig(path)
        }
        val isGguf = model.format.equals("gguf", ignoreCase = true)
        val fileSizeMB = if (model.sizeBytes > 0) model.sizeBytes / (1024f * 1024f) else 100f
        val isFreshImport = pendingImport
        val modelCtxLen = if (isGguf) {
          com.gguf.zerocopy.domain.invent.GgufMetaReader.readContextLength(model.path) ?: 32768
        } else 32768
        ModelTokenConfigDialog(
          modelName = model.name,
          modelFileSizeMB = fileSizeMB,
          totalRamMB = deviceInfo.totalRamMB.toInt(),
          isGguf = isGguf,
          modelContextLength = modelCtxLen,
          initial = curCfg ?: SettingsManager.ModelTokenConfig(
            ctx = 1024, maxNew = 1024, gpuLayers = 0
          ),
          onSave = { cfg ->
            SettingsManager.setModelTokenConfig(model.path, cfg)
            tokenConfigModel = null
            pendingImport = false
            // Load model after config (fresh import or reload)
            if (isFreshImport || loadedModelPath == model.path) {
              if (!isFreshImport || com.gguf.zerocopy.data.local.SettingsManager.autoLoadAfterImport) {
                reloading = true
                scope.launch {
                  if (loadedModelPath == model.path) app.engineManager.unloadAll()
                  val err = loadModel(model, onModelSelected)
                  reloading = false
                  if (err != null) { loadError = err; loadErrorModel = model }
                }
              }
            }
          },
          onDismiss = {
            tokenConfigModel = null
            if (isFreshImport) {
              pendingImport = false
              if (com.gguf.zerocopy.data.local.SettingsManager.autoLoadAfterImport) {
                scope.launch {
                  val err = loadModel(model, onModelSelected)
                  if (err != null) { loadError = err; loadErrorModel = model }
                }
              }
            }
          },
          onRemove = {
            SettingsManager.removeModelTokenConfig(model.path)
            tokenConfigModel = null
            pendingImport = false
            // Don't load — user chose to remove config
          }
        )
      }

      longPressModel?.let { model ->
        DropdownMenu(
          expanded = true,
          onDismissRequest = { longPressModel = null },
          modifier = Modifier.background(colors.Card)
        ) {
          DropdownMenuItem(
            text = { Text("Details", color = colors.Text, fontSize = 14.sp) },
            onClick = {
              longPressModel = null
              modelToDetail = model
            },
            leadingIcon = {
              Icon(Icons.Filled.Info, null, tint = colors.Accent2, modifier = Modifier.size(18.dp))
            }
          )
          if (activeEngine?.isModelLoaded == true && activeEngine?.loadedModelPath == model.path) {
            HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
            DropdownMenuItem(
              text = { Text("Benchmark", color = colors.Text, fontSize = 14.sp) },
              onClick = {
                longPressModel = null
                benchmarking = true
                benchmarkResult = null
                scope.launch {
                  val engine = app.engineManager.getActiveEngine()
                  val result = withContext(Dispatchers.IO) {
                    engine?.benchmark(128, 128)
                  }
                  benchmarkResult = result
                  benchmarking = false
                }
              },
              leadingIcon = {
                Icon(Icons.Filled.Refresh, null, tint = colors.Accent2, modifier = Modifier.size(18.dp))
              }
            )
          }
          HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
          DropdownMenuItem(
            text = { Text("Reload", color = colors.Accent2, fontSize = 14.sp) },
            onClick = {
              longPressModel = null
              reloading = true
              scope.launch {
                app.engineManager.unloadAll()
                val err = loadModel(model, onModelSelected)
                reloading = false
                if (err != null) { loadError = err; loadErrorModel = model }
              }
            },
            leadingIcon = {
              Icon(Icons.Filled.Refresh, null, tint = colors.Accent2, modifier = Modifier.size(18.dp))
            }
          )
          HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
          DropdownMenuItem(
            text = { Text("Delete", color = colors.Red, fontSize = 14.sp) },
            onClick = {
              longPressModel = null
              modelToDelete = model
            },
            leadingIcon = {
              Icon(Icons.Filled.Delete, null, tint = colors.Red, modifier = Modifier.size(18.dp))
            }
          )
        }
      }

      modelToDelete?.let { model ->
        AlertDialog(
          onDismissRequest = { modelToDelete = null },
          containerColor = colors.Card,
          title = { Text("Delete Model?", color = colors.Text, fontSize = 16.sp) },
          text = {
            Text("Remove ${model.name} from device? This cannot be undone.", color = colors.Text2, fontSize = 14.sp)
          },
          confirmButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { confirmDelete(model) }) {
              Text("Delete", color = colors.Red)
            }
          },
          dismissButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { modelToDelete = null }) {
              Text("Cancel", color = colors.Text2)
            }
          }
        )
      }

      modelToDetail?.let { model ->
        AlertDialog(
          onDismissRequest = { modelToDetail = null },
          containerColor = colors.Card,
          title = { Text("Model Details", color = colors.Text, fontWeight = FontWeight.Bold) },
          text = {
            Column {
              DetailRow("Name", model.name)
              DetailRow("Format", model.format.uppercase())
              DetailRow("Engine", model.engine.id)
              DetailRow("Size", model.sizeFormatted)
              if (model.isMoE) {
                DetailRow(
                  "Architecture",
                  if (model.expertUsedCount > 0) "MoE · ${model.expertCount} experts, ${model.expertUsedCount} active"
                  else "MoE · ${model.expertCount} experts"
                )
              }

              DetailRow(
                "Added",
                SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(model.addedAt))
              )
              if (model.lastUsed > 0) {
                DetailRow(
                  "Last used",
                  SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(model.lastUsed))
                )
              }
              DetailRow("Path", model.path)
            }
          },
          confirmButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = {
              modelToDetail = null
              handleModelTap(model)
            }) {
              Text(if (loadedModelPath == model.path) "Unload" else "Load", color = colors.Accent)
            }
          },
          dismissButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { modelToDetail = null }) {
              Text("Close", color = colors.Text2)
            }
          }
        )
      }

      engineSwitchWarningModel?.let { model ->
        AlertDialog(
          onDismissRequest = { engineSwitchWarningModel = null },
          containerColor = colors.Card,
          title = { Text("Switch Engine?", color = colors.Amber, fontSize = 16.sp) },
          text = {
            Column {
              Text(
                "Another model is currently loaded by a different engine. Loading ${model.name} will unload the current model.",
                color = colors.Text2,
                fontSize = 14.sp
              )
            }
          },
          confirmButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { confirmEngineSwitch(model) }) {
              Text("Switch", color = colors.Accent)
            }
          },
          dismissButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { engineSwitchWarningModel = null }) {
              Text("Cancel", color = colors.Text2)
            }
          }
        )
      }

      // ── Import warning dialog ──────────────────────────────────────
      importWarning?.let { warning ->
        AlertDialog(
          onDismissRequest = { importWarning = null },
          containerColor = colors.Card,
          title = { Text("Import Warnings", color = colors.Amber, fontSize = 16.sp) },
          text = {
            Column {
              warning.split("\n").forEach { line ->
                if (line.isNotBlank()) {
                  Text(
                    line,
                    color = colors.Text2,
                    fontSize = 13.sp
                  )
                  Spacer(Modifier.height(6.dp))
                }
              }
            }
          },
          confirmButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { importWarning = null }) {
              Text("OK", color = colors.Accent)
            }
          }
        )
      }

      // ── Model load error dialog with Retry with safe settings ────
      loadError?.let { err ->
        AlertDialog(
          onDismissRequest = { loadError = null; loadErrorModel = null },
          containerColor = colors.Card,
          title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Filled.Warning, null, tint = colors.Red, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(8.dp))
              Text("Model Load Failed", color = colors.Red, fontWeight = FontWeight.Bold)
            }
          },
          text = {
            Column {
              Text(err, color = colors.Text2, fontSize = 13.sp)
              Spacer(Modifier.height(12.dp))
              Text(
                "Possible causes:",
                color = colors.Text3,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
              )
              Spacer(Modifier.height(4.dp))
              Text(
                "• Model file may be corrupted or truncated\n" +
                "• Device may not have enough RAM (try a smaller model)\n" +
                "• Context window may be too large for available memory",
                color = colors.Text3,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          },
          confirmButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = {
              val m = loadErrorModel
              loadError = null; loadErrorModel = null
              if (m != null) {
                // Apply safe settings: reduce context, threads, disable GPU
                val safeCfg = SettingsManager.ModelTokenConfig(
                  ctx = 512,
                  maxNew = 256,
                  gpuLayers = 0,
                  threads = 2,
                  lowRamMode = true
                )
                SettingsManager.setModelTokenConfig(m.path, safeCfg)
                isLoading = true
                loadCancelRequested = false
                loadingStep = "Retrying with safe settings…"
                loadingJob = scope.launch {
                  loadModel(m, onModelSelected)
                  isLoading = false; loadingJob = null; loadingStep = ""
                }
              }
            }) {
              Text("Retry with Safe Settings", color = colors.Accent)
            }
          },
          dismissButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { loadError = null; loadErrorModel = null }) {
              Text("Dismiss", color = colors.Text2)
            }
          }
        )
      }

      // ── Benchmark dialog ────────────────────────────────────────────
      if (benchmarking) {
        AlertDialog(
          onDismissRequest = { benchmarking = false },
          containerColor = colors.Card,
          title = { Text("Benchmarking…", color = colors.Text) },
          text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.Accent)
              Spacer(Modifier.width(12.dp))
              Text("Running benchmark (128 PP + 128 TG)…", color = colors.Text2, fontSize = 13.sp)
            }
          },
          confirmButton = {}
        )
      }

      benchmarkResult?.let { result ->
        AlertDialog(
          onDismissRequest = { benchmarkResult = null },
          containerColor = colors.Card,
          title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Filled.Refresh, null, tint = colors.Accent2, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text("Benchmark Results", color = colors.Text, fontWeight = FontWeight.Bold)
            }
          },
          text = {
            Column {
              DetailRow("Engine", result.engine)
              if (result.prefillTps > 0f) {
                Spacer(Modifier.height(8.dp))
                Text("Prefill", color = colors.Accent2, fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                DetailRow("  Time", "%.1f ms".format(result.prefillMs))
                DetailRow("  Tokens", "%d tokens".format(result.prefillTokens))
                DetailRow("  Speed", "%.1f t/s".format(result.prefillTps))
              }
              if (result.decodeTps > 0f) {
                Spacer(Modifier.height(8.dp))
                Text("Decode", color = colors.Accent, fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                DetailRow("  Time", "%.1f ms".format(result.decodeMs))
                DetailRow("  Tokens", "%d tokens".format(result.decodeTokens))
                DetailRow("  Speed", "%.1f t/s".format(result.decodeTps))
              }
            }
          },
          confirmButton = {
            TextButton(shape = RoundedCornerShape(50), onClick = { benchmarkResult = null }) {
              Text("Close", color = colors.Accent)
            }
          }
        )
      }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  val colors = currentPalette()
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
    Text("$label: ", fontSize = 12.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
    Text(value, fontSize = 12.sp, color = colors.Text, fontFamily = FontFamily.Monospace)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelCard(
  model: LocalModel,
  isLoaded: Boolean = false,
  onClick: () -> Unit,
  onLongClick: () -> Unit = {},
  onGearClick: () -> Unit = {}
) {
  val colors = currentPalette()
  val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick
      ),
    shape = RoundedCornerShape(16.dp),
    color = if (isLoaded) colors.CardLight else colors.Card,
    border = if (isLoaded) BorderStroke(0.2.dp, IdentityBorderBrush)
        else BorderStroke(0.2.dp, colors.Border.copy(alpha = 0.5f))
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            model.name,
            color = colors.Text,
            fontSize = 14.sp,
            fontWeight = if (isLoaded) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          Spacer(Modifier.width(8.dp))
          EngineBadge(engine = model.engine)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            model.format.uppercase(),
            fontSize = 10.sp,
            color = colors.Accent,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.width(8.dp))
          Text(
            model.sizeFormatted,
            fontSize = 10.sp,
            color = colors.Text3,
            fontFamily = FontFamily.Monospace
          )
          // Estimated RAM needed (model file size × ~1.3x overhead)
          if (model.sizeBytes > 0) {
            val estRamMB = (model.sizeBytes.toFloat() * 1.3f / (1024f * 1024f)).roundToInt()
            Spacer(Modifier.width(8.dp))
            Text(
              "~${estRamMB}MB RAM",
              fontSize = 10.sp,
              color = colors.Text3,
              fontFamily = FontFamily.Monospace
            )
          }
          // Vision-capable badge (mmproj auto-detected next to the model or via global config)
          if (model.format.equals("gguf", ignoreCase = true) &&
              (findMmprojFor(model.path) != null || com.gguf.zerocopy.data.local.SettingsManager.mmprojPath.isNotEmpty())) {
            Spacer(Modifier.width(6.dp))
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = colors.Amber.copy(alpha = 0.2f)
            ) {
              Text(
                "VISION",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                fontSize = 8.sp,
                color = colors.Amber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
              )
            }
          }
          // MoE badge
          if (model.isMoE) {
            Spacer(Modifier.width(6.dp))
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = colors.Purple.copy(alpha = 0.2f)
            ) {
              Text(
                if (model.expertUsedCount > 0) "MoE · ${model.expertUsedCount}/${model.expertCount} active"
                else "MoE",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                fontSize = 8.sp,
                color = colors.Purple,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
              )
            }
          }
          if (model.lastUsed > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
              dateFormat.format(Date(model.lastUsed)),
              fontSize = 10.sp,
              color = colors.Text3,
              fontFamily = FontFamily.Monospace
            )
          }
        }
      }
      // Gear icon for token config
      IconButton(
        onClick = onGearClick,
        modifier = Modifier.size(36.dp)
      ) {
        Icon(
          Icons.Filled.Settings,
          "Token config",
          tint = colors.Text3.copy(alpha = 0.6f),
          modifier = Modifier.size(18.dp)
        )
      }
      if (isLoaded) {
        IconButton(
          onClick = {
            val engine = ZeroCopyApp.instance.engineManager.getActiveEngine()
            if (engine?.loadedModelPath == model.path) engine?.unloadModel()
          },
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            Icons.Filled.Delete,
            "Unload",
            tint = colors.Red.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }
  }
}

@Composable
private fun EngineBadge(engine: EngineType) {
  val colors = currentPalette()
  val (label, badgeColor) = when (engine) {
    EngineType.LLAMA_CPP -> "GGUF" to colors.Purple
    EngineType.MNN -> "MNN" to colors.Accent2
    EngineType.LITER_T -> "TFLite" to colors.Amber
  }
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = badgeColor.copy(alpha = 0.2f)
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
      fontSize = 9.sp,
      color = badgeColor,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold
    )
  }
}

/** @return error message on failure, null on success */
private suspend fun loadModel(
  model: LocalModel,
  onModelSelected: (String, String) -> Unit,
  isCancelled: () -> Boolean = { false }
): String? {
  val app = ZeroCopyApp.instance
  val engine = app.engineManager.selectEngineForFormat(model.path)

  // Use per-model token config from SettingsManager (default: 1024 ctx, 1024 maxNew).
  // If no per-model config is set, fall back to device-suggested defaults.
  val deviceInfo = app.deviceUtils.detect()
  val estimatedParamsB = when {
    model.sizeBytes <= 0L -> 4f
    else -> (model.sizeBytes.toFloat() / 512_000_000f).coerceIn(0.5f, 72f)
  }

  // Read GGUF metadata for MoE detection and native context length
  val ggufInfo = if (model.format == "gguf") {
    com.gguf.zerocopy.domain.invent.GgufMetaReader.readInfo(model.path)
  } else null

  val isMoE = ggufInfo?.isMoE == true
  val expertCount = ggufInfo?.expertCount ?: 0
  val expertUsedCount = ggufInfo?.expertUsedCount ?: 0

  val perModelCfg = SettingsManager.getModelTokenConfig(model.path)
  val config = if (perModelCfg != null) {
    SettingsManager.toConfig(model.path)
  } else {
    deviceInfo.suggestConfig(
      modelSizeB = estimatedParamsB,
      isMoE = isMoE,
      expertCount = expertCount,
      expertUsedCount = expertUsedCount
    )
  }

  val tunedConfig = if (ggufInfo != null) {
    val nativeCtx = ggufInfo.contextLength ?: -1
    if (nativeCtx > 0 && (config.nCtx <= 0 || config.nCtx > nativeCtx)) {
      config.copy(nCtx = nativeCtx)
    } else config
  } else config

  // Apply RustCore optimizations if available
  val rustAdvice = RustCore.getMemoryAdvice()
  val rustThreadCfg = RustCore.optimizeThreads(
    modelSizeMB = (model.sizeBytes / (1024 * 1024)).toInt().coerceAtLeast(100),
    gpuLayers = tunedConfig.nGpuLayers
  )
  val optimizedConfig = tunedConfig.copy(
    // Reduce context under memory pressure
    nCtx = if (rustAdvice.shouldReduceContext && SettingsManager.getModelTokenConfig(model.path) == null) {
      (tunedConfig.nCtx / 2).coerceAtLeast(512)
    } else tunedConfig.nCtx,
    // Use Rust-suggested threads if user hasn't explicitly set them
    nThreads = if (rustAdvice.shouldReduceContext) rustThreadCfg.decodeThreads.coerceAtLeast(2) else tunedConfig.nThreads
  )

  Log.i("ModelList", "RustCore: pressure=${rustAdvice.underPressure} advThreads=${rustThreadCfg.decodeThreads}")

  engine.config = optimizedConfig
  engine.repeatPenalty = SettingsManager.toRepeatPenalty()
  engine.systemPrompt = SettingsManager.systemPrompt
  engine.chatTemplate = SettingsManager.chatTemplate

  // Auto-detect an mmproj (vision projector) file for GGUF models and load it
  // automatically. Common naming conventions: <model>.mmproj (HuggingFace),
  // mmproj-model-f16.gguf, or mmproj-<modelname>. Falls back to the globally
  // configured mmproj path if no sibling is found.
  engine.mmprojPath = if (model.format.equals("gguf", ignoreCase = true)) {
    findMmprojFor(model.path) ?: SettingsManager.mmprojPath
  } else SettingsManager.mmprojPath

  Log.i("ModelList", "mmproj for ${model.name}: ${engine.mmprojPath.ifEmpty { "none" }}")

  Log.i("ModelList", "Config for ${model.name}: " +
    "ctx=${tunedConfig.nCtx} maxTkns=${tunedConfig.maxNewTokens} " +
    "ram=${deviceInfo.totalRamMB/1024}GB modelSize=${String.format("%.1f", estimatedParamsB)}B" +
    if (perModelCfg != null) " (from per-model config)" else " (from device defaults)")

  val loadResult = try {
    withContext(Dispatchers.IO) {
      engine.loadModel(model.path)
    }
  } catch (e: Exception) {
    Log.e("ModelList", "Exception loading model: ${e.message}")
    return e.message ?: "Unknown error"
  }

  // Unwrap the inner Result
  val innerError = loadResult.exceptionOrNull()
  if (innerError != null) {
    Log.e("ModelList", "Failed to load model: ${innerError.message}")
    return innerError.message ?: "Unknown error"
  }

  if (isCancelled()) {
    engine.unloadModel()
    return null
  }

  app.modelRepository.markUsed(model.id)
  onModelSelected(model.path, model.name)
  return null
}

/** Auto-detect the mmproj (vision projector) file that belongs to a GGUF
 *  model, checking common sibling naming conventions. Returns null if none
 *  is found next to the model. */
private fun findMmprojFor(modelPath: String): String? {
  val modelFile = java.io.File(modelPath)
  val parent = modelFile.parentFile ?: return null
  val candidates = listOf(
    java.io.File(parent, modelFile.nameWithoutExtension + ".mmproj"),
    java.io.File(parent, "mmproj-model-f16.gguf"),
    java.io.File(parent, "mmproj-" + modelFile.name)
  )
  return candidates.firstOrNull { it.exists() && it.isFile }?.absolutePath
}

private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
  var name = "model.gguf"
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0) cursor.getString(idx)?.let { if (it.isNotEmpty()) name = it }
    }
  }
  if ('.' !in name) {
    val mime = context.contentResolver.getType(uri)
    name += when {
      mime?.contains("gguf") == true || mime == "application/octet-stream" -> ".gguf"
      mime?.contains("tensorflow") == true || mime?.contains("tflite") == true -> ".tflite"
      mime?.contains("litert") == true -> ".litertlm"
      else -> ".gguf"
    }
  }
  return name
}
