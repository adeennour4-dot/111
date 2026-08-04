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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.ui.theme.currentPalette

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
    var inventMode by remember { mutableStateOf("multi") } // "single" or "multi"
    var showPicker by remember { mutableStateOf<String?>(null) } // "m1","m2","res"
    // 1 = one model all roles, 2 = researcher + combined, 3 = all separate
    var modelMode by remember { mutableStateOf(1) }

    val canStart = if (inventMode == "single") {
        model1Path.isNotEmpty()
    } else when (modelMode) {
        1 -> model1Path.isNotEmpty()
        2 -> researcherPath.isNotEmpty() && model1Path.isNotEmpty()
        3 -> model1Path.isNotEmpty() && model2Path.isNotEmpty() && researcherPath.isNotEmpty()
        else -> false
    }

    // State for model settings dialog (function scope — used inside Scaffold and picker dialog)
    val modelSettingsRole = remember { mutableStateOf<String?>(null) }

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
                        Text("🧠 Invent", fontSize = 24.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, color = colors.Text)
                        Spacer(Modifier.height(5.dp))
                        Text("3 AIs. Your idea. Full project structure.",
                            fontSize = 13.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = colors.Accent.copy(alpha = 0.15f)
                        ) {
                            Text("  GGUF / MNN / TFLite  ", fontSize = 10.sp, color = colors.Accent,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }

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

            // ── Mode selector: Single Model vs Multi-Agent ────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Mode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.Text, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "single" to "Single Model",
                            "multi" to "Multi-Agent"
                        ).forEach { (mode, label) ->
                            val active = inventMode == mode
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) colors.Accent.copy(alpha = 0.2f) else colors.Surface,
                                border = BorderStroke(1.dp, if (active) colors.Accent else colors.Border),
                                modifier = Modifier.weight(1f).clickable { inventMode = mode }
                            ) {
                                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        if (mode == "single") Icons.Filled.Person else Icons.Filled.Groups,
                                        null,
                                        tint = if (active) colors.Accent else colors.Text3,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                                        color = if (active) colors.Accent else colors.Text2,
                                        fontFamily = FontFamily.Monospace)
                                    Text(
                                        if (mode == "single") "One model handles all roles"
                                        else "Planner + Coder + Researcher",
                                        fontSize = 9.5.sp, color = if (active) colors.Accent else colors.Text3,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
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
                        Icon(Icons.Outlined.Warning, null, tint = colors.Amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("No GGUF models found. Import at least 2 GGUF models to use Invent.",
                            fontSize = 12.5.sp, color = colors.Amber, fontFamily = FontFamily.Monospace)
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
                Column(Modifier.padding(16.dp)) {
                    Text("How many models?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.Text, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
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
                                        fontSize = 9.5.sp, color = if (active) colors.Accent else colors.Text3,
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

            // ── Reasoning toggle ───────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (reasoningEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                        null,
                        tint = if (reasoningEnabled) colors.Amber else colors.Text2,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Think / Reason", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.Text, fontFamily = FontFamily.Monospace)
                        Text(
                            if (reasoningEnabled) "Models use step-by-step reasoning with <think> tags."
                            else "Models answer directly without explicit reasoning.",
                            fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = reasoningEnabled,
                        onCheckedChange = { reasoningEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.Amber,
                            checkedTrackColor = colors.Amber.copy(alpha = 0.3f)
                        )
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Chat template ─────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
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
                        val isSingle = inventMode == "single"
                        val coderPath = if (isSingle || modelMode != 3) model1Path else model2Path
                        val coderName = if (isSingle || modelMode != 3) model1Name else model2Name
                        // In single mode or mode 1, researcher uses the same model as planner
                        val actualResPath = if (isSingle || modelMode == 1) model1Path else researcherPath
                        val actualResName = if (isSingle || modelMode == 1) model1Name else researcherName
                        val allSame = isSingle || modelMode == 1
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
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(roles, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = colors.Text, fontFamily = FontFamily.Monospace)
                    Text(subtitle, fontSize = 11.5.sp, color = colors.Text3,
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
                    Text("   Model:  $selected", fontSize = 12.5.sp, color = colors.Accent2,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 9.dp))
                }
            }
        }
    }
}

