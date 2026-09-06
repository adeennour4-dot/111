package com.gguf.zerocopy.ui.invent
import com.gguf.zerocopy.ui.theme.ZcShape

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.gguf.zerocopy.ui.components.GradientBubbleBox
import com.gguf.zerocopy.ui.components.GradientSearchingCircle
import com.gguf.zerocopy.ui.components.GradientThinkingCircle
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

// ─── Accent palette (local to this screen, mirrors theme) ─────────────────────
private val Cy: Color
    @Composable get() = currentPalette().Accent2
private val CyGlow: Color
    @Composable get() = currentPalette().Accent2.copy(alpha = 0.38f)
private val Pr: Color
    @Composable get() = currentPalette().Accent
private val Am: Color
    @Composable get() = currentPalette().Cyan
private val Rd: Color
    @Composable get() = currentPalette().Red
private val Gy: Color
    @Composable get() = currentPalette().Text3
private val Bulb: Color
    @Composable get() = currentPalette().Cyan

// ─── Animation specs ──────────────────────────────────────────────────────────
private val tweenFast = tween<Float>(300, easing = FastOutSlowInEasing)
private val tweenSlow = tween<Float>(500, easing = FastOutSlowInEasing)
private val springFast = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy)
private val springSlow = spring<Float>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioLowBouncy)
private val slideFast = tween<IntOffset>(300, easing = FastOutSlowInEasing)
private val slideSlow = tween<IntOffset>(500, easing = FastOutSlowInEasing)

// ─── Chat model ───────────────────────────────────────────────────────────────
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

private val pipeline = listOf("ASK", "SEARCH", "PLAN", "REVIEW", "BUILD", "FINAL", "DONE")

private fun pipelineIndex(phase: InventPhase): Int = when (phase) {
    InventPhase.QUESTIONING -> 0
    InventPhase.SEARCHING -> 1
    InventPhase.PLANNING, InventPhase.REPLANNING -> 2
    InventPhase.CONFIRMING -> 3
    InventPhase.GENERATING -> 4
    InventPhase.FINALIZING -> 5
    InventPhase.DONE, InventPhase.DEBUGGING -> 6
}

