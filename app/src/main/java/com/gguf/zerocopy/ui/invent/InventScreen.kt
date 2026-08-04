package com.gguf.zerocopy.ui.invent

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gguf.zerocopy.data.invent.*
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.local.SettingsManager.ModelTokenConfig
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.roundToInt
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─── Palette (kept local to this screen, mirrors theme accents) ─────────────
private val Cy = Color(0xFF00E5A0)
private val CyGlow = Color(0x6000E5A0)
private val Pr = Color(0xFF8B83FF)
private val Am = Color(0xFFFFB74D)
private val Rd = Color(0xFFFF6B6B)
private val Gy = Color(0xFF6A6A7A)

// ─── Animation specs ──────────────────────────────────────────────────────────
private val tweenFast = tween<Float>(300, easing = FastOutSlowInEasing)
private val tweenSlow = tween<Float>(500, easing = FastOutSlowInEasing)
private val springFast = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy)
private val springSlow = spring<Float>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioLowBouncy)
private val slideFast = tween<IntOffset>(300, easing = FastOutSlowInEasing)
private val slideSlow = tween<IntOffset>(500, easing = FastOutSlowInEasing)

// ─── Log builder ──────────────────────────────────────────────────────────────
private data class ChatBubble(
    val role: String,
    val content: String,
    val phase: InventPhase,
    val isUser: Boolean = false,
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val thinkingContent: String = ""
)

private fun buildChat(messages: List<InventMessage>): List<ChatBubble> {
    return messages.map { msg ->
        ChatBubble(
            role = msg.role,
            content = msg.content,
            phase = msg.phase,
            isUser = msg.role == "user",
            isError = msg.role == "system" && msg.content.contains("error", ignoreCase = true),
            thinkingContent = msg.thinkingContent
        )
    }
}

// ─── Phase helpers ────────────────────────────────────────────────────────────
private fun phaseLabel(phase: InventPhase): String = when (phase) {
    InventPhase.QUESTIONING -> "Questioning"
    InventPhase.SEARCHING -> "Searching"
    InventPhase.PLANNING -> "Planning"
    InventPhase.CONFIRMING -> "Confirming"
    InventPhase.GENERATING -> "Generating"
    InventPhase.REPLANNING -> "Replanning"
    InventPhase.FINALIZING -> "Finalizing"
    InventPhase.DONE -> "Complete"
    InventPhase.DEBUGGING -> "Debugging"
}

/** Ordered pipeline steps shown in the header stepper. */
private data class PipelineStep(val label: String, val phase: InventPhase)

private val pipeline = listOf(
    PipelineStep("ASK", InventPhase.QUESTIONING),
    PipelineStep("SEARCH", InventPhase.SEARCHING),
    PipelineStep("PLAN", InventPhase.PLANNING),
    PipelineStep("REVIEW", InventPhase.CONFIRMING),
    PipelineStep("BUILD", InventPhase.GENERATING),
    PipelineStep("FINAL", InventPhase.FINALIZING),
    PipelineStep("DONE", InventPhase.DONE)
)

private fun pipelineIndex(phase: InventPhase): Int = when (phase) {
    InventPhase.QUESTIONING -> 0
    InventPhase.SEARCHING -> 1
    InventPhase.PLANNING, InventPhase.REPLANNING -> 2
    InventPhase.CONFIRMING -> 3
    InventPhase.GENERATING -> 4
    InventPhase.FINALIZING -> 5
    InventPhase.DONE, InventPhase.DEBUGGING -> 6
}

