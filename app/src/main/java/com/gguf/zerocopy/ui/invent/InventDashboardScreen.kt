package com.gguf.zerocopy.ui.invent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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

// Zoom disclosure thresholds
private const val DETAIL_ZOOM = 1.3f
private const val FULL_ZOOM = 1.9f
private const val FOCUS_ZOOM = 2.0f

private fun sectorColor(sector: String): Color = when (sector) {
    "sessions" -> Pr
    "files" -> Cy
    else -> Am
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

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1 shl 10))
    else -> "$bytes B"
}

/** One entry in the file manager list: name + what to open + metadata. */
private data class FileRow(
    val name: String,
    val target: File,
    val isDir: Boolean,
    val isSession: Boolean = false,
    val sizeBytes: Long = 0
)

/**
 * The Invent front door: one big square holding 4 small squares (2×2).
 * Pinch to zoom / pan. Each square starts with a +; pressing it reveals the
 * three sectors as tabs: MODELS, SESSIONS, FILES.
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

    // Pinch zoom state — Animatable so gestures and the focus animation share one source of truth
    val zoomAn = remember { Animatable(1f) }
    val offsetXAn = remember { Animatable(0f) }
    val offsetYAn = remember { Animatable(0f) }
    var focused by remember { mutableStateOf<Int?>(null) } // square index the camera is locked on
    val zoom = zoomAn.value
    val offset = Offset(offsetXAn.value, offsetYAn.value)

    fun resetView() {
        focused = null
        scope.launch {
            coroutineScope {
                launch { zoomAn.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
                launch { offsetXAn.animateTo(0f, tween(380, easing = FastOutSlowInEasing)) }
                launch { offsetYAn.animateTo(0f, tween(380, easing = FastOutSlowInEasing)) }
            }
        }
    }

    // Per-square UI state
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val currentDir = remember { mutableStateMapOf<String, File>() }

    // Bumped after any file write/delete/mkdir so the file lists refresh.
    var fileRefresh by remember { mutableIntStateOf(0) }

    // Dialogs
    var modelPickerFor by remember { mutableStateOf<Pair<String, InventRoleConfig>?>(null) }
    var addRoleFor by remember { mutableStateOf<String?>(null) }
    var roleMenuFor by remember { mutableStateOf<Triple<String, InventRoleConfig, Boolean>?>(null) } // projectId, role, fromConfigure
    var sessionMenuFor by remember { mutableStateOf<Pair<String, String>?>(null) } // projectId, sessionId
    var fileActionsFor by remember { mutableStateOf<Pair<String, File>?>(null) } // projectId, file
    var editorState by remember { mutableStateOf<Triple<String, File, String>?>(null) } // projectId, file, content
    var newFileFor by remember { mutableStateOf<String?>(null) } // projectId
    var newFolderFor by remember { mutableStateOf<String?>(null) } // projectId
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
                Text("pinch to zoom · zoom in → more info", fontSize = 9.5.sp, color = Gy, fontFamily = FontFamily.Monospace)
            }
            Text("${(zoom * 100).roundToInt()}%", fontSize = 9.sp, color = if (focused != null) Cy else Gy, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Surface(
                onClick = { resetView() },
                shape = RoundedCornerShape(8.dp),
                color = CardLight,
                border = BorderStroke(1.dp, if (focused != null) Cy.copy(alpha = 0.6f) else Line)
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FitScreen, null, tint = if (focused != null) Cy else Gy, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(if (focused != null) "Back" else "fit", fontSize = 9.sp, color = if (focused != null) Cy else Gy, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.width(8.dp))
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
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        scope.launch {
                            zoomAn.stop(); offsetXAn.stop(); offsetYAn.stop()
                            zoomAn.snapTo((zoomAn.value * zoomChange).coerceIn(0.35f, 3.2f))
                            offsetXAn.snapTo(offsetXAn.value + pan.x)
                            offsetYAn.snapTo(offsetYAn.value + pan.y)
                        }
                    }
                }
        ) {
            // Fit the big square to the screen (perfectly square), zoom scales from there.
            val canvas = (minOf(maxWidth, maxHeight) - 12.dp).coerceAtLeast(280.dp)
            val sw = (canvas - 20.dp - 12.dp) / 2 // one small square's size
            val density = LocalDensity.current
            val infoLevel = when {
                zoom >= FULL_ZOOM -> "full"
                zoom >= DETAIL_ZOOM -> "detail"
                else -> "brief"
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .size(canvas)
                    .graphicsLayer {
                        scaleX = zoom; scaleY = zoom
                        translationX = offset.x; translationY = offset.y
                    }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val slots = List(4) { i -> projects.getOrNull(i) }
                for (row in 0..1) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (col in 0..1) {
                            val idx = row * 2 + col
                            val project = slots[idx]
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (project != null) {
                                    ProjectSquare(
                                        project = project,
                                        knownPaths = knownPaths,
                                        infoLevel = infoLevel,
                                        autoOpen = focused == idx && zoom >= FULL_ZOOM,
                                        isExpanded = expanded[project.id] == true,
                                        onExpand = { expanded[project.id] = true },
                                        onCollapse = {
                                            expanded[project.id] = false
                                            if (focused == idx) resetView()
                                        },
                                        onFocus = {
                                            val pad = with(density) { 10.dp.toPx() }
                                            val gap = with(density) { 12.dp.toPx() }
                                            val C = with(density) { canvas.toPx() }
                                            val s = with(density) { sw.toPx() }
                                            val col = idx % 2
                                            val row = idx / 2
                                            val cx = pad + col * (gap + s) + s / 2
                                            val cy = pad + row * (gap + s) + s / 2
                                            focused = idx
                                            scope.launch {
                                                coroutineScope {
                                                    launch { zoomAn.animateTo(FOCUS_ZOOM, tween(480, easing = FastOutSlowInEasing)) }
                                                    launch { offsetXAn.animateTo(-FOCUS_ZOOM * (cx - C / 2), tween(480, easing = FastOutSlowInEasing)) }
                                                    launch { offsetYAn.animateTo(-FOCUS_ZOOM * (cy - C / 2), tween(480, easing = FastOutSlowInEasing)) }
                                                }
                                            }
                                        },
                                        currentDir = currentDir[project.id],
                                        onSetDir = { currentDir[project.id] = it },
                                        fileRefresh = fileRefresh,
                                        onPickModel = { role -> modelPickerFor = project.id to role },
                                        onAddRole = { addRoleFor = project.id },
                                        onRoleMenu = { role -> roleMenuFor = Triple(project.id, role, true) },
                                        onStartSession = { onStartSession(project) },
                                        onOpenSession = { sid -> onOpenSession(project, sid) },
                                        onSessionMenu = { sid -> sessionMenuFor = project.id to sid },
                                        onFileClick = { f -> fileActionsFor = project.id to f },
                                        onAddFile = { newFileFor = project.id },
                                        onAddFolder = { newFolderFor = project.id },
                                        onShareZip = { shareProjectZip(context, project) },
                                        onClear = {
                                            onClearProject(project.id)
                                            expanded.remove(project.id)
                                            currentDir.remove(project.id)
                                            fileRefresh++
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

            // ── Zoom-out pill while the camera is focused on a square ──
            if (focused != null) {
                Surface(
                    onClick = { resetView() },
                    shape = RoundedCornerShape(22.dp),
                    color = Card.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, Cy.copy(alpha = 0.55f)),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Fullscreen, null, tint = Cy, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("zoom out · back to all 4", fontSize = 10.sp, color = Cy, fontFamily = FontFamily.Monospace)
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
                scope.launch(Dispatchers.IO) {
                    file.delete()
                    fileRefresh++
                }
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
                        fileRefresh++
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
                        fileRefresh++
                    } catch (_: Exception) {}
                }
                newFileFor = null
            },
            onDismiss = { newFileFor = null }
        )
    }

    // ── New folder dialog ──
    newFolderFor?.let { pid ->
        NewFolderDialog(
            onAdd = { folderName ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val dir = currentDir[pid] ?: InventProjectStore.filesDir(context, pid)
                        File(dir, folderName).mkdirs()
                        fileRefresh++
                    } catch (_: Exception) {}
                }
                newFolderFor = null
            },
            onDismiss = { newFolderFor = null }
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
                    "• Pinch to zoom: as you zoom in, squares reveal more info — at full zoom they auto-open.\n" +
                    "• Tap ⤢ (top-right of a square) to zoom straight into it; 'zoom out' brings you back.\n" +
                    "• Tap + in a square to open it: MODELS, SESSIONS and FILES tabs.\n" +
                    "• MODELS: tap a role to pick its model (sliders: context, max tokens, RAM). Hold for rename/delete.\n" +
                    "• SESSIONS: tap + to start a session, hold a session for open/reset/delete.\n" +
                    "• FILES: file manager — up, new folder, new file, share .zip. Tap a file to open/copy/delete.\n" +
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
    infoLevel: String,   // "brief" | "detail" | "full" — how much info the zoom level unlocks
    autoOpen: Boolean,   // camera locked on this square at full zoom → auto-open editor
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onFocus: () -> Unit,
    currentDir: File?,
    onSetDir: (File) -> Unit,
    fileRefresh: Int,
    onPickModel: (InventRoleConfig) -> Unit,
    onAddRole: () -> Unit,
    onRoleMenu: (InventRoleConfig) -> Unit,
    onStartSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSessionMenu: (String) -> Unit,
    onFileClick: (File) -> Unit,
    onAddFile: () -> Unit,
    onAddFolder: () -> Unit,
    onShareZip: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val root = remember(project.id) { InventProjectStore.filesDir(context, project.id) }
    val dir = currentDir ?: root

    // Each session with generated files appears as a folder at the root level.
    val sessionRows = remember(project.sessionIds, fileRefresh) {
        project.sessionIds.mapNotNull { sid ->
            val d = InventStorage.getProjectDir(context, sid)
            if (d.exists() && (d.listFiles()?.isNotEmpty() == true)) d to sid else null
        }
    }
    val sessionDirs = sessionRows.map { it.first }.toSet()

    val files = remember(dir, fileRefresh) {
        if (dir == root) {
            val rows = mutableListOf<FileRow>()
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                rows.add(
                    if (f.isDirectory) FileRow(f.name, f, true)
                    else FileRow(f.name, f, false, sizeBytes = f.length())
                )
            }
            sessionRows.forEachIndexed { i, (d, _) ->
                rows.add(FileRow("Session ${i + 1}", d, true, isSession = true))
            }
            rows.sortedBy { it.name }
        } else {
            dir.listFiles()?.sortedBy { it.name }?.map { f ->
                if (f.isDirectory) FileRow(f.name, f, true)
                else FileRow(f.name, f, false, sizeBytes = f.length())
            } ?: emptyList()
        }
    }

    val hasSession = project.sessionIds.isNotEmpty()
    val canStart = project.roles.any { it.isPlanner && it.modelPath.isNotEmpty() } &&
                   project.roles.any { it.isCoder && it.modelPath.isNotEmpty() }
    val fileCount = remember(project.id, fileRefresh) { root.listFiles()?.size ?: 0 }

    // Active tab inside an expanded square.
    var sector by remember(project.id) { mutableStateOf("models") } // models | sessions | files

    val open = isExpanded || autoOpen
    val previewRoles = project.roles.filter { it.isPlanner || it.isDebugger || it.isCoder }

    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(CardLight.copy(alpha = 0.7f), Card)))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
    ) {
        when {
            // ── Full editor: manual (tap +) or camera locked at full zoom ──
            open -> {

            // ── Expanded: header + 3 sector tabs ──
            Column(Modifier.fillMaxSize().padding(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Collapse back
                    Surface(
                        onClick = onCollapse,
                        shape = CircleShape,
                        color = CardLight,
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.size(20.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.ArrowBack, null, tint = Gy, modifier = Modifier.size(11.dp))
                        }
                    }
                    Spacer(Modifier.width(5.dp))
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

                // ── Sector tabs ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("models" to "MODELS", "sessions" to "SESSIONS", "files" to "FILES").forEach { (key, label) ->
                        val active = sector == key
                        Surface(
                            onClick = { sector = key },
                            shape = RoundedCornerShape(6.dp),
                            color = if (active) sectorColor(key).copy(alpha = 0.16f) else CardLight,
                            border = BorderStroke(1.dp, if (active) sectorColor(key).copy(alpha = 0.8f) else Line),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                    color = if (active) sectorColor(key) else Color(0xFF8A93A8), fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))

                // ── Tab content ──
                when (sector) {
                    "models" -> {
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
                                    Row(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(roleColor(role.role)))
                                        Spacer(Modifier.width(5.dp))
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(role.role.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                                    color = roleColor(role.role), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (role.isCoder) {
                                                    Spacer(Modifier.width(3.dp))
                                                    Text("🔒", fontSize = 6.sp)
                                                }
                                            }
                                            Text(
                                                text = when {
                                                    role.modelPath.isEmpty() -> "no model"
                                                    missing -> "Unknown"
                                                    else -> role.modelName
                                                },
                                                fontSize = 7.sp, color = if (missing) Rd else Color(0xFF9AA3B5),
                                                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
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
                                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Add, null, tint = Am, modifier = Modifier.size(10.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("add role", fontSize = 8.sp, color = Am, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                    "sessions" -> {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${project.sessionIds.size} sessions", fontSize = 7.5.sp, color = Pr, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.weight(1f))
                            Surface(
                                onClick = { if (canStart) onStartSession() },
                                shape = RoundedCornerShape(6.dp),
                                color = if (canStart) Pr.copy(alpha = 0.15f) else CardLight,
                                border = BorderStroke(1.dp, if (canStart) Pr.copy(alpha = 0.6f) else Line)
                            ) {
                                Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Add, null, tint = if (canStart) Pr else Gy, modifier = Modifier.size(9.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(if (canStart) "new session" else "pick models first", fontSize = 7.5.sp,
                                        color = if (canStart) Pr else Gy, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        if (!hasSession) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(if (canStart) "no sessions yet — tap + new session" else "assign models in MODELS first",
                                    fontSize = 7.5.sp, color = Gy, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                                itemsIndexed(project.sessionIds) { i, sid ->
                                    val names = remember(sid, fileRefresh) {
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
                                        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("S#${i + 1}", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.Monospace)
                                                Spacer(Modifier.weight(1f))
                                                Text("hold: menu", fontSize = 6.sp, color = Line, fontFamily = FontFamily.Monospace)
                                            }
                                            if (names.isNotEmpty()) {
                                                Text(names, fontSize = 7.sp, color = Color(0xFF9AA3B5), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "files" -> {
                        // Toolbar: up / new folder / new file / share
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (dir != root) {
                                Surface(
                                    onClick = {
                                        val parent = if (sessionDirs.contains(dir)) root else dir.parentFile ?: root
                                        onSetDir(parent)
                                    },
                                    shape = RoundedCornerShape(5.dp),
                                    color = CardLight,
                                    border = BorderStroke(1.dp, Line)
                                ) {
                                    Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ArrowUpward, null, tint = Cy, modifier = Modifier.size(9.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("up", fontSize = 7.sp, color = Cy, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            } else {
                                Surface(
                                    onClick = onShareZip,
                                    shape = RoundedCornerShape(5.dp),
                                    color = Cy.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Cy.copy(alpha = 0.5f))
                                ) {
                                    Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Share, null, tint = Cy, modifier = Modifier.size(9.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("zip", fontSize = 7.sp, color = Cy, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Surface(
                                onClick = onAddFolder,
                                shape = RoundedCornerShape(5.dp),
                                color = Bulb.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, Bulb.copy(alpha = 0.4f))
                            ) {
                                Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CreateNewFolder, null, tint = Bulb, modifier = Modifier.size(9.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("folder", fontSize = 7.sp, color = Bulb, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                onClick = onAddFile,
                                shape = RoundedCornerShape(5.dp),
                                color = Cy.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Cy.copy(alpha = 0.5f))
                            ) {
                                Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Add, null, tint = Cy, modifier = Modifier.size(9.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("file", fontSize = 7.sp, color = Cy, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        // Current path
                        Text(
                            if (dir == root) project.name else dir.name,
                            fontSize = 7.sp, color = Gy, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
                        )
                        if (files.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("empty — add a file or folder", fontSize = 7.5.sp, color = Gy, fontFamily = FontFamily.Monospace)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                itemsIndexed(files) { _, row ->
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = CardLight,
                                        modifier = Modifier.fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { if (row.isDir) onSetDir(row.target) else onFileClick(row.target) },
                                                onLongClick = { if (!row.isDir) onFileClick(row.target) }
                                            )
                                    ) {
                                        Row(Modifier.padding(horizontal = 5.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (row.isDir) Icons.Filled.Folder else Icons.Outlined.Description,
                                                null,
                                                tint = if (row.isSession) Pr else if (row.isDir) Bulb else Cy,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(row.name, fontSize = 7.5.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.Monospace,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            if (!row.isDir && row.sizeBytes > 0) {
                                                Text(formatSize(row.sizeBytes), fontSize = 6.5.sp, color = Gy, fontFamily = FontFamily.Monospace)
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
            // ── Zoomed-in info card (auto, no tap needed) ──
            infoLevel != "brief" -> {
                DetailCard(
                    project = project,
                    previewRoles = previewRoles,
                    knownPaths = knownPaths,
                    sessionCount = project.sessionIds.size,
                    fileCount = fileCount,
                    onFocus = onFocus,
                    onExpand = onExpand
                )
            }
            // ── Collapsed: big + with mini preview ──
            else -> {
                BriefCard(
                    project = project,
                    previewRoles = previewRoles,
                    knownPaths = knownPaths,
                    fileCount = fileCount,
                    onExpand = onExpand,
                    onFocus = onFocus
                )
            }
        }
    }
}

@Composable
private fun BriefCard(
    project: InventProject,
    previewRoles: List<InventRoleConfig>,
    knownPaths: Set<String>,
    fileCount: Int,
    onExpand: () -> Unit,
    onFocus: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(6.dp)) {
        // Zoom-to-square button (top-right)
        Surface(
            onClick = onFocus,
            shape = CircleShape,
            color = CardLight,
            border = BorderStroke(1.dp, Cy.copy(alpha = 0.45f)),
            modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.OpenInFull, null, tint = Cy, modifier = Modifier.size(10.dp))
            }
        }
        // Stats pill (top-left)
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Surface(shape = RoundedCornerShape(3.dp), color = Pr.copy(alpha = 0.12f), border = BorderStroke(1.dp, Pr.copy(alpha = 0.4f))) {
                Text("${project.sessionIds.size}S", fontSize = 6.5.sp, color = Pr, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
            }
            Surface(shape = RoundedCornerShape(3.dp), color = Cy.copy(alpha = 0.12f), border = BorderStroke(1.dp, Cy.copy(alpha = 0.4f))) {
                Text("${fileCount}F", fontSize = 6.5.sp, color = Cy, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
            }
        }
        // Big + and name (center)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = onExpand,
                shape = CircleShape,
                color = Bulb.copy(alpha = 0.14f),
                border = BorderStroke(1.5.dp, Bulb.copy(alpha = 0.6f)),
                modifier = Modifier.size(52.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, null, tint = Bulb, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(project.name, fontSize = 11.sp, color = Color(0xFF9AA3B5), fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            if (previewRoles.any { it.modelPath.isNotEmpty() }) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    previewRoles.forEach { role ->
                        val missing = role.modelPath.isNotEmpty() && !knownPaths.contains(role.modelPath)
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = roleColor(role.role).copy(alpha = if (missing) 0.05f else 0.14f),
                            border = BorderStroke(1.dp, roleColor(role.role).copy(alpha = if (missing) 0.3f else 0.55f))
                        ) {
                            Text(
                                when {
                                    role.modelPath.isEmpty() -> role.role.take(1).uppercase()
                                    missing -> "?"
                                    else -> role.modelName.take(6)
                                },
                                fontSize = 6.5.sp, fontWeight = FontWeight.Bold,
                                color = if (missing) Rd else roleColor(role.role),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(
    project: InventProject,
    previewRoles: List<InventRoleConfig>,
    knownPaths: Set<String>,
    sessionCount: Int,
    fileCount: Int,
    onFocus: () -> Unit,
    onExpand: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(project.name.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Surface(
                onClick = onFocus,
                shape = CircleShape,
                color = Cy.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, Cy.copy(alpha = 0.5f)),
                modifier = Modifier.size(20.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.OpenInFull, null, tint = Cy, modifier = Modifier.size(11.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        previewRoles.forEach { role ->
            val missing = role.modelPath.isNotEmpty() && !knownPaths.contains(role.modelPath)
            Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(roleColor(role.role)))
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(role.role.uppercase(), fontSize = 7.sp, fontWeight = FontWeight.Bold, color = roleColor(role.role), fontFamily = FontFamily.Monospace)
                    Text(
                        when {
                            role.modelPath.isEmpty() -> "no model"
                            missing -> "Unknown"
                            else -> role.modelName
                        },
                        fontSize = 7.sp, color = if (missing) Rd else Color(0xFF9AA3B5), fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(shape = RoundedCornerShape(4.dp), color = Pr.copy(alpha = 0.12f), border = BorderStroke(1.dp, Pr.copy(alpha = 0.4f))) {
                Text("$sessionCount sessions", fontSize = 6.5.sp, color = Pr, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp))
            }
            Surface(shape = RoundedCornerShape(4.dp), color = Cy.copy(alpha = 0.12f), border = BorderStroke(1.dp, Cy.copy(alpha = 0.4f))) {
                Text("$fileCount files", fontSize = 6.5.sp, color = Cy, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = onExpand,
            shape = RoundedCornerShape(7.dp),
            color = Bulb.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Bulb.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, null, tint = Bulb, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text("open full editor", fontSize = 8.sp, color = Bulb, fontFamily = FontFamily.Monospace)
            }
        }
        Text("zoom in more → auto-opens", fontSize = 6.5.sp, color = Gy, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
    }
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

/** New folder dialog (file manager). */
@Composable
private fun NewFolderDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("New folder", color = Bulb, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color(0xFFE4E9F5), fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) { onAdd(name.trim()); onDismiss() } },
                enabled = name.isNotBlank()
            ) {
                Text("Create", color = Cy, fontFamily = FontFamily.Monospace)
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
