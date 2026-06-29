package com.gguf.zerocopy.ui.invent

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gguf.zerocopy.data.invent.FileNode
import com.gguf.zerocopy.data.invent.InventMessage
import com.gguf.zerocopy.data.invent.InventPhase
import com.gguf.zerocopy.ui.theme.currentPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventScreen(
    model1Path: String,
    model1Name: String,
    model2Path: String,
    model2Name: String,
    researcherPath: String,
    researcherName: String,
    offlineMode: Boolean,
    onBack: () -> Unit
) {
    val vm: InventViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val colors = currentPalette()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    // Setup session once
    LaunchedEffect(Unit) {
        if (ui.sessionId.isEmpty()) {
            vm.setupSession(model1Path, model1Name, model2Path, model2Name, researcherPath, researcherName, offlineMode)
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) {
            listState.animateScrollToItem(ui.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Invent", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, color = colors.Text)
                        Text(phaseLabel(ui.phase), fontSize = 11.sp, color = colors.Accent,
                            fontFamily = FontFamily.Monospace)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
                    }
                },
                actions = {
                    // Phase indicator dots
                    PhaseIndicator(ui.phase, colors.Accent, colors.Border)
                    Spacer(Modifier.width(8.dp))
                    // Delete session
                    IconButton(onClick = { vm._ui.value.let { /* trigger delete confirm */ } }) {
                        Icon(Icons.Outlined.DeleteOutline, "Clear session", tint = colors.Text3)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Surface)
            )
        },
        containerColor = colors.Bg
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {

            Column(Modifier.fillMaxSize()) {

                // ── Offline banner ──────────────────────────────────────────
                if (ui.offlineMode) {
                    OfflineBanner(colors)
                }

                // ── Model swap banner ────────────────────────────────────────
                AnimatedVisibility(visible = ui.swapInfo.isNotEmpty()) {
                    SwapBanner(ui.swapInfo, colors)
                }

                // ── Merge banner ─────────────────────────────────────────────
                AnimatedVisibility(visible = ui.showMergeBanner) {
                    MergeBanner(
                        mergeCount = ui.mergeCount,
                        onMerge = { vm.onMergeConfirmed() },
                        onCancel = { /* dismiss */ },
                        colors = colors
                    )
                }

                // ── Search progress ──────────────────────────────────────────
                if (ui.phase == InventPhase.SEARCHING && ui.searchRound > 0) {
                    SearchProgressBar(ui.searchRound, colors)
                }

                // ── Messages ─────────────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(ui.messages) { _, msg ->
                        InventBubble(msg, colors)
                    }

                    // Generating indicator
                    if (ui.isGenerating && ui.swapInfo.isEmpty()) {
                        item {
                            ThinkingDots(colors)
                        }
                    }

                    // File tree display
                    if (ui.phase == InventPhase.DONE && ui.fileTree.isNotEmpty()) {
                        item {
                            FileTreeCard(ui.fileTree, colors)
                        }
                    }

                    // Sure / Not Sure buttons
                    if (ui.showSureButtons) {
                        item {
                            SureButtons(
                                onSure = { vm.onSure() },
                                onNotSure = { vm.onNotSure() },
                                colors = colors
                            )
                        }
                    }
                }

                // ── Input bar ────────────────────────────────────────────────
                if (ui.phase == InventPhase.QUESTIONING) {
                    InventInputBar(
                        text = inputText,
                        onTextChange = { inputText = it },
                        onSend = {
                            if (inputText.isNotBlank()) {
                                vm.sendUserMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        onSearch = { vm.onSearchButtonPressed() },
                        isGenerating = ui.isGenerating,
                        colors = colors
                    )
                }
            }

            // ── Delete confirm dialog ─────────────────────────────────────────
            if (ui.showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Delete session?", color = colors.Text) },
                    text = { Text("This will permanently delete all session files and cannot be undone.", color = colors.Text2) },
                    confirmButton = {
                        TextButton(onClick = { vm.onDeleteConfirmed() }) {
                            Text("Delete", color = colors.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { }) {
                            Text("Cancel", color = colors.Text2)
                        }
                    },
                    containerColor = colors.Card
                )
            }

            // ── Error snack ───────────────────────────────────────────────────
            if (ui.error.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.Red.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, colors.Red.copy(alpha = 0.4f))
                    ) {
                        Text(
                            ui.error,
                            modifier = Modifier.padding(12.dp, 8.dp),
                            color = colors.Red,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
fun OfflineBanner(colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    Row(
        Modifier.fillMaxWidth().background(colors.Amber.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.WifiOff, null, tint = colors.Amber, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Offline mode — results from model knowledge only", fontSize = 11.sp,
            color = colors.Amber, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SwapBanner(info: String, colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(colors.GradientStart.copy(0.08f), colors.GradientEnd.copy(0.08f))))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = colors.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(info, fontSize = 12.sp, color = colors.Accent.copy(alpha = alpha),
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun MergeBanner(mergeCount: Int, onMerge: () -> Unit, onCancel: () -> Unit,
    colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    Surface(
        Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.Purple.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, colors.Purple.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Merge sessions? (${2 - mergeCount} attempts left)",
                fontWeight = FontWeight.SemiBold, color = colors.Purple,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("Both sessions will be merged into a new one. Old sessions will be deleted.",
                fontSize = 12.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel,
                    border = BorderStroke(1.dp, colors.Border),
                    shape = RoundedCornerShape(8.dp)) {
                    Text("Cancel", color = colors.Text2, fontSize = 12.sp)
                }
                Button(onClick = onMerge,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.Purple),
                    shape = RoundedCornerShape(8.dp)) {
                    Text("Merge & Restart", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun SearchProgressBar(round: Int, colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, tint = colors.Accent2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Search round $round", fontSize = 11.sp, color = colors.Accent2,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            modifier = Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp)),
            color = colors.Accent2,
            trackColor = colors.Border
        )
    }
}

@Composable
fun InventBubble(msg: InventMessage, colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"
    val bgColor = when (msg.role) {
        "user" -> colors.UserBg
        "model1" -> colors.Card
        "model2" -> colors.Surface.copy(alpha = 0.8f)
        "researcher" -> colors.ThinkBg
        else -> colors.Border.copy(alpha = 0.3f)
    }
    val roleLabel = when (msg.role) {
        "model1" -> "⚙ Planner"
        "model2" -> "💻 Coder"
        "researcher" -> "🔍 Researcher"
        "user" -> "You"
        else -> "System"
    }

    if (isSystem) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(msg.content, fontSize = 11.sp, color = colors.Text3,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 4.dp))
        }
        return
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(roleLabel, fontSize = 10.sp, color = colors.Text3,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp
            ),
            color = bgColor,
            border = if (!isUser) BorderStroke(1.dp, colors.Border) else null,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                msg.content,
                modifier = Modifier.padding(12.dp, 10.dp),
                color = colors.Text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun SureButtons(onSure: () -> Unit, onNotSure: () -> Unit,
    colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        OutlinedButton(
            onClick = onNotSure,
            border = BorderStroke(1.dp, colors.Red.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Close, null, tint = colors.Red, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Not Sure", color = colors.Red, fontFamily = FontFamily.Monospace)
        }
        Button(
            onClick = onSure,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent
            ),
            border = BorderStroke(1.dp, colors.Accent2.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).background(
                Brush.horizontalGradient(listOf(colors.GradientStart.copy(0.2f), colors.GradientEnd.copy(0.2f))),
                RoundedCornerShape(12.dp)
            )
        ) {
            Icon(Icons.Filled.Check, null, tint = colors.Accent2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Sure", color = colors.Accent2, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun FileTreeCard(tree: List<FileNode>, colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.Card,
        border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("📁 Project Structure", fontWeight = FontWeight.Bold,
                color = colors.Accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            tree.forEach { node ->
                val depth = node.path.count { it == '/' }
                Row(
                    Modifier.padding(start = (depth * 12).dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (node.isDir) "📂" else "📄",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        node.path.substringAfterLast("/"),
                        fontSize = 12.sp,
                        color = if (node.isDir) colors.Accent else colors.Text2,
                        fontFamily = FontFamily.Monospace
                    )
                    if (node.description.isNotEmpty()) {
                        Text(" // ${node.description}", fontSize = 10.sp,
                            color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun InventInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSearch: () -> Unit,
    isGenerating: Boolean,
    colors: com.gguf.zerocopy.ui.theme.ZcPalette
) {
    Surface(
        color = colors.Surface,
        border = BorderStroke(1.dp, colors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Answer...", color = colors.Text3,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.Text,
                        unfocusedTextColor = colors.Text,
                        focusedBorderColor = colors.Accent.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.Border,
                        cursorColor = colors.Accent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )

                // Send button
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank() && !isGenerating,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(
                            if (text.isNotBlank() && !isGenerating)
                                Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))
                            else Brush.linearGradient(listOf(colors.Border, colors.Border))
                        )
                ) {
                    Icon(Icons.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Search trigger button — appears after a few messages
            OutlinedButton(
                onClick = onSearch,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colors.Accent2.copy(alpha = if (!isGenerating) 0.6f else 0.2f))
            ) {
                Icon(Icons.Outlined.Search, null, tint = colors.Accent2,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Done talking — start search & plan",
                    color = colors.Accent2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ThinkingDots(colors: com.gguf.zerocopy.ui.theme.ZcPalette) {
    val inf = rememberInfiniteTransition(label = "dots")
    val offset by inf.animateFloat(0f, 3f,
        infiniteRepeatable(tween(900), RepeatMode.Restart), label = "o")
    Row(
        Modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val alpha = if (offset.toInt() == i) 1f else 0.3f
            Box(Modifier.size(6.dp).clip(CircleShape).background(colors.Accent.copy(alpha = alpha)))
        }
    }
}

@Composable
fun PhaseIndicator(phase: InventPhase, active: Color, inactive: Color) {
    val phases = InventPhase.values().filter { it != InventPhase.NOT_SURE }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        phases.forEachIndexed { i, p ->
            val isActive = p.ordinal <= phase.ordinal
            Box(Modifier.size(6.dp).clip(CircleShape)
                .background(if (isActive) active else inactive))
        }
    }
}

fun phaseLabel(phase: InventPhase) = when (phase) {
    InventPhase.QUESTIONING -> "Gathering info"
    InventPhase.SEARCHING -> "Researching"
    InventPhase.PLANNING -> "Planning"
    InventPhase.CONFIRMING -> "Review"
    InventPhase.DONE -> "Done"
    InventPhase.NOT_SURE -> "Refining"
}