private fun phaseColor(phase: InventPhase): Color = when (phase) {
    InventPhase.QUESTIONING -> Cy
    InventPhase.SEARCHING -> Pr
    InventPhase.PLANNING -> Am
    InventPhase.CONFIRMING -> Cy
    InventPhase.GENERATING -> Cy
    InventPhase.REPLANNING -> Am
    InventPhase.FINALIZING -> Pr
    InventPhase.DONE -> Cy
    InventPhase.DEBUGGING -> Rd
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN SCREEN
// ═══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun InventScreen(
    model1Path: String, model1Name: String,
    model2Path: String, model2Name: String,
    researcherPath: String, researcherName: String,
    offlineMode: Boolean, sameModelMode: Boolean,
    reasoningEnabled: Boolean = true,
    onBack: () -> Unit,
    onModelsClick: () -> Unit,
    onNewSession: (() -> Unit)? = null,
    vm: InventViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()
    val colors = currentPalette()
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showFilePanel by remember { mutableStateOf(false) }
    var coderChatActive by remember { mutableStateOf(false) }
    var coderChatFile by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf<Int?>(null) }
    var settingsRestrictRole by remember { mutableStateOf(-1) }
    var thinkRotate by remember { mutableStateOf(0f) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val chats = remember(ui.messages) { buildChat(ui.messages) }

    // File picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        scope.launch {
            uris.forEach { uri ->
                try {
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.filesDir, "invent_attachments").also { it.mkdirs() }
                        val mime = context.contentResolver.getType(uri) ?: "*/*"
                        val ext = when {
                            mime.contains("text") || mime.contains("json") || mime.contains("kotlin") ||
                            mime.contains("python") || mime.contains("java") || mime.contains("xml") ||
                            mime.contains("javascript") || mime.contains("html") || mime.contains("css") ||
                            mime.contains("yaml") || mime.contains("toml") || mime.contains("md") ->
                                ".${mime.substringAfterLast('/')}"
                            mime.contains("pdf") -> ".pdf"
                            else -> ".bin"
                        }
                        val name = "att_${System.currentTimeMillis()}$ext"
                        val f = File(dir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            f.outputStream().use { output -> input.copyTo(output) }
                        }
                        f
                    }
                    val content = withContext(Dispatchers.IO) {
                        if (file.length() < 50_000) file.readText()
                        else "[File too large: ${file.length()} bytes]"
                    }
                    vm.sendUserMessage("[Attached: ${uri.lastPathSegment}]\n\n$content")
                } catch (_: Exception) { }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ui.sessionId.isEmpty()) {
            vm.setupSession(model1Path, model1Name, model2Path, model2Name,
                researcherPath, researcherName, offlineMode, sameModelMode,
                reasoningEnabled = reasoningEnabled)
        }
    }
    LaunchedEffect(chats.size) {
        if (chats.isNotEmpty()) listState.animateScrollToItem(chats.size - 1)
    }

    // Animated phase color
    val animPhaseColor by animateColorAsState(
        targetValue = phaseColor(ui.phase),
        animationSpec = tween(400),
        label = "phaseColor"
    )

    Box(Modifier.fillMaxSize().background(colors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── Header (logo + actions + pipeline stepper) ─────────────────
            Column(Modifier.background(colors.Surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (vm.isBusyGenerating()) vm.setNavigateAway(true) else onBack()
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2, modifier = Modifier.size(18.dp))
                    }
                    // Logo tile
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Z", fontSize = 13.sp, fontWeight = FontWeight.Black,
                            color = colors.Bg, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Project name + mode
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            if (ui.projectName.isNotEmpty()) ui.projectName.take(22) else "New Project",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = colors.Text, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when (ui.modelMode) {
                                ModelMode.SINGLE -> "SOLO AGENT"
                                ModelMode.DUAL -> "DUO AGENTS"
                                ModelMode.TRIPLE -> "TRIO AGENTS"
                            },
                            fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            color = colors.Text3, fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Token chip
                    if (ui.totalTokensUsed > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.Accent.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.25f)),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("${ui.totalTokensUsed}t", fontSize = 9.sp,
                                color = colors.Accent, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                    // Animated action buttons
                    AnimatedVisibility(
                        visible = ui.phase == InventPhase.DONE || ui.phase == InventPhase.DEBUGGING,
                        enter = fadeIn(tweenFast) + scaleIn(initialScale = 0.8f),
                        exit = fadeOut(tweenFast) + scaleOut(targetScale = 0.8f)
                    ) {
                        HeaderBtn(Icons.Filled.FileDownload, "Export", Cy, onClick = { vm.exportProjectZip() })
                    }
                    // Thinking toggle with rotation
                    TextButton(
                        onClick = { vm.toggleReasoning(); thinkRotate += 360f },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("🧠", fontSize = 12.sp, color = if (ui.reasoningEnabled) colors.Accent2 else colors.Text3,
                            modifier = Modifier.graphicsLayer { rotationZ = thinkRotate })
                    }
                    HeaderBtn(Icons.Outlined.History, "Sessions", colors.Text2, onClick = {
                        vm.refreshSessionList()
                        showSessions = true
                    })
                    if (ui.fileTree.isNotEmpty()) {
                        HeaderBtn(
                            if (showFilePanel) Icons.Filled.Close else Icons.Filled.Description,
                            "Files",
                            if (showFilePanel) Cy else colors.Text2,
                            onClick = { showFilePanel = !showFilePanel }
                        )
                    }
                    HeaderBtn(Icons.Filled.Settings, "Settings", colors.Text2, onClick = { showSettings = true })
                    HeaderBtn(Icons.Filled.Refresh, "Restart", Color.White, onClick = {
                        vm.restartConversation()
                        onNewSession?.invoke()
                    })
                }
                // Pipeline stepper (segmented track + current phase label)
                PipelineStepper(phase = ui.phase, animColor = animPhaseColor, colors = colors)
            }

            // ── Model status pills ─────────────────────────────────────────
            ModelPills(
                modelMode = ui.modelMode,
                plannerLoaded = ui.plannerLoaded,
                researcherLoaded = ui.researcherLoaded,
                coderLoaded = ui.coderLoaded,
                plannerName = ui.model1Name,
                researcherName = ui.researcherName,
                coderName = ui.model2Name,
                phase = ui.phase,
                onTap = { roleIdx ->
                    showModelPicker = when (ui.modelMode) {
                        ModelMode.SINGLE -> -1
                        ModelMode.DUAL -> if (roleIdx == 1) 1 else -2
                        ModelMode.TRIPLE -> roleIdx
                    }
                },
                colors = colors
            )

            // ── Phase hint banner (fades in/out) ────────────────────────────
            AnimatedVisibility(
                visible = ui.phase != InventPhase.DONE && ui.phase != InventPhase.DEBUGGING && chats.isEmpty(),
                enter = fadeIn(tweenFast) + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut(tweenFast) + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                PhaseHint(ui.phase, colors)
            }

            // ── Main content area (files panel left + chat right) ────────
            Row(Modifier.weight(1f)) {
                // ── Animated left file panel ─────────────────────────────────
                AnimatedVisibility(
                    visible = showFilePanel && ui.fileTree.isNotEmpty(),
                    enter = slideInHorizontally(
                        animationSpec = slideSlow,
                        initialOffsetX = { -it }
                    ) + fadeIn(tweenFast),
                    exit = slideOutHorizontally(
                        animationSpec = slideFast,
                        targetOffsetX = { -it }
                    ) + fadeOut(tweenFast)
                ) {
                    FilePanel(
                        fileTree = ui.fileTree,
                        colors = colors,
                        vm = vm,
                        onClose = { showFilePanel = false },
                        onOpenCoderChat = if (ui.phase == InventPhase.DONE || ui.phase == InventPhase.DEBUGGING)
                            {{ coderChatFile = it; coderChatActive = true }} else null
                    )
                }

                // ── Chat / Coder chat (animated swap) ────────────────────────
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = coderChatActive,
                        transitionSpec = {
                            if (targetState) {
                                // Entering coder chat: slide in from right
                                slideInHorizontally(
                                    animationSpec = slideFast,
                                    initialOffsetX = { it }
                                ) + fadeIn(tweenFast) togetherWith
                                slideOutHorizontally(
                                    animationSpec = slideFast,
                                    targetOffsetX = { -it }
                                ) + fadeOut(tweenFast)
                            } else {
                                // Exiting coder chat: slide out to right
                                slideInHorizontally(
                                    animationSpec = slideFast,
                                    initialOffsetX = { -it }
                                ) + fadeIn(tweenFast) togetherWith
                                slideOutHorizontally(
                                    animationSpec = slideFast,
                                    targetOffsetX = { it }
                                ) + fadeOut(tweenFast)
                            }
                        },
                        label = "coderChat"
                    ) { isCoderActive ->
                        if (isCoderActive) {
                            CoderChatView(
                                filePath = coderChatFile,
                                vm = vm,
                                colors = colors,
                                onClose = { coderChatActive = false }
                            )
                        } else {
                            AnimatedContent(
                                targetState = chats.isEmpty() && ui.streamingResponse.isEmpty() && ui.swapInfo.isEmpty() && ui.error.isEmpty(),
                                transitionSpec = {
                                    fadeIn(tweenFast) + scaleIn(initialScale = 0.95f) togetherWith
                                    fadeOut(tweenFast) + scaleOut(targetScale = 0.95f)
                                },
                                label = "chatContent"
                            ) { isEmpty ->
                                if (isEmpty) {
                                    EmptyState(ui.phase, animPhaseColor, colors)
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // Swap banner
                                        if (ui.swapInfo.isNotEmpty()) {
                                            item(key = "swap") {
                                                StatusBanner(ui.swapInfo, Cy, colors)
                                            }
                                        }
                                        // Error
                                        if (ui.error.isNotEmpty()) {
                                            item(key = "err") {
                                                StatusBanner(ui.error, Rd, colors)
                                            }
                                        }
                                        // Chat bubbles with staggered animation
                                        itemsIndexed(chats, key = { i, _ -> "c_$i" }) { index, bubble ->
                                            StaggeredFadeIn(index = index, key = "c_$index") {
                                                ChatBubbleCard(bubble, colors)
                                            }
                                        }
                                        // Live streaming response
                                        if (ui.streamingResponse.isNotEmpty()) {
                                            val streamThink = Regex("<think>([\\s\\S]*?)(<\\/think>|$)").find(ui.streamingResponse)
                                            val streamContent = ui.streamingResponse
                                                .replace(Regex("<think>[\\s\\S]*?(<\\/think>|$)"), "").trim()
                                            val thinkText = streamThink?.groupValues?.getOrNull(1)?.trim() ?: ""
                                            item(key = "stream") {
                                                ChatBubbleCard(
                                                    ChatBubble(
                                                        role = "model1",
                                                        content = streamContent.ifEmpty { ui.streamingResponse },
                                                        phase = InventPhase.QUESTIONING,
                                                        isUser = false, isError = false,
                                                        isStreaming = true,
                                                        thinkingContent = thinkText
                                                    ), colors
                                                )
                                            }
                                        }
                                        // File progress
                                        if (ui.phase == InventPhase.GENERATING && ui.totalFiles > 0) {
                                            item(key = "fprog") {
                                                FileProgress(ui.currentFileIndex, ui.totalFiles, ui.currentFileName, Cy, colors)
                                            }
                                        }
                                        // Done stats
                                        if (ui.phase == InventPhase.DONE) {
                                            item(key = "stats") {
                                                DoneStats(ui.totalLines, ui.totalGeneratedBytes, ui.debugSessionCount, colors)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Questioning progress bar (Akinator-style, animated) ─────────
            AnimatedVisibility(
                visible = ui.phase == InventPhase.QUESTIONING && ui.chatStarted && ui.questioningProgress > 0f,
                enter = fadeIn(tweenSlow) + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut(tweenSlow) + shrinkVertically(shrinkTowards = Alignment.Bottom)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Planning progress", fontSize = 10.sp,
                            color = colors.Text3, fontFamily = FontFamily.Monospace)
                        Text("${(ui.questioningProgress * 100).roundToInt()}%", fontSize = 10.sp,
                            color = Cy, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { ui.questioningProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Cy,
                        trackColor = colors.Border.copy(alpha = 0.3f)
                    )
                }
            }

            // ── Input area (animated visibility by state) ─────────────────────
            if (!ui.chatStarted && ui.phase == InventPhase.QUESTIONING) {
                // Loading state
                Surface(Modifier.fillMaxWidth(), color = colors.Surface, shadowElevation = 2.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Cy)
                        Spacer(Modifier.width(8.dp))
                        Text("Planner is thinking of a first question…", fontSize = 11.sp,
                            color = colors.Text3, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                // Done button (animated)
                AnimatedVisibility(
                    visible = ui.phase == InventPhase.QUESTIONING && ui.chatStarted && !ui.isGenerating
                        && ui.conversationDepth >= 400,
                    enter = fadeIn(tweenFast) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tweenFast) + scaleOut(targetScale = 0.8f)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End) {
                        Surface(
                            onClick = vm::onDonePressed,
                            shape = RoundedCornerShape(8.dp),
                            color = Cy.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Cy)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Check, "Done", tint = Cy, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Done Gathering Info", fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, color = Cy,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                InputArea(
                    inputText = inputText,
                    onTextChange = { inputText = it },
                    onFilePick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            vm.sendUserMessage(inputText)
                            inputText = ""
                        }
                    },
                    onStop = { vm.cancelGeneration() },
                    phase = ui.phase,
                    totalTokens = ui.totalTokensUsed,
                    isGenerating = ui.isGenerating,
                    colors = colors
                )
            }
        }

        // ── Animated Dialogs ────────────────────────────────────────────
        AnimatedVisibility(
            visible = showSessions,
            enter = scaleIn(springFast) + fadeIn(tweenFast),
            exit = scaleOut(springFast) + fadeOut(tweenFast)
        ) {
            SessionPopup(
                sessions = ui.sessions, sessionId = ui.sessionId,
                onSwitch = { vm.switchToSession(it); showSessions = false },
                onDelete = { vm.deleteSessionById(it) },
                onDismiss = { showSessions = false },
                colors = colors, vm = vm
            )
        }
        AnimatedVisibility(
            visible = showSettings,
            enter = scaleIn(springFast) + fadeIn(tweenFast),
            exit = scaleOut(springFast) + fadeOut(tweenFast)
        ) {
            SettingsPopup2(
                onDismiss = { showSettings = false; settingsRestrictRole = -1 },
                colors = colors,
                model1Path = model1Path, model2Path = model2Path, researcherPath = researcherPath,
                modelMode = ui.modelMode, restrictRole = settingsRestrictRole,
                onReload = { vm.reloadInventModel() },
                reasoningEnabled = ui.reasoningEnabled,
                onToggleReasoning = { vm.toggleReasoning() }
            )
        }
        AnimatedVisibility(
            visible = showModelPicker != null,
            enter = scaleIn(springFast) + fadeIn(tweenFast),
            exit = scaleOut(springFast) + fadeOut(tweenFast)
        ) {
            showModelPicker?.let { roleIdx ->
                ModelPickerSheet(
                    roleIdx = roleIdx,
                    onDismiss = { showModelPicker = null },
                    onSelect = { path, name, useAll ->
                        when (roleIdx) {
                            -1 -> { vm.selectModelTab(0, path, name, useAll); vm.selectModelTab(1, path, name, useAll); vm.selectModelTab(2, path, name, useAll) }
                            -2 -> { vm.selectModelTab(0, path, name, false); vm.selectModelTab(2, path, name, false); settingsRestrictRole = 0 }
                            0  -> { vm.selectModelTab(0, path, name, false); settingsRestrictRole = 0 }
                            1  -> { vm.selectModelTab(1, path, name, false); settingsRestrictRole = 1 }
                            2  -> { vm.selectModelTab(2, path, name, false); settingsRestrictRole = 2 }
                        }
                        showModelPicker = null; showSettings = true
                    },
                    colors = colors
                )
            }
        }
        AnimatedVisibility(
            visible = ui.showNavigateAwayDialog,
            enter = scaleIn(springFast) + fadeIn(tweenFast),
            exit = scaleOut(springFast) + fadeOut(tweenFast)
        ) {
            AlertDialog(
                onDismissRequest = { vm.setNavigateAway(false) },
                title = { Text("Generation in Progress", fontFamily = FontFamily.Monospace) },
                text = { Text("Files already generated will be saved. You can resume later.", fontFamily = FontFamily.Monospace) },
                confirmButton = { TextButton(onClick = { vm.setNavigateAway(false); onBack() }) { Text("Leave", fontFamily = FontFamily.Monospace) } },
                dismissButton = { TextButton(onClick = { vm.setNavigateAway(false) }) { Text("Stay", fontFamily = FontFamily.Monospace) } },
                containerColor = colors.Card
            )
        }
    }
}

