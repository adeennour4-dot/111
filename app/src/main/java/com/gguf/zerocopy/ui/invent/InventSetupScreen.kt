package com.gguf.zerocopy.ui.invent

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette

// ─── Role accents (mirror InventScreen) ──────────────────────────────────────
private val Cy = Color(0xFF00E5A0)
private val Pr = Color(0xFF8B83FF)
private val Am = Color(0xFFFFB74D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventSetupScreen(
    onStart: (m1Path: String, m1Name: String, m2Path: String, m2Name: String,
              resPath: String, resName: String, offline: Boolean, sameModel: Boolean,
              reasoningEnabled: Boolean) -> Unit,
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
    var reasoningEnabled by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf<String?>(null) } // "m1","m2","res"

    // The planner is the anchor role; empty coder/researcher slots reuse it.
    // (sameModel is auto-derived from the actual paths — mirrors the old
    //  single/dual/triple modes without any mode picker UI.)
    val canStart = model1Path.isNotEmpty() || compatibleModels.isNotEmpty()

    // State for model settings dialog (function scope — used inside Scaffold and picker dialog)
    val modelSettingsRole = remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Invent", fontFamily = FontFamily.Monospace,
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
        // Show model settings dialog when requested (declaration is in outer scope)
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

            // ── Hero header — pulsing emblem ───────────────────────────────
            HeroCard(colors)

            // ── Saved projects (up to 4 slots) ────────────────────────────
            val savedSessions = remember {
                try {
                    com.gguf.zerocopy.data.invent.InventStorage.listSessions(app)
                        .mapNotNull { sid ->
                            val s = com.gguf.zerocopy.data.invent.InventStorage.loadSession(app, sid)
                            val z = com.gguf.zerocopy.data.invent.InventStorage.loadZcp(app, sid)
                            if (s != null) Triple(sid, s, z?.projectName ?: s.model1Name.ifEmpty { "Untitled" }) else null
                        }
                        .take(4)
                } catch (_: Exception) { emptyList() }
            }
            if (savedSessions.isNotEmpty()) {
                Text("Resume Project", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = colors.Text2, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    savedSessions.forEach { (sid, state, projectName) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.Card,
                            border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    // Resume this project
                                    val allSame = state.sameModelMode
                                    onStart(
                                        state.model1Path, state.model1Name,
                                        state.model2Path, state.model2Name,
                                        state.researcherPath, state.researcherName,
                                        state.offlineMode, allSame, true
                                    )
                                }
                        ) {
                            Column(
                                Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = colors.Accent,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    projectName.take(12),
                                    fontSize = 9.sp, color = colors.Text,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    state.phase.name.take(8),
                                    fontSize = 8.sp, color = colors.Text3,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    // Fill empty slots with invisible spacers to keep layout consistent
                    repeat(4 - savedSessions.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── Agent crew — three role cards ──────────────────────────────
            Text("Agent Crew", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = colors.Text2, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 2.dp))
            AgentCard(
                role = "Planner", monogram = "P",
                tagline = "Asks questions, plans the file structure, guides the build.",
                accent = Am,
                selected = model1Name,
                onPick = { showPicker = "m1" },
                onSettings = { if (model1Path.isNotEmpty()) modelSettingsRole.value = "Planner" },
                colors = colors
            )
            AgentCard(
                role = "Coder", monogram = "C",
                tagline = "Writes every file of the implementation.",
                accent = Cy,
                selected = model2Name,
                onPick = { showPicker = "m2" },
                onSettings = { if (model2Path.isNotEmpty()) modelSettingsRole.value = "Coder" },
                colors = colors
            )
            AgentCard(
                role = "Researcher", monogram = "R",
                tagline = "Searches the web for current APIs & best practices.",
                accent = Pr,
                selected = researcherName,
                onPick = { showPicker = "res" },
                onSettings = { if (researcherPath.isNotEmpty()) modelSettingsRole.value = "Researcher" },
                colors = colors
            )
            Text("Empty roles reuse the Planner model — one model is enough to start.",
                fontSize = 9.5.sp, color = colors.Text3, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 2.dp))

            // ── GGUF not found warning ──────────────────────────────────────
            if (compatibleModels.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.Amber.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, colors.Amber.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = colors.Amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("No compatible models found yet. Import a GGUF/MNN model, or the currently loaded model will be used.",
                            fontSize = 12.5.sp, color = colors.Amber, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ── Preferences: reasoning + offline ───────────────────────────
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ToggleRow(
                        icon = { if (reasoningEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb },
                        title = "Think / Reason",
                        desc = if (reasoningEnabled) "Step-by-step reasoning with <think> tags."
                                else "Direct answers, no explicit reasoning.",
                        accent = Am,
                        checked = reasoningEnabled,
                        onChange = { reasoningEnabled = it },
                        colors = colors
                    )
                    HorizontalDivider(color = colors.Border.copy(alpha = 0.4f))
                    ToggleRow(
                        icon = { if (offlineMode) Icons.Outlined.WifiOff else Icons.Outlined.Wifi },
                        title = "Offline Mode",
                        desc = if (offlineMode) "Training knowledge only. Fields marked [OFFLINE]."
                                else "Fetches real-time info from trusted domains.",
                        accent = colors.Accent,
                        checked = offlineMode,
                        onChange = { offlineMode = it },
                        colors = colors
                    )
                }
            }

            // ── Chat template ─────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Chat Template", fontSize = 12.sp,
                        color = colors.Text2, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    val chatTemplateOptions = listOf(
                        "auto" to "Auto-detect", "chatml" to "ChatML",
                        "gemma" to "Gemma", "llama3" to "Llama 3",
                        "deepseek" to "DeepSeek", "qwen" to "Qwen",
                        "phi" to "Phi-3/4", "mistral" to "Mistral",
                        "command" to "Command-R"
                    )
                    var chatTemplate by remember { mutableStateOf(
                        com.gguf.zerocopy.data.local.SettingsManager.chatTemplate
                    ) }
                    var templateExpanded by remember { mutableStateOf(false) }
                    val selectedTemplate = chatTemplateOptions.find { it.first == chatTemplate }?.second ?: "Auto-detect"
                    Box {
                        OutlinedButton(
                            onClick = { templateExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Accent)
                        ) {
                            Text(selectedTemplate, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text("▾", fontSize = 10.sp, color = colors.Text3)
                        }
                        DropdownMenu(
                            expanded = templateExpanded,
                            onDismissRequest = { templateExpanded = false },
                            containerColor = colors.Card
                        ) {
                            chatTemplateOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (value == chatTemplate) {
                                                Text("✓ ", fontSize = 11.sp, color = colors.Accent,
                                                    fontFamily = FontFamily.Monospace)
                                            } else {
                                                Spacer(Modifier.width(14.dp))
                                            }
                                            Text(label, fontSize = 11.sp,
                                                color = if (value == chatTemplate) colors.Accent else colors.Text,
                                                fontFamily = FontFamily.Monospace)
                                        }
                                    },
                                    onClick = {
                                        chatTemplate = value
                                        com.gguf.zerocopy.data.local.SettingsManager.chatTemplate = value
                                        templateExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Start button ────────────────────────────────────────────────
            Box(
                Modifier.fillMaxWidth().height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (canStart)
                            Brush.horizontalGradient(listOf(Cy, Pr))
                        else Brush.horizontalGradient(listOf(colors.Border, colors.Border))
                    )
                    .then(if (canStart) Modifier.clickable {
                        val coderPath = model2Path.ifEmpty { model1Path }
                        val coderName = model2Name.ifEmpty { model1Name }
                        val actualResPath = researcherPath.ifEmpty { model1Path }
                        val actualResName = researcherName.ifEmpty { model1Name }
                        val allSame = coderPath == model1Path && actualResPath == model1Path
                        onStart(
                            model1Path, model1Name,
                            coderPath, coderName,
                            actualResPath, actualResName,
                            offlineMode, allSame,
                            reasoningEnabled
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
        val pickAccent = when (showPicker) {
            "m1" -> Am; "m2" -> Cy; else -> Pr
        }
        val pickMonogram = when (showPicker) {
            "m1" -> "P"; "m2" -> "C"; else -> "R"
        }
        AlertDialog(
            onDismissRequest = { showPicker = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(pickAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center) {
                        Text(pickMonogram, fontSize = 12.sp, fontWeight = FontWeight.Black,
                            color = pickAccent, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        when (showPicker) {
                            "m1" -> "Pick Planner Model"
                            "m2" -> "Pick Coder Model"
                            else -> "Pick Researcher Model"
                        },
                        color = colors.Text, fontFamily = FontFamily.Monospace
                    )
                }
            },
            text = {
                if (compatibleModels.isEmpty()) {
                    Text("No compatible models found.", color = colors.Text2,
                        fontFamily = FontFamily.Monospace)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        compatibleModels.forEach { model: LocalModel ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
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
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(model.name, fontSize = 13.sp, color = colors.Text,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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

// ── Hero header — pulsing emblem + format chips ─────────────────────────────
@Composable
private fun HeroCard(colors: ZcPalette) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(colors.GradientStart.copy(alpha = 0.16f), colors.GradientEnd.copy(alpha = 0.16f)))
            )
            .border(1.dp, colors.Accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pulsing emblem
            val orb = rememberInfiniteTransition(label = "heroOrb")
            val ring by orb.animateFloat(0.72f, 1.2f,
                infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "heroRing")
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(58.dp)) {
                Box(Modifier.size(44.dp * ring).clip(CircleShape).background(colors.Accent.copy(alpha = 0.14f)))
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(Cy, Pr))),
                    contentAlignment = Alignment.Center) {
                    Text("Z", fontSize = 19.sp, fontWeight = FontWeight.Black,
                        color = colors.Bg, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Invent", fontSize = 22.sp, fontWeight = FontWeight.Black,
                    color = colors.Text, fontFamily = FontFamily.Monospace)
                Text("A crew of agents turns your idea into a full project.",
                    fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("GGUF", "MNN", "TFLite").forEach { fmt ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.Accent.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.25f))
                        ) {
                            Text(" $fmt ", fontSize = 8.5.sp, color = colors.Accent,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Agent role card — monogram avatar + model chip ──────────────────────────
@Composable
private fun AgentCard(
    role: String, monogram: String, tagline: String, accent: Color,
    selected: String, onPick: () -> Unit,
    onSettings: (() -> Unit)? = null,
    colors: ZcPalette
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected.isNotEmpty()) accent.copy(alpha = 0.06f) else colors.Card,
        border = BorderStroke(1.dp, if (selected.isNotEmpty()) accent.copy(0.4f) else colors.Border.copy(0.5f)),
        modifier = Modifier.fillMaxWidth().clickable { onPick() }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (selected.isNotEmpty()) accent.copy(0.16f) else colors.Surface)
                    .border(1.dp, accent.copy(0.3f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(monogram, fontSize = 14.sp, fontWeight = FontWeight.Black,
                    color = if (selected.isNotEmpty()) accent else colors.Text3,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(role, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                    color = colors.Text, fontFamily = FontFamily.Monospace)
                Text(tagline, fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected.isNotEmpty()) accent.copy(0.12f) else colors.Surface
                ) {
                    Text(
                        if (selected.isNotEmpty()) "Model: $selected" else "auto → uses Planner model",
                        fontSize = 10.sp, color = if (selected.isNotEmpty()) accent else colors.Text3,
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (selected.isNotEmpty() && onSettings != null) {
                IconButton(onClick = onSettings, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Settings, "Settings", tint = colors.Text3, modifier = Modifier.size(15.dp))
                }
            }
            Icon(
                if (selected.isNotEmpty()) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline,
                null,
                tint = if (selected.isNotEmpty()) accent else colors.Text3,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ── Toggle row (preferences) ────────────────────────────────────────────────
@Composable
private fun ToggleRow(
    icon: () -> ImageVector,
    title: String, desc: String, accent: Color,
    checked: Boolean, onChange: (Boolean) -> Unit, colors: ZcPalette
) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon(), null,
            tint = if (checked) accent else colors.Text2,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = colors.Text, fontFamily = FontFamily.Monospace)
            Text(desc, fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = accent.copy(alpha = 0.3f),
                uncheckedThumbColor = colors.Text3,
                uncheckedTrackColor = colors.Border
            )
        )
    }
}

// ── Model Settings Dialog ────────────────────────────────────────────────
@Composable
fun ModelSettingsDialog(
    role: String,
    modelName: String,
    modelPath: String,
    colors: ZcPalette,
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
