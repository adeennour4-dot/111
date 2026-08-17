package com.gguf.zerocopy.ui.settings
import com.gguf.zerocopy.ui.theme.ZcShape

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.domain.inference.BenchmarkResult
import com.gguf.zerocopy.domain.inference.EngineManager
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Dialog that shows all downloaded models, lets the user pick one,
 * runs a benchmark (128 prefill + 128 decode tokens), and displays results.
 */
@Composable
fun BenchmarkDialog(
    engineManager: EngineManager,
    models: List<LocalModel>,
    onDismiss: () -> Unit
) {
    val colors = currentPalette()
    val scope = rememberCoroutineScope()
    var selectedModel by remember { mutableStateOf<LocalModel?>(null) }
    var benchmarking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var runCount by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = {
            if (!benchmarking) onDismiss()
        },
        containerColor = colors.Card,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, null, tint = colors.Accent2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Benchmark", fontWeight = FontWeight.Bold, color = colors.Text, fontFamily = FontFamily.Monospace)
            }
        },
        text = {
            Column(
                Modifier
                    .widthIn(min = 300.dp, max = 420.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (models.isEmpty()) {
                    Text("No models downloaded yet.", color = colors.Text3, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    return@Column
                }

                // ── Model selector ──
                Text("Select a model:", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                models.forEach { model ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedModel?.id == model.id) colors.Accent.copy(alpha = 0.1f) else colors.Border.copy(alpha = 0.1f),
                        border = if (selectedModel?.id == model.id)
                            androidx.compose.foundation.BorderStroke(0.2.dp, colors.Accent.copy(0.5f)) else null
                    ) {
                        Column(Modifier.clickable { selectedModel = model }.padding(12.dp)) {
                            Text(model.name, fontSize = 12.sp, color = colors.Text, fontFamily = FontFamily.Monospace)
                            Text(model.sizeFormatted + " · " + model.format.uppercase(), fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                HorizontalDivider(color = colors.Border.copy(0.5f))

                // ── Run button ──
                Button(
                    shape = ZcShape.Pill,
                    onClick = {
                        selectedModel?.let { model ->
                            benchmarking = true
                            result = null
                            error = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    // Save currently loaded model so we can restore later
                                    val savedEngine = engineManager.getActiveEngine()
                                    val savedPath = savedEngine?.loadedModelPath ?: ""
                                    val savedWasLoaded = savedEngine?.isModelLoaded == true

                                    // Unload current model, load benchmark model
                                    engineManager.unloadAll()
                                    val engine = engineManager.selectEngineForFormat(model.path)
                                    val loadResult = engine.loadModel(model.path)
                                    if (loadResult.isSuccess) {
                                        // Use dynamic prefill tokens: 10% of context, min 128, max 1024
                                        val modelCtx = engine.config.nCtx.coerceAtLeast(512)
                                        val ppTokens = (modelCtx / 10).coerceIn(128, 1024)
                                        val benchResult = engine.benchmark(ppTokens, ppTokens)
                                        result = benchResult
                                        runCount++
                                        engine.unloadModel()
                                    } else {
                                        error = "Failed to load model: ${loadResult.exceptionOrNull()?.message}"
                                    }

                                    // Restore the user's previously loaded model
                                    if (savedWasLoaded && savedPath.isNotEmpty() && savedPath != model.path) {
                                        val restoreEngine = engineManager.selectEngineForFormat(savedPath)
                                        restoreEngine.loadModel(savedPath)
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "Benchmark failed"
                                } finally {
                                    benchmarking = false
                                }
                            }
                        }
                    },
                    enabled = selectedModel != null && !benchmarking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
                ) {
                    if (benchmarking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.Bg,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Benchmarking…", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    } else {
                        Text("Run Benchmark", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }

                // ── Error ──
                if (error != null) {
                    Surface(Modifier.fillMaxWidth(), shape = ZcShape.Sm, color = colors.Red.copy(0.08f)) {
                        Text(error!!, color = colors.Red, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(10.dp))
                    }
                }

                // ── Thermal awareness ──
                if (runCount >= 2) {
                    Text(
                        "⚠ Device may be warm — results may read lower than a cold run.",
                        fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
                    )
                }

                // ── Results ──
                result?.let { r ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.Surface
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Results", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                color = colors.Accent2, fontFamily = FontFamily.Monospace)
                            ResultRow("Engine", r.engine, colors)

                            if (r.prefillTps > 0f) {
                                // Prefill block — grouped, no per-row dividers
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Prefill", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                        color = colors.Accent2, fontFamily = FontFamily.Monospace)
                                    ResultRow("Tokens", "${r.prefillTokens} ctx", colors)
                                    ResultRow("Time", "%.0f ms".format(r.prefillMs), colors)
                                    ResultRow("Speed", "%.1f t/s".format(r.prefillTps), colors)
                                }
                            }

                            if (r.decodeTps > 0f) {
                                // Decode block — the decode speed is the headline number
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Decode", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                        color = colors.Accent2, fontFamily = FontFamily.Monospace)
                                    ResultRow("Tokens", "${r.decodeTokens} gen", colors)
                                    ResultRow("Time", "%.0f ms".format(r.decodeMs), colors)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Speed", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        Text("%.1f t/s".format(r.decodeTps), fontSize = 14.sp,
                                            color = colors.Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (r.prefillTps <= 0f && r.decodeTps <= 0f) {
                                Text("No benchmark data returned.", fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !benchmarking) {
                Text("Close", color = colors.Text2, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun ResultRow(label: String, value: String, colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 10.sp, color = colors.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}