// ─── Staggered fade-in wrapper for list items ──────────────────────────────
@Composable
private fun StaggeredFadeIn(
    index: Int,
    key: String,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(index * 30L) // 30ms stagger per item
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(250)) + slideInVertically(
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
            initialOffsetY = { it / 4 }
        )
    ) {
        content()
    }
}

// ─── Pipeline stepper (header wiring) ────────────────────────────────────────
@Composable
private fun PipelineStepper(phase: InventPhase, animColor: Color, colors: ZcPalette) {
    val current = pipelineIndex(phase)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        // Segmented track
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            pipeline.forEachIndexed { i, _ ->
                val done = i < current
                val active = i == current
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            when {
                                active -> animColor
                                done -> colors.Accent2.copy(alpha = 0.6f)
                                else -> colors.Border.copy(alpha = 0.6f)
                            }
                        )
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Step dots + labels
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pipeline.forEachIndexed { i, step ->
                    val done = i < current
                    val active = i == current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(if (active) 8.dp else 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when {
                                        active -> animColor
                                        done -> colors.Accent2.copy(alpha = 0.6f)
                                        else -> colors.Border.copy(alpha = 0.6f)
                                    }
                                )
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            step.label,
                            fontSize = 7.5.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                active -> animColor
                                done -> colors.Accent2.copy(alpha = 0.75f)
                                else -> colors.Text3
                            },
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Text(
                "${phaseLabel(phase)} · ${current + 1}/${pipeline.size}",
                fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = animColor, fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

// ─── Header button (pressable with scale feedback) ─────────────────────────────
@Composable
private fun HeaderBtn(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            pressed = true
            onClick()
        },
        modifier = Modifier
            .size(30.dp)
            .scale(if (pressed) 0.85f else 1f)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp))
    }
    if (pressed) {
        LaunchedEffect(Unit) {
            delay(100)
            pressed = false
        }
    }
}

