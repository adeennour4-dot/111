package com.gguf.zerocopy.ui.invent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.gguf.zerocopy.data.invent.InventProject
import com.gguf.zerocopy.data.invent.InventProjectStore
import com.gguf.zerocopy.data.invent.InventRoleConfig
import com.gguf.zerocopy.data.invent.InventStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─── Palette (mirrors the app theme) ────────────────────────────────────────
private val Cy = Color(0xFF00E5A0)
private val Pr = Color(0xFF8B83FF)
private val Am = Color(0xFFFFB74D)
private val Rd = Color(0xFFFF6B6B)
private val Gy = Color(0xFF6A6A7A)
private val Bulb = Color(0xFFFFD166)
private val Bg = Color(0xFF0B0D12)
private val Card = Color(0xFF14171F)
private val CardLight = Color(0xFF1B1F2A)
private val Line = Color(0xFF262B38)

private fun roleColor(role: String): Color = when (role.lowercase()) {
    "planner" -> Pr
    "debugger" -> Rd
    "coder" -> Cy
    else -> Bulb
}

/** Current free RAM (MB). */
private fun freeRamMb(context: Context): Long {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / (1024L * 1024L)
    } catch (_: Exception) { 0L }
}

/** Share the project's file folder as a .zip via the system share sheet. */
fun shareProjectZip(context: Context, project: InventProject) {
    try {
        val src = InventProjectStore.filesDir(context, project.id)
        if (!src.exists() || (src.listFiles()?.isEmpty() ?: true)) return
        val zipDir = File(context.cacheDir, "invent_exports").also { it.mkdirs() }
        val zipFile = File(zipDir, "${project.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            src.walkTopDown().forEach { f ->
                val rel = f.relativeTo(src).path.replace(File.separatorChar, '/')
                if (f.isDirectory) return@forEach
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Share ${project.name}.zip"))
    } catch (_: Exception) {}
}

/**
 * The Invent front door: one big square holding 4 small squares (2×2).
 * Pinch to zoom / pan. Each square starts with a +; pressing it reveals the
 * three sectors: MODELS (left), SESSIONS (middle), FILES (right).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InventDashboardScreen(
    projects: List<InventProject>,
    models: List<com.gguf.zerocopy.data.repository.LocalModel>,
    onSaveProject: (InventProject) -> Unit,
    onClearProject: (String) -> Unit,
    onStartSession: (InventProject) -> Unit,
    onOpenSession: (InventProject, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val knownPaths = remember(models) { models.map { it.path }.toSet() }

    // Pinch zoom state
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Per-square UI state
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val currentDir = remember { mutableStateMapOf<String, File>() }

    // Dialogs
    var modelPickerFor by remember { mutableStateOf<Pair<String, InventRoleConfig>?>(null) }
    var addRoleFor by remember { mutableStateOf<String?>(null) }
    var roleMenuFor by remember { mutableStateOf<Triple<String, InventRoleConfig, Boolean>?>(null) } // projectId, role, fromConfigure
    var sessionMenuFor by remember { mutableStateOf<Pair<String, String>?>(null) } // projectId, sessionId
    var fileActionsFor by remember { mutableStateOf<Pair<String, File>?>(null) } // projectId, file
    var editorState by remember { mutableStateOf<Triple<String, File, String>?>(null) } // projectId, file, content
    var newFileFor by remember { mutableStateOf<String?>(null) } // projectId
    var showZipInfo by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // ── Header ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFF9AA3B5), modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("INVENT", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FontFamily.Monospace)
                Text("4 projects · pinch to zoom · hold for menus", fontSize = 9.5.sp, color = Gy, fontFamily = FontFamily.Monospace)
            }
            Surface(
                onClick = { showZipInfo = true },
                shape = RoundedCornerShape(8.dp),
                color = CardLight,
                border = BorderStroke(1.dp, Line)
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.QuestionMark, null, tint = Bulb, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Help", fontSize = 10.sp, color = Color(0xFF9AA3B5), fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ── Zoomable canvas: the big square with 4 squares ──
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.35f, 3.2f)
                        offset += pan
                    }
                }
        ) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 760.dp, height = 860.dp)
                    .graphicsLayer {
                        scaleX = zoom; scaleY = zoom
                        translationX = offset.x; translationY = offset.y
                    }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val slots = List(4) { i -> projects.getOrNull(i) }
                for (row in 0..1) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        for (col in 0..1) {
                            val idx = row * 2 + col
                            val project = slots[idx]
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (project != null) {
                                    ProjectSquare(
                                        project = project,
                                        knownPaths = knownPaths,
                                        isExpanded = expanded[project.id] == true,
                                        onExpand = { expanded[project.id] = true },
                                        currentDir = currentDir[project.id],
                                        onSetDir = { currentDir[project.id] = it },
                                        onPickModel = { role -> modelPickerFor = project.id to role },
                                        onAddRole = { addRoleFor = project.id },
                                        onRoleMenu = { role -> roleMenuFor = Triple(project.id, role, true) },
                                        onStartSession = { onStartSession(project) },
                                        onOpenSession = { sid -> onOpenSession(project, sid) },
                                        onSessionMenu = { sid -> sessionMenuFor = project.id to sid },
                                        onFileClick = { f -> fileActionsFor = project.id to f },
                                        onAddFile = { newFileFor = project.id },
                                        onUp = {
                                            val d = currentDir[project.id]
                                            if (d != null) {
                                                val rootDir = InventProjectStore.filesDir(context, project.id)
                                                if (d != rootDir) currentDir[project.id] = d.parentFile ?: rootDir
                                            }
                                        },
                                        onShareZip = { shareProjectZip(context, project) },
                                        onClear = {
                                            onClearProject(project.id)
                                            expanded.remove(project.id)
                                            currentDir.remove(project.id)
                                        }
                                    )
                                } else {
                                    // Empty slot
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Card.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, Line),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("—", fontSize = 26.sp, color = Line.copy(alpha = 0.9f), fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Model picker dialog (sliders + toggles + RAM) ──
    modelPickerFor?.let { (pid, role) ->
        ModelPickerDialog(
            role = role,
            models = models,
            freeRamMb = freeRamMb(context),
            onPick = { newRole ->
                val p = projects.find { it.id == pid }
                if (p != null) {
                    val roles = p.roles.map { if (it.role == role.role) newRole else it }
                    onSaveProject(p.withRoles(roles))
                    modelPickerFor = null
                }
            },
            onDismiss = { modelPickerFor = null }
        )
    }

    // ── Add-role dialog ──
    addRoleFor?.let { pid ->
        AddRoleDialog(
            onAdd = { name, desc ->
                val p = projects.find { it.id == pid }
                if (p != null) {
                    val newRole = InventRoleConfig(role = name, description = desc)
                    onSaveProject(p.withRoles(p.roles + newRole))
                    addRoleFor = null
                }
            },
            onDismiss = { addRoleFor = null }
        )
    }

    // ── Role hold-click menu ──
    roleMenuFor?.let { (pid, role, _) ->
        RoleActionsDialog(
            role = role,
            onEdit = { newName, newDesc ->
                val p = projects.find { it.id == pid }
                if (p != null) {
                    onSaveProject(p.withRoles(p.roles.map {
                        if (it.role == role.role) it.copy(role = newName, description = newDesc) else it
                    }))
                    roleMenuFor = null
                }
            },
            onDelete = {
                val p = projects.find { it.id == pid }
                if (p != null && !role.isCoder) {
                    onSaveProject(p.withRoles(p.roles.filter { it.role != role.role }))
                }
                roleMenuFor = null
            },
            onDismiss = { roleMenuFor = null }
        )
    }

    // ── Session hold-click menu ──
    sessionMenuFor?.let { (pid, sid) ->
        SessionActionsDialog(
            sessionName = sid,
            onOpen = {
                val p = projects.find { it.id == pid }
                if (p != null) {
                    onOpenSession(p, sid)
                    sessionMenuFor = null
                }
            },
            onDelete = {
                val p = projects.find { it.id == pid }
                if (p != null) {
                    InventStorage.deleteSession(context, sid)
                    onSaveProject(p.withSessionIds(p.sessionIds.filter { it != sid }))
                    sessionMenuFor = null
                }
            },
            onReset = {
                // Reset session: wipe messages/zcp content, keep files.
                scope.launch(Dispatchers.IO) {
                    InventStorage.resetSessionContent(context, sid)
                }
                sessionMenuFor = null
            },
            onDismiss = { sessionMenuFor = null }
        )
    }

    // ── File action window (open / copy / delete) ──
    fileActionsFor?.let { (pid, file) ->
        FileActionsDialog(
            fileName = file.name,
            onOpen = {
                scope.launch {
                    val content = withContext(Dispatchers.IO) {
                        try { file.readText() } catch (_: Exception) { "" }
                    }
                    editorState = Triple(pid, file, content)
                }
                fileActionsFor = null
            },
            onCopy = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                scope.launch {
                    val content = withContext(Dispatchers.IO) {
                        try { file.readText() } catch (_: Exception) { "" }
                    }
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("file", content))
                }
                fileActionsFor = null
            },
            onDelete = {
                scope.launch(Dispatchers.IO) { file.delete() }
                fileActionsFor = null
            },
            onDismiss = { fileActionsFor = null }
        )
    }

    // ── File editor (open existing or create new) ──
    editorState?.let { (pid, file, content) ->
        InventFileEditorDialog(
            title = file.name,
            initialContent = content,
            onSave = { fileName, newContent ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val dir = file.parentFile ?: InventProjectStore.filesDir(context, pid)
                        val target = File(dir, fileName)
                        if (file.absolutePath != target.absolutePath) file.delete()
                        target.writeText(newContent)
                    } catch (_: Exception) {}
                }
                editorState = null
            },
            onDismiss = { editorState = null }
        )
    }
    newFileFor?.let { pid ->
        InventFileEditorDialog(
            title = "new_file.txt",
            initialContent = "",
            onSave = { fileName, newContent ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val dir = currentDir[pid] ?: InventProjectStore.filesDir(context, pid)
                        File(dir, fileName).writeText(newContent)
                    } catch (_: Exception) {}
                }
                newFileFor = null
            },
            onDismiss = { newFileFor = null }
        )
    }

    // ── Help dialog ──
    if (showZipInfo) {
        AlertDialog(
            onDismissRequest = { showZipInfo = false },
            containerColor = Card,
            title = { Text("Invent dashboard", color = Bulb, fontFamily = FontFamily.Monospace, fontSize = 15.sp) },
            text = {
                Text(
                    "• Pinch to zoom the 4 squares in and out.\n" +
                    "• Tap + in a square to open its 3 sectors: MODELS (left), SESSIONS (middle), FILES (right).\n" +
                    "• Tap a role to pick its model (sliders: context, max tokens, RAM).\n" +
                    "• HOLD a role / session / file for actions (edit, delete, reset, open…).\n" +
                    "• Files sector: read-only browser, + creates a file, share icon exports the project as .zip.\n" +
                    "• X clears the square's contents (the square stays).",
                    color = Color(0xFFB9C1D0), fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = { showZipInfo = false }) {
                    Text("Got it", color = Cy, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

// ═══ One square ═════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectSquare(
    project: InventProject,
    knownPaths: Set<String>,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    currentDir: File?,
    onSetDir: (File) -> Unit,
    onPickModel: (InventRoleConfig) -> Unit,
    onAddRole: () -> Unit,
    onRoleMenu: (InventRoleConfig) -> Unit,
    onStartSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSessionMenu: (String) -> Unit,
    onFileClick: (File) -> Unit,
    onAddFile: () -> Unit,
    onUp: () -> Unit,
    onShareZip: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val root = remember(project.id) { InventProjectStore.filesDir(context, project.id) }
    val dir = currentDir ?: root
    val files = remember(dir) { dir.listFiles()?.sortedBy { it.name } ?: emptyList() }
    val hasSession = project.sessionIds.isNotEmpty()
    val canStart = project.roles.any { it.isPlanner && it.modelPath.isNotEmpty() } &&
                   project.roles.any { it.isCoder && it.modelPath.isNotEmpty() }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Card,
        border = BorderStroke(1.dp, Line)
    ) {
        Box(Modifier.fillMaxSize()) {
            if (!isExpanded) {
                // ── Empty: big + ──
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = onExpand,
                        shape = CircleShape,
                        color = Bulb.copy(alpha = 0.14f),
                        border = BorderStroke(1.5.dp, Bulb.copy(alpha = 0.6f)),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, null, tint = Bulb, modifier = Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(project.name, fontSize = 12.sp, color = Color(0xFF9AA3B5), fontFamily = FontFamily.Monospace)
                }
            } else {
                // ── Three sectors: models | sessions | files ──
                Column(Modifier.fillMaxSize().padding(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(project.name.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = Bulb, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        // X: clear the project contents, keep the square
                        Surface(
                            onClick = onClear,
                            shape = CircleShape,
                            color = Rd.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Rd.copy(alpha = 0.5f)),
                            modifier = Modifier.size(20.dp)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, null, tint = Rd, modifier = Modifier.size(11.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // ── LEFT: models/roles ──
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            SectorHeader("MODELS", Am, Modifier.weight(1f))
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                itemsIndexed(project.roles) { _, role ->
                                    val missing = role.modelPath.isNotEmpty() && !knownPaths.contains(role.modelPath)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = CardLight,
                                        border = BorderStroke(1.dp, roleColor(role.role).copy(alpha = 0.35f)),
                                        modifier = Modifier.fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { onPickModel(role) },
                                                onLongClick = { onRoleMenu(role) }
                                            )
                                    ) {
                                        Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(role.role.uppercase(), fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
                                                    color = roleColor(role.role), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (role.isCoder) {
                                                    Spacer(Modifier.width(3.dp))
                                                    Text("🔒", fontSize = 7.sp)
                                                }
                                            }
                                            Text(
                                                text = when {
                                                    role.modelPath.isEmpty() -> "no model"
                                                    missing -> "Unknown"
                                                    else -> role.modelName
                                                },
                                                fontSize = 8.sp, color = if (missing) Rd else Color(0xFFB9C1D0),
                                                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                item {
                                    Surface(
                                        onClick = onAddRole,
                                        shape = RoundedCornerShape(6.dp),
                                        color = Am.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, Am.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
                                            Icon(Icons.Filled.Add, null, tint = Am, modifier = Modifier.size(10.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("role", fontSize = 8.sp, color = Am, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }
                        // ── MIDDLE: sessions ──
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectorHeader("SESSIONS", Pr, Modifier.weight(1f))
                                Surface(
                                    onClick = { if (canStart) onStartSession() },
                                    shape = CircleShape,
                                    color = if (canStart) Pr.copy(alpha = 0.15f) else CardLight,
                                    border = BorderStroke(1.dp, if (canStart) Pr.copy(alpha = 0.6f) else Line),
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Add, null, tint = if (canStart) Pr else Gy, modifier = Modifier.size(9.dp))
                                    }
                                }
                            }
                            if (!hasSession) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(if (canStart) "tap + to start" else "pick models first",
                                        fontSize = 7.5.sp, color = Gy, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                                    itemsIndexed(project.sessionIds) { i, sid ->
                                        val names = remember(sid) {
                                            runCatching {
                                                InventStorage.loadSession(context, sid)?.let { s ->
                                                    listOf(s.model1Name, s.model2Name).filter { it.isNotEmpty() }.joinToString(" · ")
                                                } ?: ""
                                            }.getOrDefault("")
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = CardLight,
                                            border = BorderStroke(1.dp, Pr.copy(alpha = 0.3f)),
                                            modifier = Modifier.fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { onOpenSession(sid) },
                                                    onLongClick = { onSessionMenu(sid) }
                                                )
                                        ) {
                                            Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
                                                Text("S#${i + 1}", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.Monospace)
                                                if (names.isNotEmpty()) {
                                                    Text(names, fontSize = 7.sp, color = Color(0xFF9AA3B5), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // ── RIGHT: files ──
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectorHeader("FILES", Cy, Modifier.weight(1f))
                                if (dir != root) {
                                    Surface(
                                        onClick = onUp,
                                        shape = CircleShape,
                                        color = CardLight,
                                        border = BorderStroke(1.dp, Line),
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.ArrowUpward, null, tint = Cy, modifier = Modifier.size(9.dp))
                                        }
                                    }
                                } else {
                                    Surface(
                                        onClick = onShareZip,
                                        shape = CircleShape,
                                        color = Cy.copy(alpha = 0.12f),
                                        border = BorderStroke(1.dp, Cy.copy(alpha = 0.5f)),
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Share, null, tint = Cy, modifier = Modifier.size(9.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(2.dp))
                                Surface(
                                    onClick = onAddFile,
                                    shape = CircleShape,
                                    color = Cy.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Cy.copy(alpha = 0.5f)),
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Add, null, tint = Cy, modifier = Modifier.size(9.dp))
                                    }
                                }
                            }
                            if (files.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("no files yet", fontSize = 7.5.sp, color = Gy, fontFamily = FontFamily.Monospace)
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                    itemsIndexed(files) { _, f ->
                                        val isDir = f.isDirectory
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = CardLight,
                                            modifier = Modifier.fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { if (isDir) onSetDir(f) else onFileClick(f) },
                                                    onLongClick = { if (!isDir) onFileClick(f) }
                                                )
                                        ) {
                                            Row(Modifier.padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    if (isDir) Icons.Filled.Folder else Icons.Outlined.Description,
                                                    null, tint = if (isDir) Bulb else Cy, modifier = Modifier.size(10.dp)
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(f.name, fontSize = 7.5.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectorHeader(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace,
        modifier = modifier)
}

// ═══ Dialogs ════════════════════════════════════════════════════════════════

/** Model picker: model list + context/max-tokens sliders + thinking + RAM + background (coder). */
@Composable
private fun ModelPickerDialog(
    role: InventRoleConfig,
    models: List<com.gguf.zerocopy.data.repository.LocalModel>,
    freeRamMb: Long,
    onPick: (InventRoleConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var ctx by remember { mutableStateOf(role.contextWindow) }
    var maxNew by remember { mutableStateOf(role.maxTokens) }
    var thinking by remember { mutableStateOf(role.thinkingEnabled) }
    var background by remember { mutableStateOf(role.backgroundWork) }
    var selectedPath by remember { mutableStateOf(role.modelPath) }
    var selectedName by remember { mutableStateOf(role.modelName) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Card,
            border = BorderStroke(1.dp, Line)
        ) {
            LazyColumn(Modifier.widthIn(max = 420.dp).heightIn(max = 560.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚙ ${role.role.uppercase()}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = roleColor(role.role), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text("Free RAM: ${freeRamMb} MB", fontSize = 10.sp, color = if (freeRamMb > 1500) Cy else Am, fontFamily = FontFamily.Monospace)
                    }
                }
                item {
                    Text("Model", fontSize = 10.sp, color = Gy, fontFamily = FontFamily.Monospace)
                }
                itemsIndexed(models) { _, m ->
                    val isSel = m.path == selectedPath
                    Surface(
                        onClick = { selectedPath = m.path; selectedName = m.name },
                        shape = RoundedCornerShape(8.dp),
                        color = CardLight,
                        border = BorderStroke(1.dp, if (isSel) roleColor(role.role) else Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, fontSize = 12.sp, color = Color(0xFFE4E9F5), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${m.sizeFormatted} · ${m.format}", fontSize = 9.sp, color = Gy, fontFamily = FontFamily.Monospace)
                            }
                            if (isSel) {
                                Icon(Icons.Filled.Check, null, tint = roleColor(role.role), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(2.dp))
                    Text("Context window: $ctx", fontSize = 10.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.Monospace)
                    Slider(
                        value = ctx.toFloat(),
                        onValueChange = { ctx = it.toInt() },
                        valueRange = 512f..16384f,
                        steps = 30,
                        colors = SliderDefaults.colors(thumbColor = roleColor(role.role), activeTrackColor = roleColor(role.role))
                    )
                }
                item {
                    Text("Max tokens: $maxNew", fontSize = 10.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.Monospace)
                    Slider(
                        value = maxNew.toFloat(),
                        onValueChange = { maxNew = it.toInt() },
                        valueRange = 64f..4096f,
                        steps = 30,
                        colors = SliderDefaults.colors(thumbColor = roleColor(role.role), activeTrackColor = roleColor(role.role))
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Thinking mode", fontSize = 11.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.Monospace)
                            Text("wrap replies in <think> reasoning", fontSize = 8.5.sp, color = Gy, fontFamily = FontFamily.Monospace)
                        }
                        Switch(
                            checked = thinking,
                            onCheckedChange = { thinking = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = roleColor(role.role))
                        )
                    }
                }
                if (role.isCoder) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Work in background", fontSize = 11.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.Monospace)
                                Text("coder keeps generating while you use another app", fontSize = 8.5.sp, color = Gy, fontFamily = FontFamily.Monospace)
                            }
                            Switch(
                                checked = background,
                                onCheckedChange = { background = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = roleColor(role.role))
                            )
                        }
                    }
                }
                item {
                    val sel = models.find { it.path == selectedPath }
                    Surface(
                        onClick = {
                            if (selectedPath.isNotEmpty()) {
                                onPick(
                                    role.copy(
                                        modelPath = selectedPath, modelName = selectedName.ifEmpty { sel?.name ?: selectedPath },
                                        contextWindow = ctx, maxTokens = maxNew,
                                        thinkingEnabled = thinking, backgroundWork = background
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = roleColor(role.role).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (selectedPath.isNotEmpty()) roleColor(role.role) else Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    selectedPath.isEmpty() -> "Tap a model above first"
                                    else -> "Done · ${sel?.name ?: selectedName}"
                                },
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (selectedPath.isNotEmpty()) roleColor(role.role) else Gy, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** Add a custom role: name + description (the command that tells the model who he is). */
@Composable
private fun AddRoleDialog(
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("New role", color = Bulb, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Role name", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFE4E9F5), fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description / command (who he is, what he does)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    minLines = 3,
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFE4E9F5), fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) { onAdd(name.trim(), desc.trim()); onDismiss() } },
                enabled = name.isNotBlank()
            ) {
                Text("Add", color = Cy, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.Monospace) }
        }
    )
}

/** Hold-click on a role: edit name/description or delete (never the coder). */
@Composable
private fun RoleActionsDialog(
    role: InventRoleConfig,
    onEdit: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(role.role) }
    var desc by remember { mutableStateOf(role.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(role.role.uppercase(), color = roleColor(role.role), fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            if (editing) {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") },
                        singleLine = true, textStyle = LocalTextStyle.current.copy(color = Color(0xFFE4E9F5), fontFamily = FontFamily.Monospace, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") },
                        minLines = 3, textStyle = LocalTextStyle.current.copy(color = Color(0xFFE4E9F5), fontFamily = FontFamily.Monospace, fontSize = 12.sp), modifier = Modifier.fillMaxWidth())
                }
            } else {
                Column {
                    if (role.description.isNotEmpty()) {
                        Text(role.description, color = Color(0xFFB9C1D0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Model: ${role.modelName.ifEmpty { "none" }}", color = Gy, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            if (editing) {
                TextButton(onClick = { onEdit(name.trim(), desc.trim()); onDismiss() }, enabled = name.isNotBlank()) {
                    Text("Save", color = Cy, fontFamily = FontFamily.Monospace)
                }
            } else {
                TextButton(onClick = { editing = true }) {
                    Text("Change name/description", color = Am, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        dismissButton = {
            if (editing) {
                TextButton(onClick = { editing = false }) { Text("Back", color = Gy, fontFamily = FontFamily.Monospace) }
            } else if (!role.isCoder) {
                TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete role", color = Rd, fontFamily = FontFamily.Monospace) }
            } else {
                TextButton(onClick = onDismiss) { Text("Close", color = Gy, fontFamily = FontFamily.Monospace) }
            }
        }
    )
}

/** Hold-click on a session: open / delete / reset (keep files). */
@Composable
private fun SessionActionsDialog(
    sessionName: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Session", color = Pr, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = { Text(sessionName, color = Color(0xFFB9C1D0), fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
        confirmButton = {
            TextButton(onClick = { onOpen(); onDismiss() }) { Text("Open session", color = Cy, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onReset(); onDismiss() }) { Text("Reset", color = Am, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete", color = Rd, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    )
}

/** Floating window behind a clicked file: open / copy / delete. */
@Composable
private fun FileActionsDialog(
    fileName: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("📄 $fileName", color = Cy, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
        text = { },
        confirmButton = {
            TextButton(onClick = { onOpen(); onDismiss() }) { Text("Open", color = Cy, fontFamily = FontFamily.Monospace) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onCopy(); onDismiss() }) { Text("Copy code", color = Am, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete", color = Rd, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    )
}