@Composable
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
// MAIN SCREEN — rebuilt from scratch (visuals), all vm.*/ui.* wiring preserved
// ═══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun InventScreen(
    model1Path: String, model1Name: String,
    model2Path: String, model2Name: String,
    debuggerPath: String, debuggerName: String,
    offlineMode: Boolean, sameModelMode: Boolean,
    reasoningEnabled: Boolean = true,
    onBack: () -> Unit,
    onModelsClick: () -> Unit,
    onNewSession: (() -> Unit)? = null,
    startFresh: Boolean = false,
    sessionToOpen: String? = null,
    onSessionCreated: (String) -> Unit = {},
    onDeleteProject: (() -> Unit)? = null,
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
        // A session is only re-entered (not re-created) when the user reaches
        // InventScreen WITHOUT going through the setup flow (process-death
        // auto-resume). When the user explicitly picked models in setup, always
        // start a fresh session with those exact models — never a stale one.
        if (!sessionToOpen.isNullOrEmpty()) {
            // Opening an existing session from the dashboard
            vm.switchToSession(sessionToOpen)
        } else if (ui.sessionId.isEmpty() || startFresh) {
            vm.setupSession(model1Path, model1Name, model2Path, model2Name,
                debuggerPath, debuggerName, offlineMode, sameModelMode,
                reasoningEnabled = reasoningEnabled)
        }
    }
    // Register freshly-created sessions with the dashboard project
    LaunchedEffect(ui.sessionId) {
        if (startFresh && ui.sessionId.isNotEmpty()) onSessionCreated(ui.sessionId)
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
            // ══ HEADER — mission strip + action rail + flow ribbon ══
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
                    // Pulsing phase orb
                    PhaseOrb(animPhaseColor)
                    Spacer(Modifier.width(8.dp))
                    // Project name
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            if (ui.projectName.isNotEmpty()) ui.projectName.take(22) else "New Project",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = colors.Text, fontFamily = FontFamily.SansSerif,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            phaseLabel(ui.phase).uppercase(),
                            fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            color = colors.Text3, fontFamily = FontFamily.SansSerif,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Token chip
                    if (ui.totalTokensUsed > 0) {
                        Surface(
                            shape = ZcShape.Sm,
                            color = colors.Accent.copy(alpha = 0.10f),
                            border = BorderStroke(0.2.dp, colors.Accent.copy(alpha = 0.25f)),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("${ui.totalTokensUsed}t", fontSize = 9.sp,
                                color = colors.Accent, fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
                // Action rail (compact chips)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AnimatedVisibility(
                        visible = ui.phase == InventPhase.DONE || ui.phase == InventPhase.DEBUGGING,
                        enter = fadeIn(tweenFast) + scaleIn(initialScale = 0.8f),
                        exit = fadeOut(tweenFast) + scaleOut(targetScale = 0.8f)
                    ) {
                        ActionChip(Icons.Filled.FileDownload, "Export", Cy) { vm.exportProjectZip() }
                    }
                    ActionChip(Icons.Outlined.History, "Sessions", colors.Text2) {
                        vm.refreshSessionList()
                        showSessions = true
                    }
                    if (ui.fileTree.isNotEmpty()) {
                        ActionChip(
                            if (showFilePanel) Icons.Filled.Close else Icons.Filled.Description,
                            "Files",
                            if (showFilePanel) Cy else colors.Text2,
                            active = showFilePanel
                        ) { showFilePanel = !showFilePanel }
                    }
                    ActionChip(Icons.Filled.Settings, "Settings", colors.Text2) { onModelsClick() }
                    ActionChip(Icons.Filled.Refresh, "Restart", Color.White) {
                        vm.restartConversation()
                        onNewSession?.invoke()
                    }
                }
                // Flow ribbon pipeline
                FlowRibbon(phase = ui.phase, animColor = animPhaseColor, colors = colors)
            }

            // ══ Model status monograms ══
            ModelPills(
                modelMode = ui.modelMode,
                plannerLoaded = ui.plannerLoaded,
                debuggerLoaded = ui.debuggerLoaded,
                coderLoaded = ui.coderLoaded,
                plannerName = ui.model1Name,
                debuggerName = ui.debuggerName,
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

            // ══ Chat with a role selector ══
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("planner" to "PLANNER" to Am, "coder" to "CODER" to Cy, "debugger" to "DEBUGGER" to Rd)
                    .forEach { (kv, accent) ->
                        val (role, label) = kv
                        val active = ui.chatRole == role
                        Surface(
                            onClick = { vm.setChatRole(role) },
                            shape = ZcShape.Sm,
                            color = if (active) accent.copy(alpha = 0.14f) else colors.Surface,
                            border = BorderStroke(0.2.dp, if (active) accent.copy(alpha = 0.7f) else colors.Border.copy(alpha = 0.3f))
                        ) {
                            Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) accent else colors.Border))
                                Spacer(Modifier.width(5.dp))
                                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    color = if (active) accent else colors.Text3, fontFamily = FontFamily.SansSerif)
                            }
                        }
                    }
            }

            // ══ Phase hint banner ══
            AnimatedVisibility(
                visible = ui.phase != InventPhase.DONE && ui.phase != InventPhase.DEBUGGING && chats.isEmpty(),
                enter = fadeIn(tweenFast) + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut(tweenFast) + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                PhaseHint(ui.phase, colors)
            }

            // ══ Main content area ══
            Row(Modifier.weight(1f)) {
                // Animated left file panel
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

                // Chat / coder chat
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = coderChatActive,
                        transitionSpec = {
                            if (targetState) {
                                slideInHorizontally(
                                    animationSpec = slideFast,
                                    initialOffsetX = { it }
                                ) + fadeIn(tweenFast) togetherWith
                                slideOutHorizontally(
                                    animationSpec = slideFast,
                                    targetOffsetX = { -it }
                                ) + fadeOut(tweenFast)
                            } else {
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
                        } else if (ui.phase == InventPhase.CONFIRMING && ui.fileTree.isNotEmpty()) {
                            // PLAN REVIEW — tree preview; approve/regenerate via the footer
                            PlanReviewPanel(
                                fileTree = ui.fileTree,
                                projectName = ui.projectName,
                                colors = colors
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
                                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 0.dp),
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
                                        // Research libraries button (under the planner summary)
                                        if (ui.awaitingResearch && !ui.isGenerating) {
                                            item(key = "research") {
                                                ResearchLibrariesCard(colors, onResearch = { vm.researchLibraries() })
                                            }
                                        }
                                        // Live streaming response
                                        if (ui.streamingResponse.isNotEmpty()) {
                                            item(key = "stream") {
                                                ChatBubbleCard(
                                                    ChatBubble(
                                                        role = "model1",
                                                        content = ui.streamingResponse,
                                                        phase = InventPhase.QUESTIONING,
                                                        isUser = false, isError = false,
                                                        isStreaming = true
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
                                        // Session success card
                                        if (ui.phase == InventPhase.DONE) {
                                            item(key = "success") {
                                                SessionSuccessCard(ui, colors)
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

            // ══ Questioning progress (Akinator-style) ══
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
                            color = colors.Text3, fontFamily = FontFamily.SansSerif)
                        Text("${(ui.questioningProgress * 100).roundToInt()}%", fontSize = 10.sp,
                            color = Cy, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
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

            // ══ Input dock ══
            if (!ui.chatStarted && ui.phase == InventPhase.QUESTIONING) {
                // Loading state
                Surface(Modifier.fillMaxWidth(), color = colors.Surface, shadowElevation = 2.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Cy)
                        Spacer(Modifier.width(8.dp))
                        Text("Setting up session…", fontSize = 11.sp,
                            color = colors.Text3, fontFamily = FontFamily.SansSerif)
                    }
                }
            } else if (ui.phase == InventPhase.CONFIRMING) {
                // Plan review footer: Approve / Regenerate / Cancel
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = { vm.approvePlan() },
                        shape = ZcShape.Sm,
                        color = Cy.copy(alpha = 0.15f),
                        border = BorderStroke(0.2.dp, Cy),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, null, tint = Cy, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Approve & Generate", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
                        }
                    }
                    Surface(
                        onClick = { vm.regeneratePlan() },
                        shape = ZcShape.Sm,
                        color = Am.copy(alpha = 0.12f),
                        border = BorderStroke(0.2.dp, Am.copy(alpha = 0.7f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Refresh, null, tint = Am, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Regenerate", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Am, fontFamily = FontFamily.SansSerif)
                        }
                    }
                    Surface(
                        onClick = { vm.cancelPlanReview() },
                        shape = ZcShape.Sm,
                        color = Color.Transparent,
                        border = BorderStroke(0.2.dp, colors.Border),
                        modifier = Modifier.weight(0.7f)
                    ) {
                        Row(Modifier.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Close, null, tint = colors.Text3, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Cancel", fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
                        }
                    }
                }
            } else {
                // Done button (animated)
                AnimatedVisibility(
                    // Always available during questioning — the 400-char gate was
                    // removed so users can finish early or unstick a chatty model.
                    visible = ui.phase == InventPhase.QUESTIONING && ui.chatStarted && !ui.isGenerating,
                    enter = fadeIn(tweenFast) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tweenFast) + scaleOut(targetScale = 0.8f)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End) {
                        Surface(
                            onClick = vm::onDonePressed,
                            shape = ZcShape.Sm,
                            color = Cy.copy(alpha = 0.15f),
                            border = BorderStroke(0.2.dp, Cy)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Check, "Done", tint = Cy, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Done Gathering Info", fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, color = Cy,
                                    fontFamily = FontFamily.SansSerif)
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

        // ══ Blue researching overlay (research libraries in progress) ══
        AnimatedVisibility(
            visible = ui.researching,
            enter = fadeIn(tweenFast),
            exit = fadeOut(tweenFast)
        ) {
            ResearchingOverlay(onCancel = { vm.cancelGeneration() })
        }

        // ══ Animated dialogs ══
        AnimatedVisibility(
            visible = showSessions,
            enter = scaleIn(springFast) + fadeIn(tweenFast),
            exit = scaleOut(springFast) + fadeOut(tweenFast)
        ) {
            SessionPopup(
                sessions = ui.sessions, sessionId = ui.sessionId,
                onSwitch = { vm.switchToSession(it); showSessions = false },
                onDeleteProject = onDeleteProject,
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
                model1Path = model1Path, model2Path = model2Path, debuggerPath = debuggerPath,
                modelMode = ui.modelMode, restrictRole = settingsRestrictRole,
                onReload = { vm.reloadInventModel() }
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
                title = { Text("Generation in Progress", fontFamily = FontFamily.SansSerif) },
                text = { Text("Files already generated will be saved. You can resume later.", fontFamily = FontFamily.SansSerif) },
                confirmButton = { TextButton(shape = ZcShape.Pill, onClick = { vm.setNavigateAway(false); onBack() }) { Text("Leave", fontFamily = FontFamily.SansSerif) } },
                dismissButton = { TextButton(shape = ZcShape.Pill, onClick = { vm.setNavigateAway(false) }) { Text("Stay", fontFamily = FontFamily.SansSerif) } },
                containerColor = colors.Card
            )
        }
    }
}

// ═══ Pulsing phase orb ═══
@Composable
private fun PhaseOrb(color: Color) {
    val t = rememberInfiniteTransition(label = "orb")
    val ring by t.animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbRing")
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(24.dp)
        .semantics { contentDescription = "Current phase indicator" }
    ) {
        Box(Modifier.size(20.dp * ring).clip(CircleShape).background(color.copy(alpha = 0.18f)))
        Box(Modifier.size(11.dp).clip(CircleShape).background(color))
    }
}

// ═══ Action chip (compact rail button) ═══
@Composable
private fun ActionChip(
    icon: ImageVector, label: String, tint: Color,
    active: Boolean = false, onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
          .height(24.dp)
          .clip(ZcShape.Sm)
          .clickable { onClick() }
          .semantics {
            contentDescription = label
            role = androidx.compose.ui.semantics.Role.Button
            stateDescription = if (active) "Activated" else "Not activated"
          },
        color = if (active) tint.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(0.2.dp, if (active) tint.copy(0.5f) else tint.copy(0.28f))
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold,
                color = tint, fontFamily = FontFamily.SansSerif)
        }
    }
}