// ─── Empty state (animated, gradient orb) ───────────────────────────────────
@Composable
private fun EmptyState(phase: InventPhase, phaseColor: Color, colors: ZcPalette) {
    val (title, subtitle) = when (phase) {
        InventPhase.QUESTIONING -> "Tell me what to build" to "Describe your project idea below — I'll ask a few questions to shape it."
        InventPhase.SEARCHING -> "Scanning the web" to "Looking up current best practices and APIs for your project…"
        InventPhase.PLANNING -> "Drafting the blueprint" to "Building a file-by-file implementation plan…"
        InventPhase.CONFIRMING -> "Plan ready for review" to "Review the plan and confirm before I start writing code."
        InventPhase.GENERATING -> "Writing code files" to "Generating your project structure…"
        InventPhase.REPLANNING -> "Adjusting the plan" to "Tuning the plan based on your feedback…"
        InventPhase.FINALIZING -> "Wrapping up" to "Generating README and build instructions…"
        InventPhase.DONE -> "Mission complete" to "Export your project zip or start a fresh one."
        InventPhase.DEBUGGING -> "Debug session" to "Describe the issue and I'll fix it."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp)
        ) {
            // Glowing layered orb
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(104.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(phaseColor.copy(alpha = 0.06f))
                )
                Box(
                    Modifier.size(80.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(phaseColor.copy(alpha = 0.10f))
                )
                val bounce by rememberInfiniteTransition(label = "bounce")
                    .animateFloat(
                        initialValue = 0f, targetValue = -8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ), label = "bounce"
                    )
                Text("🧠", fontSize = 34.sp, modifier = Modifier.offset(y = bounce.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = colors.Text, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 11.5.sp,
                color = colors.Text3, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        }
    }
}

