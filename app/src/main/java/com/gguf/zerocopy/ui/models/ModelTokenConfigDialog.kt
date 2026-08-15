package com.gguf.zerocopy.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.device.DeviceUtils
import com.gguf.zerocopy.ui.components.ZcPillButton
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlin.math.roundToInt

/**
 * Full per-model settings panel.
 * All fields are optional — null = "use global default from Settings".
 * Each control shows a checkbox/toggle to enable per-model override.
 */
@Composable
fun ModelTokenConfigDialog(
    modelName: String,
    modelFileSizeMB: Float,
    totalRamMB: Int,
    isGguf: Boolean = false,
    initial: SettingsManager.ModelTokenConfig = SettingsManager.ModelTokenConfig(
        ctx = 1024, maxNew = 1024, gpuLayers = 0
    ),
    onSave: (SettingsManager.ModelTokenConfig) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    /** Maximum context the loaded model supports (from GGUF metadata).
     *  Slider is capped to this value instead of 32768. */
    modelContextLength: Int = 32768
) {
    val colors = currentPalette()
    val context = LocalContext.current
    // Device-recommended thread count (big-core count) for the hint below.
    val recommendedThreads = remember {
        try { DeviceUtils(context).detect().bigCores.size.coerceIn(1, 8) } catch (_: Exception) { 4 }
    }
    // Flash Attention needs ARMv8.2-a dot-product / i8mm — disable the switch
    // itself (not just a caption) when this CPU can't use it.
    val flashSupported = remember { cpuSupportsFlashAttention() }

    // ── GPU layers enable (GGUF-specific) ──
    var enableGpu by remember { mutableStateOf(isGguf) }

    val ctxMax = modelContextLength.coerceIn(512, 32768)

    // ── Slider / text values ──
    var ctxSlider by remember { mutableIntStateOf(initial.ctx.coerceIn(512, ctxMax)) }
    var maxNewSlider by remember { mutableIntStateOf(initial.maxNew.coerceIn(64, 32768)) }
    var gpuSlider by remember { mutableIntStateOf(initial.gpuLayers.coerceIn(0, 99)) }
    var tempText by remember { mutableStateOf((initial.temperature ?: SettingsManager.temperature).toString()) }
    var topPText by remember { mutableStateOf((initial.topP ?: SettingsManager.topP).toString()) }
    var minPText by remember { mutableStateOf((initial.minP ?: SettingsManager.minP).toString()) }
    var topKText by remember { mutableStateOf((initial.topK ?: SettingsManager.topK).toString()) }
    var repPenText by remember { mutableStateOf((initial.repeatPenalty ?: SettingsManager.repeatPenalty).toString()) }
    var freqPenText by remember { mutableStateOf((initial.freqPenalty ?: SettingsManager.freqPenalty).toString()) }
    var presPenText by remember { mutableStateOf((initial.presPenalty ?: SettingsManager.presPenalty).toString()) }
    var seedText by remember { mutableStateOf((initial.seed ?: -1).toString()) }
    var flashSwitch by remember { mutableStateOf(initial.flashAttention ?: SettingsManager.flashAttention) }
    var lowRamSwitch by remember { mutableStateOf(initial.lowRamMode ?: SettingsManager.lowRamMode) }
    var threadsText by remember { mutableStateOf((initial.threads ?: SettingsManager.threads).toString()) }
    var batchText by remember { mutableStateOf((initial.nBatch ?: SettingsManager.nBatch).toString()) }
    // "auto" = inherit the global backend; "cpu"/"gpu" = explicit per-model override.
    var backendSel by remember { mutableStateOf(initial.backend ?: SettingsManager.backend) }

    // ── RAM calc ──
    val kvCacheMB by remember {
        derivedStateOf { (ctxSlider + maxNewSlider) * modelFileSizeMB * 0.000046f }
    }
    val totalEstMB by remember {
        derivedStateOf { modelFileSizeMB + kvCacheMB }
    }
    val ramOk by remember {
        derivedStateOf { totalEstMB < totalRamMB * 0.85f }
    }

    if (maxNewSlider > ctxSlider - 64) {
        maxNewSlider = (ctxSlider - 64).coerceAtLeast(64)
    }

    // Auto-clamp RAM
    LaunchedEffect(ctxSlider, maxNewSlider) {
        val maxAllowed = (totalRamMB * 0.85f) - modelFileSizeMB
        val maxTotalTokens = if (modelFileSizeMB > 0)
            (maxAllowed / (modelFileSizeMB * 0.000046f)).roundToInt() else 32768
        if (ctxSlider + maxNewSlider > maxTotalTokens) {
            val ratio = ctxSlider.toFloat() / (ctxSlider + maxNewSlider).toFloat()
            ctxSlider = ((maxTotalTokens * ratio).roundToInt()).coerceIn(512, ctxMax)
            maxNewSlider = (maxTotalTokens - ctxSlider).coerceIn(64, 32768)
        }
    }

    // ── Collect result ──
    fun buildConfig(): SettingsManager.ModelTokenConfig {
        return SettingsManager.ModelTokenConfig(
            ctx = ctxSlider,
            maxNew = maxNewSlider,
            gpuLayers = if (enableGpu) gpuSlider else 0,
            // Empty/non-parseable = null = use global default
            temperature = tempText.toFloatOrNull()?.coerceIn(0f, 2f),
            topP = topPText.toFloatOrNull()?.coerceIn(0f, 1f),
            minP = minPText.toFloatOrNull()?.coerceIn(0f, 1f),
            topK = topKText.toIntOrNull()?.coerceIn(1, 200),
            repeatPenalty = repPenText.toFloatOrNull()?.coerceIn(1f, 3f),
            freqPenalty = freqPenText.toFloatOrNull()?.coerceIn(0f, 2f),
            presPenalty = presPenText.toFloatOrNull()?.coerceIn(0f, 2f),
            seed = seedText.toIntOrNull(),
            flashAttention = flashSwitch,
            lowRamMode = lowRamSwitch,
            threads = threadsText.toIntOrNull()?.coerceIn(1, 16),
            nBatch = batchText.toIntOrNull()?.coerceIn(512, 8192),
            backend = if (backendSel == "auto") null else backendSel
        )
    }

    @Suppress("DEPRECATION")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.Card,
        shape = RoundedCornerShape(22.dp),
        title = {
            Column {
                Text("⚙  Per-Model Config", fontWeight = FontWeight.Bold,
                    color = colors.Text, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                Text(modelName, fontSize = 11.sp, color = colors.Text3,
                    fontFamily = FontFamily.Monospace, maxLines = 2)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(buildConfig()) },
                shape = RoundedCornerShape(50),
                enabled = ramOk,
                colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
            ) { Text("Save", fontFamily = FontFamily.Monospace,
                color = colors.Bg, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Text2)
            ) { Text("Cancel", fontFamily = FontFamily.Monospace) }
        },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HorizontalDivider(color = colors.Border)

                // ── Context + Max New (slider + editable number) ──
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Context window", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text("max ${ctxMax}", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        // Editable number alongside the slider value.
                        // Track raw text so the user can clear and re-type without
                        // the field snapping back to the previous value.
                        // Sync with slider: update text when slider moves externally,
                        // but only if the user isn't mid-edit (text is parseable).
                        var ctxText by remember { mutableStateOf(ctxSlider.toString()) }
                        val ctxTextFieldFocused = remember { mutableStateOf(false) }
                        // When the slider changes and the user isn't actively editing,
                        // sync the text field to match.
                        LaunchedEffect(ctxSlider) {
                            if (!ctxTextFieldFocused.value) {
                                ctxText = ctxSlider.toString()
                            }
                        }
                        OutlinedTextField(
                            value = ctxText,
                            onValueChange = { v: String ->
                                ctxText = v
                                val n = v.filter { it.isDigit() || it == '-' }.toIntOrNull()
                                // Only update slider when the typed value is a valid
                                // number within range — don't clamp mid-typing.
                                if (n != null && n >= 512 && n <= ctxMax) {
                                    ctxSlider = n
                                }
                            },
                            modifier = Modifier
                                .widthIn(min = 100.dp, max = 160.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .onFocusChanged { ctxTextFieldFocused.value = it.isFocused },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = colors.Text
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border,
                                cursorColor = colors.Accent,
                                focusedTextColor = colors.Text, unfocusedTextColor = colors.Text
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    Slider(
                        value = ctxSlider.toFloat(),
                        onValueChange = { v -> ctxSlider = v.roundToInt().coerceIn(512, ctxMax) },
                        valueRange = 512f..ctxMax.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = colors.Accent, activeTrackColor = colors.Accent, inactiveTrackColor = colors.Border),
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Tick marks — readable guides, not exact positions
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("512", fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        Text("8K", fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        Text("16K", fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        Text("24K", fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        Text("32K", fontSize = 7.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                }

                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Max new tokens", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        var maxNewText by remember { mutableStateOf(maxNewSlider.toString()) }
                        val maxNewTextFieldFocused = remember { mutableStateOf(false) }
                        LaunchedEffect(maxNewSlider) {
                            if (!maxNewTextFieldFocused.value) {
                                maxNewText = maxNewSlider.toString()
                            }
                        }
                        OutlinedTextField(
                            value = maxNewText,
                            onValueChange = { v: String ->
                                maxNewText = v
                                val n = v.filter { it.isDigit() || it == '-' }.toIntOrNull()
                                if (n != null && n >= 64) {
                                    val clamped = n.coerceIn(64, (ctxSlider - 64).coerceAtLeast(64))
                                    maxNewSlider = clamped
                                }
                            },
                            modifier = Modifier
                                .widthIn(min = 100.dp, max = 160.dp)
                                .onFocusChanged { maxNewTextFieldFocused.value = it.isFocused },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = colors.Text
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.Accent2, unfocusedBorderColor = colors.Border,
                                cursorColor = colors.Accent2,
                                focusedTextColor = colors.Text, unfocusedTextColor = colors.Text
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    Slider(
                        value = maxNewSlider.toFloat(),
                        onValueChange = { v -> maxNewSlider = v.roundToInt().coerceIn(64, ctxSlider - 64) },
                        valueRange = 64f..(ctxSlider - 64).coerceAtLeast(128).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = colors.Accent2, activeTrackColor = colors.Accent2, inactiveTrackColor = colors.Border),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── GPU layers (GGUF only) ──
                if (isGguf) {
                    CheckOverride("GPU layers", enableGpu, { enableGpu = it }, colors) {
                        SliderSection("", "${gpuSlider}", colors) {
                            Slider(
                                value = gpuSlider.toFloat(),
                                onValueChange = { v -> gpuSlider = v.roundToInt().coerceIn(0, 99) },
                                valueRange = 0f..99f,
                                colors = SliderDefaults.colors(thumbColor = colors.Purple, activeTrackColor = colors.Purple, inactiveTrackColor = colors.Border),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0 = CPU", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                            Text("99 = all layers", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = colors.Border.copy(0.1f)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, null, tint = colors.Text3, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GPU offload only for GGUF (llama.cpp)",
                                fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // ── Compute backend (per-model) ──
                Column {
                  Text("Compute Backend", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                  Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZcPillButton(onClick = { backendSel = "auto" }, modifier = Modifier.weight(1f), label = "Auto", tint = colors.Accent, ghost = backendSel != "auto")
                    ZcPillButton(onClick = { backendSel = "cpu" }, modifier = Modifier.weight(1f), label = "CPU", tint = colors.Accent, ghost = backendSel != "cpu")
                    ZcPillButton(onClick = { backendSel = "gpu" }, modifier = Modifier.weight(1f), label = "GPU", tint = colors.Accent, ghost = backendSel != "gpu")
                  }
                  Text(
                    when (backendSel) {
                      "cpu" -> "CPU only — ignores GPU Layers"
                      "gpu" -> "Offload all layers to GPU / Vulkan"
                      else -> "Auto — inherit global backend"
                    },
                    fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp)
                  )
                }

                HorizontalDivider(color = colors.Border.copy(0.5f))

                // ── Sampling ──
                SectionHeader("Sampling", colors)
                SamplingField("Temperature", "0-2", tempText, { tempText = it }, colors)
                SamplingField("Top-P", "0-1", topPText, { topPText = it }, colors)
                SamplingField("Min-P", "0-1", minPText, { minPText = it }, colors)
                SamplingField("Top-K", "1-200, 0=off", topKText, { topKText = it }, colors)

                // ── Penalties ──
                SectionHeader("Penalties", colors)
                SamplingField("Repeat", "1.0=off, >1 reduces repeats", repPenText, { repPenText = it }, colors)
                SamplingField("Freq", "0=off, penalizes freq tokens", freqPenText, { freqPenText = it }, colors)
                SamplingField("Presence", "0=off, penalizes seen tokens", presPenText, { presPenText = it }, colors)

                // ── Advanced ──
                SectionHeader("Advanced", colors)
                SamplingField("Seed", "-1=random", seedText, { seedText = it }, colors)

                SingleSwitch("Flash Attention", flashSwitch, { flashSwitch = it }, colors,
                    hint = if (flashSupported) "llama.cpp only — auto-disabled on ARMv8.2-a" else "Not supported on this CPU",
                    enabled = flashSupported)
                SingleSwitch("Low RAM mode", lowRamSwitch, { lowRamSwitch = it }, colors,
                    hint = "caps n_ctx to 2048, llama.cpp only")

                SamplingField("Threads", "1-16", threadsText, { threadsText = it }, colors)
                Text("Recommended: $recommendedThreads (big cores) for this device",
                    fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                SamplingField("Batch", "512-8192", batchText, { batchText = it }, colors)

                // ── RAM estimate ──
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                    color = if (ramOk) colors.Surface else colors.Red.copy(alpha = 0.08f),
                    border = if (!ramOk) androidx.compose.foundation.BorderStroke(0.2.dp, colors.Red.copy(0.5f)) else null
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("📊  RAM Estimate", fontSize = 10.sp, color = colors.Text3,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        RamRow("Model", "${String.format("%.1f", modelFileSizeMB)} MB", colors.Text2, colors)
                        RamRow("KV Cache", "${String.format("%.0f", kvCacheMB)} MB", colors.Text2, colors)
                        HorizontalDivider(color = colors.Border.copy(0.5f))
                        // Reserve color for state: neutral when within budget,
                        // only red when over — not decorative green/cyan.
                        RamRow("Total", "${String.format("%.0f", totalEstMB)} MB / ${totalRamMB} MB",
                            if (ramOk) colors.Text2 else colors.Red, colors)
                        if (!ramOk) Text("⚠  Exceeds RAM — reduce ctx or max tokens",
                            fontSize = 9.sp, color = colors.Red, fontFamily = FontFamily.Monospace)
                    }
                }

                // ── Reset / Remove / Save buttons ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            ctxSlider = SettingsManager.nCtx.coerceIn(512, 32768)
                            maxNewSlider = SettingsManager.maxTokens.coerceIn(64, 32768)
                            gpuSlider = SettingsManager.gpuLayers.coerceIn(0, 999)
                            enableGpu = isGguf
                            tempText = SettingsManager.temperature.toString()
                            topPText = SettingsManager.topP.toString()
                            minPText = SettingsManager.minP.toString()
                            topKText = SettingsManager.topK.toString()
                            repPenText = SettingsManager.repeatPenalty.toString()
                            freqPenText = SettingsManager.freqPenalty.toString()
                            presPenText = SettingsManager.presPenalty.toString()
                            seedText = ""
                            flashSwitch = SettingsManager.flashAttention
                            lowRamSwitch = SettingsManager.lowRamMode
                            threadsText = SettingsManager.threads.toString()
                            batchText = SettingsManager.nBatch.toString()
                            backendSel = SettingsManager.backend
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Text3)
                    ) { Text("Reset", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    if (onRemove != null) {
                        Button(
                            onClick = onRemove,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.Red)
                        ) { Text("Remove", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.Text, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    )
}

/**
 * The actual scrollable content rendered as a separate composable used
 * inside the dialog's text/content slot.
 */

// ── Reusable composables ─────────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String, colors: ZcPalette) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.Accent,
        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
}

@Composable
private fun SliderSection(
    label: String,
    valueText: String,
    colors: ZcPalette,
    slider: @Composable (String) -> Unit
) {
    Column {
        if (label.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                Text(valueText, fontSize = 13.sp, color = colors.Accent,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        slider(valueText)
    }
}

@Composable
private fun CheckOverride(
    label: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    colors: ZcPalette,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = checked,
                onCheckedChange = onCheck,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg,
                    uncheckedTrackColor = colors.Border, uncheckedThumbColor = colors.Text3),
                modifier = Modifier.height(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
        }
        if (checked) {
            Column(modifier = Modifier.padding(start = 26.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SamplingField(
    label: String,
    hint: String,
    value: String,
    onValue: (String) -> Unit,
    colors: ZcPalette
) {
    Column {
        Text(label, fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
        OutlinedTextField(
            value = value,
            onValueChange = { v ->
                // Reject bare "." / "-" / "-." and non-numeric input
                // so the field never shows just a decimal separator.
                if (v.isEmpty()) return@OutlinedTextField
                if (v == "." || v == "-" || v == "-.") return@OutlinedTextField
                if (v.count { it == '.' } > 1) return@OutlinedTextField
                val clean = v.filterIndexed { i, c -> c.isDigit() || c == '.' || (c == '-' && i == 0) }
                if (clean != v) return@OutlinedTextField
                onValue(v)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
            placeholder = { Text(hint, fontSize = 10.sp, color = colors.Text3) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.Accent, unfocusedBorderColor = colors.Border,
                focusedTextColor = colors.Text, unfocusedTextColor = colors.Text,
                cursorColor = colors.Accent
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun SingleSwitch(
    label: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    colors: ZcPalette,
    hint: String = "",
    enabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = onCheck,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.Accent, checkedThumbColor = colors.Bg,
                uncheckedTrackColor = colors.Border, uncheckedThumbColor = colors.Text3,
                disabledCheckedTrackColor = colors.Border, disabledUncheckedTrackColor = colors.Border)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = if (enabled) colors.Text2 else colors.Text3,
                fontFamily = FontFamily.Monospace)
            if (hint.isNotEmpty()) {
                Text(hint, fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Flash Attention (llama.cpp FA) requires ARMv8.2-a dot-product / i8mm
 *  instructions. Detect by scanning /proc/cpuinfo Features. */
private fun cpuSupportsFlashAttention(): Boolean {
    return try {
        val info = java.io.File("/proc/cpuinfo").readText()
        info.contains("i8mm") || info.contains("dotprod")
    } catch (_: Exception) { true }
}

@Composable
private fun RamRow(label: String, value: String, color: androidx.compose.ui.graphics.Color, colors: ZcPalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 10.sp, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

/** Format large numbers with K suffix for compact tick labels. */
private fun formatTick(v: Int): String = when {
    v >= 1000 -> "${v / 1000}K"
    else -> "$v"
}