// ═══ Flow ribbon — glowing node pipeline ═══
@Composable
private fun FlowRibbon(phase: InventPhase, animColor: Color, colors: ZcPalette) {
    val current = pipelineIndex(phase)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pipeline.forEachIndexed { i, _ ->
            val done = i < current
            val active = i == current
            if (i > 0) {
                Box(
                    Modifier.weight(1f).height(2.dp).clip(ZcShape.Sm)
                        .background(
                            when {
                                i <= current -> animColor.copy(alpha = 0.75f)
                                else -> colors.Border.copy(alpha = 0.5f)
                            }
                        )
                )
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
                if (active) {
                    val t = rememberInfiniteTransition(label = "node")
                    val ring by t.animateFloat(0.5f, 1f,
                        infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "nodeRing")
                    Box(Modifier.size(13.dp * ring).clip(CircleShape).background(animColor.copy(alpha = 0.28f)))
                }
                Box(
                    Modifier.size(if (active) 10.dp else 7.dp).clip(CircleShape)
                        .background(
                            when {
                                active -> animColor
                                done -> colors.Accent2.copy(alpha = 0.8f)
                                else -> colors.Border.copy(alpha = 0.9f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) Text("✓", fontSize = 6.sp, fontWeight = FontWeight.Black, color = colors.Bg)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${phaseLabel(phase)} · ${current + 1}/${pipeline.size}",
            fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold,
            color = animColor, fontFamily = FontFamily.SansSerif, maxLines = 1
        )
    }
}

// ═══ Model status monogram chips ═══
@Composable
private fun ModelPills(
    modelMode: ModelMode,
    plannerLoaded: Boolean, debuggerLoaded: Boolean, coderLoaded: Boolean,
    plannerName: String, debuggerName: String, coderName: String,
    phase: InventPhase, onTap: (Int) -> Unit, colors: ZcPalette
) {
    val pills = mutableListOf<Pair<Int, Triple<String, Boolean, String>>>()
    pills.add(0 to Triple("PLANNER", plannerLoaded, plannerName))
    if (modelMode != ModelMode.SINGLE) {
        pills.add(1 to Triple("DEBUGGER", debuggerLoaded, debuggerName))
        pills.add(2 to Triple("CODER", coderLoaded, coderName))
    }
    val accents = listOf(Am, Pr, Cy)
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
                    .clip(ZcShape.Sm)
                    .clickable { onTap(pair.first) },
                color = if (loaded) accent.copy(alpha = 0.10f) else colors.Surface,
                border = BorderStroke(0.2.dp, if (loaded) accent.copy(0.4f) else colors.Border.copy(0.25f))
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Monogram avatar
                    Box(
                        Modifier.size(15.dp).clip(CircleShape)
                            .background(if (loaded) accent else colors.Border),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label.take(1), fontSize = 7.5.sp, fontWeight = FontWeight.Black,
                            color = if (loaded) colors.Bg else colors.Text3, fontFamily = FontFamily.SansSerif)
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(shortName, fontSize = 8.5.sp,
                        color = if (loaded) accent else colors.Text3.copy(0.5f),
                        fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (loaded) {
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
                    }
                }
            }
        }
    }
}

