package com.gguf.zerocopy.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Slider-based dialog for per-model token config.
 * Shows estimated RAM usage and prevents exceeding device RAM.
 */
@Composable
fun ModelTokenConfigDialog(
    modelName: String,
    modelFileSizeMB: Float,
    totalRamMB: Int,
    currentCtx: Int,
    currentMaxNew: Int,
    currentGpuLayers: Int = 0,
    isGguf: Boolean = false,
    onSave: (ctx: Int, maxNew: Int, gpuLayers: Int) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val colors = currentPalette()
    var ctxSlider by remember { mutableIntStateOf(currentCtx.coerceIn(512, 32768)) }
    var maxNewSlider by remember { mutableIntStateOf(currentMaxNew.coerceIn(64, 32768)) }
    var gpuLayersSlider by remember { mutableIntStateOf(currentGpuLayers.coerceIn(0, 99)) }

    // RAM estimation: KV cache per token ≈ modelFileSizeMB * 0.000046f
    // Total RAM ≈ modelFileSizeMB + (ctx + maxNew) * modelFileSizeMB * 0.000046f
    val kvCacheMB by remember {
        derivedStateOf {
            (ctxSlider + maxNewSlider) * modelFileSizeMB * 0.000046f
        }
    }
    val totalEstMB by remember {
        derivedStateOf { modelFileSizeMB + kvCacheMB }
    }
    val ramOk by remember {
        derivedStateOf { totalEstMB < totalRamMB * 0.85f }
    }

    // Clamp: max tokens can't exceed context - 64
    if (maxNewSlider > ctxSlider - 64) {
        maxNewSlider = (ctxSlider - 64).coerceAtLeast(64)
    }

    // Auto-clamp sliders so total estimated RAM stays within 85% of device RAM
    LaunchedEffect(ctxSlider, maxNewSlider) {
        val maxAllowed = (totalRamMB * 0.85f) - modelFileSizeMB
        val maxTotalTokens = if (modelFileSizeMB > 0) (maxAllowed / (modelFileSizeMB * 0.000046f)).roundToInt() else 32768
        if (ctxSlider + maxNewSlider > maxTotalTokens) {
            val ratio = ctxSlider.toFloat() / (ctxSlider + maxNewSlider).toFloat()
            ctxSlider = ((maxTotalTokens * ratio).roundToInt()).coerceIn(512, 32768)
            maxNewSlider = (maxTotalTokens - ctxSlider).coerceIn(64, 32768)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.Card,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title
            Text("⚙  Token Config", fontWeight = FontWeight.Bold,
                color = colors.Text, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Text(modelName, fontSize = 12.sp, color = colors.Text3,
                fontFamily = FontFamily.Monospace, maxLines = 1)

            HorizontalDivider(color = colors.Border)

            // Context slider
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Context window", fontSize = 11.sp, color = colors.Text2,
                        fontFamily = FontFamily.Monospace)
                    Text("${ctxSlider}", fontSize = 13.sp, color = colors.Accent,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = ctxSlider.toFloat(),
                    onValueChange = { v -> ctxSlider = v.roundToInt().coerceIn(512, 32768) },
                    valueRange = 512f..32768f,
                    steps = 0,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.Accent,
                        activeTrackColor = colors.Accent,
                        inactiveTrackColor = colors.Border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // Tick marks at powers of two
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(512, 1024, 2048, 4096, 8192, 16384, 32768).forEach { tick ->
                        Text("$tick", fontSize = 7.sp, color = colors.Text3,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Max new tokens slider
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Max new tokens", fontSize = 11.sp, color = colors.Text2,
                        fontFamily = FontFamily.Monospace)
                    Text("${maxNewSlider}", fontSize = 13.sp, color = colors.Accent2,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = maxNewSlider.toFloat(),
                    onValueChange = { v -> maxNewSlider = v.roundToInt().coerceIn(64, ctxSlider - 64) },
                    valueRange = 64f..(ctxSlider - 64).coerceAtLeast(128).toFloat(),
                    steps = 0,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.Accent2,
                        activeTrackColor = colors.Accent2,
                        inactiveTrackColor = colors.Border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // GPU layers slider (only for GGUF models — MNN/TFLite don't support GPU)
            if (isGguf) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("GPU layers", fontSize = 11.sp, color = colors.Text2,
                            fontFamily = FontFamily.Monospace)
                        Text("${gpuLayersSlider}", fontSize = 13.sp, color = colors.Purple,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = gpuLayersSlider.toFloat(),
                        onValueChange = { v -> gpuLayersSlider = v.roundToInt().coerceIn(0, 99) },
                        valueRange = 0f..99f,
                        steps = 0,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.Purple,
                            activeTrackColor = colors.Purple,
                            inactiveTrackColor = colors.Border
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0 = CPU", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                        Text("99 = all layers", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                // Show as read-only for non-GGUF models
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = colors.Border.copy(alpha = 0.1f)
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = colors.Text3, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("GPU offload only supported for GGUF models",
                            fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // RAM estimate card
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (ramOk) colors.Surface else colors.Red.copy(alpha = 0.08f),
                border = if (!ramOk) androidx.compose.foundation.BorderStroke(1.dp, colors.Red.copy(0.5f)) else null
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("📊  RAM Estimate", fontSize = 10.sp, color = colors.Text3,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)

                    RamRow("Model", "${"%.1f".format(modelFileSizeMB)} MB", colors.Text2, colors)
                    RamRow("KV Cache", "${"%.0f".format(kvCacheMB)} MB", colors.Text2, colors)
                    HorizontalDivider(color = colors.Border.copy(0.5f))
                    RamRow("Total", "${"%.0f".format(totalEstMB)} MB / ${totalRamMB} MB",
                        if (ramOk) colors.Accent2 else colors.Red, colors)
                    if (!ramOk) {
                        Text("⚠  Exceeds available RAM — reduce context or max tokens",
                            fontSize = 9.sp, color = colors.Red, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Default: 1024 x 1024
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        ctxSlider = 1024
                        maxNewSlider = 1024
                        gpuLayersSlider = 0
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Text3)
                ) { Text("Reset defaults", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }

                if (onRemove != null) {
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Red)
                    ) { Text("Remove", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
            }

            // Action buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Text2)
                ) { Text("Cancel", fontFamily = FontFamily.Monospace) }

                Button(
                    onClick = { onSave(ctxSlider, maxNewSlider, gpuLayersSlider) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    enabled = ramOk,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
                ) { Text("Save", fontFamily = FontFamily.Monospace,
                    color = colors.Bg, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun RamRow(label: String, value: String, color: androidx.compose.ui.graphics.Color, colors: ZcPalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 10.sp, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}