// ─── Phase hint ──────────────────────────────────────────────────────────────
@Composable
private fun PhaseHint(phase: InventPhase, colors: ZcPalette) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(10.dp),
        color = colors.Accent.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, colors.Accent.copy(alpha = 0.14f))
    ) {
        Text(
            when (phase) {
                InventPhase.QUESTIONING -> "💬  Tell me what you want to build and I'll ask questions to refine the idea."
                InventPhase.SEARCHING -> "🔍  Looking up current best practices and APIs for your project."
                InventPhase.PLANNING -> "📋  Drafting a file-by-file implementation plan."
                InventPhase.CONFIRMING -> "✅  Review the plan. Tap 'Sure' to proceed or 'Not Sure' to adjust."
                InventPhase.GENERATING -> "⚙️  Writing your project files…"
                InventPhase.REPLANNING -> "🔄  Adjusting the plan based on your feedback."
                InventPhase.FINALIZING -> "📦  Generating README and build instructions."
                InventPhase.DONE -> "✅  Done! Export the zip or start a new project."
                InventPhase.DEBUGGING -> "🔧  Debug mode — describe the issue."
            },
            fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// ─── Model pills (role chips with status) ─────────────────────────────────────
@Composable
private fun ModelPills(
    modelMode: ModelMode,
    plannerLoaded: Boolean, researcherLoaded: Boolean, coderLoaded: Boolean,
    plannerName: String, researcherName: String, coderName: String,
    phase: InventPhase, onTap: (Int) -> Unit, colors: ZcPalette
) {
    val pills = mutableListOf<Pair<Int, Triple<String, Boolean, String>>>()
    pills.add(0 to Triple("PLANNER", plannerLoaded, plannerName))
    if (modelMode != ModelMode.SINGLE) {
        pills.add(1 to Triple("RESEARCH", researcherLoaded, researcherName))
        pills.add(2 to Triple("CODER", coderLoaded, coderName))
    }
    val accents = listOf(Am, Pr, Cy)
    val emojis = listOf("⚙", "🔍", "💻")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        pills.forEachIndexed { idx, pair ->
            val (_, info) = pair
            val (label, loaded, name) = info
            val accent = accents[idx]
            val shortName = name.substringBeforeLast('.').take(12).ifEmpty { if (loaded) "ready" else "off" }
            Surface(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onTap(pair.first) },
                color = if (loaded) accent.copy(alpha = 0.10f) else colors.Surface,
                border = BorderStroke(1.dp, if (loaded) accent.copy(0.4f) else colors.Border.copy(0.25f))
            ) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (loaded) accent else colors.Text3.copy(0.3f)))
                    Spacer(Modifier.width(5.dp))
                    Text("${emojis[idx]} ${label.take(8)}", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = if (loaded) Color.White else colors.Text3, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(5.dp))
                    Text(shortName, fontSize = 8.5.sp,
                        color = if (loaded) accent else colors.Text3.copy(0.5f),
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ─── Status banner ────────────────────────────────────────────────────────────
@Composable
private fun StatusBanner(text: String, accent: Color, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.06f), border = BorderStroke(1.dp, accent.copy(0.18f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (accent != Rd) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = accent)
            Spacer(Modifier.width(6.dp))
            Text(text, color = accent, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Chat bubble (with smooth appearance) ─────────────────────────────────────
@Composable
private fun ChatBubbleCard(bubble: ChatBubble, colors: ZcPalette) {
    val roleColor = when (bubble.role) {
        "user" -> Cy
        "model1" -> Am
        "model2" -> Cy
        "researcher" -> Pr
        "system" -> Gy
        else -> colors.Text2
    }
    val roleLabel = when (bubble.role) {
        "user" -> "YOU"
        "model1" -> "PLANNER"
        "model2" -> "CODER"
        "researcher" -> "RESEARCH"
        "system" -> "SYS"
        else -> bubble.role.uppercase()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        // Role chip
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = roleColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, roleColor.copy(alpha = 0.3f))
            ) {
                Text(roleLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = roleColor, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
        // Thinking block
        if (bubble.thinkingContent.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Spacer(Modifier.height(5.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.Accent2.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, colors.Accent2.copy(0.18f)),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧠  Reasoning", fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, color = colors.Accent2,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text(if (expanded) "▲" else "▼", fontSize = 8.sp, color = colors.Text3)
                    }
                    AnimatedVisibility(visible = expanded) {
                        Text(bubble.thinkingContent, fontSize = 10.sp,
                            color = colors.Text3, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (bubble.isUser) Cy.copy(alpha = 0.04f) else colors.Surface,
            border = BorderStroke(1.dp, if (bubble.isUser) Cy.copy(0.10f) else colors.Border.copy(0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bubble.content,
                    fontSize = 12.5.sp, color = if (bubble.isError) Rd else colors.Text,
                    fontFamily = FontFamily.Monospace
                )
                if (bubble.isStreaming) {
                    StreamingCursor(roleColor)
                }
            }
        }
    }
}

@Composable
private fun StreamingCursor(color: Color) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            alpha.animateTo(0.15f, animationSpec = tween(300))
            alpha.animateTo(1f, animationSpec = tween(300))
        }
    }
    Box(Modifier.width(2.dp).height(13.dp).background(color.copy(alpha = alpha.value)))
    {}
}

// ─── File progress ────────────────────────────────────────────────────────────
@Composable
private fun FileProgress(index: Int, total: Int, name: String, accent: Color, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.06f), border = BorderStroke(1.dp, accent.copy(0.18f))) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(9.dp))
            Column {
                Text("Writing files…", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text("${index + 1}/$total  $name", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ─── Done stats ───────────────────────────────────────────────────────────────
@Composable
private fun DoneStats(lines: Int, bytes: Long, debugCount: Int, colors: ZcPalette) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 5.dp), color = Cy.copy(0.15f))
    Text("📦  Lines: $lines  ·  Size: ${bytes / 1024}KB  ·  Debug sessions: $debugCount",
        fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 5.dp))
}

