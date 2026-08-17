package com.gguf.zerocopy.ui.invent
import com.gguf.zerocopy.ui.theme.ZcShape

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.gguf.zerocopy.ui.components.FuturisticFont
import com.gguf.zerocopy.ui.components.IdentityBorderBrush
import com.gguf.zerocopy.ui.components.IdentityCyan
import com.gguf.zerocopy.ui.components.ZcPillButton
import com.gguf.zerocopy.ui.components.IdentityPurple
import com.gguf.zerocopy.ui.components.IdentitySweepBrush
import com.gguf.zerocopy.ui.theme.ZcEnter
import com.gguf.zerocopy.ui.theme.currentPalette
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.gguf.zerocopy.data.invent.InventPhase
import com.gguf.zerocopy.data.invent.InventProject
import com.gguf.zerocopy.data.invent.InventProjectStore
import com.gguf.zerocopy.data.invent.InventRoleConfig
import com.gguf.zerocopy.data.invent.InventStopSignal
import com.gguf.zerocopy.data.invent.InventStorage
import com.gguf.zerocopy.data.invent.InventTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─── Palette (mirrors the app theme) ────────────────────────────────────────
private val Cy = Color(0xFF00E5A0)
private val Pr = Color(0xFF8B83FF)
private val Am = Color(0xFF00E5F0)   // cyan
private val Rd = Color(0xFFC44DFF)   // hot purple
// Theme-aware surfaces/text — the Invent dashboard follows the app palette
// (light in light mode, dark in dark mode) instead of hardcoding a dark lab.
private val Gy: Color
    @Composable get() = currentPalette().Text2
private val Bulb: Color
    @Composable get() = currentPalette().Cyan
private val Bg: Color
    @Composable get() = currentPalette().Bg
private val Card: Color
    @Composable get() = currentPalette().Card
private val CardLight: Color
    @Composable get() = currentPalette().CardLight
private val Line: Color
    @Composable get() = currentPalette().Border
private val Txt: Color
    @Composable get() = currentPalette().Text
private val Txt2: Color
    @Composable get() = currentPalette().Text2
// First-run tour rides an opaque dark scrim → keeps its own light text.
private val TourText = Color(0xFFE4E9F5)
// True when the app is in dark mode (used for black-pill orbs).
private val DarkMode: Boolean
    @Composable get() = currentPalette().Bg.luminance() < 0.5f

@Composable
private fun roleColor(role: String): Color = when (role.lowercase()) {
    "planner" -> Pr
    "debugger" -> Rd
    "coder" -> Cy
    else -> Bulb
}