// ═══ Phase hint banner ═══
@Composable
private fun PhaseHint(phase: InventPhase, colors: ZcPalette) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = ZcShape.Sm,
        color = colors.Accent.copy(alpha = 0.06f),
        border = BorderStroke(0.2.dp, colors.Accent.copy(alpha = 0.14f))
    ) {
        Text(
            when (phase) {
                InventPhase.QUESTIONING -> "💬  You start the session — type your project idea and I'll ask questions to refine it."
                InventPhase.SEARCHING -> "🔍  Looking up current best practices and APIs for your project."
                InventPhase.PLANNING -> "📋  Drafting a file-by-file implementation plan."
                InventPhase.CONFIRMING -> "✅  Review the plan. Tap 'Sure' to proceed or 'Not Sure' to adjust."
                InventPhase.GENERATING -> "⚙️  Writing your project files…"
                InventPhase.REPLANNING -> "🔄  Adjusting the plan based on your feedback."
                InventPhase.FINALIZING -> "📦  Generating README and build instructions."
                InventPhase.DONE -> "✅  Done! Export the zip or start a new project."
                InventPhase.DEBUGGING -> "🔧  Debug mode — describe the issue."
            },
            fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.SansSerif,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// ═══ Staggered fade-in wrapper ═══
@Composable
private fun StaggeredFadeIn(
    index: Int,
    key: String,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(index * 30L)
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

// ═══ Empty state — orbiting emblem ═══
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
            // Orbiting emblem
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                val orbit = rememberInfiniteTransition(label = "orbit")
                val rot by orbit.animateFloat(0f, 360f,
                    infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "orbitRot")
                val spin by orbit.animateFloat(0f, 360f,
                    infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "orbitSpin")
                Box(Modifier.size(88.dp).clip(CircleShape).border(0.2.dp, phaseColor.copy(alpha = 0.35f)))
                Box(
                    Modifier.size(88.dp).graphicsLayer { rotationZ = rot },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(Modifier.size(8.dp).offset(y = (-4).dp).clip(CircleShape).background(phaseColor))
                }
                Box(
                    Modifier.size(50.dp).graphicsLayer { rotationZ = spin }
                        .clip(ZcShape.Lg)
                        .background(Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Z", fontSize = 20.sp, fontWeight = FontWeight.Black,
                        color = colors.Bg, fontFamily = FontFamily.SansSerif)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = colors.Text, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 11.5.sp,
                color = colors.Text3, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
        }
    }
}

// ═══ Status banner ═══
@Composable
private fun StatusBanner(text: String, accent: Color, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth(), shape = ZcShape.Sm,
        color = accent.copy(alpha = 0.06f), border = BorderStroke(0.2.dp, accent.copy(0.18f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (accent != Rd) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = accent)
            Spacer(Modifier.width(6.dp))
            Text(text, color = accent, fontSize = 10.5.sp, fontFamily = FontFamily.SansSerif)
        }
    }
}