// ─── Input area (with animated send button) ──────────────────────────────────
@Composable
private fun InputArea(
    inputText: String, onTextChange: (String) -> Unit,
    onFilePick: () -> Unit, onSend: () -> Unit,
    onStop: () -> Unit = {},
    phase: InventPhase, totalTokens: Int,
    isGenerating: Boolean = false,
    colors: ZcPalette
) {
    Surface(Modifier.fillMaxWidth().imePadding(), color = colors.Surface, shadowElevation = 2.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniToggle(Icons.Outlined.AttachFile, "Attach", false, onFilePick, colors)
                Spacer(Modifier.weight(1f))
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Cy)
                    Spacer(Modifier.width(4.dp))
                }
                if (totalTokens > 0) {
                    Text("${totalTokens}t", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(4.dp))
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onTextChange,
                    enabled = !isGenerating,
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 84.dp),
                    singleLine = false,
                    placeholder = {
                        Text(
                            when {
                                isGenerating -> "Waiting for response…"
                                phase == InventPhase.QUESTIONING -> "Describe your project…"
                                phase == InventPhase.DONE -> "All done! Export or start new."
                                else -> ">  type here…"
                            }, fontSize = 12.sp, color = colors.Text3, fontFamily = FontFamily.Monospace
                        )
                    },
                    textStyle = TextStyle(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cy.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.Border,
                        cursorColor = Cy
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank() && !isGenerating) onSend() }),
                    maxLines = 2
                )
                Spacer(Modifier.width(7.dp))
                // Animated send / stop button
                AnimatedContent(
                    targetState = isGenerating,
                    transitionSpec = {
                        fadeIn(tweenFast) + scaleIn(initialScale = 0.5f) togetherWith
                        fadeOut(tweenFast) + scaleOut(targetScale = 0.5f)
                    },
                    label = "sendStop"
                ) { generating ->
                    if (generating) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                .background(Rd.copy(alpha = 0.2f))
                                .clickable { onStop() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Close, "Stop", tint = Rd, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        val canSend = inputText.isNotBlank()
                        var sendPressed by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .scale(if (sendPressed) 0.9f else 1f)
                                .let { m ->
                                    if (canSend) m.background(Brush.linearGradient(listOf(Cy, Cy.copy(0.6f))))
                                    else m.background(colors.Border)
                                }
                                .clickable {
                                    if (canSend) {
                                        sendPressed = true
                                        onSend()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send, "Send",
                                tint = if (canSend) Color.Black else colors.Text3,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (sendPressed) {
                            LaunchedEffect(Unit) { delay(100); sendPressed = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit, colors: ZcPalette) {
    Surface(
        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).clickable { onClick() },
        color = if (active) Cy.copy(alpha = 0.12f) else Color.Transparent,
        border = BorderStroke(1.dp, if (active) Cy.copy(0.4f) else colors.Border.copy(0.35f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) Cy else colors.Text3, modifier = Modifier.size(12.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MODEL PICKER SHEET (animated)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun ModelPickerSheet(
    roleIdx: Int, onDismiss: () -> Unit,
    onSelect: (path: String, name: String, useForAll: Boolean) -> Unit,
    colors: ZcPalette
) {
    val app = com.gguf.zerocopy.ZeroCopyApp.instance
    val models by app.modelRepository.models.collectAsState()
    var useForAll by remember { mutableStateOf(false) }

    val roleLabel = when (roleIdx) {
        -2 -> "Planner + Coder"
        -1 -> "All Roles"
        0 -> "Planner"
        1 -> "Researcher"
        2 -> "Coder"
        else -> "Model"
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.72f).clickable {},
            shape = RoundedCornerShape(18.dp), color = colors.Card,
            border = BorderStroke(1.dp, colors.Border.copy(alpha = 0.4f))) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("$roleLabel Model", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clickable { useForAll = !useForAll }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useForAll, onCheckedChange = { useForAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = Cy, checkmarkColor = Color.Black))
                    Text("Use for all roles", fontSize = 11.sp, color = colors.Text, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
                if (models.isEmpty()) {
                    Text("No models found. Import from Models tab.", fontSize = 11.sp,
                        color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(10.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(models) { m ->
                            Surface(Modifier.fillMaxWidth().clickable { onSelect(m.path, m.name, useForAll) },
                                shape = RoundedCornerShape(10.dp), color = colors.Surface,
                                border = BorderStroke(1.dp, colors.Border.copy(0.35f))) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, fontSize = 11.5.sp, color = colors.Text,
                                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(2.dp))
                                        Row {
                                            Text(m.format.uppercase(), fontSize = 8.5.sp, color = Cy, fontFamily = FontFamily.Monospace)
                                            Text(" · ${m.sizeFormatted}", fontSize = 8.5.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                    Icon(Icons.Filled.PlayArrow, "Select", tint = Cy, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SESSION POPUP (animated)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun SessionPopup(
    sessions: List<SessionInfo>, sessionId: String,
    onSwitch: (String) -> Unit, onDelete: (String) -> Unit,
    onDismiss: () -> Unit, colors: ZcPalette, vm: InventViewModel
) {
    val context = LocalContext.current
    var selectedSession by remember { mutableStateOf<String?>(null) }
    var selectedFiles by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var selectedProjectName by remember { mutableStateOf("") }
    var selectedPhase by remember { mutableStateOf<InventPhase?>(null) }

    LaunchedEffect(selectedSession) {
        if (selectedSession != null) {
            val z = InventStorage.loadZcp(context, selectedSession!!)
            val st = InventStorage.loadSession(context, selectedSession!!)
            selectedFiles = z?.fileTree ?: emptyList()
            selectedProjectName = z?.projectName ?: ""
            selectedPhase = st?.phase
        } else { selectedFiles = emptyList(); selectedProjectName = ""; selectedPhase = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.72f).clickable {},
            shape = RoundedCornerShape(18.dp), color = colors.Card,
            border = BorderStroke(1.dp, colors.Border.copy(alpha = 0.4f))) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selectedSession != null) selectedSession = null else onDismiss() }) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text(if (selectedSession != null) "Session Files" else "Sessions",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                // Animated content swap (session list ↔ file list)
                AnimatedContent(
                    targetState = selectedSession,
                    transitionSpec = {
                        fadeIn(tweenFast) + slideInHorizontally { it / 4 } togetherWith
                        fadeOut(tweenFast) + slideOutHorizontally { -it / 4 }
                    },
                    label = "sessionFiles"
                ) { sess ->
                    if (sess != null) {
                        // Files view
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                            color = Cy.copy(alpha = 0.10f), border = BorderStroke(1.dp, Cy.copy(0.35f))) {
                            Row(Modifier.clickable { onSwitch(sess) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Cy, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Continue ${selectedProjectName.ifEmpty { "Session" }}", fontSize = 11.sp, color = Cy,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                if (selectedPhase != null) Text(selectedPhase!!.name, fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            items(selectedFiles) { node ->
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
                                    color = colors.Surface.copy(alpha = 0.5f)) {
                                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description, null,
                                            tint = if (node.isDir) Am else Cy, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text(node.path, fontSize = 10.5.sp, color = colors.Text, fontFamily = FontFamily.Monospace,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (selectedFiles.isEmpty()) item { Text("No files yet", fontSize = 10.5.sp, color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(6.dp)) }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(sessions) { s ->
                                val isCurrent = s.id == sessionId
                                Surface(Modifier.fillMaxWidth().clickable { selectedSession = s.id },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isCurrent) Cy.copy(alpha = 0.08f) else colors.Surface,
                                    border = if (isCurrent) BorderStroke(1.dp, Cy.copy(0.35f)) else BorderStroke(1.dp, colors.Border.copy(0.2f))) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(s.projectName, fontSize = 11.5.sp, color = colors.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.height(2.dp))
                                            Row {
                                                Text(s.phase.name, fontSize = 8.5.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                                if (s.fileCount > 0) Text(" · ${s.fileCount} files", fontSize = 8.5.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                        IconButton(onClick = { onSwitch(s.id) }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Filled.PlayArrow, "Switch", tint = Cy, modifier = Modifier.size(14.dp))
                                        }
                                        IconButton(onClick = { onDelete(s.id) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Outlined.DeleteOutline, "Delete", tint = Rd.copy(0.5f), modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                            if (sessions.isEmpty()) item { Text("No saved sessions", fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(10.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SETTINGS POPUP (animated)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun SettingsPopup2(
    onDismiss: () -> Unit, colors: ZcPalette,
    model1Path: String, model2Path: String, researcherPath: String,
    modelMode: ModelMode = ModelMode.TRIPLE, restrictRole: Int = -1,
    onReload: () -> Unit = {},
    reasoningEnabled: Boolean = false,
    onToggleReasoning: () -> Unit = {}
) {
    var settingsTab by remember { mutableIntStateOf(0) }

    val getCfg = { role: String -> SettingsManager.getInventModelConfig(role) }
    val plannerCfg = remember(model1Path) { getCfg("Planner") }
    val researcherCfg = remember(researcherPath) { getCfg("Researcher") }
    val coderCfg = remember(model2Path) { getCfg("Coder") }

    val allTabs = when (modelMode) {
        ModelMode.SINGLE -> listOf("Planner" to "Planner")
        ModelMode.DUAL -> listOf("Planner+Coder" to "Planner", "Researcher" to "Researcher")
        ModelMode.TRIPLE -> listOf("Planner" to "Planner", "Researcher" to "Researcher", "Coder" to "Coder")
    }
    val tabs = if (restrictRole < 0) allTabs else allTabs.filterIndexed { i, _ -> i == restrictRole }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.72f).clickable {},
            shape = RoundedCornerShape(18.dp), color = colors.Card,
            border = BorderStroke(1.dp, colors.Border.copy(alpha = 0.4f))) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text("Model Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                // Animated tab chips
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    tabs.forEachIndexed { i, (label, _) ->
                        val isActive = settingsTab == i
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isActive) Cy.copy(alpha = 0.12f) else colors.Surface,
                            border = BorderStroke(1.dp, if (isActive) Cy else colors.Border),
                            modifier = Modifier.clickable { settingsTab = i }
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isActive) Cy else colors.Text3, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Config area with animated content
                AnimatedContent(
                    targetState = settingsTab,
                    transitionSpec = {
                        fadeIn(tweenFast) + slideInHorizontally { it / 3 } togetherWith
                        fadeOut(tweenFast) + slideOutHorizontally { -it / 3 }
                    },
                    label = "settingsTabs"
                ) { tab ->
                    val (_, roleKey) = if (tab < tabs.size) tabs[tab] else ("Planner" to "Planner")
                    val cfg = when (roleKey) {
                        "Planner" -> plannerCfg; "Researcher" -> researcherCfg; else -> coderCfg
                    }
                    val path = when (roleKey) {
                        "Planner" -> model1Path; "Researcher" -> researcherPath; else -> model2Path
                    }
                    ConfigSliders(role = roleKey, config = cfg, modelPath = path, colors)
                }
                Spacer(Modifier.height(6.dp))
                // Thinking toggle
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠 Reasoning", fontSize = 10.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = reasoningEnabled,
                        onCheckedChange = { onToggleReasoning() },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Cy, checkedThumbColor = colors.Bg,
                            uncheckedTrackColor = colors.Border, uncheckedThumbColor = colors.Text3
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onReload(); onDismiss() }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Cy)) {
                    Text("Confirm ✓", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun ConfigSliders(role: String, config: ModelTokenConfig?, modelPath: String, colors: ZcPalette) {
    val modelName = modelPath.substringAfterLast('/').substringAfterLast('\\').take(28)
    var ctx by remember { mutableStateOf(config?.ctx ?: 2048) }
    var maxNew by remember { mutableStateOf(config?.maxNew ?: 512) }
    var gpuLayers by remember { mutableStateOf(config?.gpuLayers ?: 0) }
    var temp by remember { mutableStateOf(config?.temperature ?: 0.7f) }
    var topP by remember { mutableStateOf(config?.topP ?: 0.9f) }

    fun save() {
        val base = config ?: ModelTokenConfig(ctx = 2048, maxNew = 512, gpuLayers = 0)
        SettingsManager.setInventModelConfig(role, base.copy(ctx = ctx, maxNew = maxNew, gpuLayers = gpuLayers, temperature = temp, topP = topP))
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        Text(role, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.Monospace)
        Text(modelName, fontSize = 9.5.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Context", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = ctx.toString(),
                onValueChange = { v: String -> val n = v.filter { it.isDigit() }.toIntOrNull(); if (n != null) { ctx = n.coerceIn(512, 32768); save() } },
                modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cy, unfocusedBorderColor = colors.Border, cursorColor = Cy, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text),
                shape = RoundedCornerShape(8.dp)
            )
        }
        Slider(value = ctx.toFloat(), onValueChange = { ctx = it.roundToInt().coerceIn(512, 32768); save() },
            valueRange = 512f..32768f,
            modifier = Modifier.fillMaxWidth().height(20.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Max Tokens", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = maxNew.toString(),
                onValueChange = { v: String -> val n = v.filter { it.isDigit() }.toIntOrNull(); if (n != null) { maxNew = n.coerceIn(64, ctx - 64); save() } },
                modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cy, unfocusedBorderColor = colors.Border, cursorColor = Cy, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text),
                shape = RoundedCornerShape(8.dp)
            )
        }
        Slider(value = maxNew.toFloat(), onValueChange = { maxNew = it.roundToInt().coerceIn(64, ctx - 64); save() },
            valueRange = 64f..(ctx - 64).coerceAtLeast(128).toFloat(),
            modifier = Modifier.fillMaxWidth().height(20.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))

        SliderRow("GPU Layers", gpuLayers.toFloat(), -1f..200f, 201, { if (it.toInt() < 0) "All" else "${it.toInt()}" }, { gpuLayers = it.toInt(); save() }, colors)
        SliderRow("Temperature", temp, 0.0f..2.0f, 40, { "%.2f".format(it) }, { temp = it; save() }, colors)
        SliderRow("Top-P", topP, 0.0f..1.0f, 20, { "%.2f".format(it) }, { topP = it; save() }, colors)
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int, format: (Float) -> String, onChange: (Float) -> Unit, colors: ZcPalette
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 10.sp, color = colors.Text2, fontFamily = FontFamily.Monospace)
            Text(format(value), fontSize = 10.sp, color = Cy, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().height(20.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CODER CHAT (with slide-in animation on the parent)
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun CoderChatView(
    filePath: String,
    vm: InventViewModel,
    colors: ZcPalette,
    onClose: () -> Unit
) {
    var coderInput by remember { mutableStateOf("") }
    val ui by vm.ui.collectAsState()
    val coderMessages = remember(ui.messages) {
        ui.messages.filter { it.role == "user" || it.role == "coder" }
    }
    val listState = rememberLazyListState()

    // Auto-scroll on new coder messages
    LaunchedEffect(coderMessages.size) {
        if (coderMessages.isNotEmpty()) listState.animateScrollToItem(coderMessages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.Card,
            shadowElevation = 1.dp
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Chat, null, tint = Cy, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Chat with Coder", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = colors.Text, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text(filePath.substringAfterLast('/'), fontSize = 10.sp, color = colors.Text3,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Close, "Close", tint = colors.Text3, modifier = Modifier.size(15.dp))
                }
            }
        }
        HorizontalDivider(color = colors.Border.copy(alpha = 0.3f))

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(coderMessages, key = { i, _ -> "coder_$i" }) { index, msg ->
                val isUser = msg.role == "user"
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(300)) + slideInVertically(
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f),
                        initialOffsetY = { it / 2 }
                    )
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isUser) colors.Accent.copy(alpha = 0.12f) else colors.CardLight,
                        border = BorderStroke(1.dp, colors.Border.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            msg.content,
                            fontSize = 12.5.sp, color = colors.Text,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        // Input
        HorizontalDivider(color = colors.Border.copy(alpha = 0.3f))
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = coderInput,
                onValueChange = { coderInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the coder about this file…", fontSize = 11.sp,
                    color = colors.Text3, fontFamily = FontFamily.Monospace) },
                minLines = 1, maxLines = 3,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (coderInput.isNotBlank()) {
                        vm.handleCoderChatMessage(coderInput, filePath)
                        coderInput = ""
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cy.copy(alpha = 0.5f),
                    unfocusedBorderColor = colors.Border,
                    focusedContainerColor = colors.Card,
                    unfocusedContainerColor = colors.Card,
                    focusedTextColor = colors.Text,
                    unfocusedTextColor = colors.Text
                ),
                textStyle = TextStyle(fontSize = 12.5.sp)
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (coderInput.isNotBlank()) {
                        vm.handleCoderChatMessage(coderInput, filePath)
                        coderInput = ""
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Cy, modifier = Modifier.size(18.dp))
            }
        }
    }
}
