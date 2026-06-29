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
import com.gguf.zerocopy.ui.theme.currentPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventSetupScreen(
    onStart: (m1Path: String, m1Name: String, m2Path: String, m2Name: String,
              resPath: String, resName: String, offline: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val colors = currentPalette()
    val app = ZeroCopyApp.instance

    // Load available models
    val models by remember {
        mutableStateOf(app.modelRepository.getLocalModels().filter { it.path.endsWith(".gguf") })
    }

    var model1Path by remember { mutableStateOf("") }
    var model1Name by remember { mutableStateOf("") }
    var model2Path by remember { mutableStateOf("") }
    var model2Name by remember { mutableStateOf("") }
    var researcherPath by remember { mutableStateOf("") }
    var researcherName by remember { mutableStateOf("") }
    var offlineMode by remember { mutableStateOf(false) }

    var showPicker by remember { mutableStateOf<String?>(null) } // "m1", "m2", "res"

    val canStart = model1Path.isNotEmpty() && model2Path.isNotEmpty() && researcherPath.isNotEmpty()

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
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Header
            Surface(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent,
                border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.3f))
            ) {
                Box(
                    Modifier.background(
                        Brush.horizontalGradient(listOf(colors.GradientStart.copy(0.08f), colors.GradientEnd.copy(0.08f))),
                        RoundedCornerShape(16.dp)
                    ).padding(16.dp)
                ) {
                    Column {
                        Text("🧠 Invent", fontSize = 20.sp, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = colors.Text)
                        Spacer(Modifier.height(4.dp))
                        Text("3 AIs. Your idea. Full project structure.",
                            fontSize = 13.sp, color = colors.Text2,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        Text("GGUF models only", fontSize = 10.sp, color = colors.Accent,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Model 1 — Planner
            ModelPickerCard(
                label = "⚙ Planner Model",
                subtitle = "Logic model — asks questions & plans",
                selected = model1Name,
                onPick = { showPicker = "m1" },
                colors = colors
            )

            // Model 2 — Coder
            ModelPickerCard(
                label = "💻 Coder Model",
                subtitle = "Code-specialized — builds the project plan",
                selected = model2Name,
                onPick = { showPicker = "m2" },
                colors = colors
            )

            // Researcher — 1B
            ModelPickerCard(
                label = "🔍 Researcher Model",
                subtitle = "~1B model — searches & extracts info",
                selected = researcherName,
                onPick = { showPicker = "res" },
                colors = colors
            )

            // Offline toggle
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.Card,
                border = BorderStroke(1.dp, colors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (offlineMode) Icons.Outlined.WifiOff else Icons.Outlined.Wifi,
                        null, tint = if (offlineMode) colors.Amber else colors.Text2,
                        modifier = Modifier.size(20.dp))
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

            Spacer(Modifier.height(8.dp))

            // Start button
            Button(
                onClick = {
                    onStart(model1Path, model1Name, model2Path, model2Name,
                        researcherPath, researcherName, offlineMode)
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                border = if (canStart) BorderStroke(1.dp, colors.Accent.copy(0.5f)) else null
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        if (canStart) Brush.horizontalGradient(listOf(colors.GradientStart, colors.GradientEnd))
                        else Brush.horizontalGradient(listOf(colors.Border, colors.Border)),
                        RoundedCornerShape(14.dp)
                    ),
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
    }

    // Model picker dialog
    if (showPicker != null) {
        AlertDialog(
            onDismissRequest = { showPicker = null },
            title = {
                Text(
                    when (showPicker) {
                        "m1" -> "Pick Planner Model"
                        "m2" -> "Pick Coder Model"
                        else -> "Pick Researcher Model"
                    },
                    color = colors.Text, fontFamily = FontFamily.Monospace
                )
            },
            text = {
                if (models.isEmpty()) {
                    Text("No GGUF models found. Add models first.",
                        color = colors.Text2, fontFamily = FontFamily.Monospace)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        models.forEach { model ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.CardLight,
                                border = BorderStroke(1.dp, colors.Border),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    when (showPicker) {
                                        "m1" -> { model1Path = model.path; model1Name = model.name }
                                        "m2" -> { model2Path = model.path; model2Name = model.name }
                                        "res" -> { researcherPath = model.path; researcherName = model.name }
                                    }
                                    showPicker = null
                                }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(model.name, fontSize = 13.sp, color = colors.Text,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                    Text(model.path.substringAfterLast("/"), fontSize = 10.sp,
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

@Composable
fun ModelPickerCard(
    label: String,
    subtitle: String,
    selected: String,
    onPick: () -> Unit,
    colors: com.gguf.zerocopy.ui.theme.ZcPalette
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.Card,
        border = BorderStroke(1.dp, if (selected.isNotEmpty()) colors.Accent.copy(0.4f) else colors.Border),
        modifier = Modifier.fillMaxWidth().clickable { onPick() }
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.Text, fontFamily = FontFamily.Monospace)
                Text(subtitle, fontSize = 11.sp, color = colors.Text3,
                    fontFamily = FontFamily.Monospace)
                if (selected.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("✓ $selected", fontSize = 11.sp, color = colors.Accent2,
                        fontFamily = FontFamily.Monospace)
                }
            }
            Icon(
                if (selected.isNotEmpty()) Icons.Filled.CheckCircle else Icons.Outlined.AddCircleOutline,
                null,
                tint = if (selected.isNotEmpty()) colors.Accent2 else colors.Text3,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
