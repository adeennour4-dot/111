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

/**
 * Project selection screen shown after model setup.
 * Displays 4 project slots, a model-mode selector (1/2/3),
 * clickable model cards with settings and remove buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventProjectScreen(
    model1Path: String, model1Name: String,
    model2Path: String, model2Name: String,
    researcherPath: String, researcherName: String,
    offlineMode: Boolean,
    sameModelMode: Boolean,
    reasoningEnabled: Boolean,
    completedProjects: Set<Int> = emptySet(),
    onStartProject: (projectIndex: Int) -> Unit,
    onBack: () -> Unit,
    onSettings: (role: String, modelPath: String, modelName: String) -> Unit,
    onPickModel: (role: String) -> Unit
) {
    val colors = currentPalette()
    val app = ZeroCopyApp.instance
    val allModels by app.modelRepository.models.collectAsState()
    val compatibleModels: List<LocalModel> = remember(allModels) {
        allModels.filter { it.format in setOf("gguf", "mnn", "tflite", "litertlm") }
    }

    // Project slots (4)
    val projectNames = remember { List(4) { "Project ${it + 1}" } }
    val projectColors = listOf(colors.Purple, colors.Accent2, colors.Amber, colors.Red)

    // Model mode (1=solo, 2=dual, 3=triple)
    var modelMode by remember { mutableStateOf(if (sameModelMode) 1 else 3) }

    // Currently selected project index (-1 = none)
    var selectedProject by remember { mutableStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Inventory2, null, tint = colors.Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Invent", fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, color = colors.Text)
                    }
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
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 4 Project slots ──────────────────────────────────────────
            Text("Select a Project Slot", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = colors.Text, fontFamily = FontFamily.Monospace)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                projectNames.forEachIndexed { idx, name ->
                    val isSelected = selectedProject == idx
                    val slotColor = projectColors[idx]
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) slotColor.copy(alpha = 0.15f) else colors.Card,
                        border = BorderStroke(
                            2.dp,
                            if (isSelected) slotColor else colors.Border
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedProject = idx }
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                if (isSelected) Icons.Filled.Folder else Icons.Outlined.FolderOpen,
                                null,
                                tint = if (isSelected) slotColor else colors.Text3,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(name, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (isSelected) slotColor else colors.Text2,
                                fontFamily = FontFamily.Monospace)
                            Text(
                                if (isSelected) "Selected" else "Tap to select",
                                fontSize = 8.sp, color = if (isSelected) slotColor else colors.Text3,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // ── Model mode selector (1/2/3) ──────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Model Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.Text, fontFamily = FontFamily.Monospace)
                    Text("How many models work on this project?",
                        fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            1 to "Solo", 2 to "Duo", 3 to "Trio"
                        ).forEach { (mode, label) ->
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
                                    Text(label, fontSize = 9.sp, color = if (active) colors.Accent else colors.Text3,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            // ── Model cards (clickable, with settings & remove) ──────────
            val activeModelCount = modelMode
            Text("Models", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = colors.Text, fontFamily = FontFamily.Monospace)

            // Planner
            ModelAssignmentCard(
                role = "Planner", emoji = "⚙",
                path = model1Path, name = model1Name,
                isActive = activeModelCount >= 1,
                onPick = { onPickModel("Planner") },
                onSettings = { onSettings("Planner", model1Path, model1Name) },
                onRemove = { /* handled by unselecting */ },
                colors = colors
            )

            // Coder
            if (activeModelCount >= 2) {
                ModelAssignmentCard(
                    role = "Coder", emoji = "💻",
                    path = model2Path, name = model2Name,
                    isActive = true,
                    onPick = { onPickModel("Coder") },
                    onSettings = { onSettings("Coder", model2Path, model2Name) },
                    onRemove = { modelMode = 1 },
                    colors = colors
                )
            }

            // Researcher
            if (activeModelCount >= 3) {
                ModelAssignmentCard(
                    role = "Researcher", emoji = "🔍",
                    path = researcherPath, name = researcherName,
                    isActive = true,
                    onPick = { onPickModel("Researcher") },
                    onSettings = { onSettings("Researcher", researcherPath, researcherName) },
                    onRemove = { modelMode = 2 },
                    colors = colors
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Start button ────────────────────────────────────────────
            val canStart = selectedProject >= 0 && model1Path.isNotEmpty()
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (canStart)
                            Brush.horizontalGradient(listOf(colors.GradientStart, colors.GradientEnd))
                        else Brush.horizontalGradient(listOf(colors.Border, colors.Border))
                    )
                    .then(if (canStart) Modifier.clickable { onStartProject(selectedProject) } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Rocket, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Project ${selectedProject + 1}", color = Color.White,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ModelAssignmentCard(
    role: String, emoji: String,
    path: String, name: String,
    isActive: Boolean,
    onPick: () -> Unit,
    onSettings: () -> Unit,
    onRemove: () -> Unit,
    colors: com.gguf.zerocopy.ui.theme.ZcPalette
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.Card,
        border = BorderStroke(1.dp, if (path.isNotEmpty()) colors.Accent.copy(0.4f) else colors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Clickable model name area
                Column(Modifier.weight(1f).clickable { onPick() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$emoji  $role", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = colors.Text, fontFamily = FontFamily.Monospace)
                        if (!isActive) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = colors.Text3.copy(alpha = 0.2f)) {
                                Text(" off ", fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    if (name.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(name.take(40), fontSize = 11.sp, color = colors.Accent,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    } else {
                        Spacer(Modifier.height(2.dp))
                        Text("Tap to select model", fontSize = 10.sp, color = colors.Text3,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                // Settings button
                if (path.isNotEmpty()) {
                    IconButton(onClick = onSettings, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Settings, "Settings", tint = colors.Text3, modifier = Modifier.size(16.dp))
                    }
                }
                // Remove button
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "Remove", tint = colors.Red, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