@Composable
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
            // Project files dir first.
            src.walkTopDown().forEach { f ->
                val rel = f.relativeTo(src).path.replace(File.separatorChar, '/')
                if (f.isDirectory) return@forEach
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            // Then each session's generated files under sessions/S#N/… so the
            // ZIP contains the coder's real output, not just the project dir.
            project.sessionIds.forEachIndexed { i, sid ->
                val sdir = InventStorage.getProjectDir(context, sid)
                if (!sdir.exists()) return@forEachIndexed
                sdir.walkTopDown().forEach { f ->
                    val rel = "sessions/S${i + 1}/" + f.relativeTo(sdir).path.replace(File.separatorChar, '/')
                    if (f.isDirectory) return@forEach
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
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

/** Save the current content of [file] into its hidden .history folder (max 5 versions). */
private fun pushHistory(file: File) {
    try {
        if (!file.exists()) return
        val hDir = File(file.parentFile, ".history/${file.name}")
        hDir.mkdirs()
        File(hDir, System.currentTimeMillis().toString()).writeText(file.readText())
        val all = hDir.listFiles()?.sortedBy { it.name } ?: emptyList()
        while (all.size > 5) all.first().delete()
    } catch (_: Exception) {}
}

/** Timestamped versions of [file] from its .history folder, newest first. */
private fun listHistory(file: File): List<File> {
    val hDir = File(file.parentFile, ".history/${file.name}")
    return hDir.listFiles()?.sortedByDescending { it.name } ?: emptyList()
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
    onDeleteProject: (String) -> Unit,
    onNewProject: (String, String) -> Unit,
    onDiagnostics: () -> Unit,
    onStartSession: (InventProject) -> Unit,
    onOpenSession: (InventProject, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val knownPaths = remember(models) { models.map { it.path }.toSet() }

    // Window manager: the 2×2 grid, or one maximized project window.
    var maximized by remember { mutableStateOf<String?>(null) }
    val currentDir = remember { mutableStateMapOf<String, File>() }
    var fileRefresh by remember { mutableIntStateOf(0) }

    // Dialogs
    var modelPickerFor by remember { mutableStateOf<Pair<String, InventRoleConfig>?>(null) }
    var addRoleFor by remember { mutableStateOf<String?>(null) }
    var roleMenuFor by remember { mutableStateOf<Triple<String, InventRoleConfig, Boolean>?>(null) }
    var sessionMenuFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fileActionsFor by remember { mutableStateOf<Pair<String, File>?>(null) }
    var editorState by remember { mutableStateOf<Triple<String, File, String>?>(null) }
    var newFileFor by remember { mutableStateOf<String?>(null) }
    var newFolderFor by remember { mutableStateOf<String?>(null) }
    var projectMenuFor by remember { mutableStateOf<String?>(null) } // long-press a square
    var renameFor by remember { mutableStateOf<String?>(null) }
    var newProjectDialog by remember { mutableStateOf(false) } // empty slot → new project
    var historyFor by remember { mutableStateOf<Pair<String, File>?>(null) } // projectId, file
    var showZipInfo by remember { mutableStateOf(false) }
    val squarePanels = remember { mutableStateListOf<String>() } // tap squares → in-place panels; MULTIPLE squares can be active at once
    var modelInfoFor by remember { mutableStateOf<Triple<String, InventRoleConfig, com.gguf.zerocopy.data.repository.LocalModel>?>(null) } // pid, role, model → info + RAM window
    var deleteProjectFor by remember { mutableStateOf<String?>(null) } // delete-project confirm (window ✕)
    // First-run tour (dismissed permanently)
    val prefs = remember { context.getSharedPreferences("invent", Context.MODE_PRIVATE) }
    var showTour by remember { mutableStateOf(!prefs.getBoolean("tour_done_v1", false)) }
    fun dismissTour() {
        prefs.edit().putBoolean("tour_done_v1", true).apply()
        showTour = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // ── Title bar ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Txt2, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("INVENT", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FuturisticFont)
                Text("⤢ maximize · hold a square for menu · RAM ${freeRamMb(context)} MB", fontSize = 8.sp, color = Gy, fontFamily = FontFamily.SansSerif)
            }
            Surface(
                onClick = { onDiagnostics() },
                shape = ZcShape.Sm,
                color = CardLight,
                border = BorderStroke(0.2.dp, Line)
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BugReport, null, tint = Cy, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Diag", fontSize = 10.sp, color = Txt2, fontFamily = FontFamily.SansSerif)
                }
            }
            Spacer(Modifier.width(6.dp))
            Surface(
                onClick = { showZipInfo = true },
                shape = ZcShape.Sm,
                color = CardLight,
                border = BorderStroke(0.2.dp, Line)
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.QuestionMark, null, tint = Bulb, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Help", fontSize = 10.sp, color = Txt2, fontFamily = FontFamily.SansSerif)
                }
            }
        }

        val active = projects.find { it.id == maximized }
        if (active != null) {
            // ── Maximized project window ──
            ProjectWindow(
                project = active,
                knownPaths = knownPaths,
                currentDir = currentDir[active.id],
                onSetDir = { currentDir[active.id] = it },
                fileRefresh = fileRefresh,
                onMinimize = {
                    maximized = null
                    // Belt-and-suspenders: minimize NEVER deletes — it also
                    // clears any pending delete flag so the confirm dialog can
                    // never appear after minimizing.
                    deleteProjectFor = null
                },
                onPickModel = { role -> modelPickerFor = active.id to role },
                onAddRole = { addRoleFor = active.id },
                onRoleMenu = { role -> roleMenuFor = Triple(active.id, role, true) },
                onStartSession = { onStartSession(active) },
                onOpenSession = { sid -> onOpenSession(active, sid) },
                onSessionMenu = { sid -> sessionMenuFor = active.id to sid },
                onFileClick = { f -> fileActionsFor = active.id to f },
                onAddFile = { newFileFor = active.id },
                onAddFolder = { newFolderFor = active.id },
                onShareZip = { shareProjectZip(context, active) },
                onDelete = {
                    deleteProjectFor = active.id
                }
            )
        } else {
            // ── 2×2 grid — the squares FILL the whole screen below the title bar ──
            Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (row in 0..1) {
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            for (col in 0..1) {
                                val idx = row * 2 + col
                                val project = projects.getOrNull(idx)
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    if (project != null) {
                                        if (project.id in squarePanels) {
                                            // The minimized square (panel) lives INSIDE the square.
                                            SquarePanel(
                                                project = project,
                                                knownPaths = knownPaths,
                                                models = models,
                                                fileRefresh = fileRefresh,
                                                onMaximize = { squarePanels.remove(project.id); maximized = project.id },
                                                onClose = { squarePanels.remove(project.id) },
                                                onPickModel = { role -> modelPickerFor = project.id to role },
                                                onModelInfo = { role, model -> modelInfoFor = Triple(project.id, role, model) },
                                                onAddRole = { addRoleFor = project.id },
                                                onRoleMenu = { role -> roleMenuFor = Triple(project.id, role, true) },
                                                onStartSession = { onStartSession(project) },
                                                onOpenSession = { sid -> onOpenSession(project, sid) },
                                                onSessionMenu = { sid -> sessionMenuFor = project.id to sid },
                                                onToggleBackground = { role ->
                                                    val p = projects.find { it.id == project.id }
                                                    if (p != null) {
                                                        onSaveProject(p.withRoles(p.roles.map {
                                                            if (it.role == role.role) it.copy(backgroundWork = !it.backgroundWork) else it
                                                        }))
                                                    }
                                                }
                                            )
                                        } else {
                                            ZcEnter(index = idx) {
                                                ProjectSquare(
                                                index = idx,
                                                project = project,
                                                knownPaths = knownPaths,
                                                fileRefresh = fileRefresh,
                                                onPanel = { if (!squarePanels.remove(project.id)) squarePanels.add(project.id) },
                                                onMaximize = { maximized = project.id },
                                                onMenu = { projectMenuFor = project.id },
                                                onClear = {
                                                    onClearProject(project.id)
                                                    currentDir.remove(project.id)
                                                    fileRefresh++
                                                }
                                            )
                                            }
                                        }
                                    } else {
                                        ZcEnter(index = idx) {
                                        // Empty slot → new project
                                        Surface(
                                            onClick = { newProjectDialog = true },
                                            shape = ZcShape.Lg,
                                            color = Card.copy(alpha = 0.5f),
                                            border = BorderStroke(0.5.dp, Line),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(Icons.Filled.Add, null, tint = Am.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
                                                    Spacer(Modifier.height(4.dp))
                                                    Text("New project", fontSize = 7.sp, color = Gy, fontFamily = FontFamily.SansSerif)
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
            // ── First-run tour overlay ──
            if (showTour) {
                Surface(
                    onClick = { dismissTour() },
                    color = Color(0xFF0B0D12),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("INVENT TOUR", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FontFamily.SansSerif)
                        Spacer(Modifier.height(12.dp))
                        Text("• Tap a square (or ⤢ top-right) to maximize it into a full window", fontSize = 11.sp, color = TourText, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("• ROLES · SESSIONS · FILES sections live inside the window", fontSize = 11.sp, color = TourText, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("• — minimizes · ✕ clears · hold a square for rename / export", fontSize = 11.sp, color = TourText, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("• Tap + on an empty slot to create a project from a template", fontSize = 11.sp, color = TourText, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            onClick = { dismissTour() },
                            shape = RoundedCornerShape(10.dp),
                            color = Cy.copy(alpha = 0.18f),
                            border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.6f))
                        ) {
                            Text("Got it — start building", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
                        }
                    }
                    }
                }
            }

        }
            }
    }

    // ── Delete-project confirm (window title bar 🗑) — composed OUTSIDE the
    // grid/maximized branch so it works while a project window is maximized ──
    deleteProjectFor?.let { pid ->
        val p = projects.find { it.id == pid }
        AlertDialog(
            onDismissRequest = { deleteProjectFor = null },
            containerColor = Card,
            title = { Text("Delete project?", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FontFamily.SansSerif) },
            text = { Text("'${p?.name ?: ""}' and all its sessions + files will be permanently removed. This cannot be undone.", fontSize = 11.sp, color = Txt2, fontFamily = FontFamily.SansSerif) },
            confirmButton = {
                TextButton(shape = ZcShape.Pill, onClick = {
                    onDeleteProject(pid)
                    currentDir.remove(pid)
                    fileRefresh++
                    maximized = null
                    squarePanels.remove(pid)
                    deleteProjectFor = null
                }) { Text("Delete", color = Rd, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(shape = ZcShape.Pill, onClick = { deleteProjectFor = null }) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif) }
            }
        )
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
                    // Selecting a model opens the model info + RAM window.
                    if (newRole.modelPath.isNotEmpty()) {
                        val m = models.find { it.path == newRole.modelPath }
                        if (m != null) modelInfoFor = Triple(pid, newRole, m)
                    }
                }
            },
            onDismiss = { modelPickerFor = null }
        )
    }

    // ── Model info + conversation RAM window ──
    modelInfoFor?.let { (pid, role, model) ->
        ModelInfoDialog(
            role = role,
            model = model,
            freeRamMb = freeRamMb(context),
            onChangeModel = {
                modelInfoFor = null
                modelPickerFor = pid to role
            },
            onClose = { modelInfoFor = null }
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
            onExport = {
                scope.launch(Dispatchers.IO) {
                    val f = InventStorage.exportTranscript(context, sid)
                    withContext(Dispatchers.Main) {
                        if (f != null) {
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/jsonl"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Export transcript"))
                            } catch (_: Exception) {}
                        }
                    }
                }
                sessionMenuFor = null
            },
            onDelete = {
                // Delete only THIS session — the project itself can only be
                // removed via the trash icon (window / square).
                scope.launch(Dispatchers.IO) {
                    InventStorage.deleteSession(context, sid)
                }
                val p = projects.find { it.id == pid }
                if (p != null) {
                    onSaveProject(p.withSessionIds(p.sessionIds.filter { it != sid }))
                }
                sessionMenuFor = null
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

    // ── File action window (open / copy / history / delete) ──
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
            onHistory = {
                historyFor = pid to file
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
                        if (file.absolutePath != target.absolutePath) {
                            file.delete()
                        } else {
                            pushHistory(target)
                        }
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

    // ── Project menu (long-press a square) ──
    projectMenuFor?.let { pid ->
        val p = projects.find { it.id == pid }
        if (p != null) {
            ProjectMenuDialog(
                project = p,
                onRename = {
                    renameFor = pid
                    projectMenuFor = null
                },
                onExportZip = {
                    shareProjectZip(context, p)
                    projectMenuFor = null
                },
                onClear = {
                    onClearProject(pid)
                    currentDir.remove(pid)
                    fileRefresh++
                    projectMenuFor = null
                },
                onDismiss = { projectMenuFor = null }
            )
        } else projectMenuFor = null
    }

    // ── Rename project ──
    renameFor?.let { pid ->
        val p = projects.find { it.id == pid }
        if (p != null) {
            RenameDialog(
                initialName = p.name,
                onRename = { newName ->
                    onSaveProject(p.copy(name = newName))
                    renameFor = null
                },
                onDismiss = { renameFor = null }
            )
        } else renameFor = null
    }

    // ── New project (empty slot) ──
    if (newProjectDialog) {
        NewProjectDialog(
            onCreate = { name, templateId ->
                onNewProject(name, templateId)
                newProjectDialog = false
            },
            onDismiss = { newProjectDialog = false }
        )
    }

    // ── File history ──
    historyFor?.let { (pid, file) ->
        HistoryDialog(
            versions = remember(file) { listHistory(file) },
            onRestore = { versionFile ->
                scope.launch(Dispatchers.IO) {
                    try {
                        file.writeText(versionFile.readText())
                        fileRefresh++
                    } catch (_: Exception) {}
                }
                historyFor = null
            },
            onView = { versionFile ->
                scope.launch {
                    val content = withContext(Dispatchers.IO) {
                        try { versionFile.readText() } catch (_: Exception) { "" }
                    }
                    editorState = Triple(pid, file, content)
                }
                historyFor = null
            },
            onDismiss = { historyFor = null }
        )
    }

    // ── Help dialog ──
    if (showZipInfo) {
        AlertDialog(
            onDismissRequest = { showZipInfo = false },
            containerColor = Card,
            title = { Text("Invent dashboard", color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 15.sp) },
            text = {
                Text(
                    "• 4 equal squares: tap a square (or its ⤢ button) to maximize it into a window.\n" +
                    "• In the window: — minimizes back to the grid; ✕ clears the project's contents.\n" +
                    "• MODELS: tap a role to pick its model (sliders). Hold a role for rename/delete.\n" +
                    "• MODELS: tap a role to pick its model (sliders: context, max tokens, RAM). Hold for rename/delete.\n" +
                    "• SESSIONS: tap + to start a session, hold a session for open/reset/delete.\n" +
                    "• FILES: file manager — up, new folder, new file, share .zip. Tap a file to open/copy/delete.\n" +
                    "• X clears the square's contents (the square stays).",
                    color = Color(0xFFB9C1D0), fontSize = 12.sp, fontFamily = FontFamily.SansSerif
                )
            },
            confirmButton = {
                TextButton(shape = ZcShape.Pill, onClick = { showZipInfo = false }) {
                    Text("Got it", color = Cy, fontFamily = FontFamily.SansSerif)
                }
            }
        )
    }
}

// ═══ Grid square (compact) ══════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectSquare(
    index: Int,
    project: InventProject,
    knownPaths: Set<String>,
    fileRefresh: Int,
    onPanel: () -> Unit,
    onMaximize: () -> Unit,
    onMenu: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val fileCount = remember(project.id, fileRefresh) {
        InventProjectStore.filesDir(context, project.id).listFiles()?.size ?: 0
    }
    val previewRoles = project.roles.filter { it.isPlanner || it.isDebugger || it.isCoder }
    // The door: RED while a coder runs in the background, CYAN when idle.
    val coderRunning = project.roles.any { it.isCoder && it.backgroundWork }
    val doorColor = if (coderRunning) Rd else Cy

    // Calm entrance: staggered fade + gentle rise + scale.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(320, delayMillis = (index * 70).coerceAtMost(420))) }
    // Soft, slow breathing glow on the door (professional micro-motion).
    val glowAlpha by rememberInfiniteTransition(label = "sqOrb").animateFloat(
        0.14f, 0.26f, infiniteRepeatable(tween(1500), RepeatMode.Reverse)
    )

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = appear.value
                val s = 0.95f + 0.05f * appear.value
                scaleX = s; scaleY = s
            }
            .clip(ZcShape.Lg)
            .background(Brush.verticalGradient(listOf(CardLight.copy(alpha = 0.7f), Card)))
            .border(0.2.dp, Line, ZcShape.Lg)
            .combinedClickable(onClick = onPanel, onLongClick = onMenu)
    ) {
        // Folded-corner paper-note effect (bottom-right)
        val foldColor = CardLight.copy(alpha = 0.9f)
        Canvas(
            Modifier.align(Alignment.BottomEnd).size(16.dp)
        ) {
            val w = size.width; val h = size.height
            val fold = Path().apply { moveTo(0f, h); lineTo(w, 0f); lineTo(w, h); close() }
            drawPath(fold, foldColor)
            drawLine(
                IdentityBorderBrush,
                Offset(0f, h), Offset(w, 0f), strokeWidth = 0.2.dp.toPx()
            )
        }
        // Maximize + X — rounded-square tabs touching the top line
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Surface(onClick = onMaximize, shape = ZcShape.Sm, color = CardLight, border = BorderStroke(0.2.dp, Line), modifier = Modifier.size(15.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.OpenInFull, null, tint = Cy, modifier = Modifier.size(8.dp)) }
            }
            Surface(onClick = onClear, shape = ZcShape.Sm, color = CardLight, border = BorderStroke(0.2.dp, Rd.copy(alpha = 0.5f)), modifier = Modifier.size(15.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, null, tint = Rd, modifier = Modifier.size(8.dp)) }
            }
        }
        // Count pills (top-left) — small, tucked into the corner
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Surface(shape = ZcShape.Sm, color = Pr.copy(alpha = 0.12f), border = BorderStroke(0.2.dp, Pr.copy(alpha = 0.4f))) {
                Text("${project.sessionIds.size}S", fontSize = 6.sp, color = Pr, fontFamily = FontFamily.SansSerif, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
            }
            Surface(shape = ZcShape.Sm, color = Cy.copy(alpha = 0.12f), border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.4f))) {
                Text("${fileCount}F", fontSize = 6.sp, color = Cy, fontFamily = FontFamily.SansSerif, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
            }
        }
        // Name pill — horizontal expanded circle connected to the top (inside)
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 3.dp)
                .clip(ZcShape.Pill)
                .background(Card)
                .border(0.2.dp, Line, ZcShape.Pill)
        ) {
            Text(project.name.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Txt, fontFamily = FuturisticFont,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
        }
        // ── Center stage: the DOOR (state orb) + status ──
        Column(
            Modifier.fillMaxSize().padding(bottom = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Soft state glow behind the door
            Box(Modifier.size(66.dp).background(Brush.radialGradient(listOf(doorColor.copy(alpha = glowAlpha), doorColor.copy(alpha = 0f))), CircleShape), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onPanel,
                    shape = CircleShape,
                    color = if (DarkMode) Color.Black else Card,
                    border = BorderStroke(0.2.dp, doorColor),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, null, tint = doorColor, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (previewRoles.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    previewRoles.forEach { role ->
                        val missing = role.modelPath.isNotEmpty() && !knownPaths.contains(role.modelPath)
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (missing) Rd else roleColor(role.role)))
                    }
                }
            }
            // Status chip: ● READY (cyan) or ● CODING… (red) while a coder runs
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(doorColor))
                Text(
                    if (coderRunning) "CODING…" else "READY",
                    fontSize = 6.sp, fontWeight = FontWeight.Bold,
                    color = if (coderRunning) Rd else Cy,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
        // ── Footer: gradient hairline + action bar (OPEN / menu) — the square is a tiny app UI ──
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(IdentityCyan, IdentityPurple)))
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZcPillButton(
                onClick = onPanel,
                tint = Cy,
                modifier = Modifier.weight(1f).height(26.dp),
                label = "OPEN"
            )
            ZcPillButton(
                onClick = onMenu,
                tint = Pr,
                ghost = true,
                modifier = Modifier.height(26.dp),
                label = "⋯"
            )
        }
    }
}

