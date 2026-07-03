package com.gguf.zerocopy.ui.invent

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.ui.theme.currentPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventSetupScreen(
    onStart: (m1Path: String, m1Name: String, m2Path: String, m2Name: String,
              resPath: String, resName: String, offline: Boolean, sameModel: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val colors = currentPalette()
    val app = ZeroCopyApp.instance

    // Observe models from StateFlow — properly typed
    val allModels by app.modelRepository.models.collectAsState()
    val compatibleModels: List<LocalModel> = remember(allModels) {
        allModels.filter { it.format in setOf("gguf", "mnn", "tflite", "litertlm") }
    }

    var model1Path by remember { mutableStateOf("") }
    var model1Name by remember { mutableStateOf("") }
    var model2Path by remember { mutableStateOf("") }
    var model2Name by remember { mutableStateOf("") }
    var researcherPath by remember { mutableStateOf("") }
    var researcherName by remember { mutableStateOf("") }
    var offlineMode by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf<String?>(null) } // "m1","m2","res"
    // 1 = one model all roles, 2 = researcher + combined, 3 = all separate
    var modelMode by remember { mutableStateOf(1) }

    val canStart = when (modelMode) {
        1 -> model1Path.isNotEmpty()
        2 -> researcherPath.isNotEmpty() && model1Path.isNotEmpty()
        3 -> model1Path.isNotEmpty() && model2Path.isNotEmpty() && researcherPath.isNotEmpty()
        else -> false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Invent Setup", fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = colors.Text)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Surface)
            )
        },
        containerColor = colors.Bg
    ) { pad ->
        // State for model settings dialog
        val modelSettingsRole = remember { mutableStateOf<String?>(null) }

        // Show model settings dialog when requested
        modelSettingsRole.value?.let { role ->
            val modelPath = when (role) {
                "Planner" -> model1Path; "Coder" -> model2Path; else -> researcherPath
            }
            val modelName = when (role) {
                "Planner" -> model1Name; "Coder" -> model2Name; else -> researcherName
            }
            ModelSettingsDialog(
                role = role,
                modelName = modelName,
                modelPath = modelPath,
                colors = colors,
                onDismiss = { modelSettingsRole.value = null },
                onSaved = { modelSettingsRole.value = null }
            )
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ── Header card ─────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier.background(
                        Brush.horizontalGradient(
                            listOf(colors.GradientStart.copy(0.08f), colors.GradientEnd.copy(0.08f))
                        ), RoundedCornerShape(16.dp)
                    ).padding(16.dp)
                ) {
                    Column {
                        Text("🧠 Invent", fontSize = 22.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, color = colors.Text)
                        Spacer(Modifier.height(4.dp))
                        Text("3 AIs. Your idea. Full project structure.",
                            fontSize = 13.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.Accent.copy(alpha = 0.15f)
                        ) {
                            Text("  GGUF / MNN / TFLite  ", fontSize = 10.sp, color = colors.Accent,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            // ── GGUF not found warning ──────────────────────────────────────
            if (compatibleModels.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.Amber.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, colors.Amber.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = colors.Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("No GGUF models found. Import at least 2 GGUF models to use Invent.",
                            fontSize = 12.sp, color = colors.Amber, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ── Model mode selector ────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("How many models?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.Text, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3).forEach { mode ->
                            val active = modelMode == mode
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) colors.Accent.copy(alpha = 0.2f) else colors.Surface,
                                border = BorderStroke(1.dp, if (active) colors.Accent else colors.Border),
                                modifier = Modifier.weight(1f).clickable { modelMode = mode }
                            ) {
                                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$mode", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                        color = if (active) colors.Accent else colors.Text2,
                                        fontFamily = FontFamily.Monospace)
                                    Text(
                                        when (mode) {
                                            1 -> "All-in-one"
                                            2 -> "Two models"
                                            else -> "Three models"
                                        },
                                        fontSize = 9.sp, color = if (active) colors.Accent else colors.Text3,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Model group cards ──────────────────────────────────────────
            when (modelMode) {
                1 -> {
                    // One card: Planner + Researcher + Coder (all same model)
                    InventGroupCard(
                        roles = "Planner + Researcher + Coder",
                        subtitle = "One model handles all three roles",
                        selected = model1Name,
                        onPick = { showPicker = "m1" },
                        onSettings = { if (model1Path.isNotEmpty()) modelSettingsRole.value = "Planner" },
                        colors = colors
                    )
                }
                2 -> {
                    // Two cards: Researcher + Planner+Coder
                    InventGroupCard(
                        roles = "🔍  Researcher",
                        subtitle = "~1B — searches web & extracts info",
                        selected = researcherName,
                        onPick = { showPicker = "res" },
                        onSettings = { if (researcherPath.isNotEmpty()) modelSettingsRole.value = "Researcher" },
                        colors = colors
                    )
                    InventGroupCard(
                        roles = "⚙  Planner + 💻  Coder",
                        subtitle = "Logic, planning & code generation",
                        selected = model1Name,
                        onPick = { showPicker = "m1" },
                        onSettings = { if (model1Path.isNotEmpty()) modelSettingsRole.value = "Planner" },
                        colors = colors
                    )
                }
                3 -> {
                    // Three separate cards
                    InventGroupCard(
                        roles = "⚙  Planner",
                        subtitle = "Logic — asks questions & plans the project",
                        selected = model1Name,
                        onPick = { showPicker = "m1" },
                        onSettings = { if (model1Path.isNotEmpty()) modelSettingsRole.value = "Planner" },
                        colors = colors
                    )
                    InventGroupCard(
                        roles = "💻  Coder",
                        subtitle = "Code-specialized — builds the implementation plan",
                        selected = model2Name,
                        onPick = { showPicker = "m2" },
                        onSettings = { if (model2Path.isNotEmpty()) modelSettingsRole.value = "Coder" },
                        colors = colors
                    )
                    InventGroupCard(
                        roles = "🔍  Researcher",
                        subtitle = "~1B — searches web & extracts info",
                        selected = researcherName,
                        onPick = { showPicker = "res" },
                        onSettings = { if (researcherPath.isNotEmpty()) modelSettingsRole.value = "Researcher" },
                        colors = colors
                    )
                }
            }

            // ── Offline toggle ──────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (offlineMode) Icons.Outlined.WifiOff else Icons.Outlined.Wifi,
                        null,
                        tint = if (offlineMode) colors.Amber else colors.Text2,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Offline Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.Text, fontFamily = FontFamily.Monospace)
                        Text(
                            if (offlineMode) "Uses model training knowledge only. Fields marked [OFFLINE]."
                            else "Fetches real-time info from trusted domains.",
                            fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = offlineMode,
                        onCheckedChange = { offlineMode = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.Amber,
                            checkedTrackColor = colors.Amber.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Start button ────────────────────────────────────────────────
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (canStart)
                            Brush.horizontalGradient(listOf(colors.GradientStart, colors.GradientEnd))
                        else Brush.horizontalGradient(listOf(colors.Border, colors.Border))
                    )
                    .then(if (canStart) Modifier.clickable {
                        val coderPath = if (modelMode == 3) model2Path else model1Path
                        val coderName = if (modelMode == 3) model2Name else model1Name
                        val allSame = modelMode == 1
                        onStart(
                            model1Path, model1Name,
                            coderPath, coderName,
                            researcherPath, researcherName,
                            offlineMode, allSame
                        )
                    } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Inventing", color = Color.White,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = 15.sp)
                }
            }
        }
    }

    // ── Model picker dialog ─────────────────────────────────────────────────
    if (showPicker != null) {
        AlertDialog(
            onDismissRequest = { showPicker = null },
            title = {
                Text(
                    when (showPicker) {
                        "m1" -> "Pick Planner Model"
                        "m2" -> "Pick Coder Model"
                        else -> "Pick Researcher (~1B)"
                    },
                    color = colors.Text, fontFamily = FontFamily.Monospace
                )
            },
            text = {
                if (compatibleModels.isEmpty()) {
                    Text("No compatible models found.", color = colors.Text2,
                        fontFamily = FontFamily.Monospace)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        compatibleModels.forEach { model: LocalModel ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.CardLight,
                                border = BorderStroke(1.dp, colors.Border),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    // Set the model path/name
                                    when (showPicker) {
                                        "m1" -> { model1Path = model.path; model1Name = model.name }
                                        "m2" -> { model2Path = model.path; model2Name = model.name }
                                        "res" -> { researcherPath = model.path; researcherName = model.name }
                                    }
                                    // Determine role for settings
                                    val settingsRole = when (showPicker) {
                                        "m1" -> "Planner"; "m2" -> "Coder"; else -> "Researcher"
                                    }
                                    showPicker = null
                                    // Open settings dialog for this model
                                    modelSettingsRole.value = settingsRole
                                }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(model.name, fontSize = 13.sp, color = colors.Text,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium)
                                    Text(model.sizeFormatted, fontSize = 10.sp,
                                        color = colors.Text3, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = null }) {
                    Text("Cancel", color = colors.Text2)
                }
            },
            containerColor = colors.Card
        )
    }
}

// ── Model Settings Dialog ────────────────────────────────────────────────
@Composable
fun ModelSettingsDialog(
    role: String,
    modelName: String,
    modelPath: String,
    colors: com.gguf.zerocopy.ui.theme.ZcPalette,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val existingCfg = com.gguf.zerocopy.data.local.SettingsManager.getInventModelConfig(role)
    var ctx by remember { mutableStateOf(existingCfg?.ctx ?: 2048) }
    var maxNew by remember { mutableStateOf(existingCfg?.maxNew ?: 512) }
    var gpuLayers by remember { mutableStateOf(existingCfg?.gpuLayers ?: 0) }
    var temperature by remember { mutableStateOf(existingCfg?.temperature ?: 0.7f) }
    var topP by remember { mutableStateOf(existingCfg?.topP ?: 0.9f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("$role Settings", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, color = colors.Text)
                Text(modelName.take(30), fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, color = colors.Text3)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Context Window
                Text("Context Window: $ctx", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                Slider(value = ctx.toFloat(), onValueChange = { ctx = it.toInt() },
                    valueRange = 256f..32768f, steps = 127,
                    colors = SliderDefaults.colors(thumbColor = colors.Accent, activeTrackColor = colors.Accent))
                // Max Tokens
                Text("Max New Tokens: $maxNew", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                Slider(value = maxNew.toFloat(), onValueChange = { maxNew = it.toInt() },
                    valueRange = 64f..8192f, steps = 127,
                    colors = SliderDefaults.colors(thumbColor = colors.Accent, activeTrackColor = colors.Accent))
                // Temperature
                Text("Temperature: %.2f".format(temperature), fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                Slider(value = temperature, onValueChange = { temperature = it },
                    valueRange = 0.0f..2.0f, steps = 40,
                    colors = SliderDefaults.colors(thumbColor = colors.Accent, activeTrackColor = colors.Accent))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    com.gguf.zerocopy.data.local.SettingsManager.setInventModelConfig(role,
                        com.gguf.zerocopy.data.local.SettingsManager.ModelTokenConfig(
                            ctx = ctx, maxNew = maxNew, gpuLayers = gpuLayers,
                            temperature = temperature, topP = topP
                        )
                    )
                    onSaved()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.Accent)
            ) {
                Text("✓ Save", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip", color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
        },
        containerColor = colors.Card
    )
}

@Composable
fun InventGroupCard(
    roles: String,
    subtitle: String,
    selected: String,
    onPick: () -> Unit,
    colors: com.gguf.zerocopy.ui.theme.ZcPalette,
    onSettings: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.Card,
        border = BorderStroke(1.dp, if (selected.isNotEmpty()) colors.Accent.copy(0.4f) else colors.Border),
        modifier = Modifier.fillMaxWidth().clickable { onPick() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(roles, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = colors.Text, fontFamily = FontFamily.Monospace)
                    Text(subtitle, fontSize = 11.sp, color = colors.Text3,
                        fontFamily = FontFamily.Monospace)
                }
                if (selected.isNotEmpty() && onSettings != null) {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Settings, "Settings", tint = colors.Text3, modifier = Modifier.size(16.dp))
                    }
                }
                Icon(
                    if (selected.isNotEmpty()) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline,
                    null,
                    tint = if (selected.isNotEmpty()) colors.Accent2 else colors.Text3,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (selected.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = colors.Accent.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("   Model:  $selected", fontSize = 12.sp, color = colors.Accent2,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 5.dp, horizontal = 8.dp))
                }
            }
        }
    }
}

