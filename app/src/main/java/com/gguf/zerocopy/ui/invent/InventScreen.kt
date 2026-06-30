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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gguf.zerocopy.data.invent.FileNode
import com.gguf.zerocopy.data.invent.InventMessage
import com.gguf.zerocopy.data.invent.InventPhase
import com.gguf.zerocopy.ui.theme.ZcPalette
import com.gguf.zerocopy.ui.theme.currentPalette

// ─── Colors ──────────────────────────────────────────────────────────────────

private val CyanGreen = Color(0xFF00E5A0)
private val GlowCyan = Color(0x4000E5A0)
private val GlowPurple = Color(0x408B83FF)

// ─── Main Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventScreen(
    model1Path: String, model1Name: String,
    model2Path: String, model2Name: String,
    researcherPath: String, researcherName: String,
    offlineMode: Boolean, sameModelMode: Boolean,
    onBack: () -> Unit,
    onModelsClick: () -> Unit,
    vm: InventViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()
    val colors = currentPalette()
    var showSessionPopup by remember { mutableStateOf(false) }
    var showSettingsPopup by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0=Planner, 1=Researcher, 2=Coder
    var inputText by remember { mutableStateOf("") }
    var showThinking by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (ui.sessionId.isEmpty()) {
            vm.setupSession(model1Path, model1Name, model2Path, model2Name,
                researcherPath, researcherName, offlineMode, sameModelMode)
        }
    }

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        if (ui.projectName.isNotEmpty()) {
                            Text(ui.projectName.take(16), fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.Text, fontFamily = FontFamily.Monospace)
                        } else {
                            Text("Invent", fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.Text, fontFamily = FontFamily.Monospace)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
                    }
                },
                actions = {
                    if (ui.totalTokensUsed > 0) {
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(CyanGreen.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("${ui.totalTokensUsed} tok", fontSize = 9.sp,
                                color = CyanGreen, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Surface)
            )
        },
        containerColor = colors.Bg
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {

            Column(Modifier.fillMaxSize()) {

                // ── Model Selector Row ────────────────────────────────────────
                ModelSelectorRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    plannerLoaded = ui.plannerLoaded,
                    researcherLoaded = ui.researcherLoaded,
                    coderLoaded = ui.coderLoaded,
                    colors = colors,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )

                // ── Floating Bar (Settings + History) ────────────────────────
                FloatingActionBar(
                    onSettings = { showSettingsPopup = true },
                    onHistory = { showSessionPopup = true },
                    colors = colors
                )

                // ── Chat Messages ─────────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Stats bar
                    item {
                        if (ui.totalTokensUsed > 0 || ui.phase != InventPhase.QUESTIONING) {
                            StatsBar(
                                phase = ui.phase,
                                totalTokens = ui.totalTokensUsed,
                                currentTokens = 0,
                                colors = colors
                            )
                        }
                    }

                    // Phase indicator
                    item {
                        PhaseIndicator(ui.phase, colors)
                    }

                    // Error banner
                    if (ui.error.isNotEmpty()) {
                        item {
                            Surface(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = colors.Red.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, colors.Red.copy(0.3f))
                            ) {
                                Text(ui.error, modifier = Modifier.padding(12.dp),
                                    color = colors.Red, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // Messages
                    itemsIndexed(ui.messages) { _, msg ->
                        InventBubbleNew(msg, colors)
                    }
                }

                // ── Input Bar ─────────────────────────────────────────────────
                Surface(
                    Modifier.fillMaxWidth(),
                    color = colors.Surface,
                    shadowElevation = 4.dp
                ) {
                    Column {
                        // Control icons row
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // File upload
                            OutlinedIconButton(
                                onClick = { /* TODO */ },
                                modifier = Modifier.size(32.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, colors.Border)
                            ) {
                                Icon(Icons.Outlined.AttachFile, "Attach",
                                    tint = colors.Text3, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            // Thinking toggle
                            OutlinedIconButton(
                                onClick = { showThinking = !showThinking },
                                modifier = Modifier.size(32.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp,
                                    if (showThinking) CyanGreen else colors.Border)
                            ) {
                                Icon(Icons.Outlined.Psychology, "Think",
                                    tint = if (showThinking) CyanGreen else colors.Text3,
                                    modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            // Search toggle
                            OutlinedIconButton(
                                onClick = { showSearch = !showSearch },
                                modifier = Modifier.size(32.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp,
                                    if (showSearch) CyanGreen else colors.Border)
                            ) {
                                Icon(Icons.Outlined.Search, "Search",
                                    tint = if (showSearch) CyanGreen else colors.Text3,
                                    modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            // Token stats
                            if (ui.totalTokensUsed > 0) {
                                Text("${ui.totalTokensUsed} tok", fontSize = 9.sp,
                                    color = colors.Text3, fontFamily = FontFamily.Monospace)
                            }
                        }

                        // Text input + send
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f).heightIn(min = 42.dp, max = 120.dp),
                                singleLine = false,
                                placeholder = {
                                    Text(when {
                                        ui.phase == InventPhase.QUESTIONING -> "Describe your project..."
                                        ui.phase == InventPhase.DONE -> "Project complete — export or start new"
                                        else -> "Type a message..."
                                    }, fontSize = 13.sp, color = colors.Text3)
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                    color = colors.Text),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanGreen.copy(alpha = 0.5f),
                                    unfocusedBorderColor = colors.Border,
                                    cursorColor = CyanGreen
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (inputText.isNotBlank()) {
                                        vm.sendUserMessage(inputText,
                                            planWithSearch = showSearch,
                                            thinkTag = showThinking)
                                        inputText = ""
                                    }
                                }),
                                maxLines = 4
                            )
                            Spacer(Modifier.width(8.dp))
                            // Send button — Telegram-style
                            val canSend = inputText.isNotBlank() && selectedTab >= 0
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .let { mod ->
                                        if (canSend) mod.background(
                                            Brush.linearGradient(listOf(CyanGreen, CyanGreen.copy(alpha = 0.7f)))
                                        ) else mod.background(colors.Border)
                                    }
                                    .clickable {
                                        if (canSend) {
                                            vm.sendUserMessage(inputText,
                                                planWithSearch = showSearch,
                                                thinkTag = showThinking)
                                            inputText = ""
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send",
                                    tint = if (canSend) Color.Black else colors.Text3,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // ── Session Popup ─────────────────────────────────────────────────
            if (showSessionPopup) {
                SessionPopup(
                    sessions = ui.sessions,
                    sessionId = ui.sessionId,
                    zcpFileTree = ui.fileTree,
                    selectedSession = selectedSession,
                    onSelectSession = { selectedSession = it },
                    onSwitch = { vm.switchToSession(it); selectedSession = null },
                    onDelete = { vm.deleteSessionById(it) },
                    onBack = { selectedSession = null },
                    onDismiss = { showSessionPopup = false; selectedSession = null },
                    colors = colors,
                    vm = vm
                )
            }

            // ── Settings Popup ────────────────────────────────────────────────
            if (showSettingsPopup) {
                SettingsPopup(
                    onDismiss = { showSettingsPopup = false },
                    colors = colors,
                    vm = vm
                )
            }
        }
    }
}

// ─── Model Selector Row ───────────────────────────────────────────────────────

@Composable
fun ModelSelectorRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    plannerLoaded: Boolean,
    researcherLoaded: Boolean,
    coderLoaded: Boolean,
    colors: ZcPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Planner
        ModelButton(
            label = "Planner",
            subtitle = if (plannerLoaded) "Loaded ✓" else "Select",
            selected = selectedTab == 0,
            loaded = plannerLoaded,
            color = CyanGreen,
            onClick = { onTabSelected(0) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        // Researcher
        ModelButton(
            label = "Researcher",
            subtitle = if (researcherLoaded) "Loaded ✓" else "Select",
            selected = selectedTab == 1,
            loaded = researcherLoaded,
            color = Color(0xFF8B83FF),
            onClick = { onTabSelected(1) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        // Coder
        ModelButton(
            label = "Coder",
            subtitle = if (coderLoaded) "Loaded ✓" else "Select",
            selected = selectedTab == 2,
            loaded = coderLoaded,
            color = Color(0xFF00E5A0),
            onClick = { onTabSelected(2) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ModelButton(
    label: String,
    subtitle: String,
    selected: Boolean,
    loaded: Boolean,
    color: Color,
    onClick: () -> Unit,
    colors: ZcPalette,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        loaded && selected -> color
        loaded -> color.copy(alpha = 0.5f)
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.3f)
    }
    val glow = when {
        loaded -> color.copy(alpha = 0.15f)
        selected -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(glow)
            .border(
                BorderStroke(
                    if (loaded || selected) 1.5f.dp else 1.dp,
                    borderColor
                ),
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (selected || loaded) Color.White else colors.Text3,
                fontFamily = FontFamily.Monospace)
            Text(subtitle, fontSize = 8.sp,
                color = if (loaded) color else colors.Text3.copy(0.6f),
                fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Floating Action Bar ──────────────────────────────────────────────────────

@Composable
fun FloatingActionBar(
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    colors: ZcPalette
) {
    Surface(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = colors.CardLight.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.Border.copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Settings gear
            IconButton(onClick = onSettings, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Settings, "Settings", tint = colors.Text2,
                    modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(4.dp))
            // History
            IconButton(onClick = onHistory, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.History, "History", tint = colors.Text2,
                    modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ─── Stats Bar ────────────────────────────────────────────────────────────────

@Composable
fun StatsBar(
    phase: InventPhase,
    totalTokens: Int,
    currentTokens: Int,
    colors: ZcPalette
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = CyanGreen.copy(alpha = 0.04f)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(phaseLabel(phase), fontSize = 9.sp, color = CyanGreen,
                fontFamily = FontFamily.Monospace)
            if (totalTokens > 0) {
                Text("${totalTokens} tok", fontSize = 9.sp, color = colors.Text3,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ─── Phase Indicator ──────────────────────────────────────────────────────────

@Composable
fun PhaseIndicator(phase: InventPhase, colors: ZcPalette) {
    val text = when (phase) {
        InventPhase.QUESTIONING -> "💡 Tell me what you want to build"
        InventPhase.SEARCHING -> "🔍 Researching your idea..."
        InventPhase.PLANNING -> "📋 Planning the architecture..."
        InventPhase.CONFIRMING -> "✅ Review the plan below"
        InventPhase.REPLANNING -> "🔄 Resizing oversized files..."
        InventPhase.GENERATING -> "⚡ Generating code..."
        InventPhase.FINALIZING -> "📖 Reading project files..."
        InventPhase.DONE -> "✅ Project complete! Export .zip"
        InventPhase.DEBUGGING -> "🔧 Debugging..."
    }
    Text(text, fontSize = 11.sp, color = colors.Text3, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 4.dp))
}

// ─── Chat Bubble ──────────────────────────────────────────────────────────────

@Composable
fun InventBubbleNew(msg: InventMessage, colors: ZcPalette) {
    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"
    val bgColor = when {
        isUser -> colors.UserBg
        isSystem -> colors.Accent.copy(alpha = 0.06f)
        msg.role == "model2" -> CyanGreen.copy(alpha = 0.04f)
        else -> colors.Card
    }
    val borderColor = when {
        isUser -> colors.Accent.copy(alpha = 0.3f)
        msg.role == "model2" -> CyanGreen.copy(alpha = 0.15f)
        else -> colors.Border.copy(alpha = 0.3f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 12.dp
            ),
            color = bgColor,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(Modifier.padding(10.dp)) {
                if (!isUser && !isSystem) {
                    Text(msg.role.uppercase().take(1) + msg.role.drop(1),
                        fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = if (msg.role == "model2") CyanGreen else colors.Accent,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                }
                Text(msg.content, fontSize = 12.sp, color = colors.Text,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ─── Session Popup ────────────────────────────────────────────────────────────

@Composable
fun SessionPopup(
    sessions: List<SessionInfo>,
    sessionId: String,
    zcpFileTree: List<FileNode>,
    selectedSession: String?,
    onSelectSession: (String?) -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    colors: ZcPalette,
    vm: InventViewModel
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.7f)
                .clickable { /* block clicks through */ },
            shape = RoundedCornerShape(20.dp),
            color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(16.dp)) {
                // Header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (selectedSession != null) onBack()
                        else onDismiss()
                    }) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text3)
                    }
                    Text(
                        if (selectedSession != null) "Session Files" else "Past Sessions",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = CyanGreen, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border)
                Spacer(Modifier.height(8.dp))

                if (selectedSession != null) {
                    // Show files for this session
                    SessionFilesView(
                        files = zcpFileTree,
                        colors = colors,
                        vm = vm
                    )
                } else {
                    // Show session list
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(sessions) { s ->
                            val isCurrent = s.id == sessionId
                            Surface(
                                Modifier.fillMaxWidth()
                                    .clickable { onSelectSession(s.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurrent) CyanGreen.copy(alpha = 0.08f)
                                    else colors.Surface,
                                border = if (isCurrent) BorderStroke(1.dp, CyanGreen.copy(0.3f))
                                    else null
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.projectName, fontSize = 12.sp,
                                            color = colors.Text,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold)
                                        Row {
                                            Text(s.phase.name, fontSize = 9.sp,
                                                color = colors.Text3,
                                                fontFamily = FontFamily.Monospace)
                                            if (s.fileCount > 0) {
                                                Text(" · ${s.fileCount} files", fontSize = 9.sp,
                                                    color = colors.Text3,
                                                    fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                    // Switch button
                                    IconButton(onClick = { onSwitch(s.id) },
                                        modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Filled.PlayArrow, "Switch",
                                            tint = CyanGreen,
                                            modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = { onDelete(s.id) },
                                        modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline, "Delete",
                                            tint = colors.Red.copy(0.5f),
                                            modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                        if (sessions.isEmpty()) {
                            item {
                                Text("No saved sessions", fontSize = 12.sp,
                                    color = colors.Text3, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Session Files View ───────────────────────────────────────────────────────

@Composable
fun SessionFilesView(
    files: List<FileNode>,
    colors: ZcPalette,
    vm: InventViewModel
) {
    Column {
        // "Continue Inventing" button
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = CyanGreen.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, CyanGreen.copy(0.3f))
        ) {
            Row(
                Modifier.clickable { onDismiss() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = CyanGreen,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Continue Inventing", fontSize = 12.sp,
                    color = CyanGreen, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(files) { node ->
                FileRow(node, colors)
            }
            if (files.isEmpty()) {
                item {
                    Text("No files yet", fontSize = 11.sp,
                        color = colors.Text3, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun FileRow(node: FileNode, colors: ZcPalette) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.Surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, colors.Border.copy(0.2f))
    ) {
        Row(
            Modifier.padding(8.dp).clickable { /* open file chat */ },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (node.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                null,
                tint = if (node.isDir) colors.Amber else CyanGreen,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(node.path, fontSize = 10.sp, color = colors.Text,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (node.description.isNotEmpty()) {
                    Text(node.description, fontSize = 8.sp, color = colors.Text3,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }
    }
}

// ─── Settings Popup ───────────────────────────────────────────────────────────

@Composable
fun SettingsPopup(
    onDismiss: () -> Unit,
    colors: ZcPalette,
    vm: InventViewModel
) {
    var settingsTab by remember { mutableStateOf(0) } // 0=Planner, 1=Researcher, 2=Coder

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.6f)
                .clickable { /* block clicks */ },
            shape = RoundedCornerShape(20.dp),
            color = colors.Card,
            border = BorderStroke(1.dp, CyanGreen.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(16.dp)) {
                // Header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text3)
                    }
                    Text("Model Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = CyanGreen, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(40.dp))
                }
                HorizontalDivider(color = colors.Border)
                Spacer(Modifier.height(8.dp))

                // Model tabs
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    SettingsTab("Planner", 0, settingsTab, colors) { settingsTab = 0 }
                    Spacer(Modifier.width(8.dp))
                    SettingsTab("Researcher", 1, settingsTab, colors) { settingsTab = 1 }
                    Spacer(Modifier.width(8.dp))
                    SettingsTab("Coder", 2, settingsTab, colors) { settingsTab = 2 }
                }
                Spacer(Modifier.height(12.dp))

                // Settings content
                when (settingsTab) {
                    0 -> ModelSettingsContent("Planner", vm, colors)
                    1 -> ModelSettingsContent("Researcher", vm, colors)
                    2 -> ModelSettingsContent("Coder", vm, colors)
                }
            }
        }
    }
}

@Composable
fun SettingsTab(label: String, index: Int, current: Int, colors: ZcPalette, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (index == current) CyanGreen.copy(alpha = 0.15f) else colors.Surface,
        border = BorderStroke(1.dp, if (index == current) CyanGreen else colors.Border)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (index == current) CyanGreen else colors.Text3,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
fun ModelSettingsContent(label: String, vm: InventViewModel, colors: ZcPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label Configuration", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = colors.Text, fontFamily = FontFamily.Monospace)
        Text("Context Length: 4096", fontSize = 10.sp, color = colors.Text2,
            fontFamily = FontFamily.Monospace)
        Text("Max Tokens: 1024", fontSize = 10.sp, color = colors.Text2,
            fontFamily = FontFamily.Monospace)
        Text("GPU Layers: 0 (CPU)", fontSize = 10.sp, color = colors.Text2,
            fontFamily = FontFamily.Monospace)
        Text("Temperature: 0.7", fontSize = 10.sp, color = colors.Text2,
            fontFamily = FontFamily.Monospace)
        Text("Top-P: 0.9", fontSize = 10.sp, color = colors.Text2,
            fontFamily = FontFamily.Monospace)
        Text("Supported: GGUF, TFLite, MNN", fontSize = 10.sp,
            color = CyanGreen, fontFamily = FontFamily.Monospace)
    }
}

// ─── Phase Label ──────────────────────────────────────────────────────────────

fun phaseLabel(phase: InventPhase) = when (phase) {
    InventPhase.QUESTIONING -> "Gathering info"
    InventPhase.SEARCHING   -> "Researching"
    InventPhase.PLANNING    -> "Planning"
    InventPhase.CONFIRMING  -> "Review"
    InventPhase.GENERATING  -> "Generating code"
    InventPhase.REPLANNING  -> "Resizing files"
    InventPhase.FINALIZING  -> "Reading project"
    InventPhase.DONE        -> "Done ✓"
    InventPhase.DEBUGGING   -> "Debugging"
}