// ═══ Maximized project window (like a desktop window) ════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectWindow(
    project: InventProject,
    knownPaths: Set<String>,
    currentDir: File?,
    onSetDir: (File) -> Unit,
    fileRefresh: Int,
    onMinimize: () -> Unit,
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
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val root = remember(project.id) { InventProjectStore.filesDir(context, project.id) }
    val dir = currentDir ?: root

    val sessionRows = remember(project.sessionIds, fileRefresh) {
        project.sessionIds.mapNotNull { sid ->
            val d = InventStorage.getProjectDir(context, sid)
            if (d.exists() && (d.listFiles()?.isNotEmpty() == true)) d to sid else null
        }
    }
    val sessionDirs = sessionRows.map { it.first }.toSet()

    val files = remember(dir, fileRefresh) {
        val visible: (File) -> Boolean = { !it.name.startsWith(".") } // hide .history etc.
        if (dir == root) {
            val rows = mutableListOf<FileRow>()
            dir.listFiles()?.filter(visible)?.sortedBy { it.name }?.forEach { f ->
                rows.add(if (f.isDirectory) FileRow(f.name, f, true) else FileRow(f.name, f, false, sizeBytes = f.length()))
            }
            sessionRows.forEachIndexed { i, (d, _) -> rows.add(FileRow("Session ${i + 1}", d, true, isSession = true)) }
            rows.sortedBy { it.name }
        } else {
            dir.listFiles()?.filter(visible)?.sortedBy { it.name }?.map { f ->
                if (f.isDirectory) FileRow(f.name, f, true) else FileRow(f.name, f, false, sizeBytes = f.length())
            } ?: emptyList()
        }
    }

    val canStart = project.roles.any { it.isPlanner && it.modelPath.isNotEmpty() } &&
                   project.roles.any { it.isCoder && it.modelPath.isNotEmpty() }
    var sector by remember(project.id) { mutableStateOf("models") }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Card,
        border = BorderStroke(0.2.dp, Line),
        modifier = Modifier.fillMaxSize().padding(8.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            // ── Window title bar ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "−" minimize → collapse back to the 2×2 grid
                Surface(
                    onClick = onMinimize,
                    shape = CircleShape,
                    color = Bulb.copy(alpha = 0.12f),
                    border = BorderStroke(0.2.dp, Bulb.copy(alpha = 0.5f)),
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Minimize, null, tint = Bulb, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FuturisticFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("ROLES · SESSIONS · FILES", fontSize = 8.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                }
                // 🗑 trash → delete the project (asks for confirmation)
                Surface(
                    onClick = onDelete,
                    shape = CircleShape,
                    color = Rd.copy(alpha = 0.12f),
                    border = BorderStroke(0.2.dp, Rd.copy(alpha = 0.5f)),
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Delete, null, tint = Rd, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── Sections ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("models" to "ROLES", "sessions" to "SESSIONS", "files" to "FILES").forEach { (key, label) ->
                    val active = sector == key
                    Surface(
                        onClick = { sector = key },
                        shape = ZcShape.Sm,
                        color = if (active) sectorColor(key).copy(alpha = 0.16f) else CardLight,
                        border = BorderStroke(0.2.dp, if (active) sectorColor(key).copy(alpha = 0.8f) else Line),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = if (active) sectorColor(key) else Color(0xFF8A93A8), fontFamily = FontFamily.SansSerif)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            when (sector) {
                "models" -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        itemsIndexed(project.roles) { _, role ->
                            val missing = role.modelPath.isNotEmpty() && !knownPaths.contains(role.modelPath)
                            Surface(
                                shape = ZcShape.Sm,
                                color = CardLight,
                                border = BorderStroke(0.2.dp, roleColor(role.role).copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onPickModel(role) },
                                        onLongClick = { onRoleMenu(role) }
                                    )
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(roleColor(role.role)))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(role.role.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = roleColor(role.role), fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (role.isCoder) { Spacer(Modifier.width(4.dp)); Text("🔒", fontSize = 8.sp) }
                                        }
                                        Text(
                                            when {
                                                role.modelPath.isEmpty() -> "no model"
                                                missing -> "Unknown"
                                                else -> role.modelName
                                            },
                                            fontSize = 9.5.sp, color = if (missing) Rd else Txt2, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Surface(
                                onClick = onAddRole,
                                shape = ZcShape.Sm,
                                color = Am.copy(alpha = 0.1f),
                                border = BorderStroke(0.2.dp, Am.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Add, null, tint = Am, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("add role", fontSize = 10.sp, color = Am, fontFamily = FontFamily.SansSerif)
                                }
                            }
                        }
                    }
                }
                "sessions" -> {
                    // Live preview: refresh session states every 2s while the tab is open.
                    var liveTick by remember { mutableIntStateOf(0) }
                    LaunchedEffect(liveTick) { delay(2000); liveTick++ }
                    val sessionInfos = remember(project.sessionIds, fileRefresh, liveTick) {
                        project.sessionIds.mapNotNull { sid ->
                            runCatching { InventStorage.loadSession(context, sid) }.getOrNull()?.let { s -> sid to s }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${project.sessionIds.size} sessions", fontSize = 9.5.sp, color = Pr, fontFamily = FontFamily.SansSerif)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            onClick = { if (canStart) onStartSession() },
                            shape = ZcShape.Sm,
                            color = if (canStart) Pr.copy(alpha = 0.15f) else CardLight,
                            border = BorderStroke(0.2.dp, if (canStart) Pr.copy(alpha = 0.6f) else Line)
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Add, null, tint = if (canStart) Pr else Gy, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (canStart) "new session" else "pick models first", fontSize = 9.5.sp, color = if (canStart) Pr else Gy, fontFamily = FontFamily.SansSerif)
                            }
                        }
                    }
                    if (sessionInfos.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(if (canStart) "no sessions yet — tap + new session" else "assign models in MODELS first",
                                fontSize = 10.sp, color = Gy, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            itemsIndexed(sessionInfos) { i, (sid, s) ->
                                val phase = s.phase
                                val active = phase != InventPhase.DONE && phase != InventPhase.QUESTIONING
                                val phaseColor = when {
                                    phase == InventPhase.DEBUGGING -> Rd
                                    phase == InventPhase.GENERATING -> Cy
                                    else -> Pr
                                }
                                val names = listOf(s.model1Name, s.model2Name).filter { it.isNotEmpty() }.joinToString(" · ")
                                Surface(
                                    shape = ZcShape.Sm,
                                    color = CardLight,
                                    border = BorderStroke(0.2.dp, if (active) phaseColor.copy(alpha = 0.55f) else Pr.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onOpenSession(sid) },
                                            onLongClick = { onSessionMenu(sid) }
                                        )
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("S#${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif)
                                            Spacer(Modifier.width(6.dp))
                                            Text(phase.name, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = phaseColor, fontFamily = FontFamily.SansSerif)
                                            if (s.totalFiles > 0) {
                                                Spacer(Modifier.width(5.dp))
                                                Text("file ${s.currentFileIndex}/${s.totalFiles}", fontSize = 8.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                                            }
                                            Spacer(Modifier.weight(1f))
                                            if (active) {
                                                Surface(
                                                    onClick = {
                                                        InventStopSignal.requested = true
                                                        liveTick++
                                                    },
                                                    shape = ZcShape.Sm,
                                                    color = Rd.copy(alpha = 0.15f),
                                                    border = BorderStroke(0.2.dp, Rd.copy(alpha = 0.6f))
                                                ) {
                                                    Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Filled.Stop, null, tint = Rd, modifier = Modifier.size(9.dp))
                                                        Spacer(Modifier.width(3.dp))
                                                        Text("stop", fontSize = 8.sp, color = Rd, fontFamily = FontFamily.SansSerif)
                                                    }
                                                }
                                            } else {
                                                Text("hold: menu", fontSize = 8.sp, color = Line, fontFamily = FontFamily.SansSerif)
                                            }
                                        }
                                        if (names.isNotEmpty()) {
                                            Text(names, fontSize = 9.5.sp, color = Txt2, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "files" -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (dir != root) {
                            Surface(
                                onClick = { onSetDir(if (sessionDirs.contains(dir)) root else dir.parentFile ?: root) },
                                shape = ZcShape.Sm,
                                color = CardLight,
                                border = BorderStroke(0.2.dp, Line)
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.ArrowUpward, null, tint = Cy, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("up", fontSize = 9.sp, color = Cy, fontFamily = FontFamily.SansSerif)
                                }
                            }
                        } else {
                            Surface(
                                onClick = onShareZip,
                                shape = ZcShape.Sm,
                                color = Cy.copy(alpha = 0.12f),
                                border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.5f))
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Share, null, tint = Cy, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("zip", fontSize = 9.sp, color = Cy, fontFamily = FontFamily.SansSerif)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(
                            onClick = onAddFolder,
                            shape = ZcShape.Sm,
                            color = Bulb.copy(alpha = 0.1f),
                            border = BorderStroke(0.2.dp, Bulb.copy(alpha = 0.4f))
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CreateNewFolder, null, tint = Bulb, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("folder", fontSize = 9.sp, color = Bulb, fontFamily = FontFamily.SansSerif)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            onClick = onAddFile,
                            shape = ZcShape.Sm,
                            color = Cy.copy(alpha = 0.12f),
                            border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.5f))
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Add, null, tint = Cy, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("file", fontSize = 9.sp, color = Cy, fontFamily = FontFamily.SansSerif)
                            }
                        }
                    }
                    Text(if (dir == root) project.name else dir.name,
                        fontSize = 9.sp, color = Gy, fontFamily = FontFamily.SansSerif,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    if (files.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("empty — add a file or folder", fontSize = 10.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            itemsIndexed(files) { _, row ->
                                Surface(
                                    shape = ZcShape.Sm,
                                    color = CardLight,
                                    modifier = Modifier.fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { if (row.isDir) onSetDir(row.target) else onFileClick(row.target) },
                                            onLongClick = { if (!row.isDir) onFileClick(row.target) }
                                        )
                                ) {
                                    Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (row.isDir) Icons.Filled.Folder else Icons.Outlined.Description,
                                            null,
                                            tint = if (row.isSession) Pr else if (row.isDir) Bulb else Cy,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(row.name, fontSize = 10.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.SansSerif,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        if (!row.isDir && row.sizeBytes > 0) {
                                            Text(formatSize(row.sizeBytes), fontSize = 8.5.sp, color = Gy, fontFamily = FontFamily.SansSerif)
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
    var background by remember { mutableStateOf(role.backgroundWork) }
    var selectedPath by remember { mutableStateOf(role.modelPath) }
    var selectedName by remember { mutableStateOf(role.modelName) }
    var search by remember { mutableStateOf("") }
    val filteredModels = remember(models, search) {
        if (search.isBlank()) models
        else models.filter { it.name.contains(search, ignoreCase = true) || it.format.contains(search, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ZcShape.Lg,
            color = Card,
            border = BorderStroke(0.2.dp, Line)
        ) {
            LazyColumn(Modifier.widthIn(max = 420.dp).heightIn(max = 560.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚙ ${role.role.uppercase()}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = roleColor(role.role), fontFamily = FontFamily.SansSerif)
                        Spacer(Modifier.weight(1f))
                        Text("Free RAM: ${freeRamMb} MB", fontSize = 10.sp, color = if (freeRamMb > 1500) Cy else Am, fontFamily = FontFamily.SansSerif)
                    }
                }
                item {
                    Text("Model", fontSize = 10.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                }
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                        singleLine = true,
                        placeholder = { Text("🔍  search ${models.size} models…", fontSize = 11.sp, color = Gy, fontFamily = FontFamily.SansSerif) },
                        textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.SansSerif, color = Txt),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = roleColor(role.role),
                            unfocusedBorderColor = Line,
                            cursorColor = roleColor(role.role)
                        )
                    )
                }
                if (filteredModels.isEmpty()) {
                    item {
                        Text("no models match '${search.trim()}'", fontSize = 10.sp, color = Gy, fontFamily = FontFamily.SansSerif, modifier = Modifier.padding(4.dp))
                    }
                }
                itemsIndexed(filteredModels) { _, m ->
                    val isSel = m.path == selectedPath
                    Surface(
                        onClick = { selectedPath = m.path; selectedName = m.name },
                        shape = ZcShape.Sm,
                        color = CardLight,
                        border = BorderStroke(0.2.dp, if (isSel) roleColor(role.role) else Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, fontSize = 12.sp, color = Txt, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${m.sizeFormatted} · ${m.format}", fontSize = 9.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                            }
                            if (isSel) {
                                Icon(Icons.Filled.Check, null, tint = roleColor(role.role), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(2.dp))
                    Text("Context window: $ctx", fontSize = 10.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.SansSerif)
                    Slider(
                        value = ctx.toFloat(),
                        onValueChange = { ctx = it.toInt() },
                        valueRange = 512f..16384f,
                        steps = 30,
                        colors = SliderDefaults.colors(thumbColor = roleColor(role.role), activeTrackColor = roleColor(role.role))
                    )
                }
                item {
                    Text("Max tokens: $maxNew", fontSize = 10.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.SansSerif)
                    Slider(
                        value = maxNew.toFloat(),
                        onValueChange = { maxNew = it.toInt() },
                        valueRange = 64f..4096f,
                        steps = 30,
                        colors = SliderDefaults.colors(thumbColor = roleColor(role.role), activeTrackColor = roleColor(role.role))
                    )
                }
                if (role.isCoder) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Work in background", fontSize = 11.sp, color = Color(0xFFB9C1D0), fontFamily = FontFamily.SansSerif)
                                Text("coder keeps generating while you use another app", fontSize = 8.5.sp, color = Gy, fontFamily = FontFamily.SansSerif)
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
                                        thinkingEnabled = role.thinkingEnabled, backgroundWork = background
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = roleColor(role.role).copy(alpha = 0.15f),
                        border = BorderStroke(0.2.dp, if (selectedPath.isNotEmpty()) roleColor(role.role) else Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    selectedPath.isEmpty() -> "Tap a model above first"
                                    else -> "Done · ${sel?.name ?: selectedName}"
                                },
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (selectedPath.isNotEmpty()) roleColor(role.role) else Gy, fontFamily = FontFamily.SansSerif
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
        title = { Text("New role", color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Role name", fontFamily = FontFamily.SansSerif, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description / command (who he is, what he does)", fontFamily = FontFamily.SansSerif, fontSize = 11.sp) },
                    minLines = 3,
                    textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                shape = ZcShape.Pill,
                onClick = { if (name.isNotBlank()) { onAdd(name.trim(), desc.trim()); onDismiss() } },
                enabled = name.isNotBlank()
            ) {
                Text("Add", color = Cy, fontFamily = FontFamily.SansSerif)
            }
        },
        dismissButton = {
            TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif) }
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
        title = { Text("New folder", color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name", fontFamily = FontFamily.SansSerif, fontSize = 12.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                shape = ZcShape.Pill,
                onClick = { if (name.isNotBlank()) { onAdd(name.trim()); onDismiss() } },
                enabled = name.isNotBlank()
            ) {
                Text("Create", color = Cy, fontFamily = FontFamily.SansSerif)
            }
        },
        dismissButton = {
            TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif) }
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
        title = { Text(role.role.uppercase(), color = roleColor(role.role), fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = {
            if (editing) {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") },
                        singleLine = true, textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = FontFamily.SansSerif, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") },
                        minLines = 3, textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = FontFamily.SansSerif, fontSize = 12.sp), modifier = Modifier.fillMaxWidth())
                }
            } else {
                Column {
                    if (role.description.isNotEmpty()) {
                        Text(role.description, color = Color(0xFFB9C1D0), fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Model: ${role.modelName.ifEmpty { "none" }}", color = Gy, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
                }
            }
        },
        confirmButton = {
            if (editing) {
                TextButton(shape = ZcShape.Pill, onClick = { onEdit(name.trim(), desc.trim()); onDismiss() }, enabled = name.isNotBlank()) {
                    Text("Save", color = Cy, fontFamily = FontFamily.SansSerif)
                }
            } else {
                TextButton(shape = ZcShape.Pill, onClick = { editing = true }) {
                    Text("Change name/description", color = Am, fontFamily = FontFamily.SansSerif, fontSize = 11.sp)
                }
            }
        },
        dismissButton = {
            if (editing) {
                TextButton(shape = ZcShape.Pill, onClick = { editing = false }) { Text("Back", color = Gy, fontFamily = FontFamily.SansSerif) }
            } else if (!role.isCoder) {
                TextButton(shape = ZcShape.Pill, onClick = { onDelete(); onDismiss() }) { Text("Delete role", color = Rd, fontFamily = FontFamily.SansSerif) }
            } else {
                TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Close", color = Gy, fontFamily = FontFamily.SansSerif) }
            }
        }
    )
}

/** Hold-click on a session: open / export / delete / reset (keep files). */
@Composable
private fun SessionActionsDialog(
    sessionName: String,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Session", color = Pr, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = { Text(sessionName, color = Color(0xFFB9C1D0), fontSize = 12.sp, fontFamily = FontFamily.SansSerif) },
        confirmButton = {
            TextButton(shape = ZcShape.Pill, onClick = { onOpen(); onDismiss() }) { Text("Open session", color = Cy, fontFamily = FontFamily.SansSerif, fontSize = 12.sp) }
        },
        dismissButton = {
            Row {
                TextButton(shape = ZcShape.Pill, onClick = { onExport(); onDismiss() }) { Text("Export transcript", color = Am, fontFamily = FontFamily.SansSerif, fontSize = 10.sp) }
                TextButton(shape = ZcShape.Pill, onClick = { onReset(); onDismiss() }) { Text("Reset", color = Am, fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                TextButton(shape = ZcShape.Pill, onClick = { onDelete(); onDismiss() }) { Text("Delete project", color = Rd, fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
            }
        }
    )
}

/** Floating window behind a clicked file: open / copy / history / delete. */
@Composable
private fun FileActionsDialog(
    fileName: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("📄 $fileName", color = Cy, fontFamily = FontFamily.SansSerif, fontSize = 13.sp) },
        text = { },
        confirmButton = {
            TextButton(shape = ZcShape.Pill, onClick = { onOpen(); onDismiss() }) { Text("Open", color = Cy, fontFamily = FontFamily.SansSerif) }
        },
        dismissButton = {
            Row {
                TextButton(shape = ZcShape.Pill, onClick = { onCopy(); onDismiss() }) { Text("Copy code", color = Am, fontFamily = FontFamily.SansSerif, fontSize = 10.sp) }
                TextButton(shape = ZcShape.Pill, onClick = { onHistory(); onDismiss() }) { Text("History", color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 10.sp) }
                TextButton(shape = ZcShape.Pill, onClick = { onDelete(); onDismiss() }) { Text("Delete", color = Rd, fontFamily = FontFamily.SansSerif, fontSize = 10.sp) }
                TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif, fontSize = 10.sp) }
            }
        }
    )
}

/** Long-press on a square: rename / export zip / clear contents. */
@Composable
private fun ProjectMenuDialog(
    project: InventProject,
    onRename: () -> Unit,
    onExportZip: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(project.name, color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = {
            Text("${project.roles.size} roles · ${project.sessionIds.size} sessions", color = Txt2, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
        },
        confirmButton = {
            TextButton(shape = ZcShape.Pill, onClick = { onRename(); onDismiss() }) { Text("Rename", color = Am, fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
        },
        dismissButton = {
            Row {
                TextButton(shape = ZcShape.Pill, onClick = { onExportZip(); onDismiss() }) { Text("Export .zip", color = Cy, fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                TextButton(shape = ZcShape.Pill, onClick = { onClear(); onDismiss() }) { Text("Clear", color = Rd, fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
                TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif, fontSize = 11.sp) }
            }
        }
    )
}

/** Rename a project. */
@Composable
private fun RenameDialog(
    initialName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Rename project", color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name", fontFamily = FontFamily.SansSerif, fontSize = 12.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(shape = ZcShape.Pill, onClick = { if (name.isNotBlank()) { onRename(name.trim()); onDismiss() } }, enabled = name.isNotBlank()) {
                Text("Save", color = Cy, fontFamily = FontFamily.SansSerif)
            }
        },
        dismissButton = {
            TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif) }
        }
    )
}

/** Empty slot → new project with a template (roles pre-seeded + skeleton files). */
@Composable
private fun NewProjectDialog(
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf("blank") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("New project", color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name", fontFamily = FontFamily.SansSerif, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("Template", fontSize = 10.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.height(5.dp))
                InventTemplates.ALL.forEach { t ->
                    val active = selected == t.id
                    Surface(
                        onClick = { selected = t.id },
                        shape = ZcShape.Sm,
                        color = if (active) Cy.copy(alpha = 0.12f) else CardLight,
                        border = BorderStroke(0.2.dp, if (active) Cy.copy(alpha = 0.6f) else Line),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.label} — ${t.tagline}", fontSize = 11.sp, color = Txt, fontFamily = FontFamily.SansSerif)
                                Text(t.description, fontSize = 8.5.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                            }
                            if (active) Icon(Icons.Filled.Check, null, tint = Cy, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                shape = ZcShape.Pill,
                onClick = { onCreate(name.trim().ifEmpty { "Project" }, selected); onDismiss() },
                enabled = true
            ) { Text("Create", color = Cy, fontFamily = FontFamily.SansSerif) }
        },
        dismissButton = {
            TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Cancel", color = Gy, fontFamily = FontFamily.SansSerif) }
        }
    )
}

/** File version history: tap a version to view, Restore writes it back. */
@Composable
private fun HistoryDialog(
    versions: List<File>,
    onRestore: (File) -> Unit,
    onView: (File) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("History", color = Bulb, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
        text = {
            if (versions.isEmpty()) {
                Text("No saved versions yet — edit the file once and it will be kept here.",
                    color = Txt2, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(versions) { i, v ->
                        val stamp = runCatching { java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(v.name.toLong())) }
                            .getOrDefault(v.name)
                        Surface(
                            shape = ZcShape.Sm,
                            color = CardLight,
                            border = BorderStroke(0.2.dp, Line),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("v${versions.size - i}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif)
                                Spacer(Modifier.width(8.dp))
                                Text(stamp, fontSize = 9.sp, color = Txt2, fontFamily = FontFamily.SansSerif)
                                Spacer(Modifier.weight(1f))
                                TextButton(shape = ZcShape.Pill, onClick = { onView(v); onDismiss() }) { Text("View", fontSize = 9.sp, color = Cy, fontFamily = FontFamily.SansSerif) }
                                TextButton(shape = ZcShape.Pill, onClick = { onRestore(v); onDismiss() }) { Text("Restore", fontSize = 9.sp, color = Am, fontFamily = FontFamily.SansSerif) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(shape = ZcShape.Pill, onClick = onDismiss) { Text("Close", color = Gy, fontFamily = FontFamily.SansSerif) }
        }
    )
}

// ═══ In-square panel: models + sessions + roles (the "minimized square" view) ═══

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SquarePanel(
    project: InventProject,
    knownPaths: Set<String>,
    models: List<com.gguf.zerocopy.data.repository.LocalModel>,
    fileRefresh: Int,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
    onPickModel: (InventRoleConfig) -> Unit,
    onModelInfo: (InventRoleConfig, com.gguf.zerocopy.data.repository.LocalModel) -> Unit,
    onAddRole: () -> Unit,
    onRoleMenu: (InventRoleConfig) -> Unit,
    onStartSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSessionMenu: (String) -> Unit,
    onToggleBackground: (InventRoleConfig) -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) } // 0 = MODELS, 1 = SESSIONS
    Surface(
        shape = ZcShape.Lg,
        color = Card,
        border = BorderStroke(0.2.dp, Line),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // ── Title bar (slim — more room for the notebook below) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Cy))
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FuturisticFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("RAM ${freeRamMb(context)} MB free", fontSize = 6.5.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                }
                Surface(onClick = onMaximize, shape = ZcShape.Sm, color = Cy.copy(alpha = 0.12f), border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.5f)), modifier = Modifier.size(20.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.OpenInFull, null, tint = Cy, modifier = Modifier.size(10.dp)) }
                }
                Spacer(Modifier.width(5.dp))
                Surface(onClick = onClose, shape = ZcShape.Sm, color = Rd.copy(alpha = 0.12f), border = BorderStroke(0.2.dp, Rd.copy(alpha = 0.5f)), modifier = Modifier.size(20.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, null, tint = Rd, modifier = Modifier.size(10.dp)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            // ── Notebook pages: ROLES | SESSIONS (click flips to the next page) ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                listOf(0 to "ROLES", 1 to "SESSIONS").forEach { (t, label) ->
                    val active = tab == t
                    Surface(
                        onClick = { tab = t },
                        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                        color = if (active) CardLight else Card.copy(alpha = 0.6f),
                        border = BorderStroke(0.2.dp, if (active) Line else Line.copy(alpha = 0.35f)),
                        modifier = Modifier.weight(1f).then(if (active) Modifier else Modifier.offset(y = 2.dp))
                    ) {
                        Box(Modifier.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                color = if (active) Pr else Gy, fontFamily = FuturisticFont, letterSpacing = 1.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            // ── Tab content ──
            if (tab == 0) {
                // MODELS
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(project.roles) { _, role ->
                        val missing = role.modelPath.isNotEmpty() && !knownPaths.contains(role.modelPath)
                        val model = models.find { it.path == role.modelPath }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CardLight,
                            border = BorderStroke(0.2.dp, roleColor(role.role).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = { if (model != null) onModelInfo(role, model) else onPickModel(role) },
                                onLongClick = { onRoleMenu(role) }
                            )
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (missing) Rd else roleColor(role.role)))
                                    Spacer(Modifier.width(5.dp))
                                    Text(role.role.uppercase(), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = roleColor(role.role), fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (role.isCoder) { Spacer(Modifier.width(3.dp)); Text("🔒", fontSize = 7.sp) }
                                }
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when {
                                            role.modelPath.isEmpty() -> "no model — tap to pick"
                                            missing -> "Unknown file — tap to pick"
                                            else -> role.modelName.ifEmpty { model?.name ?: "model" }
                                        },
                                        fontSize = 8.sp, color = Txt2, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (role.isCoder) {
                                        Spacer(Modifier.width(4.dp))
                                        MiniToggle(checked = role.backgroundWork, onToggle = { onToggleBackground(role) })
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Surface(
                            onClick = onAddRole,
                            shape = RoundedCornerShape(10.dp),
                            color = Pr.copy(alpha = 0.10f),
                            border = BorderStroke(0.2.dp, Pr.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Add, null, tint = Pr, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("add role", fontSize = 7.5.sp, color = Pr, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                // SESSIONS
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        if (project.sessionIds.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                    Text("no sessions yet", fontSize = 9.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                                }
                            }
                        }
                        itemsIndexed(project.sessionIds) { i, sid ->
                            val s = remember(sid, fileRefresh) { runCatching { InventStorage.loadSession(context, sid) }.getOrNull() }
                            val title = s?.model1Name?.takeIf { it.isNotBlank() } ?: "Session ${i + 1}"
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CardLight,
                                border = BorderStroke(0.2.dp, Pr.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { onOpenSession(sid) },
                                    onLongClick = { onSessionMenu(sid) }
                                )
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(16.dp).clip(ZcShape.Sm).background(Pr.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                        Text("S#${i + 1}", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(title, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("phase: ${s?.phase?.name ?: "?"}", fontSize = 7.sp, color = Gy, fontFamily = FontFamily.SansSerif)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = onStartSession,
                        shape = RoundedCornerShape(10.dp),
                        color = Pr.copy(alpha = 0.16f),
                        border = BorderStroke(0.2.dp, Pr.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, null, tint = Pr, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("new session", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif)
                        }
                    }
                }
            }
        }
    }
}

// ── Compact on/off pill (thinking / background toggles) ──
@Composable
private fun MiniToggle(checked: Boolean, onToggle: () -> Unit) {
    val col = if (checked) Cy else Gy
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(10.dp),
        color = col.copy(alpha = 0.18f),
        border = BorderStroke(0.2.dp, col.copy(alpha = 0.55f))
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (checked) "on" else "off", fontSize = 7.sp, color = col, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Model info + conversation RAM window ──
@Composable
private fun ModelInfoDialog(
    role: InventRoleConfig,
    model: com.gguf.zerocopy.data.repository.LocalModel,
    freeRamMb: Long,
    onChangeModel: () -> Unit,
    onClose: () -> Unit
) {
    val modelMb = model.sizeBytes / (1024.0 * 1024.0)
    val kvMb = estimateKvMb(role.contextWindow, modelMb)
    val activMb = modelMb * 0.08
    val totalMb = modelMb + kvMb + activMb + 280.0 // weights + KV + activations + app floor
    val fits = totalMb < freeRamMb.toDouble()

    // Small, centered window (the captain: model settings + RAM calc should
    // be compact and in the middle of the screen).
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = ZcShape.Lg,
            color = Card,
            border = BorderStroke(0.2.dp, if (fits) Cy.copy(alpha = 0.5f) else Rd.copy(alpha = 0.5f)),
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.85f)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("MODEL INFO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Bulb, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.height(8.dp))
                InfoRow("model", model.name)
                InfoRow("size", model.sizeFormatted)
                InfoRow("format", model.format)
                InfoRow("role", role.role)
                InfoRow("context", "${role.contextWindow} tokens")
                InfoRow("max tokens", "${role.maxTokens}")
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Line, thickness = 0.5.dp)
                Spacer(Modifier.height(6.dp))
                Text("CONVERSATION RAM (estimate)", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.height(5.dp))
                InfoRow("weights", "%.0f MB".format(modelMb))
                InfoRow("kv cache (${role.contextWindow} ctx)", "%.0f MB".format(kvMb))
                InfoRow("activations", "%.0f MB".format(activMb))
                InfoRow("app overhead", "280 MB")
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("≈ total %.0f MB".format(totalMb), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (fits) Cy else Rd, fontFamily = FontFamily.SansSerif)
                    Spacer(Modifier.weight(1f))
                    Text("free: $freeRamMb MB", fontSize = 8.5.sp, color = if (fits) Gy else Rd, fontFamily = FontFamily.SansSerif)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = onChangeModel,
                        shape = RoundedCornerShape(10.dp),
                        color = Pr.copy(alpha = 0.15f),
                        border = BorderStroke(0.2.dp, Pr.copy(alpha = 0.6f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                            Text("Change model", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Pr, fontFamily = FontFamily.SansSerif)
                        }
                    }
                    Surface(
                        onClick = onClose,
                        shape = RoundedCornerShape(10.dp),
                        color = Cy.copy(alpha = 0.15f),
                        border = BorderStroke(0.2.dp, Cy.copy(alpha = 0.6f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                            Text("Close", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Cy, fontFamily = FontFamily.SansSerif)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 9.sp, color = Gy, fontFamily = FontFamily.SansSerif, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 9.sp, color = Txt, fontFamily = FontFamily.SansSerif, modifier = Modifier.weight(1f))
    }
}

// GQA heuristic: KV ≈ ctx × layers × heads × headDim × 2(K+V) × 2B(f16).
// layers ≈ modelGb × 7 clamped to [8, 64]; heads ≈ layers/4 (GQA-8 for 7B).
private fun estimateKvMb(ctx: Int, modelMb: Double): Double {
    val layers = (modelMb * 7.0).coerceIn(8.0, 64.0)
    val heads = (layers / 4.0).coerceAtLeast(4.0)
    val headDim = 128.0
    val bytesPerTok = layers * heads * headDim * 2 * 2
    return ctx * bytesPerTok / (1024.0 * 1024.0)
}