// ═══ Chat bubble — monogram avatar + accent bar ═══
@Composable
private fun ChatBubbleCard(bubble: ChatBubble, colors: ZcPalette) {
    val roleColor = when (bubble.role) {
        "user" -> Cy
        "model1" -> Am
        "model2" -> Cy
        "debugger" -> Rd
        "system" -> Gy
        else -> colors.Text2
    }
    val roleLabel = when (bubble.role) {
        "user" -> "YOU"
        "model1" -> "PLANNER"
        "model2" -> "CODER"
        "debugger" -> "DEBUGGER"
        "system" -> "SYS"
        else -> bubble.role.uppercase()
    }
    val avatar = when (bubble.role) {
        "user" -> "Y"; "model1" -> "P"; "model2" -> "C"; "debugger" -> "D"; "system" -> "S"; else -> "?"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        // Monogram avatar
        Box(
            Modifier.size(24.dp).clip(ZcShape.Sm)
                .background(roleColor.copy(alpha = 0.14f))
                .border(0.2.dp, roleColor.copy(alpha = 0.3f), ZcShape.Sm),
            contentAlignment = Alignment.Center
        ) {
            Text(avatar, fontSize = 10.sp, fontWeight = FontWeight.Black,
                color = roleColor, fontFamily = FontFamily.SansSerif)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            // Role label row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(roleLabel, fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                    color = roleColor, fontFamily = FontFamily.SansSerif, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                if (bubble.isStreaming) {
                    Text("thinking…", fontSize = 8.sp, color = roleColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily.SansSerif)
                }
            }
            // Thinking block (collapsible)
            if (bubble.thinkingContent.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = ZcShape.Sm,
                    color = colors.Accent2.copy(alpha = 0.06f),
                    border = BorderStroke(0.2.dp, colors.Accent2.copy(0.18f)),
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🧠  Reasoning", fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.Accent2,
                                fontFamily = FontFamily.SansSerif)
                            Spacer(Modifier.weight(1f))
                            Text(if (expanded) "▲" else "▼", fontSize = 8.sp, color = colors.Text3)
                        }
                        AnimatedVisibility(visible = expanded) {
                            Text(bubble.thinkingContent, fontSize = 10.sp,
                                color = colors.Text3, fontFamily = FontFamily.SansSerif,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            // Content card — black/white bubble with the app's gradient ring
            // (circulating while the response streams).
            GradientBubbleBox(
                circulating = bubble.isStreaming,
                bubbleColor = colors.Surface,
                shape = ZcShape.Sm
            ) {
                Row(
                    Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(roleColor))
                    Text(
                        bubble.content,
                        fontSize = 12.5.sp, color = if (bubble.isError) Rd else colors.Text,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)
                    )
                    if (bubble.isStreaming) {
                        Spacer(Modifier.width(2.dp))
                        StreamingCursor(roleColor)
                    }
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
}

// ═══ File progress ═══
@Composable
private fun FileProgress(index: Int, total: Int, name: String, accent: Color, colors: ZcPalette) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = ZcShape.Sm,
        color = accent.copy(alpha = 0.06f), border = BorderStroke(0.2.dp, accent.copy(0.18f))) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(9.dp))
            Column {
                Text("Writing files…", fontSize = 11.sp, color = accent, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
                Text("${index + 1}/$total  $name", fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
            }
        }
    }
}

// ═══ Done stats — tile grid ═══
@Composable
private fun DoneStats(lines: Int, bytes: Long, debugCount: Int, colors: ZcPalette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile("LINES", "$lines", Cy, colors, Modifier.weight(1f))
        StatTile("SIZE", "${bytes / 1024}KB", Pr, colors, Modifier.weight(1f))
        StatTile("DEBUG", "$debugCount", Am, colors, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, accent: Color, colors: ZcPalette, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = ZcShape.Sm,
        color = accent.copy(alpha = 0.07f),
        border = BorderStroke(0.2.dp, accent.copy(0.22f))
    ) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Black,
                color = accent, fontFamily = FontFamily.SansSerif)
            Text(label, fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
                color = colors.Text3, fontFamily = FontFamily.SansSerif, letterSpacing = 1.sp)
        }
    }
}

// ═══ Input dock — glowing send orb ═══
@Composable
private fun InputArea(
    inputText: String, onTextChange: (String) -> Unit,
    onFilePick: () -> Unit, onSend: () -> Unit,
    onStop: () -> Unit = {},
    phase: InventPhase, totalTokens: Int,
    isGenerating: Boolean = false,
    colors: ZcPalette
) {
    Surface(Modifier.fillMaxWidth().imePadding(), color = colors.Surface, shadowElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniToggle(Icons.Outlined.AttachFile, "Attach", false, onFilePick, colors)
                Spacer(Modifier.weight(1f))
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Cy)
                    Spacer(Modifier.width(4.dp))
                }
                if (totalTokens > 0) {
                    Text("${totalTokens}t", fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
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
                            }, fontSize = 12.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif
                        )
                    },
                    textStyle = TextStyle(fontSize = 12.5.sp, fontFamily = FontFamily.SansSerif, color = colors.Text),
                    shape = ZcShape.Lg,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cy.copy(alpha = 0.5f),
                        unfocusedBorderColor = colors.Border,
                        cursorColor = Cy
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank() && !isGenerating) onSend() }),
                    maxLines = 2
                )
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = isGenerating,
                    transitionSpec = {
                        fadeIn(tweenFast) + scaleIn(initialScale = 0.5f) togetherWith
                        fadeOut(tweenFast) + scaleOut(targetScale = 0.5f)
                    },
                    label = "sendStop"
                ) { generating ->
                    if (generating) {
                        // Stop orb
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(Rd.copy(alpha = 0.18f))
                                .border(0.2.dp, Rd.copy(alpha = 0.4f), CircleShape)
                                .clickable { onStop() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Close, "Stop", tint = Rd, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        val canSend = inputText.isNotBlank()
                        var sendPressed by remember { mutableStateOf(false) }
                        // Idle pulse on the send orb
                        val idle = rememberInfiniteTransition(label = "sendIdle")
                        val idleScale by idle.animateFloat(1f, 1.08f,
                            infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sendIdleVal")
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .scale(if (sendPressed) 0.9f else if (canSend) idleScale else 1f)
                                .let { m ->
                                    if (canSend) m.background(Brush.linearGradient(listOf(Cy, Pr)))
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
        modifier = Modifier.size(26.dp).clip(ZcShape.Sm).clickable { onClick() },
        color = if (active) Cy.copy(alpha = 0.12f) else Color.Transparent,
        border = BorderStroke(0.2.dp, if (active) Cy.copy(0.4f) else colors.Border.copy(0.35f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) Cy else colors.Text3, modifier = Modifier.size(12.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MODEL PICKER SHEET
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
        1 -> "Debugger"
        2 -> "Coder"
        else -> "Model"
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.74f).clickable {},
            shape = ZcShape.Xl, color = colors.Card,
            border = BorderStroke(0.2.dp, colors.Border.copy(alpha = 0.4f))) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Cy.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center) {
                        Text("M", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Cy, fontFamily = FontFamily.SansSerif)
                    }
                    Text("$roleLabel Model", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                }
                HorizontalDivider(color = colors.Border)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clickable { useForAll = !useForAll }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useForAll, onCheckedChange = { useForAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = Cy, checkmarkColor = Color.Black))
                    Text("Use for all roles", fontSize = 11.sp, color = colors.Text, fontFamily = FontFamily.SansSerif)
                }
                Spacer(Modifier.height(4.dp))
                if (models.isEmpty()) {
                    Text("No models found. Import from Models tab.", fontSize = 11.sp,
                        color = colors.Text3, fontFamily = FontFamily.SansSerif, modifier = Modifier.padding(10.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(models) { m ->
                            Surface(Modifier.fillMaxWidth().clickable { onSelect(m.path, m.name, useForAll) },
                                shape = ZcShape.Lg, color = colors.Surface,
                                border = BorderStroke(0.2.dp, colors.Border.copy(0.35f))) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(26.dp).clip(ZcShape.Sm)
                                        .background(Cy.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                                        Text("🧠", fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, fontSize = 11.5.sp, color = colors.Text,
                                            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(2.dp))
                                        Row {
                                            Text(m.format.uppercase(), fontSize = 8.5.sp, color = Cy, fontFamily = FontFamily.SansSerif)
                                            Text(" · ${m.sizeFormatted}", fontSize = 8.5.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
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
// PLAN REVIEW PANEL — shown during CONFIRMING: the proposed file tree, with
// Approve/Regenerate/Cancel handled by the footer dock below.
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun PlanReviewPanel(
    fileTree: List<FileNode>,
    projectName: String,
    colors: ZcPalette
) {
    val files = fileTree.filter { !it.isDir }
    val dirs = fileTree.filter { it.isDir }
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = ZcShape.Lg,
            color = colors.Card,
            border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.35f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FactCheck, null, tint = Cy, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Plan Review — ${projectName.ifEmpty { "New Project" }}",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.Text, fontFamily = FontFamily.SansSerif)
                }
                Spacer(Modifier.height(4.dp))
                Text("${files.size} files · ${dirs.size} folders — approve to generate code, or regenerate for a different breakdown.",
                    fontSize = 9.5.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
            }
        }
        Surface(
            shape = ZcShape.Lg,
            color = colors.Surface,
            border = BorderStroke(0.2.dp, colors.Border.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(fileTree) { node ->
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            null,
                            tint = if (node.isDir) Am else Cy,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Text(node.path, fontSize = 10.5.sp, color = colors.Text, fontFamily = FontFamily.SansSerif,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (node.description.isNotEmpty()) {
                                Text(node.description.take(60), fontSize = 8.5.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SESSION POPUP
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun SessionPopup(
    sessions: List<SessionInfo>, sessionId: String,
    onSwitch: (String) -> Unit, onDeleteProject: (() -> Unit)?,
    onDismiss: () -> Unit, colors: ZcPalette, vm: InventViewModel
) {
    val context = LocalContext.current
    var selectedSession by remember { mutableStateOf<String?>(null) }
    var selectedFiles by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var selectedProjectName by remember { mutableStateOf("") }
    var selectedPhase by remember { mutableStateOf<InventPhase?>(null) }
    var pendingDelete by remember { mutableStateOf<SessionInfo?>(null) }

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
        Surface(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.74f).clickable {},
            shape = ZcShape.Xl, color = colors.Card,
            border = BorderStroke(0.2.dp, colors.Border.copy(alpha = 0.4f))) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selectedSession != null) selectedSession = null else onDismiss() }) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                    Text(if (selectedSession != null) "Session Files" else "Sessions",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Cy.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center) {
                        Text("S", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Cy, fontFamily = FontFamily.SansSerif)
                    }
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                AnimatedContent(
                    targetState = selectedSession,
                    transitionSpec = {
                        fadeIn(tweenFast) + slideInHorizontally { it / 4 } togetherWith
                        fadeOut(tweenFast) + slideOutHorizontally { -it / 4 }
                    },
                    label = "sessionFiles"
                ) { sess ->
                    if (sess != null) {
                        Surface(Modifier.fillMaxWidth(), shape = ZcShape.Sm,
                            color = Cy.copy(alpha = 0.10f), border = BorderStroke(0.2.dp, Cy.copy(0.35f))) {
                            Row(Modifier.clickable { onSwitch(sess) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Cy, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Continue ${selectedProjectName.ifEmpty { "Session" }}", fontSize = 11.sp, color = Cy,
                                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                if (selectedPhase != null) Text(selectedPhase!!.name, fontSize = 8.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            items(selectedFiles) { node ->
                                Surface(Modifier.fillMaxWidth(), shape = ZcShape.Sm,
                                    color = colors.Surface.copy(alpha = 0.5f)) {
                                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description, null,
                                            tint = if (node.isDir) Am else Cy, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text(node.path, fontSize = 10.5.sp, color = colors.Text, fontFamily = FontFamily.SansSerif,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (selectedFiles.isEmpty()) item { Text("No files yet", fontSize = 10.5.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif, modifier = Modifier.padding(6.dp)) }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(sessions) { s ->
                                val isCurrent = s.id == sessionId
                                Surface(Modifier.fillMaxWidth().clickable { selectedSession = s.id },
                                    shape = ZcShape.Sm,
                                    color = if (isCurrent) Cy.copy(alpha = 0.08f) else colors.Surface,
                                    border = if (isCurrent) BorderStroke(0.2.dp, Cy.copy(0.35f)) else BorderStroke(0.2.dp, colors.Border.copy(0.2f))) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(s.projectName, fontSize = 11.5.sp, color = colors.Text, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.height(2.dp))
                                            Row {
                                                Text(s.phase.name, fontSize = 8.5.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
                                                if (s.fileCount > 0) Text(" · ${s.fileCount} files", fontSize = 8.5.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
                                            }
                                        }
                                        IconButton(onClick = { onSwitch(s.id) }, modifier = Modifier.size(26.dp)) {
                                            Icon(Icons.Filled.PlayArrow, "Switch", tint = Cy, modifier = Modifier.size(14.dp))
                                        }
                                        IconButton(onClick = { pendingDelete = s }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Outlined.DeleteOutline, "Delete", tint = Rd.copy(0.5f), modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                            if (sessions.isEmpty()) item { Text("No saved sessions", fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif, modifier = Modifier.padding(10.dp)) }
                        }
                    }
                }
            }
        }
        // Delete confirmation overlay — never delete a session with one tap
        pendingDelete?.let { target ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { pendingDelete = null },
                contentAlignment = Alignment.Center) {
                Surface(Modifier.fillMaxWidth(0.82f).clickable {}, shape = ZcShape.Lg, color = colors.Card,
                    border = BorderStroke(0.2.dp, Rd.copy(0.4f))) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = Rd, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete Project?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.Text, fontFamily = FontFamily.SansSerif)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${target.projectName} — the whole project, all its sessions and files will be permanently removed.",
                            fontSize = 10.5.sp, color = colors.Text2, fontFamily = FontFamily.SansSerif)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(shape = ZcShape.Pill, onClick = { pendingDelete = null }) {
                                Text("Cancel", color = colors.Text3, fontFamily = FontFamily.SansSerif, fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                            TextButton(shape = ZcShape.Pill, onClick = { onDeleteProject?.invoke(); pendingDelete = null }) {
                                Text("Delete project", color = Rd, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SETTINGS POPUP
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
private fun SettingsPopup2(
    onDismiss: () -> Unit, colors: ZcPalette,
    model1Path: String, model2Path: String, debuggerPath: String,
    modelMode: ModelMode = ModelMode.TRIPLE, restrictRole: Int = -1,
    onReload: () -> Unit = {}
) {
    var settingsTab by remember { mutableIntStateOf(0) }

    val getCfg = { role: String -> SettingsManager.getInventModelConfig(role) }
    val plannerCfg = remember(model1Path) { getCfg("Planner") }
    val debuggerCfg = remember(debuggerPath) { getCfg("Debugger") }
    val coderCfg = remember(model2Path) { getCfg("Coder") }

    val allTabs = when (modelMode) {
        ModelMode.SINGLE -> listOf("Planner" to "Planner")
        ModelMode.DUAL -> listOf("Planner+Coder" to "Planner", "Debugger" to "Debugger")
        ModelMode.TRIPLE -> listOf("Planner" to "Planner", "Debugger" to "Debugger", "Coder" to "Coder")
    }
    val tabs = if (restrictRole < 0) allTabs else allTabs.filterIndexed { i, _ -> i == restrictRole }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.74f).clickable {},
            shape = ZcShape.Xl, color = colors.Card,
            border = BorderStroke(0.2.dp, colors.Border.copy(alpha = 0.4f))) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Am.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center) {
                        Text("⚙", fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
                    }
                    Text("Model Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = colors.Text3) }
                }
                HorizontalDivider(color = colors.Border); Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    tabs.forEachIndexed { i, (label, _) ->
                        val isActive = settingsTab == i
                        Surface(
                            shape = ZcShape.Sm,
                            color = if (isActive) Cy.copy(alpha = 0.12f) else colors.Surface,
                            border = BorderStroke(0.2.dp, if (isActive) Cy else colors.Border),
                            modifier = Modifier.clickable { settingsTab = i }
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isActive) Cy else colors.Text3, fontFamily = FontFamily.SansSerif,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                        "Planner" -> plannerCfg; "Debugger" -> debuggerCfg; else -> coderCfg
                    }
                    val path = when (roleKey) {
                        "Planner" -> model1Path; "Debugger" -> debuggerPath; else -> model2Path
                    }
                    ConfigSliders(role = roleKey, config = cfg, modelPath = path, colors)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onReload(); onDismiss() }, modifier = Modifier.fillMaxWidth(),
                    shape = ZcShape.Sm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth().background(
                            Brush.linearGradient(listOf(Cy, Pr, Am))
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Confirm ✓", fontSize = 12.sp, fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.padding(vertical = 10.dp))
                    }
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
        Text(role, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
        Text(modelName, fontSize = 9.5.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Context", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = ctx.toString(),
                onValueChange = { v: String -> val n = v.filter { it.isDigit() }.toIntOrNull(); if (n != null) { ctx = n.coerceIn(512, 32768); save() } },
                modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.SansSerif, color = colors.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cy, unfocusedBorderColor = colors.Border, cursorColor = Cy, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text),
                shape = ZcShape.Sm
            )
        }
        Slider(value = ctx.toFloat(), onValueChange = { ctx = it.roundToInt().coerceIn(512, 32768); save() },
            valueRange = 512f..32768f,
            modifier = Modifier.fillMaxWidth().height(20.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Max Tokens", fontSize = 11.sp, color = colors.Text2, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = maxNew.toString(),
                onValueChange = { v: String -> val n = v.filter { it.isDigit() }.toIntOrNull(); if (n != null) { maxNew = n.coerceIn(64, ctx - 64); save() } },
                modifier = Modifier.widthIn(min = 80.dp, max = 140.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.SansSerif, color = colors.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cy, unfocusedBorderColor = colors.Border, cursorColor = Cy, focusedTextColor = colors.Text, unfocusedTextColor = colors.Text),
                shape = ZcShape.Sm
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
            Text(label, fontSize = 10.sp, color = colors.Text2, fontFamily = FontFamily.SansSerif)
            Text(format(value), fontSize = 10.sp, color = Cy, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps,
            modifier = Modifier.fillMaxWidth().height(20.dp),
            colors = SliderDefaults.colors(thumbColor = Cy, activeTrackColor = Cy, inactiveTrackColor = colors.Border.copy(alpha = 0.2f)))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CODER CHAT
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

    LaunchedEffect(coderMessages.size) {
        if (coderMessages.isNotEmpty()) listState.animateScrollToItem(coderMessages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.Card,
            shadowElevation = 1.dp
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(26.dp).clip(ZcShape.Sm).background(Cy.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center) {
                    Text("C", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Cy, fontFamily = FontFamily.SansSerif)
                }
                Spacer(Modifier.width(7.dp))
                Text("Chat with Coder", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = colors.Text, fontFamily = FontFamily.SansSerif, modifier = Modifier.weight(1f))
                Text(filePath.substringAfterLast('/'), fontSize = 10.sp, color = colors.Text3,
                    fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Close, "Close", tint = colors.Text3, modifier = Modifier.size(15.dp))
                }
            }
        }
        HorizontalDivider(color = colors.Border.copy(alpha = 0.3f))

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
                        shape = ZcShape.Sm,
                        color = if (isUser) colors.Accent.copy(alpha = 0.12f) else colors.CardLight,
                        border = BorderStroke(0.2.dp, colors.Border.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            msg.content,
                            fontSize = 12.5.sp, color = colors.Text,
                            fontFamily = FontFamily.SansSerif,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

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
                    color = colors.Text3, fontFamily = FontFamily.SansSerif) },
                minLines = 1, maxLines = 3,
                shape = ZcShape.Lg,
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
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Cy.copy(alpha = 0.14f))
                    .clickable {
                        if (coderInput.isNotBlank()) {
                            vm.handleCoderChatMessage(coderInput, filePath)
                            coderInput = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Cy, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── Research libraries card (shown under the planner summary) ──
@Composable
private fun ResearchLibrariesCard(colors: ZcPalette, onResearch: () -> Unit) {
    Surface(
        shape = ZcShape.Lg,
        color = Pr.copy(alpha = 0.10f),
        border = BorderStroke(0.2.dp, Pr.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("🔍 Summary ready — research libraries?", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif)
            Spacer(Modifier.height(4.dp))
            Text("The planner will search each library's official docs for the latest versions and changelogs, then build the file plan.",
                fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onResearch,
                shape = ZcShape.Sm,
                color = Pr.copy(alpha = 0.2f),
                border = BorderStroke(0.2.dp, Pr)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = Pr, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Research libraries", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif)
                }
            }
        }
    }
}

// ── Session-complete success card ──
@Composable
private fun SessionSuccessCard(ui: InventUiState, colors: ZcPalette) {
    Surface(
        shape = ZcShape.Lg,
        color = Cy.copy(alpha = 0.12f),
        border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Cy, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Session complete", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
            }
            Spacer(Modifier.height(5.dp))
            Text("All files are generated. Open this project's window (⤢) on the dashboard to edit them.",
                fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.SansSerif)
            Spacer(Modifier.height(4.dp))
            Text("${ui.totalFiles} files · ${ui.totalLines} lines · ${ui.totalTokensUsed} tokens · debug rounds ${ui.debugSessionCount}",
                fontSize = 8.5.sp, color = colors.Text2, fontFamily = FontFamily.SansSerif)
        }
    }
}

// ── Researching overlay: rotating square + orbiting circle + "researching" ──
@Composable
private fun ResearchingOverlay(onCancel: () -> Unit) {
    val p = currentPalette()
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(p.Bg.copy(alpha = 0.97f), p.Card))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Circular searching indicator — the gradient ring sweeps around the search glyph.
            GradientSearchingCircle(size = 88.dp)
            Spacer(Modifier.height(26.dp))
            Text("researching", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.Cyan, fontFamily = FontFamily.SansSerif)
            Spacer(Modifier.height(6.dp))
            Text("searching official docs · latest versions · changelogs",
                fontSize = 9.sp, color = p.Text2, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Surface(
                onClick = onCancel,
                shape = ZcShape.Sm,
                color = p.CardLight,
                border = BorderStroke(0.2.dp, p.Border)
            ) {
                Text("✕ cancel", fontSize = 9.sp, color = p.Cyan, fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
