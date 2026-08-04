package com.gguf.zerocopy.ui.invent

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.invent.*
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.invent.GgufMetaReader
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.RepeatPenaltyConfig
import com.gguf.zerocopy.domain.inference.TokenCallback
import com.gguf.zerocopy.domain.inference.ToolCall
import com.gguf.zerocopy.domain.inference.ToolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ModelMode { SINGLE, DUAL, TRIPLE }

data class InventUiState(
    val phase: InventPhase = InventPhase.QUESTIONING,
    val messages: List<InventMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val swapInfo: String = "",
    val searchRound: Int = 0,
    val mergeCount: Int = 0,
    val showDeleteConfirm: Boolean = false,
    val showSureButtons: Boolean = false,
    val fileTree: List<FileNode> = emptyList(),
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String = "",
    val sessionId: String = "",
    val projectName: String = "",
    val model1Name: String = "",
    val model2Name: String = "",
    val researcherName: String = "",
    val offlineMode: Boolean = false,
    val sameModelMode: Boolean = false,
    val error: String = "",
    val zipReady: Boolean = false,
    val debugMode: Boolean = false,
    val totalTokensUsed: Int = 0,
    val totalLines: Int = 0,
    val totalGeneratedBytes: Long = 0,
    val debugSessionCount: Int = 0,
    val sessions: List<SessionInfo> = emptyList(),
    val showSessionList: Boolean = false,
    val currentModelLabel: String = "",
    val processLabel: String = "",
    val plannerLoaded: Boolean = false,
    val researcherLoaded: Boolean = false,
    val coderLoaded: Boolean = false,
    val modelMode: ModelMode = ModelMode.TRIPLE,
    val showNavigateAwayDialog: Boolean = false,
    val showPlanReview: Boolean = false,
    val pendingPlan: String = "",
    val chatStarted: Boolean = false,
    val streamingResponse: String = "",
    val conversationDepth: Int = 0,  // total chars in user+model messages, for Done threshold
    val reasoningEnabled: Boolean = false,
    val thinkingContent: String = "",
    val questioningProgress: Float = 0f, // 0..1 akinator-style progress during QUESTIONING
    val projectCompleted: Boolean = false
)

data class SessionInfo(
    val id: String,
    val projectName: String,
    val phase: InventPhase,
    val fileCount: Int,
    val lastActivity: String
)

class InventViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val engineManager get() = ZeroCopyApp.instance.engineManager
    private val toolManager get() = ZeroCopyApp.instance.toolManager

    private val _ui = MutableStateFlow(InventUiState())
    val ui: StateFlow<InventUiState> = _ui

    /**
     * State holders with debounced auto-persist.
     * Setters automatically schedule a coalesced save (300ms debounce).
     * For atomic multi-holder updates, use withTransaction().
     */
    private var sessionState: InventSessionState? = null
        set(value) {
            field = value
            if (value != null && sessionId.isNotEmpty()) scheduleSave()
        }
    private var zcp: ZcpProtocol = ZcpProtocol()
        set(value) {
            field = value
            if (sessionState != null && sessionId.isNotEmpty()) scheduleSave()
        }
    private var sessionId: String = ""
    /** Saved original paths for model mode switching. */
    private var savedOriginalPaths = mutableMapOf<String, String>()

    /** Debounced persist — coalesces multiple rapid updates into one save. */
    private var pendingSave = false
    private var saveJob: kotlinx.coroutines.Job? = null

    private fun scheduleSave() {
        if (pendingSave) return
        pendingSave = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300L)
            pendingSave = false
            val s = sessionState ?: return@launch
            val sid = sessionId.ifEmpty { return@launch }
            val z = zcp
            InventStorage.saveSession(ctx, s)
            InventStorage.saveZcp(ctx, sid, z)
        }
    }

    /**
     * Atomically update UI state AND session state in one step,
     * ensuring the setter-based auto-save captures the final state.
     * Use this for operations that change both _ui and sessionState/zcp.
     */
    private fun withTransaction(
        uiTransform: (InventUiState) -> InventUiState = { it },
        sessionTransform: (InventSessionState?) -> InventSessionState? = { it },
        zcpTransform: (ZcpProtocol) -> ZcpProtocol = { it }
    ) {
        _ui.value = uiTransform(_ui.value)
        val newSession = sessionTransform(sessionState)
        if (newSession !== sessionState) sessionState = newSession
        val newZcp = zcpTransform(zcp)
        if (newZcp !== zcp) zcp = newZcp
    }

    /** Force an immediate save (for navigation away / export). */
    private suspend fun flushSave() {
        saveJob?.cancel()
        pendingSave = false
        val s = sessionState ?: return
        val sid = sessionId.ifEmpty { return }
        val z = zcp
        withContext(Dispatchers.IO) {
            InventStorage.saveSession(ctx, s)
            InventStorage.saveZcp(ctx, sid, z)
        }
    }

    init {
        restoreLastSession()
    }

    private fun restoreLastSession() {
        val phaseOrder = InventPhase.values().toList()
        val existing = InventStorage.listSessions(ctx)
            .mapNotNull { sid ->
                val s = InventStorage.loadSession(ctx, sid)
                if (s != null && s.phase != InventPhase.DONE && s.phase != InventPhase.DEBUGGING) sid to s else null
            }
            .sortedByDescending { (_, s) -> phaseOrder.indexOf(s.phase) }
            .firstOrNull()?.first ?: return
        val saved = InventStorage.loadSession(ctx, existing) ?: return
        val savedZcp = InventStorage.loadZcp(ctx, existing) ?: return
        // Validate model files still exist on disk before restoring
        val modelFilesExist = listOf(saved.model1Path, saved.model2Path, saved.researcherPath)
            .filter { it.isNotEmpty() }
            .all { java.io.File(it).exists() }
        if (!modelFilesExist) {
            android.util.Log.w("InventVM", "Skipping restore: model files no longer exist")
            InventStorage.deleteSession(ctx, existing)
            return
        }
        withTransaction(
            uiTransform = {
                it.copy(
                    phase = saved.phase, messages = saved.messages, sessionId = existing,
                    model1Name = saved.model1Name, model2Name = saved.model2Name,
                    researcherName = saved.researcherName,
                    offlineMode = saved.offlineMode, sameModelMode = saved.sameModelMode,
                    modelMode = when {
                        saved.sameModelMode && saved.researcherPath == saved.model1Path -> ModelMode.SINGLE
                        saved.sameModelMode -> ModelMode.DUAL
                        else -> ModelMode.TRIPLE
                    },
                    searchRound = saved.searchRound, mergeCount = saved.mergeCount,
                    currentFileIndex = saved.currentFileIndex, totalFiles = saved.totalFiles,
                    debugMode = saved.phase == InventPhase.DEBUGGING,
                    zipReady = saved.phase == InventPhase.DONE || saved.phase == InventPhase.DEBUGGING
                )
            },
            sessionTransform = { saved },
            zcpTransform = { savedZcp }
        )
        sessionId = existing
        // Process-death recovery: restart any in-flight pipeline step so a
        // restored GENERATING/FINALIZING session doesn't sit frozen.
        viewModelScope.launch {
            when (saved.phase) {
                InventPhase.GENERATING -> resumeGeneration()
                InventPhase.FINALIZING -> finishGeneration()
                else -> {}
            }
        }
    }

    // ── Atomic persistence ── All state mutations either go through
    // withTransaction() for multi-holder updates, or trigger auto-save via
    // sessionState/zcp setters for single-holder updates.

    fun setShowDeleteConfirm(v: Boolean) { _ui.value = _ui.value.copy(showDeleteConfirm = v) }

    fun toggleSameModelMode() {
        val state = sessionState ?: return
        val newMode = !state.sameModelMode
        withTransaction(
            uiTransform = { it.copy(sameModelMode = newMode, model2Name = if (newMode) it.model1Name else state.model2Name) },
            sessionTransform = {
                if (newMode) it?.copy(sameModelMode = true, model2Path = it.model1Path, model2Name = it.model1Name)
                else it?.copy(sameModelMode = false)
            }
        )
    }

    fun setModelMode(mode: ModelMode) {
        val state = sessionState ?: return
        // Save original paths only on first mode switch from setup
        if (savedOriginalPaths.isEmpty()) {
            savedOriginalPaths["planner"] = state.model1Path
            savedOriginalPaths["plannerName"] = state.model1Name
            savedOriginalPaths["coder"] = state.model2Path
            savedOriginalPaths["coderName"] = state.model2Name
            savedOriginalPaths["researcher"] = state.researcherPath
            savedOriginalPaths["researcherName"] = state.researcherName
        }

        val plannerP = savedOriginalPaths["planner"]?.takeIf { it.isNotEmpty() } ?: state.model1Path
        val plannerN = savedOriginalPaths["plannerName"]?.takeIf { it.isNotEmpty() } ?: state.model1Name
        val coderP = savedOriginalPaths["coder"]?.takeIf { it.isNotEmpty() } ?: state.model2Path
        val coderN = savedOriginalPaths["coderName"]?.takeIf { it.isNotEmpty() } ?: state.model2Name
        val resP = savedOriginalPaths["researcher"]?.takeIf { it.isNotEmpty() } ?: state.researcherPath
        val resN = savedOriginalPaths["researcherName"]?.takeIf { it.isNotEmpty() } ?: state.researcherName

        val newSessionState = when (mode) {
            ModelMode.SINGLE -> state.copy(
                sameModelMode = true,
                researcherPath = plannerP, researcherName = plannerN,
                model2Path = plannerP, model2Name = plannerN
            )
            ModelMode.DUAL -> state.copy(
                sameModelMode = true,
                model2Path = plannerP, model2Name = plannerN,
                researcherPath = resP, researcherName = resN
            )
            ModelMode.TRIPLE -> state.copy(
                sameModelMode = false,
                model2Path = coderP, model2Name = coderN,
                researcherPath = resP, researcherName = resN
            )
        }
        withTransaction(
            uiTransform = {
                it.copy(
                    modelMode = mode,
                    researcherName = newSessionState.researcherName,
                    model1Name = newSessionState.model1Name,
                    model2Name = newSessionState.model2Name,
                    sameModelMode = newSessionState.sameModelMode
                )
            },
            sessionTransform = { newSessionState }
        )
    }

    fun selectModelTab(tab: Int, modelPath: String = "", modelName: String = "", useForAll: Boolean = false) {
        val state = sessionState ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var path = if (modelPath.isNotEmpty()) modelPath else when (tab) {
                0 -> state.model1Path; 1 -> state.researcherPath; 2 -> state.model2Path; else -> return@launch
            }
            var name = if (modelName.isNotEmpty()) modelName else when (tab) {
                0 -> state.model1Name; 1 -> state.researcherName; 2 -> state.model2Name; else -> return@launch
            }
            if (path.isEmpty()) {
                val active = engineManager.getActiveEngine()
                path = active?.loadedModelPath ?: ""
                name = if (path.isNotEmpty()) path.substringAfterLast('/').substringAfterLast('\\') else "Loaded Model"
            }
            if (path.isNotEmpty()) {
                val ok = loadOrKeepModel(path)
                if (ok) {
                    withTransaction(
                        uiTransform = { cur ->
                            cur.copy(
                                plannerLoaded = cur.plannerLoaded || ((useForAll || tab == 0) && ok),
                                researcherLoaded = cur.researcherLoaded || ((useForAll || tab == 1) && ok),
                                coderLoaded = cur.coderLoaded || ((useForAll || tab == 2) && ok),
                                model1Name = if (ok && (useForAll || tab == 0) && name.isNotEmpty()) name else cur.model1Name,
                                model2Name = if (ok && (useForAll || tab == 2) && name.isNotEmpty()) name else cur.model2Name,
                                researcherName = if (ok && (useForAll || tab == 1) && name.isNotEmpty()) name else cur.researcherName
                            )
                        },
                        sessionTransform = { s ->
                            if (useForAll) s?.copy(
                                model1Path = path, model1Name = name,
                                researcherPath = path, researcherName = name,
                                model2Path = path, model2Name = name
                            ) else when (tab) {
                                0 -> { val s2 = s?.copy(model1Path = path, model1Name = name); if (s?.sameModelMode == true) s2?.copy(model2Path = path, model2Name = name) else s2 }
                                1 -> s?.copy(researcherPath = path, researcherName = name)
                                2 -> { val s2 = s?.copy(model2Path = path, model2Name = name); if (s?.sameModelMode == true) s2?.copy(model1Path = path, model1Name = name) else s2 }
                                else -> s
                            }
                        }
                    )
                }
            }
        }
    }

    // ── Navigation guard ───────────────────────────────────────────────────

    fun setNavigateAway(v: Boolean) { _ui.value = _ui.value.copy(showNavigateAwayDialog = v) }

    fun isBusyGenerating(): Boolean = _ui.value.isGenerating ||
        _ui.value.phase in listOf(InventPhase.GENERATING, InventPhase.SEARCHING, InventPhase.FINALIZING)

    // ── Session Management ─────────────────────────────────────────────────

    fun refreshSessionList() {
        viewModelScope.launch(Dispatchers.IO) {
            // Flush current session before listing so the snapshot is saved
            flushSave()
            val list = InventStorage.listSessions(ctx).mapNotNull { sid ->
                val saved = InventStorage.loadSession(ctx, sid)
                val z = InventStorage.loadZcp(ctx, sid)
                if (saved != null) {
                    SessionInfo(
                        id = sid,
                        projectName = z?.projectName?.ifEmpty { saved.model1Name } ?: saved.model1Name,
                        phase = saved.phase,
                        fileCount = z?.fileTree?.count { !it.isDir } ?: 0,
                        lastActivity = saved.messages.lastOrNull()?.content?.take(40) ?: ""
                    )
                } else null
            }
            withContext(Dispatchers.Main) {
                _ui.value = _ui.value.copy(sessions = list)
            }
        }
    }

    fun switchToSession(targetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Save current session before switching
            flushSave()
            val saved = InventStorage.loadSession(ctx, targetId)
            val savedZcp = InventStorage.loadZcp(ctx, targetId)
            if (saved != null && savedZcp != null) {
                engineManager.unloadAll()
                sessionId = targetId
                withTransaction(
                    uiTransform = {
                        it.copy(
                            phase = saved.phase,
                            messages = saved.messages,
                            sessionId = targetId,
                            model1Name = saved.model1Name, model2Name = saved.model2Name,
                            researcherName = saved.researcherName,
                            offlineMode = saved.offlineMode, sameModelMode = saved.sameModelMode,
                            modelMode = when {
                                saved.sameModelMode && saved.researcherPath == saved.model1Path -> ModelMode.SINGLE
                                saved.sameModelMode -> ModelMode.DUAL
                                else -> ModelMode.TRIPLE
                            },
                            fileTree = savedZcp.fileTree,
                            searchRound = saved.searchRound, mergeCount = saved.mergeCount,
                            currentFileIndex = saved.currentFileIndex, totalFiles = saved.totalFiles,
                            debugMode = saved.phase == InventPhase.DEBUGGING,
                            showSessionList = false,
                            zipReady = saved.phase == InventPhase.DONE || saved.phase == InventPhase.DEBUGGING
                        )
                    },
                    sessionTransform = { saved },
                    zcpTransform = { savedZcp }
                )
                computeStats()
            }
        }
    }

    fun deleteSessionById(targetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            InventStorage.deleteSession(ctx, targetId)
            refreshSessionList()
            if (targetId == sessionId) {
                _ui.value = InventUiState(sessions = _ui.value.sessions)
            }
        }
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    private fun computeStats() {
        val projectDir = getProjectDir()
        var lines = 0L; var bytes = 0L
        for (path in zcp.generatedFiles) {
            val content = InventStorage.readGeneratedFile(projectDir, path) ?: continue
            lines += content.count { it == '\n' } + 1
            bytes += content.length.toLong()
        }
        _ui.value = _ui.value.copy(
            totalLines = lines.toInt(), totalGeneratedBytes = bytes, debugSessionCount = zcp.debugSessions.size
        )
    }

    /**
     * Lightweight local telemetry — appends one record per pipeline milestone to
     * filesDir/invent_telemetry.json (capped at the 200 most recent records).
     * Lets us measure real Invent success: sessions started vs. projects that
     * reached DONE, files planned vs. generated, failures and sanity warnings.
     */
    private fun recordTelemetry(extra: JSONObject? = null) {
        try {
            val record = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("sessionId", sessionId)
                put("projectName", zcp.projectName.ifEmpty { "unknown" })
                put("language", org.json.JSONArray(zcp.language))
                put("framework", zcp.framework)
                put("phase", _ui.value.phase.name)
                put("filesPlanned", zcp.fileTree.count { !it.isDir })
                put("filesGenerated", zcp.generatedFiles.size)
                put("conversationDepth", _ui.value.conversationDepth)
                put("sameModelMode", _ui.value.sameModelMode)
            }
            extra?.keys()?.forEach { key -> record.put(key, extra.get(key)) }
            val file = File(ctx.filesDir, "invent_telemetry.json")
            var arr = org.json.JSONArray()
            if (file.exists()) {
                try { arr = org.json.JSONArray(file.readText()) } catch (_: Exception) { arr = org.json.JSONArray() }
            }
            arr.put(record)
            if (arr.length() > 200) {
                val trimmed = org.json.JSONArray()
                for (i in arr.length() - 200 until arr.length()) trimmed.put(arr.get(i))
                arr = trimmed
            }
            file.writeText(arr.toString(2))
        } catch (_: Exception) { /* telemetry must never crash the pipeline */ }
    }

    private fun getProjectDir(): File {
        val sid = sessionId.ifEmpty { "_" }
        return InventStorage.getProjectDir(ctx, sid, zcp.projectName)
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    fun setupSession(
        model1Path: String, model1Name: String,
        model2Path: String, model2Name: String,
        researcherPath: String, researcherName: String,
        offlineMode: Boolean, sameModelMode: Boolean,
        reasoningEnabled: Boolean = true
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedTemplate = com.gguf.zerocopy.data.local.SettingsManager.chatTemplate
            var m1p = model1Path; var m1n = model1Name
            var m2p = model2Path; var m2n = model2Name
            var rp = researcherPath; var rn = researcherName
            if (m1p.isEmpty()) {
                val active = engineManager.getActiveEngine()
                val curPath = active?.loadedModelPath ?: ""
                val curName = if (curPath.isNotEmpty()) curPath.substringAfterLast('/').substringAfterLast('\\') else "Loaded Model"
                m1p = curPath; m1n = curName; m2p = curPath; m2n = curName; rp = curPath; rn = curName
            }

            clearToolManagerOnEngines()

            val m1Ctx = GgufMetaReader.readContextLength(m1p).let { if (it == null || it <= 0) 2048 else it }
            val m2Ctx = if (sameModelMode) m1Ctx
                        else GgufMetaReader.readContextLength(m2p).let { if (it == null || it <= 0) 2048 else it }

            val userCtx = SettingsManager.nCtx
            val effectiveCtx = if (userCtx <= 0) m1Ctx else userCtx.coerceAtMost(m1Ctx)
            val userConfig = SettingsManager.toConfig()
            val tunedConfig = userConfig.copy(nCtx = effectiveCtx)
            engineManager.llamaCpp.config = tunedConfig
            engineManager.mnn.config = tunedConfig
            engineManager.liteRt.config = tunedConfig

            val newSessionId = UUID.randomUUID().toString().take(8)
            sessionId = newSessionId
            val newSession = InventSessionState(
                sessionId = newSessionId, phase = InventPhase.QUESTIONING,
                model1Path = m1p, model1Name = m1n,
                model2Path = if (sameModelMode) m1p else m2p,
                model2Name = if (sameModelMode) m1n else m2n,
                researcherPath = rp, researcherName = rn,
                model1ContextSize = m1Ctx, model2ContextSize = m2Ctx,
                offlineMode = offlineMode, sameModelMode = sameModelMode,
                chatTemplate = selectedTemplate
            )
            val newZcp = ZcpProtocol(model2ContextSize = m2Ctx, offlineMode = offlineMode)
            withTransaction(
                uiTransform = {
                    it.copy(
                        phase = InventPhase.QUESTIONING, sessionId = newSessionId,
                        model1Name = m1n, model2Name = if (sameModelMode) m1n else m2n,
                        researcherName = rn, offlineMode = offlineMode, sameModelMode = sameModelMode,
                        reasoningEnabled = reasoningEnabled,
                        modelMode = when { sameModelMode && rp == m1p -> ModelMode.SINGLE
                            sameModelMode -> ModelMode.DUAL; else -> ModelMode.TRIPLE }
                    )
                },
                sessionTransform = { newSession },
                zcpTransform = { newZcp }
            )
            recordTelemetry(JSONObject().apply { put("outcome", "session_start") })
            startModel1Questioning()
        }
    }

    // ── Phase 1: Questioning ───────────────────────────────────────────────

    private suspend fun startModel1Questioning() {
        val state = sessionState ?: return
        setSwap("Loading ${state.model1Name}…")
        if (!loadOrKeepModel(state.model1Path)) {
            setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return
        }
        _ui.value = _ui.value.copy(plannerLoaded = true)
        setSwap("")
        // runInference resets context internally before inference
        val opening = runInference(
            systemPrompt = buildQuestioningPrompt(),
            userMessage = "Hi! I want to build a software project. Please help me plan it by asking about my requirements — one question at a time.",
            onStream = { partial ->
                _ui.value = _ui.value.copy(streamingResponse = partial)
            }
        )
        _ui.value = _ui.value.copy(streamingResponse = "")
        addMessage("model1", opening, InventPhase.QUESTIONING)
    }

    fun toggleReasoning() {
        val enabled = !_ui.value.reasoningEnabled
        _ui.value = _ui.value.copy(reasoningEnabled = enabled)
    }

    fun sendUserMessage(text: String) {
        if (_ui.value.isGenerating) return

        // Parse attachments for tech stack hints
        var effectiveText = text
        if (text.startsWith("[Attached:")) {
            val hints = extractAttachedFileHints(text)
            if (hints.isNotEmpty()) effectiveText = "$text\n\n[From attached files, I noticed: $hints]"
        }
        addMessage("user", effectiveText, _ui.value.phase)
        viewModelScope.launch(Dispatchers.IO) {
            when (_ui.value.phase) {
                InventPhase.QUESTIONING -> handleQuestioningReply(effectiveText)
                InventPhase.DEBUGGING -> handleDebuggingReply(effectiveText)
                else -> {}
            }
        }
    }

    /** Extract tech-stack hints from attached file content to pre-fill ZCP. */
    private fun extractAttachedFileHints(text: String): String {
        val hints = mutableListOf<String>()
        if (text.contains("package ", ignoreCase = true)) hints.add("lang:Kotlin/Java")
        if (text.contains("import android.", ignoreCase = true)) hints.add("platform:Android")
        if (text.contains("import kotlinx.", ignoreCase = true)) hints.add("framework:Kotlin Coroutines")
        if (text.contains("import androidx.", ignoreCase = true)) hints.add("framework:Jetpack")
        if (text.contains("fun ", ignoreCase = true) && text.contains("main(", ignoreCase = true)) hints.add("lang:Kotlin")
        if (text.contains("def ", ignoreCase = true) || text.contains("requirements.txt", ignoreCase = true)) hints.add("lang:Python")
        if (text.contains("node_modules", ignoreCase = true) || text.contains("package.json", ignoreCase = true)) hints.add("lang:JavaScript")
        if (text.contains("Cargo.toml", ignoreCase = true) || text.contains("fn ", ignoreCase = true)) hints.add("lang:Rust")
        if (text.contains("flutter", ignoreCase = true) || text.contains("dart", ignoreCase = true)) hints.add("framework:Flutter")
        return hints.joinToString(", ")
    }

    private suspend fun handleQuestioningReply(userText: String) {
        // Make sure the engine is still loaded and ready
        val state = sessionState ?: return
        val active = engineManager.getActiveEngine()
        if (active == null || !active.isModelLoaded) {
            addMessage("model1", "Reloading planner…", InventPhase.QUESTIONING)
            if (!loadOrKeepModel(state.model1Path)) {
                addMessage("model1", "Failed to reload planner model after unload.", InventPhase.QUESTIONING)
                return
            }
        }

        // Update questioning progress based on conversation depth
        // The more the user tells us, the closer we get to 100%
        val currentMsgs = _ui.value.messages.count { it.role == "user" || it.role == "model1" }
        // Progress: 0 at 0 turns, ~85% at 12 turns, 100% when user hits Done
        val newProgress = (1f - kotlin.math.exp(-currentMsgs.toFloat() / 5f)).coerceIn(0f, 0.95f)
        _ui.value = _ui.value.copy(questioningProgress = newProgress)
        // Full prompt with entire conversation — no cache dependency
        val response = runInference(
            systemPrompt = buildQuestioningPrompt(),
            userMessage = userText,
            history = buildConversationHistory(excludeLast = 1),
            onStream = { partial ->
                _ui.value = _ui.value.copy(streamingResponse = partial)
            }
        )
        _ui.value = _ui.value.copy(streamingResponse = "")
        val trimmed = response.trim()
        if (trimmed.isNotEmpty()) {
            // Extract thinking content and strip <think> tags
            val thinkMatch = Regex("<think>([\\s\\S]*?)<\\/think>").find(trimmed)
            val cleanContent = when {
                thinkMatch != null -> {
                    val thinking = thinkMatch.groupValues[1].trim()
                    _ui.value = _ui.value.copy(thinkingContent = thinking)
                    trimmed.replace(Regex("<think>[\\s\\S]*?<\\/think>"), "").trim()
                }
                else -> trimmed
            }
            if (thinkMatch != null && cleanContent.isEmpty()) {
                // Model only output <think> tags — show thinking content as message
                addMessage("model1", "[Thinking... ${_ui.value.thinkingContent.take(200)}]", InventPhase.QUESTIONING, _ui.value.thinkingContent)
            } else {
                val think = _ui.value.thinkingContent
                addMessage("model1", cleanContent.ifEmpty { trimmed }, InventPhase.QUESTIONING, think)
            }
        } else {
            addMessage("model1", "I see! What else can you tell me about this project?", InventPhase.QUESTIONING)
        }
    }

    // ── Done button: full pipeline ──────────────────────────────────────────
    //  1. Planner creates project summary + research prompt
    //  2. Researcher loads, searches web, saves to search.txt
    //  3. Planner reloads with search results, creates folder tree + .txt
    //     placeholder files + CODER_INSTRUCTIONS.txt
    //  4. Done

    fun onDonePressed() {
        if (_ui.value.isGenerating) return
        _cancelGeneration = false
        _ui.value = _ui.value.copy(isGenerating = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = sessionState ?: return@launch
                setSwap("Planner summarizing project…")
                addMessage("system", "▶ Pipeline started: planner → research → structure → ready", InventPhase.PLANNING)

                // ── Step 1: Comprehensive project summary ──
                val summary = runInference(
                    systemPrompt = "You are a senior project architect. Summarize everything known about this project from the Q&A.\n\nOutput exactly:\n§PROJECT{name:X|desc:X|platform:Y|lang:Z|framework:W}\n§FEATURES{list:f1,f2,f3}\n§ARCH{style:X|pattern:Y|notes:Z}\n§VERSIONS{known:p3.10,k1.9,f3.16}\n§SEARCH_NEEDS{list:topic1,topic2}",
                    userMessage = "Write the complete project summary using § blocks. Cover all requirements, tech stack, architecture, and what needs online research.",
                    history = buildConversationHistory(),
                    onStream = { partial -> _ui.value = _ui.value.copy(streamingResponse = partial) }
                )
                _ui.value = _ui.value.copy(streamingResponse = "")
                addMessage("system", "✓ Project summary ready", InventPhase.PLANNING)

                // ── Step 2: Research prompt ──
                setSwap("Creating research prompt…")
                val researchPrompt = runInference(
                    systemPrompt = "List exactly which official URLs to fetch changelogs from. " +
                        "Examples: https://docs.python.org/3/whatsnew/changelog.html, " +
                        "https://flutter.dev/docs/release/notes, " +
                        "https://kotlinlang.org/docs/releases.html, " +
                        "https://github.com/flutter/flutter/releases, " +
                        "https://gradle.org/releases/. " +
                        "Only official URLs — no blog posts, no tutorials.",
                    userMessage = "Project:\n$summary\n\nList official changelog/release URLs to fetch.",
                    expectedModelPath = state.model1Path
                )
                addMessage("system", "✓ Research URLs ready", InventPhase.PLANNING)

                // ── Step 3: Research phase — fetch official changelogs ──
                updatePhase(InventPhase.SEARCHING)
                setSwap("Loading ${state.researcherName}…")
                if (!loadOrKeepModel(state.researcherPath)) {
                    setSwap(""); _ui.value = _ui.value.copy(error = "Researcher load failed"); return@launch
                }
                setSwap("Fetching official changelogs…")
                val fetched = fetchOfficialChangelogs(researchPrompt)

                setSwap("Extracting results…")
                val searchResults = runInference(
                    systemPrompt = "Extract ONLY latest version numbers, release dates, and key API changes. Output KEY:VALUE pairs.",
                    userMessage = "Research prompt:\n$researchPrompt\n\nSearch results:\n$fetched\n\nExtract latest versions and changes."
                )

                // Save search.txt
                val projName = zcp.projectName.ifEmpty { "project_$sessionId" }
                val projDir = InventStorage.getProjectDir(ctx, sessionId, projName)
                projDir.mkdirs()
                File(projDir, "search.txt").writeText(
                    "SEARCH RESULTS\n============\n\nPrompt:\n$researchPrompt\n\nResults:\n$searchResults"
                )
                addMessage("system", "✓ search.txt saved", InventPhase.SEARCHING)

                // ── Step 4: Reload planner ──
                setSwap("Loading ${state.model1Name} for architecture…")
                if (!loadOrKeepModel(state.model1Path)) {
                    setSwap(""); _ui.value = _ui.value.copy(error = "Planner reload failed"); return@launch
                }
                setSwap("Building project structure…")
                updatePhase(InventPhase.PLANNING)

                val structPrompt = buildString {
                    appendLine("You are a project architect. You have ALL info.")
                    appendLine()
                    appendLine("=== PROJECT ===")
                    appendLine(summary)
                    appendLine()
                    appendLine("=== RESEARCH ===")
                    appendLine(searchResults)
                    appendLine()
                    appendLine("=== TASK ===")
                    appendLine("1. Output §PROJECT{name:X}")
                    appendLine("2. Output §TREE blocks for every file")
                    appendLine("3. For EACH file, output §PROMPT{path:X|desc:X|imports:X|classes:X|functions:X}")
                    appendLine("4. Coder context limit: ${zcp.model2ContextSize} tokens. Max new tokens: 4096.")
                    appendLine("5. If a file's code would exceed ${zcp.model2ContextSize} tokens, " +
                        "SPLIT it into multiple smaller files that together produce the same result.")
                    appendLine("6. Each file must be completable in ONE prompt (≤4096 output tokens).")
                    appendLine("7. Name split files like: databaselayer.dart, databaselayer_queries.dart, databaselayer_migrations.dart")
                    appendLine()
                    appendLine("§TREE{path:src/main.dart|type:file|desc:Main entry}")
                    appendLine("§PROMPT{path:src/main.dart|desc:Entry point|imports:flutter/material|classes:MyApp|functions:main,build}")
                    appendLine()
                    appendLine("Output ONLY blocks — no prose.")
                }
                var structure = runInference(
                    systemPrompt = "You output machine-parseable project blocks only.",
                    userMessage = structPrompt
                )
                zcp = parseZcpFromModel1(structure, zcp)
                var tree = parseFileTree(structure)
                if (tree.isEmpty()) {
                    // Small models frequently drop the §TREE blocks on the first pass —
                    // retry once with explicit feedback before giving up.
                    addMessage("system", "⚠ No §TREE blocks parsed — retrying structure output once…", InventPhase.PLANNING)
                    val retryPrompt = "Your previous output contained NO valid §TREE{path:...|type:file|desc:...} blocks. " +
                        "You MUST output a §TREE block for every file, plus §PROMPT blocks. " +
                        "Output ONLY machine-parseable blocks, no prose.\n\n$structPrompt"
                    val retry = runInference(
                        systemPrompt = "You output machine-parseable project blocks only.",
                        userMessage = retryPrompt
                    )
                    val retryTree = parseFileTree(retry)
                    if (retryTree.isNotEmpty()) {
                        structure = retry
                        tree = retryTree
                        zcp = parseZcpFromModel1(retry, zcp)
                        addMessage("system", "✓ Structure parsed on retry (${tree.count { !it.isDir }} files)", InventPhase.PLANNING)
                    } else {
                        addMessage("system", "⚠ Still no §TREE blocks — continuing with summary only (0 files)", InventPhase.PLANNING)
                    }
                }
                zcp = zcp.copy(fileTree = tree, phase = InventPhase.PLANNING)

                // ── Step 5: Create .txt placeholder files ──
                val specs = parsePlannerPrompts(structure)
                var created = 0
                for ((path, spec) in specs) {
                    val txtFile = File(projDir, "$path.txt")
                    txtFile.parentFile?.mkdirs()
                    txtFile.writeText(buildString {
                        appendLine("PROMPT FOR THIS FILE")
                        appendLine("====================")
                        appendLine()
                        appendLine("Replace this .txt with the real source file.")
                        appendLine()
                        appendLine("File: $path")
                        if (spec.description.isNotEmpty()) appendLine("Description: ${spec.description}")
                        if (spec.imports.isNotEmpty()) appendLine("Imports: ${spec.imports}")
                        if (spec.classes.isNotEmpty()) appendLine("Classes: ${spec.classes}")
                        if (spec.functions.isNotEmpty()) appendLine("Functions: ${spec.functions}")
                        if (spec.dependencies.isNotEmpty()) appendLine("Dependencies: ${spec.dependencies.joinToString(", ")}")
                    })
                    created++
                }

                // ── Step 6: CODER_INSTRUCTIONS.txt ──
                File(projDir, "CODER_INSTRUCTIONS.txt").writeText(buildString {
                    appendLine("CODER INSTRUCTIONS")
                    appendLine("==================")
                    appendLine()
                    appendLine("Project root: ${projDir.absolutePath}")
                    appendLine()
                    appendLine("Folder tree:")
                    tree.filter { !it.isDir }.forEach { appendLine("  ${it.path}") }
                    appendLine()
                    appendLine("Instructions:")
                    appendLine("1. Read each *.txt file in the project — it describes one source file.")
                    appendLine("2. Create the real source file (e.g. main.dart) based on the .txt prompt.")
                    appendLine("3. Delete the .txt file after creating the real file.")
                    appendLine("4. Use version info from search.txt for latest APIs.")
                    appendLine("5. Files: ${tree.count { !it.isDir }}")
                    appendLine()
                    appendLine("${summary.take(500)}")
                })

                // Save summary1 and research1 files
                val projName2 = zcp.projectName.ifEmpty { "project_$sessionId" }
                val projDir2 = InventStorage.getProjectDir(ctx, sessionId, projName2)
                File(projDir2, "summary1.txt").writeText(summary)
                File(projDir2, "research1.txt").writeText(searchResults)
                addMessage("system", "✓ summary1.txt + research1.txt saved", InventPhase.PLANNING)

                // Save the file specs so startFileGeneration can use them
                zcp = zcp.copy(
                    fileSpecs = parsePlannerPrompts(structure),
                    fileTree = tree,
                    phase = InventPhase.PLANNING
                )
                addMessage("system", "▶ Project structure ready. Starting code generation with coder model…", InventPhase.PLANNING)
                // Generate actual source code files using the coder model
                startFileGeneration()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(isGenerating = false, error = "Pipeline: ${e.message}")
                recordTelemetry(JSONObject().apply { put("outcome", "error"); put("error", e.message) })
            } finally {
                _ui.value = _ui.value.copy(isGenerating = false)
            }
        }
    }

    /** Shared file generation loop — used by both startFileGeneration and resumeGeneration. */
    /** Order files topologically so dependencies are generated before their dependents.
     *  Deterministic — stable across save/resume. Cycles are tolerated (visited guard). */
    private fun topoSortFiles(files: List<FileNode>): List<FileNode> {
        val pathSet = files.map { it.path }.toSet()
        val result = mutableListOf<FileNode>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun visit(node: FileNode) {
            if (node.path in visited) return
            if (node.path in visiting) return // cycle — keep first occurrence
            visiting.add(node.path)
            val deps = zcp.fileSpecs[node.path]?.dependencies?.filter { it in pathSet } ?: emptyList()
            for (dep in deps) {
                val depNode = files.firstOrNull { it.path == dep }
                if (depNode != null) visit(depNode)
            }
            visiting.remove(node.path)
            visited.add(node.path)
            result.add(node)
        }

        files.forEach { visit(it) }
        return result
    }

    private suspend fun generateFiles(startFrom: Int = 0, skipExisting: Boolean = false) {
        val state = sessionState ?: return
        lastGenFailures = mutableListOf()
        lastSanityWarnings = mutableMapOf()
        val filesToGenerate = topoSortFiles(zcp.fileTree.filter { !it.isDir })
        if (filesToGenerate.isEmpty()) { finishGeneration(); return }

        val startIdx = startFrom.coerceAtLeast(0)
        withTransaction(
            uiTransform = { it.copy(totalFiles = filesToGenerate.size, currentFileIndex = startIdx) },
            sessionTransform = { it?.copy(totalFiles = filesToGenerate.size, currentFileIndex = startIdx) }
        )

        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name

        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
        zcp.fileTree.filter { it.isDir }.forEach { File(projectDir, it.path).mkdirs() }

        setSwap("Loading $targetName…")
        if (!loadOrKeepModel(targetPath)) {
            setSwap(""); _ui.value = _ui.value.copy(isGenerating = false, error = "Failed to load $targetName")
            recordTelemetry(JSONObject().apply { put("outcome", "error"); put("error", "Failed to load $targetName") })
            return
        }
        enableSearchOnEngine()
        setSwap("")

        for (idx in startIdx until filesToGenerate.size) {
            val fileNode = filesToGenerate[idx]
            if (_ui.value.phase != InventPhase.GENERATING) break
            if (_cancelGeneration) {
                _cancelGeneration = false
                addMessage("system", "⏸ Generation cancelled by user", InventPhase.QUESTIONING)
                updatePhase(InventPhase.QUESTIONING)
                return
            }
            if (skipExisting && zcp.generatedFiles.contains(fileNode.path)) continue

            val fileSpec = zcp.fileSpecs[fileNode.path] ?: FileSpec(path = fileNode.path, description = fileNode.description)

            withTransaction(
                uiTransform = { it.copy(currentFileIndex = idx + 1, currentFileName = fileNode.path) },
                sessionTransform = { it?.copy(currentFileIndex = idx + 1) }
            )

            val preSearchResults = checkAndRunPreSearch(fileSpec)
            var code = generateCodeWithPreSearch(fileSpec, projectDir, preSearchResults)
            if (code == null) {
                // Retry once — a single glitch shouldn't abort the whole run.
                setSwap("Retrying ${fileNode.path}…")
                code = generateCodeWithPreSearch(fileSpec, projectDir, preSearchResults, retry = true)
                setSwap("")
            }
            if (code == null) {
                // Skip-and-continue: record the failure and keep generating the rest.
                lastGenFailures.add(fileNode.path)
                addMessage("system", "⚠ Skipped ${fileNode.path} — generation failed after 2 attempts", InventPhase.GENERATING)
                continue
            }

            InventStorage.writeGeneratedFile(projectDir, fileNode.path, code)
            withTransaction(zcpTransform = { it.copy(generatedFiles = it.generatedFiles + fileNode.path) })
            sanityCheckFile(fileNode.path, code)?.let { lastSanityWarnings[fileNode.path] = it }
            addMessage("system", "✓ Generated ${fileNode.path} (${code.count { it == '\n' } + 1} lines)", InventPhase.GENERATING)
        }

        clearToolManagerOnEngines()
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        finishGeneration()
    }

    private suspend fun startFileGeneration() {
        updatePhase(InventPhase.GENERATING)
        generateFiles(startFrom = 0, skipExisting = false)
    }

    /** Before running code generation, check if search is likely needed and fetch results. */
    private suspend fun checkAndRunPreSearch(spec: FileSpec): String? {
        val searchTriggers = listOf("api", "sdk", "library", "dependency", "import",
            "version", "setup", "install", "configure", "example", "tutorial")
        val desc = spec.description.lowercase()
        val imports = spec.imports.lowercase()
        val classes = spec.classes.lowercase()
        val functions = spec.functions.lowercase()
        val combined = "$desc $imports $classes $functions"

        val needsSearch = searchTriggers.any { combined.contains(it) }
        if (!needsSearch) return null

        val query = "How to implement ${spec.path} ${spec.description} ${zcp.language.joinToString(" ")} ${zcp.framework}"
        val args = JSONObject().apply { put("query", query.take(150)); put("num_results", 3) }
        val call = ToolCall("presearch_${System.currentTimeMillis()}", "web_search", args)
        return try {
            val res = toolManager.executeTool(call)
            val t = res.result.trim()
            if (t.isNotBlank() && !t.startsWith("Error", true) && !t.startsWith("No results", true) && !t.startsWith("Web search failed", true)) t
            else null
        } catch (_: Exception) { null }
    }

    /** Generate code with pre-fetched search results plus dependency code context. */
    private suspend fun generateCodeWithPreSearch(
        spec: FileSpec, projectDir: File, preSearchResults: String?, retry: Boolean = false
    ): String? {
        val codeGenPrompt = buildCodeGenPrompt(spec, zcp, projectDir)
        val fullPrompt = buildString {
            if (preSearchResults != null) append("Web research for this file:\n$preSearchResults\n\n---\n\n")
            append(codeGenPrompt)
            if (retry) append("\n\nYour previous response was empty or invalid. Output ONLY the code for this file. No explanations, no markdown fences.")
        }

        return runInference(
            systemPrompt = "You are a senior software engineer. Output ONLY the code for this file. No explanations, no markdown.",
            userMessage = fullPrompt
        ).let { result ->
            val cleaned = result.replace(Regex("""\[SEARCH:\s*(.+?)]""", RegexOption.IGNORE_CASE), "").trim()
            cleaned.ifEmpty { null }
        }
    }

    /** Lightweight post-generation sanity checks — heuristic, informational only.
     *  Returns a short warning string, or null when the file looks plausible. */
    private fun sanityCheckFile(path: String, code: String): String? {
        if (code.isBlank()) return "empty file"
        val issues = mutableListOf<String>()

        // 1. Delimiter balance, skipping strings and comments
        val pairs = mapOf('{' to '}', '(' to ')', '[' to ']')
        val count = mutableMapOf<Char, Int>()
        var inString = false; var stringQuote = ' '; var escaped = false
        var inLineComment = false; var inBlockComment = false
        var i = 0
        val n = code.length
        while (i < n) {
            val ch = code[i]
            when {
                inLineComment -> { if (ch == '\n') inLineComment = false }
                inBlockComment -> { if (ch == '*' && i + 1 < n && code[i + 1] == '/') { inBlockComment = false; i++ } }
                inString -> {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == stringQuote) inString = false
                }
                ch == '"' || ch == '\'' || ch == '`' -> { inString = true; stringQuote = ch }
                ch == '/' && i + 1 < n && code[i + 1] == '/' -> { inLineComment = true; i++ }
                ch == '/' && i + 1 < n && code[i + 1] == '*' -> { inBlockComment = true; i++ }
                pairs.containsKey(ch) -> count[ch] = (count[ch] ?: 0) + 1
                pairs.containsValue(ch) -> {
                    val opener = pairs.entries.firstOrNull { it.value == ch }?.key
                    if (opener != null) count[opener] = (count[opener] ?: 0) - 1
                }
            }
            i++
        }
        count.forEach { (opener, c) -> if (c != 0) issues.add("unbalanced '$opener' (delta $c)") }

        // 2. Relative imports pointing at files that were never generated
        val generatedBases = (zcp.generatedFiles + zcp.fileTree.filter { !it.isDir }.map { it.path })
            .map { it.substringAfterLast('/').lowercase() }.toSet()
        val relativeImport = Regex("""(?:from|import|require)\s*\(?\s*['"](\.\.?/[^'"]+)['"]""")
        val missing = relativeImport.findAll(code).mapNotNull { m ->
            val imp = m.groupValues[1]
            val base = imp.substringAfterLast('/')
                .removeSuffix(".kt").removeSuffix(".java").removeSuffix(".py")
                .removeSuffix(".dart").removeSuffix(".ts").removeSuffix(".js")
                .removeSuffix(".tsx").removeSuffix(".jsx").removeSuffix(".go")
                .lowercase()
            if (base.isNotEmpty() && !generatedBases.any { it.startsWith(base) || base.startsWith(it) }) base else null
        }.distinct().toList()
        if (missing.isNotEmpty()) issues.add("imports never generated: ${missing.take(3).joinToString(", ")}")

        // 3. Truncation heuristic — ending on an operator suggests the model was cut off
        val lastChar = code.trimEnd().lastOrNull()
        if (lastChar != null && (lastChar == ',' || lastChar == '&' || lastChar == '|' || lastChar == '=' || lastChar == '+' || lastChar == '-' || lastChar == ':')) {
            issues.add("may be truncated (ends with '$lastChar')")
        }

        return if (issues.isEmpty()) null else issues.joinToString("; ")
    }

    private suspend fun resumeGeneration() {
        updatePhase(InventPhase.GENERATING)
        val startFrom = _ui.value.currentFileIndex.coerceAtLeast(0)
        generateFiles(startFrom = startFrom, skipExisting = true)
    }

    private suspend fun finishGeneration() {
        runFinalizeStep()
        zcp = zcp.copy(phase = InventPhase.DONE)
        updatePhase(InventPhase.DONE)
        computeStats()
        _ui.value = _ui.value.copy(zipReady = true)
        val failed = lastGenFailures
        val warnings = lastSanityWarnings
        val planned = zcp.fileTree.count { !it.isDir }
        if (failed.isNotEmpty()) {
            addMessage("system", "⚠ ${failed.size} file(s) skipped after 2 attempts: ${failed.take(5).joinToString(", ")}${if (failed.size > 5) ", …" else ""}", InventPhase.DONE)
        }
        if (warnings.isNotEmpty()) {
            val names = warnings.keys.take(5).joinToString(", ")
            addMessage("system", "⚠ ${warnings.size} file(s) may not compile — ${names}${if (warnings.size > 5) ", …" else ""}. Review before export.", InventPhase.DONE)
        }
        addMessage("system", "✓ ${zcp.generatedFiles.size}/$planned files generated. Ready to export!", InventPhase.DONE)
        recordTelemetry(JSONObject().apply {
            put("outcome", "done")
            put("filesFailed", failed.size)
            put("sanityWarnings", warnings.size)
        })
    }

    /** Generate README from file content on disk — no model context juggling. */
    private suspend fun runFinalizeStep() {
        val state = sessionState ?: return
        if (zcp.generatedFiles.isEmpty()) return
        updatePhase(InventPhase.FINALIZING)
        _ui.value = _ui.value.copy(currentModelLabel = state.model1Name, processLabel = "Generating README…")

        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
        val filePaths = zcp.generatedFiles.sorted()

        // Read file summaries directly from disk — no model inference needed
        val summaries = filePaths.mapNotNull { path ->
            val content = InventStorage.readGeneratedFile(projectDir, path)
            if (content != null) {
                val lines = content.count { it == '\n' } + 1
                val firstLine = content.lines().firstOrNull()?.take(80) ?: ""
                "$path ($lines lines) — $firstLine"
            } else null
        }

        // Get build instructions from web search
        _ui.value = _ui.value.copy(processLabel = "Researching build instructions…")
        val platform = zcp.platform.joinToString(", ").ifEmpty { "general" }
        val language = zcp.language.joinToString(", ").ifEmpty { "unknown" }
        val framework = zcp.framework.ifEmpty { "none" }
        val searchQuery = "How to build and compile $language $framework project for $platform"
        val buildInstructions = runWebSearch(searchQuery) ?: "No web search results available."

        // Write README from summaries + search, not from model's failing memory
        _ui.value = _ui.value.copy(processLabel = "Writing README…")
        val readmeContent = buildString {
            appendLine("========================================")
            appendLine("${zcp.projectName} — Build Instructions")
            appendLine("========================================")
            appendLine()
            appendLine("Generated by ZeroCopy Invent")
            appendLine("Project: ${zcp.projectName}")
            appendLine("Platform: $platform")
            appendLine("Language: $language")
            appendLine("Framework: $framework")
            appendLine()
            appendLine("--- Project Overview ---")
            appendLine(zcp.coreIdea)
            appendLine()
            appendLine("--- Features ---")
            zcp.mainFeatures.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("--- Files Generated (${filePaths.size} total) ---")
            summaries.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("--- How to Build ---")
            appendLine(buildInstructions.take(3000))
            appendLine()
            appendLine("--- Prerequisites ---")
            appendPrerequisites(language, framework, platform)
            appendLine()
            appendLine("--- Quick Start ---")
            appendLine("1. Install prerequisites")
            appendLine("2. Open a terminal in this directory")
            appendLine("3. Follow build instructions for your platform")
            appendLine("4. Refer to file summaries for architecture details")
            appendLine()
            appendLine("Generated by ZeroCopy Invent — github.com/adeennour4-dot/111")
        }

        InventStorage.writeGeneratedFile(projectDir, "README.txt", readmeContent)
        if (!zcp.generatedFiles.contains("README.txt")) {
            zcp = zcp.copy(generatedFiles = zcp.generatedFiles + "README.txt")
        }
        addMessage("system", "✅ README.txt written with build instructions", InventPhase.FINALIZING)
        _ui.value = _ui.value.copy(currentModelLabel = "", processLabel = "")
    }

    private fun StringBuilder.appendPrerequisites(language: String, framework: String, platform: String) {
        if (language.contains("kotlin", true) || language.contains("java", true) || platform.contains("android", true)) {
            appendLine("  - Android Studio (developer.android.com/studio)")
            appendLine("  - Android SDK (bundled with Android Studio)")
            appendLine("  - JDK 17+")
        }
        if (language.contains("python", true)) {
            appendLine("  - Python 3.10+ (python.org)")
            appendLine("  - pip install -r requirements.txt")
        }
        if (language.contains("javascript", true) || language.contains("typescript", true)) {
            appendLine("  - Node.js 18+ (nodejs.org)")
            appendLine("  - npm install && npm run build")
        }
        if (framework.contains("flutter", true)) {
            appendLine("  - Flutter SDK (flutter.dev)")
            appendLine("  - Dart SDK (bundled with Flutter)")
        }
        if (framework.contains("react", true)) {
            appendLine("  - Node.js 18+")
            appendLine("  - npm install && npm start")
        }
        if (language.contains("rust", true)) {
            appendLine("  - Rust toolchain (rustup.rs)")
            appendLine("  - cargo build")
        }
    }

    // ── Export .ZIP ────────────────────────────────────────────────────────

    fun exportProjectZip(): File? {
        if (_ui.value.phase != InventPhase.DONE && _ui.value.phase != InventPhase.DEBUGGING) return null
        return try {
            val projectName = zcp.projectName.ifEmpty { sessionId }.ifEmpty { "invent_project" }
            val zipDir = File(ctx.cacheDir, "invent_exports").also { it.mkdirs() }
            val zipFile = File(zipDir, "${projectName}.zip")
            val projectDir = getProjectDir()

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // README.md (canonical export — not duplicated with README.txt)
                val readme = buildString {
                    appendLine("# ${zcp.projectName}")
                    appendLine()
                    appendLine("Generated by ZeroCopy Invent")
                    appendLine()
                    if (zcp.coreIdea.isNotEmpty()) { appendLine("## Core Idea"); appendLine(zcp.coreIdea); appendLine() }
                    if (zcp.mainFeatures.isNotEmpty()) { appendLine("## Features"); zcp.mainFeatures.forEach { appendLine("- $it") }; appendLine() }
                    if (zcp.platform.isNotEmpty()) appendLine("## Platform: ${zcp.platform.joinToString(", ")}")
                    if (zcp.language.isNotEmpty()) appendLine("## Language: ${zcp.language.joinToString(", ")}")
                    if (zcp.framework.isNotEmpty()) appendLine("## Framework: ${zcp.framework}")
                    appendLine()
                    appendLine("## Stats")
                    appendLine("- Files: ${zcp.generatedFiles.size}")
                    var lines = 0L
                    for (p in zcp.generatedFiles) {
                        val c = InventStorage.readGeneratedFile(projectDir, p)
                        if (c != null) lines += c.count { ch -> ch == '\n' } + 1
                    }
                    appendLine("- Lines of code: $lines")
                    if (zcp.debugSessions.isNotEmpty()) appendLine("- Debug sessions: ${zcp.debugSessions.size}")
                    appendLine()
                    appendLine("## Project Structure")
                    zcp.fileTree.forEach { node ->
                        appendLine("  ${if (node.isDir) "[DIR]" else "[FILE]"} ${node.path}")
                        if (node.description.isNotEmpty()) appendLine("       // ${node.description}")
                    }
                }
                zos.putNextEntry(ZipEntry("README.md"))
                zos.write(readme.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val zcpJson = buildZcpExportJson()
                zos.putNextEntry(ZipEntry("zcp_protocol.json"))
                zos.write(zcpJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // All generated files (skip README.txt duplicated by finalize)
                for (path in zcp.generatedFiles) {
                    if (path == "README.txt") continue
                    val code = InventStorage.readGeneratedFile(projectDir, path) ?: continue
                    zos.putNextEntry(ZipEntry(path))
                    zos.write(code.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }

            // Share intent
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zipFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(shareIntent, "Share ${projectName}.zip"))
            zipFile
        } catch (e: Exception) {
            android.util.Log.e("InventViewModel", "Failed to create zip: ${e.message}")
            null
        }
    }

    private fun buildZcpExportJson(): String {
        return JSONObject().apply {
            put("projectName", zcp.projectName)
            put("platform", org.json.JSONArray(zcp.platform))
            put("language", org.json.JSONArray(zcp.language))
            put("framework", zcp.framework)
            put("coreIdea", zcp.coreIdea)
            put("mainFeatures", org.json.JSONArray(zcp.mainFeatures))
            put("uniquePoint", zcp.uniquePoint)
            put("viable", zcp.viable)
            val treeArr = org.json.JSONArray()
            zcp.fileTree.forEach { fn ->
                treeArr.put(JSONObject().apply {
                    put("path", fn.path)
                    put("type", if (fn.isDir) "dir" else "file")
                    put("description", fn.description)
                })
            }
            put("fileTree", treeArr)
            put("generatedFiles", org.json.JSONArray(zcp.generatedFiles))
            put("totalFiles", zcp.generatedFiles.size)
            if (zcp.debugSessions.isNotEmpty()) {
                val debugArr = org.json.JSONArray()
                zcp.debugSessions.forEach { ds ->
                    debugArr.put(JSONObject().apply {
                        put("filePath", ds.filePath)
                        put("problem", ds.problem)
                        put("timestamp", ds.timestamp)
                    })
                }
                put("debugSessions", debugArr)
            }
        }.toString(2)
    }

    // ── Debugging ──────────────────────────────────────────────────────────

    fun startDebugging() {
        viewModelScope.launch(Dispatchers.IO) {
            updatePhase(InventPhase.DEBUGGING)
            _ui.value = _ui.value.copy(debugMode = true)
            val state = sessionState ?: return@launch
            setSwap("Loading ${state.model1Name}…")
            if (!loadOrKeepModel(state.model1Path)) {
                setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return@launch
            }
            setSwap("")
            addMessage("model1", "I'm ready for debugging. Tell me which file has an issue and describe the problem.", InventPhase.DEBUGGING)
        }
    }

    fun exitDebugging() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(debugMode = false, zipReady = true, projectCompleted = true)
            zcp = zcp.copy(phase = InventPhase.DONE)
            updatePhase(InventPhase.DONE)
            // Generate EXPLANATION.md — explains everything in the code
            try {
                val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
                val allCode = StringBuilder()
                zcp.generatedFiles.forEach { path ->
                    val code = InventStorage.readGeneratedFile(projectDir, path)
                    if (code != null) {
                        allCode.appendLine("=== $path ===")
                        allCode.appendLine(code.take(2000))
                        allCode.appendLine()
                    }
                }
                if (allCode.isNotEmpty()) {
                    val explanation = runInference(
                        systemPrompt = "You are a technical writer. Write a clear EXPLANATION.md " +
                            "that explains every part of the project code: architecture, " +
                            "each file's purpose, key classes/functions, and how they work together.",
                        userMessage = "Write EXPLANATION.md for this project:\n\n${allCode.take(8000)}",
                        expectedModelPath = sessionState?.model1Path ?: ""
                    )
                    File(projectDir, "EXPLANATION.md").writeText(explanation)
                    addMessage("system", "✓ EXPLANATION.md generated", InventPhase.DONE)
                }
            } catch (_: Exception) { }
            computeStats()
            addMessage("system", "✓ Debugging complete. Export .zip to get the fixed files.", InventPhase.DONE)
        }
    }

    private suspend fun handleDebuggingReply(userText: String) {
        val state = sessionState ?: return
        // Exclude last entry — it's the user message we just added via addMessage()
        val history = buildConversationHistory(excludeLast = 1)

        // Provide project context for debugging
        val projectOverview = buildString {
            appendLine("Project: ${zcp.projectName}")
            appendLine("Total files: ${zcp.generatedFiles.size}")
            appendLine("File tree:")
            zcp.fileTree.filter { !it.isDir }.forEach { appendLine("  - ${it.path}: ${it.description}") }
            appendLine()
            appendLine("Debug sessions so far: ${zcp.debugSessions.size}")
        }

        val diagnosis = runInference(
            systemPrompt = "You are a debugging assistant. Identify which file has the bug and what needs to change. Use §FILE{path:X} to specify the file. If the user says 'done' or 'exit', output [DEBUG_DONE].",
            userMessage = "Project context:\n$projectOverview\n\nUser bug report: $userText",
            history = history
        )

        if (diagnosis.contains("[DEBUG_DONE]", ignoreCase = true)) { exitDebugging(); return }

        addMessage("model1", diagnosis, InventPhase.DEBUGGING)

        val filePath = Regex("§FILE\\{path:([^}]+)\\}").find(diagnosis)?.groupValues?.get(1)?.trim()
        if (filePath == null || !zcp.generatedFiles.contains(filePath)) {
            addMessage("system", "Could not identify the file. Use the exact path from the file tree.", InventPhase.DEBUGGING)
            return
        }

        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
        val originalCode = InventStorage.readGeneratedFile(projectDir, filePath) ?: ""
        val targetPath = if (state.sameModelMode || state.model1Path == state.model2Path) state.model1Path else state.model2Path
        val targetName = if (state.sameModelMode || state.model1Path == state.model2Path) state.model1Name else state.model2Name

        setSwap("Loading $targetName to fix $filePath…")
        if (!loadOrKeepModel(targetPath)) {
            setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return
        }
        enableSearchOnEngine()

        // Include related file context for the fix
        val relatedContext = buildString {
            val deps = zcp.fileSpecs[filePath]?.dependencies ?: emptyList()
            deps.take(3).forEach { depPath ->
                val depCode = InventStorage.readGeneratedFile(projectDir, depPath)
                if (depCode != null) {
                    appendLine("--- Dependency: $depPath (first 30 lines) ---")
                    appendLine(depCode.lines().take(30).joinToString("\n"))
                    appendLine()
                }
            }
        }

        val fixPrompt = buildString {
            appendLine("Fix this bug.")
            appendLine("Problem: $userText")
            appendLine()
            appendLine("Project context: ${zcp.projectName} (${zcp.language.joinToString(", ")} / ${zcp.framework})")
            if (relatedContext.isNotBlank()) { appendLine(); appendLine(relatedContext) }
            appendLine()
            appendLine("Buggy file — $filePath:")
            appendLine("```")
            appendLine(originalCode)
            appendLine("```")
            appendLine()
            appendLine("Output ONLY the corrected code. No explanations.")
        }

        val fixedCode = runInference(
            systemPrompt = "You are a senior software engineer fixing a bug. Output ONLY the corrected code.",
            userMessage = fixPrompt, expectedModelPath = targetPath
        ).trim()

        // Remove any remaining search markers
        val cleanCode = fixedCode.replace(Regex("""\[SEARCH:\s*(.+?)]""", RegexOption.IGNORE_CASE), "").trim()

        InventStorage.writeGeneratedFile(projectDir, filePath, cleanCode)
        if (!zcp.generatedFiles.contains(filePath)) zcp = zcp.copy(generatedFiles = zcp.generatedFiles + filePath)
        withTransaction(zcpTransform = {
            it.copy(debugSessions = it.debugSessions + DebugSession(
                filePath = filePath, problem = userText, originalCode = originalCode, fixedCode = cleanCode
            ))
        })

        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        clearToolManagerOnEngines()
        computeStats()

        setSwap("Loading ${state.model1Name}…")
        loadOrKeepModel(state.model1Path)
        setSwap("")

        addMessage("system", "✓ Fixed $filePath. Tell me about other bugs, or say 'done' to finish.", InventPhase.DEBUGGING)
    }

    // ── Session lifecycle ──────────────────────────────────────────────────

    fun startNewSession(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // Save current session before discarding it
            flushSave()
            engineManager.unloadAll()
            withContext(Dispatchers.Main) {
                _ui.value = InventUiState()
                sessionState = null
                zcp = ZcpProtocol()
                sessionId = ""
                savedOriginalPaths.clear()
            }
            onDone()
        }
    }

    fun restartConversation() {
        // Keep session, models, and mode — just clear messages and reset phase
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Flush current state to disk before creating new session
            flushSave()
            // 2. Assign a new session ID so the old one is preserved in history
            val newId = UUID.randomUUID().toString().take(8)
            sessionId = newId
            val offline = _ui.value.offlineMode
            val models = sessionState
            // 3. Reset all state atomically via withTransaction
            withTransaction(
                uiTransform = {
                    it.copy(
                        sessionId = newId,
                        phase = InventPhase.QUESTIONING,
                        messages = emptyList(),
                        fileTree = emptyList(),
                        currentFileIndex = 0, totalFiles = 0, currentFileName = "",
                        searchRound = 0, mergeCount = 0,
                        totalLines = 0, totalGeneratedBytes = 0L, debugSessionCount = 0,
                        showSureButtons = false, error = "", swapInfo = "",
                        zipReady = false, debugMode = false,
                        isGenerating = false
                    )
                },
                sessionTransform = {
                    models?.copy(
                        sessionId = newId,
                        phase = InventPhase.QUESTIONING,
                        messages = emptyList(),
                        searchRound = 0, mergeCount = 0,
                        currentFileIndex = 0, totalFiles = 0
                    )
                },
                zcpTransform = { ZcpProtocol(offlineMode = offline) }
            )
        }
    }

    fun saveCurrentSession() {
        viewModelScope.launch(Dispatchers.IO) { flushSave() }
    }

    fun onDeleteConfirmed() {
        viewModelScope.launch(Dispatchers.IO) {
            InventStorage.deleteSession(ctx, sessionId)
            engineManager.unloadAll()
            _ui.value = InventUiState()
            sessionState = null; zcp = ZcpProtocol(); sessionId = ""
        }
    }

    // ── Inference ──────────────────────────────────────────────────────────

    private suspend fun loadOrKeepModel(path: String): Boolean {
        val engine = engineManager.getActiveEngine()
        if (engine != null && engine.isModelLoaded && engine.loadedModelPath == path) {
            return true
        }
        // If engine has a different model loaded, unload it first to free RAM
        if (engine != null && engine.isModelLoaded) {
            engineManager.unloadAll()
        }
        return withContext(Dispatchers.IO) {
            try {
                engineManager.selectEngineForFormat(path)
                engineManager.getActiveEngine()?.loadModel(path)?.isSuccess == true
            } catch (e: Exception) { false }
        }
    }

    private fun ensureEngineReady(expectedPath: String): Boolean {
        val engine = engineManager.getActiveEngine()
        return engine != null && engine.isModelLoaded && engine.loadedModelPath == expectedPath
    }

    private suspend fun reloadEngineFor(path: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                engineManager.unloadAll()
                val engine = engineManager.selectEngineForFormat(path)
                engine.config = SettingsManager.toConfig(path)
                engine.repeatPenalty = SettingsManager.toRepeatPenalty()
                engine.loadModel(path)?.isSuccess == true
            } catch (e: Exception) { false }
        }
    }

    fun reloadInventModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val active = engineManager.getActiveEngine()
            val path = active?.loadedModelPath ?: return@launch
            val state = sessionState ?: return@launch
            val role = when (path) {
                state.model1Path -> "Planner"
                state.model2Path -> "Coder"
                state.researcherPath -> "Researcher"
                else -> return@launch
            }
            val inventCfg = SettingsManager.getInventModelConfig(role) ?: return@launch
            try {
                engineManager.unloadAll()
                val engine = engineManager.selectEngineForFormat(path)
                engine.config = InferenceConfig(
                    nCtx = inventCfg.ctx, maxNewTokens = inventCfg.maxNew,
                    nGpuLayers = inventCfg.gpuLayers, temperature = inventCfg.temperature ?: 0.7f,
                    topP = inventCfg.topP ?: 0.9f, minP = inventCfg.minP ?: 0f,
                    topK = inventCfg.topK ?: 40, seed = inventCfg.seed ?: -1,
                    flashAttention = inventCfg.flashAttention ?: false, lowRamMode = inventCfg.lowRamMode ?: false,
                    nThreads = inventCfg.threads ?: 4, nBatch = inventCfg.nBatch ?: 512
                )
                engine.repeatPenalty = RepeatPenaltyConfig(
                    repeatPenalty = inventCfg.repeatPenalty ?: 1.1f,
                    freqPenalty = inventCfg.freqPenalty ?: 0f, presPenalty = inventCfg.presPenalty ?: 0f
                )
                engine.loadModel(path)
            } catch (_: Exception) { }
        }
    }

    /** Cancellation flag for mid-generation interrupt. */
    private var _cancelGeneration = false

    /** Outcome tracking for the last generation run — used for retry/skip reporting and telemetry. */
    private var lastGenFailures = mutableListOf<String>()
    private var lastSanityWarnings = mutableMapOf<String, String>()

    private var lastCompactionNotified: Long = 0

    private fun checkCompactionAndNotify(historySize: Int, compactedSize: Int, phase: InventPhase) {
        if (compactedSize < historySize && phase == InventPhase.QUESTIONING) {
            val now = System.currentTimeMillis()
            if (now - lastCompactionNotified > 30_000) {
                lastCompactionNotified = now; val dropped = historySize - compactedSize
                addMessage("system", "⚠ Context limit reached — $dropped oldest messages compacted. Go to Settings to increase context size.", phase)
            }
        }
    }

    /** Run inference with timeout to prevent hangs. */
    private suspend fun runInference(
        systemPrompt: String, userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        expectedModelPath: String? = null,
        onStream: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        _ui.value = _ui.value.copy(isGenerating = true, thinkingContent = "")
        val sb = StringBuilder()

        if (expectedModelPath != null && !ensureEngineReady(expectedModelPath)) {
            if (!reloadEngineFor(expectedModelPath)) {
                _ui.value = _ui.value.copy(isGenerating = false, error = "Failed to load $expectedModelPath")
                return@withContext "[Failed to load model]"
            }
        }

        // Prepend think instruction when reasoning toggle is on
        val thinkPrefix = if (_ui.value.reasoningEnabled)
            "Use <think> tags for step-by-step reasoning before answering.\n\n" else ""
        val (fullPrompt, compacted) = buildPromptWithInfo(systemPrompt, history, "$thinkPrefix$userMessage")
        checkCompactionAndNotify(history.size, compacted, _ui.value.phase)
        val engine = engineManager.getActiveEngine()

        if (engine == null) {
            _ui.value = _ui.value.copy(isGenerating = false)
            return@withContext "[No engine loaded]"
        }

        var streamedTokens = 0
        var flushCount = 0
        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                sb.append(token)
                flushCount++
                // Flush to UI every ~5 tokens for live streaming
                if (onStream != null && flushCount % 5 == 0) {
                    onStream(sb.toString())
                }
            }
            override fun onDone() {
                // Use local accumulator to avoid racing with finally block's _ui.value read
                val tokens = streamedTokens
                _ui.value = _ui.value.copy(totalTokensUsed = _ui.value.totalTokensUsed + tokens)
            }
            override fun onError(error: String) { sb.append("[ERROR: $error]") }
            override fun onKvUsage(percent: Int) {}
            override fun onTokensGenerated(count: Int) { streamedTokens = count }
        }

        try {
            withTimeout(120_000L) { // 2 min timeout to prevent hangs
                engine.executeInference(fullPrompt, callback)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            sb.append("[ERROR: Inference timed out after 2 minutes]")
            engine.abortInference()
        } catch (e: Exception) {
            sb.append("[ERROR: ${e.message}]")
        } finally {
            // Always reset generating state, even on cancellation
            _ui.value = _ui.value.copy(isGenerating = false, streamingResponse = "")
        }

        sb.toString().trim()
    }

    // ── Prompt Builders ────────────────────────────────────────────────────

    private fun buildQuestioningPrompt(): String = """
You are a senior project architect interviewing a client. Your job is to extract EVERY detail needed to build their software project.

You MUST ask about ALL of these topics before stopping. Do NOT skip any:

1. **Coding language** — Python, Kotlin, JavaScript, Rust, Go, Swift, etc.
2. **Platform / device** — Android, iOS, Web, Desktop (Windows/Mac/Linux), Server, Embedded
3. **Framework & libraries** — Flutter, React, Django, Spring, PyTorch, etc.
4. **What it does** — Core purpose, main functionality
5. **Target audience / users** — Who will use it? Developers, consumers, enterprise?
6. **Key features** — List the main features the app must have
7. **Tricky / hard parts** — What's the hardest problem to solve?
8. **Architecture preference** — Monolith, microservices, MVC, MVVM, etc.
9. **Database / storage** — SQLite, PostgreSQL, Firebase, Room, etc.
10. **Authentication** — Login system needed? OAuth, email/password, biometric?
11. **UI / design** — Material Design, custom, terminal-based, game engine?
12. **Third-party integrations** — APIs, payment (Stripe), maps, analytics, etc.
13. **Workflow / user flow** — Describe a typical user session from start to finish
14. **Offline support** — Does it need to work without internet?
15. **Deployment / distribution** — App Store, Play Store, Docker, self-hosted?
16. **Timeline / priority** — MVP scope vs future features?

RULES:
- Ask ONE question at a time — short and direct.
- After every answer, pick a NEW topic from the list above that you haven't asked about yet.
- NEVER repeat a topic you already covered.
- Every response MUST end with a question mark.
- Do NOT summarize, do NOT give advice, do NOT conclude.
- Only stop when the client presses the "Done" button.
- If the client gives a vague answer, ask a more specific follow-up on the SAME topic before moving to the next.

Example:
  Client: "I want to build a todo app."
  You: "What platform should it run on — Android, iOS, web, or all three?"
  Client: "Android mostly."
  You: "Are you targeting phones only, or tablets too?"
  Client: "Phones."
  You: "Got it. What coding language are you planning to use — Kotlin or Java?""".trimIndent()

    private fun buildPlanningPrompt(model2Ctx: Int): String = """
You are a senior software architect. Create a complete build plan.

Output these blocks exactly:
§APP{name:X|platform:Y|language:Z|framework:W}
§IDEA{core:X|features:Y|unique:Z}
§VIABLE{status:yes/no|note:X}
§SEARCH{topic:X|platform:Y|question:Z|category:W}
§TREE{path:X|type:dir/file|desc:X}
§FILEZCP{path:X|description:X|imports:X|classes:X|functions:X|dependencies:Y,Z}

Coder context limit: $model2Ctx tokens.
Each FILEZCP must be self-contained for independent implementation.
Do NOT wrap the blocks in markdown or code fences. Output them as plain text.
""".trimIndent()

    private fun buildCodeGenPrompt(spec: FileSpec, projectZcp: ZcpProtocol, projectDir: File): String = buildString {
        appendLine("Project: ${projectZcp.projectName} | ${projectZcp.language.joinToString(", ")} | ${projectZcp.framework}")
        if (projectZcp.coreIdea.isNotEmpty()) appendLine("Idea: ${projectZcp.coreIdea}")
        if (projectZcp.mainFeatures.isNotEmpty()) appendLine("Features: ${projectZcp.mainFeatures.joinToString(", ")}")
        appendLine()
        appendLine("--- File: ${spec.path} ---")
        appendLine("Desc: ${spec.description}")
        if (spec.imports.isNotEmpty()) appendLine("Imports: ${spec.imports}")
        if (spec.classes.isNotEmpty()) appendLine("Classes: ${spec.classes}")
        if (spec.functions.isNotEmpty()) appendLine("Functions: ${spec.functions}")
        if (spec.dependencies.isNotEmpty()) {
            appendLine("Dependencies with existing code:")
            spec.dependencies.forEach { dep ->
                val ds = projectZcp.fileSpecs[dep]
                if (ds != null) appendLine("  $dep → ${ds.description}")
                // Include actual dependency code if already generated
                val depCode = InventStorage.readGeneratedFile(projectDir, dep)
                if (depCode != null) {
                    val preview = depCode.lines().take(20).joinToString("\n")
                    appendLine("  [$dep source preview (first 20 lines)]:")
                    appendLine("  ```")
                    appendLine(preview.lines().joinToString("\n  "))
                    appendLine("  ```")
                }
            }
        }
        appendLine()
        appendLine("Write production-ready code. No explanations. No markdown fences.")
    }

    private fun buildResearcherPrompt(content: Map<String, String>, intents: List<SearchIntent>): String {
        val sb = StringBuilder("Extract the following from fetched content:\n\n")
        intents.forEachIndexed { i, intent ->
            sb.appendLine("SLOT_${i + 1}: ${intent.question} (${intent.category})")
            sb.appendLine("Content: ${content[intent.category]?.take(1500) ?: "No content"}\n")
        }
        sb.append("\nOutput:\nSLOT_1: [answer]\nSLOT_2: [answer]\n…")
        return sb.toString()
    }

    private fun buildZcpSummaryForModel2(): String = buildString {
        appendLine("§APP{name:${zcp.projectName}|platform:${zcp.platform.joinToString(",")}|language:${zcp.language.joinToString(",")}|framework:${zcp.framework}}")
        appendLine("§IDEA{core:${zcp.coreIdea}|features:${zcp.mainFeatures.joinToString(",")}|unique:${zcp.uniquePoint}}")
        appendLine("§VIABLE{status:${if (zcp.viable) "yes" else "no"}|note:${zcp.viabilityNote}}")
        appendLine("§TREE{")
        zcp.fileTree.forEach { appendLine("  ${if (it.isDir) "[DIR]" else "[FILE]"} ${it.path} // ${it.description}") }
        appendLine("}")
        appendLine("§FILES_TOTAL{count:${zcp.fileTree.count { !it.isDir }}}")
        appendLine("§FILE_SPECS{")
        zcp.fileSpecs.forEach { (path, spec) ->
            appendLine("  $path → ${spec.description.take(100)}")
            if (spec.classes.isNotEmpty()) appendLine("    classes: ${spec.classes.take(100)}")
            if (spec.functions.isNotEmpty()) appendLine("    functions: ${spec.functions.take(100)}")
        }
        appendLine("}")
    }

    /** Token estimate: ~1 token per 3 chars for code-heavy content, /4 for plain text. */
    private fun estimateTokens(text: String): Int = (text.length / 3).coerceAtLeast(1) + 1

    private fun getInventConfigForActiveModel(): SettingsManager.ModelTokenConfig? {
        val activePath = engineManager.getActiveEngine()?.loadedModelPath ?: return null
        val state = sessionState ?: return null
        return when (activePath) {
            state.model1Path -> SettingsManager.getInventModelConfig("Planner")
            state.model2Path -> SettingsManager.getInventModelConfig("Coder")
            state.researcherPath -> SettingsManager.getInventModelConfig("Researcher")
            else -> null
        }
    }

    private fun clearToolManagerOnEngines() {
        engineManager.llamaCpp.setToolManager(null)
        engineManager.mnn.setToolManager(null)
        engineManager.liteRt.setToolManager(null)
    }

    private fun enableSearchOnEngine() {
        val eng = engineManager.getActiveEngine()
        if (eng != null && eng.getToolManager() == null) eng.setToolManager(toolManager)
    }

    // ── Template-aware prompt builder ──────────────────────────────────────

    private fun detectTemplate(modelPath: String): String {
        // Use the user-selected template from SettingsManager if not "auto"
        val userTemplate = com.gguf.zerocopy.data.local.SettingsManager.chatTemplate
        if (userTemplate != "auto") return userTemplate
        // Also check per-session template
        val sessionTemplate = sessionState?.chatTemplate
        if (sessionTemplate != null && sessionTemplate != "auto") return sessionTemplate
        // Auto-detect from model name
        val name = modelPath.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return when {
            name.contains("gemma") -> "gemma"
            name.contains("llama") && (name.contains("3") || name.contains("3.1") || name.contains("3.2") || name.contains("3.3")) -> "llama3"
            name.contains("deepseek") -> "deepseek"
            name.contains("qwen") -> "qwen"
            name.contains("phi") -> "phi"
            name.contains("mistral") || name.contains("mixtral") -> "mistral"
            name.contains("command") -> "command"
            else -> "chatml"
        }
    }

    /**
     * Use JNI chat template when available (NativeBridge.formatWithChatTemplateNative).
     * Falls back to manual template formatting if JNI is not available or the model
     * is not loaded (e.g. MNN engine).
     */
    private fun formatViaJni(messages: List<Pair<String, String>>): String? {
        // Only works with the llama.cpp engine which has NativeBridge
        if (!com.gguf.zerocopy.domain.inference.NativeBridge.nativeLibLoaded) return null
        val activePath = engineManager.getActiveEngine()?.loadedModelPath ?: return null
        if (!activePath.endsWith(".gguf", true)) return null
        return try {
            val arr = org.json.JSONArray()
            messages.forEach { (role, content) ->
                arr.put(org.json.JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
            val formatted = com.gguf.zerocopy.domain.inference.NativeBridge.formatWithChatTemplateNative(arr.toString())
            if (formatted.isNotEmpty()) formatted else null
        } catch (_: Exception) { null }
    }

    private fun formatRole(template: String, role: String): Pair<String, String> = when (template) {
        "gemma" -> {
            val (h, f) = when (role) {
                "user" -> "<start_of_turn>user\n" to "<end_of_turn>\n<start_of_turn>model\n"
                "assistant" -> "<start_of_turn>model\n" to "<end_of_turn>\n"
                "system" -> "<start_of_turn>user\n" to "<end_of_turn>\n"
                else -> "<start_of_turn>user\n" to "<end_of_turn>\n"
            }
            h to f
        }
        "llama3" -> {
            val (h, f) = when (role) {
                "system" -> "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" to "<|eot_id|>\n"
                "user" -> "<|start_header_id|>user<|end_header_id|>\n\n" to "<|eot_id|>\n"
                "assistant" -> "<|start_header_id|>assistant<|end_header_id|>\n\n" to "<|eot_id|>\n"
                else -> "<|start_header_id|>user<|end_header_id|>\n\n" to "<|eot_id|>\n"
            }
            h to f
        }
        "qwen" -> {
            val (h, f) = when (role) {
                "system" -> "<|im_start|>system\n" to "<|im_end|>\n"
                "user" -> "<|im_start|>user\n" to "<|im_end|>\n"
                "assistant" -> "<|im_start|>assistant\n" to "<|im_end|>\n"
                else -> "<|im_start|>user\n" to "<|im_end|>\n"
            }
            h to f
        }
        "phi" -> {
            val (h, f) = when (role) {
                "system" -> "<|system|>\n" to "<|end|>\n"
                "user" -> "<|user|>\n" to "<|end|>\n"
                "assistant" -> "<|assistant|>\n" to "<|end|>\n"
                else -> "<|user|>\n" to "<|end|>\n"
            }
            h to f
        }
        "deepseek" -> Pair( when (role) {
            "system" -> "<｜begin▁of▁sentence｜>\n"
            "user" -> "<｜User｜>\n"
            "assistant" -> "<｜Assistant｜>\n"
            else -> "<｜User｜>\n"
        }, when (role) {
            "assistant" -> "<｜Assistant｜>"  // Assistant turn ended when next message starts
            else -> ""
        })
        else -> { // ChatML / default
            val (h, f) = when (role) {
                "system" -> "<|im_start|>system\n" to "<|im_end|>\n"
                "user" -> "<|im_start|>user\n" to "<|im_end|>\n"
                "assistant" -> "<|im_start|>assistant\n" to "<|im_end|>\n"
                else -> "<|im_start|>user\n" to "<|im_end|>\n"
            }
            h to f
        }
    }

    private fun buildPromptWithInfo(
        system: String, history: List<Pair<String, String>>, user: String
    ): Pair<String, Int> {
        val activePath = engineManager.getActiveEngine()?.loadedModelPath ?: ""
        val inventCfg = getInventConfigForActiveModel()
        val modelCfg = inventCfg ?: SettingsManager.getModelTokenConfig(activePath)
        val availableCtx = modelCfg?.ctx?.coerceAtLeast(512) ?: SettingsManager.nCtx.coerceAtLeast(1024)
        val maxNew = modelCfg?.maxNew?.coerceAtLeast(64) ?: SettingsManager.maxTokens.coerceAtLeast(64)
        val budget = (availableCtx - maxNew - 256).coerceAtLeast(512)

        // Try JNI template first (correct for GGUF models)
        val jniResult = if (activePath.endsWith(".gguf", true)) {
            val allMsgs = mutableListOf<Pair<String, String>>()
            allMsgs.add("system" to system)
            allMsgs.addAll(history)
            allMsgs.add("user" to user)
            formatViaJni(allMsgs)
        } else null

        if (jniResult != null) {
            // JNI returned a properly formatted prompt — no compaction needed (JNI handles it)
            return jniResult to history.size
        }

        // Fallback: manual template formatting (MNN engine or JNI unavailable)
        val template = detectTemplate(activePath)

        var compactedHistory = history
        var estimatedTotal = estimateTokens(system) + history.sumOf { (r, c) -> estimateTokens("$r $c") } + estimateTokens(user)
        if (estimatedTotal > budget) {
            val mutable = history.toMutableList()
            while (mutable.isNotEmpty()) {
                val testTotal = estimateTokens(system) + mutable.sumOf { (r, c) -> estimateTokens("$r $c") } + estimateTokens(user)
                if (testTotal <= budget) break
                if (mutable.size > 1) {
                    val first = mutable.first()
                    mutable[0] = first.copy(second = first.second.take(2000))
                    val retryTotal = estimateTokens(system) + mutable.sumOf { (r, c) -> estimateTokens("$r $c") } + estimateTokens(user)
                    if (retryTotal <= budget) break
                    mutable.removeAt(0)
                } else break
            }
            compactedHistory = mutable
        }

        val prompt = buildString {
            if (template == "deepseek") {
                // Fixed DeepSeek formatting: proper newlines + turn markers
                val (sysH, _) = formatRole(template, "system")
                append(sysH); appendLine(system); appendLine()
                compactedHistory.forEach { (role, content) ->
                    val mappedRole = if (role == "user") "user" else if (role == "system") "system" else "assistant"
                    val (h, f) = formatRole(template, mappedRole)
                    append(h); appendLine(); appendLine(content.take(16_000)); if (f.isNotEmpty()) appendLine(f); appendLine()
                }
                val (uH, uF) = formatRole(template, "user")
                append(uH); appendLine(); appendLine(user.take(16_000)); if (uF.isNotEmpty()) { appendLine(uF); appendLine() }
                append(formatRole(template, "assistant").first); appendLine()
            } else {
                val (sysH, sysF) = formatRole(template, "system")
                append(sysH); appendLine(system); if (sysF.isNotEmpty()) append(sysF)
                compactedHistory.forEach { (role, content) ->
                    val mappedRole = if (role == "user") "user" else if (role == "system") "system" else "assistant"
                    val (h, f) = formatRole(template, mappedRole)
                    append(h); appendLine(content.take(16_000)); if (f.isNotEmpty()) append(f)
                }
                val (uH, uF) = formatRole(template, "user")
                append(uH); appendLine(user.take(16_000)); if (uF.isNotEmpty()) append(uF)
                append(formatRole(template, "assistant").first)
            }
        }
        return prompt to compactedHistory.size
    }

    // ── Conversation history ───────────────────────────────────────────────

    /** Build conversation history, optionally excluding the last N messages
     *  (used when the last message was just added and will be sent separately). */
    private fun buildConversationHistory(excludeLast: Int = 0): List<Pair<String, String>> =
        _ui.value.messages
            .filter { it.role != "system" }
            .takeLast(20 + excludeLast)
            .dropLast(excludeLast)
            .map { msg -> (if (msg.role == "user") "user" else "assistant") to msg.content }

    // ── Parsers with validation ────────────────────────────────────────────

    private fun parseZcpFromModel1(raw: String, existing: ZcpProtocol): ZcpProtocol {
        fun extract(tag: String, field: String): String =
            Regex("§$tag\\{[^}]*$field:([^|}]+)").find(raw)?.groupValues?.get(1)?.trim() ?: ""
        fun extractList(tag: String, field: String) =
            extract(tag, field).split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val intents = Regex("§SEARCH\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2)
                p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            SearchIntent(kv["topic"] ?: "", kv["platform"] ?: "", kv["question"] ?: "", kv["category"] ?: "general")
        }.toList()

        return existing.copy(
            projectName = extract("APP", "name").ifEmpty { existing.projectName },
            platform = extractList("APP", "platform").ifEmpty { existing.platform },
            language = extractList("APP", "language").ifEmpty { existing.language },
            framework = extract("APP", "framework").ifEmpty { existing.framework },
            coreIdea = extract("IDEA", "core").ifEmpty { existing.coreIdea },
            mainFeatures = extractList("IDEA", "features").ifEmpty { existing.mainFeatures },
            uniquePoint = extract("IDEA", "unique").ifEmpty { existing.uniquePoint },
            viable = extract("VIABLE", "status") != "no",
            viabilityNote = extract("VIABLE", "note").ifEmpty { existing.viabilityNote },
            searchIntents = intents.ifEmpty { existing.searchIntents }
        )
    }

    private fun parseFileTree(raw: String): List<FileNode> =
        Regex("§TREE\\{([^}]+)\\}").findAll(raw).mapNotNull { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2)
                p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            val path = kv["path"] ?: return@mapNotNull null
            if (path.isNotEmpty()) FileNode(path = path, isDir = kv["type"] == "dir", description = kv["desc"] ?: "") else null
        }.toList()

    private fun parseFileSpecs(raw: String): Map<String, FileSpec> {
        val specs = mutableMapOf<String, FileSpec>()
        Regex("§FILEZCP\\{([^}]+)\\}").findAll(raw).forEach { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2)
                p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            val path = kv["path"] ?: return@forEach
            if (path.isNotEmpty()) specs[path] = FileSpec(
                path = path, description = kv["description"] ?: "",
                imports = kv["imports"] ?: "", classes = kv["classes"] ?: "", functions = kv["functions"] ?: "",
                dependencies = (kv["dependencies"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                estimatedTokens = (kv["estimatedTokens"] ?: "0").toIntOrNull() ?: 0,
                continuationOf = kv["continuationOf"] ?: ""
            )
        }
        return specs
    }

    /** Parse §PROMPT blocks (new pipeline format for .txt placeholders). */
    private fun parsePlannerPrompts(raw: String): Map<String, FileSpec> {
        val specs = mutableMapOf<String, FileSpec>()
        Regex("§PROMPT\\{([^}]+)\\}").findAll(raw).forEach { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2)
                p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            val path = kv["path"] ?: return@forEach
            if (path.isNotEmpty()) specs[path] = FileSpec(
                path = path, description = kv["desc"] ?: "",
                imports = kv["imports"] ?: "", classes = kv["classes"] ?: "",
                functions = kv["functions"] ?: ""
            )
        }
        return specs
    }

    private fun parseSearchIntents(raw: String): List<SearchIntent> =
        Regex("§SEARCH\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2); p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            SearchIntent(kv["topic"] ?: "", kv["platform"] ?: "", kv["question"] ?: "", kv["category"] ?: "general")
        }.toList()

    private fun parseSearchResults(extracted: String, intents: List<SearchIntent>): List<SearchResult> =
        intents.mapIndexed { i, intent ->
            // Flexible format: SLOT_1:, 1., or bullet point with the topic name
            val content = Regex(
                """(?:SLOT_${i + 1}:|^${i + 1}\.\s*|•\s*+)\\s*(.+)$""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            ).find(extracted)?.groupValues?.get(1)?.trim() ?: ""
            SearchResult(intent, content, intent.category, true)
        }

    // ── Search ─────────────────────────────────────────────────────────────

    /** Fetch changelogs from official URLs (python.org, flutter.dev, etc.) directly.
     *  Instead of general web search, this fetches HTML from official release/changelog pages
     *  and extracts version information. */
    private suspend fun fetchOfficialChangelogs(researchPrompt: String): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        // Extract URLs from the research prompt
        val urls = Regex("https?://[^\\s,;]+\\.(?:org|com|dev|io|app)[^\\s,;]*")
            .findAll(researchPrompt)
            .map { it.value.trimEnd('.', ',', ';') }
            .distinct()
            .take(5)
            .toList()
        if (urls.isEmpty()) {
            // Fallback to general web search
            return@withContext fetchSearchContent(researchPrompt)
        }
        urls.forEach { url ->
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                }
                if (conn.responseCode == 200) {
                    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(8000)
                    result[url] = html
                }
                conn.disconnect()
            } catch (_: Exception) { /* skip failed URLs */ }
        }
        if (result.isEmpty()) {
            // All URLs failed, fallback to web search
            return@withContext fetchSearchContent(researchPrompt)
        }
        result
    }

    /** Actual web search using ToolManager — parallel async execution. */
    private suspend fun fetchSearchContent(promptArg: String = ""): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        val queries = mutableListOf<Pair<String, String>>()  // (key, query)

        if (promptArg.isNotBlank()) {
            val topics = promptArg.lines().map { it.trim() }.filter {
                it.isNotBlank() && it.length > 10 && !it.startsWith("§")
            }.ifEmpty { listOf(promptArg.take(200)) }
            topics.forEachIndexed { i, topic ->
                queries.add("research_$i" to topic.take(200))
            }
        } else {
            zcp.searchIntents.forEach { intent ->
                val key = intent.category
                if (!result.containsKey(key)) {
                    val query = intent.question.ifEmpty { "${intent.topic} ${intent.platform}" }
                    if (query.isNotBlank()) queries.add(key to query)
                }
            }
        }

        // Execute all searches in parallel using async
        if (queries.isNotEmpty()) {
            // withContext(Dispatchers.IO) provides CoroutineScope receiver for async
            val deferred = queries.map { (key, query) ->
                async {
                    val args = JSONObject().apply { put("query", query); put("num_results", 3) }
                    val call = ToolCall("search_${System.currentTimeMillis()}", "web_search", args)
                    try {
                        val res = toolManager.executeTool(call)
                        val text = res.result.trim()
                        key to if (text.isNotBlank() && !text.startsWith("Error", true) &&
                            !text.startsWith("No results", true) && !text.startsWith("Web search failed", true)
                        ) text.take(3000) else "[No search results for: $query]"
                    } catch (e: Exception) { key to "[Search failed: ${e.message}]" }
                }
            }
            deferred.awaitAll().forEach { (k, v) -> result[k] = v }
        }
        result
    }

    private fun runWebSearch(query: String): String? {
        val args = JSONObject().apply { put("query", query); put("num_results", 3) }
        val call = ToolCall("invent_${System.currentTimeMillis()}", "web_search", args)
        return try {
            val res = toolManager.executeTool(call); val text = res.result.trim()
            if (text.isBlank() || text.startsWith("Error", true) || text.startsWith("No results", true)) null else text
        } catch (e: Exception) { null }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    fun cancelGeneration() {
        _cancelGeneration = true
        _ui.value = _ui.value.copy(isGenerating = false)
        updatePhase(InventPhase.QUESTIONING)
    }

    private fun addMessage(role: String, content: String, phase: InventPhase, thinkingContent: String = "") {
        // Strip turn-ending tokens the model may have generated — prevents
        // double endings in the next prompt's template-formatted history.
        val cleaned = content.trimEnd()
            .removeSuffix("<|eot_id|>")
            .removeSuffix("<|im_end|>")
            .removeSuffix("<|end|>")
            .removeSuffix("<end_of_turn>")
            .removeSuffix("<｜User｜>").trimEnd()
        val started = role == "model1" || role == "model2" || role == "researcher"
        val added = if (role == "user" || role == "model1" || role == "model2" || role == "researcher") content.length else 0
        withTransaction(
            uiTransform = { state ->
                val newMsg = InventMessage(role, cleaned, phase, thinkingContent)
                val appended = state.messages + newMsg
                // Compact context if messages exceed 60 turns
                val compacted = if (appended.size > 60) {
                    InventStorage.compressMessages(appended, maxMessages = 50, keepRecent = 10)
                } else appended
                state.copy(messages = compacted, chatStarted = state.chatStarted || started,
                    conversationDepth = state.conversationDepth + added)
            },
            sessionTransform = { sess ->
                val newMsg = InventMessage(role, cleaned, phase, thinkingContent)
                val appended = (sess?.messages ?: emptyList()) + newMsg
                val compacted = if (appended.size > 60) {
                    InventStorage.compressMessages(appended, maxMessages = 50, keepRecent = 10)
                } else appended
                sess?.copy(messages = compacted)
            }
        )
    }

    private fun updatePhase(phase: InventPhase) {
        withTransaction(
            uiTransform = { it.copy(phase = phase) },
            sessionTransform = { it?.copy(phase = phase) }
        )
    }

    private fun updateSearchRound(round: Int) {
        withTransaction(
            uiTransform = { it.copy(searchRound = round) },
            sessionTransform = { it?.copy(searchRound = round) }
        )
    }

    private fun setSwap(info: String) { _ui.value = _ui.value.copy(swapInfo = info) }

    /**
     * Get the content of a generated file by searching through the chat messages
     * for the relevant code block.
     */
    fun getFileContent(filePath: String): String? {
        val cur = _ui.value
        // First try from zcp memory
        val z = zcp
        if (z.fileTree.any { it.path == filePath }) {
            // Scan session messages for code blocks matching this file name
            val fileName = filePath.substringAfterLast('/')
            for (msg in cur.messages) {
                val content = msg.content
                // Match code blocks with a comment/heading that includes the file name
                val codeRegex = Regex("`{3}[\\s\\S]*?" + Regex.escape(fileName) + "[\\s\\S]*?`{3}", RegexOption.IGNORE_CASE)
                val match = codeRegex.find(content)
                if (match != null) {
                    return match.value
                        .replace(Regex("^```\\w*\\n?", RegexOption.MULTILINE), "")
                        .replace(Regex("\\n?```$", RegexOption.MULTILINE), "")
                        .trim()
                }
            }
            // Fallback: try loading from the actual file if it was saved
            try {
                val dir = java.io.File(getApplication<android.app.Application>().filesDir, "invent_generated/${cur.sessionId}${filePath.substringBeforeLast('/')}")
                val file = java.io.File(dir, fileName)
                if (file.exists()) return file.readText()
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Start a debug session for a specific file.
     */
    fun requestDebug(filePath: String, fileContent: String) {
        val cur = _ui.value
        // Send a message to the coder model asking it to debug this specific file
        val debugPrompt = "Please review the following file for bugs and issues. " +
            "File: $filePath\n\n```\n$fileContent\n```\n\n" +
            "Identify any bugs, security issues, or improvements needed."
        _ui.value = cur.copy(debugMode = true, phase = InventPhase.DEBUGGING)
        // Queue the message for sending
        viewModelScope.launch {
            sendUserMessage(debugPrompt)
        }
    }

    /**
     * Handle a user message in the post-workflow coder chat.
     * Runs the coder model with the file content and user question as context.
     */
    fun handleCoderChatMessage(userText: String, filePath: String) {
        val state = sessionState ?: return
        val cur = _ui.value
        if (cur.isGenerating) return // already busy
        addMessage("user", userText, cur.phase)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Determine coder model: use model2 if distinct, otherwise model1
                val isSame = state.sameModelMode || state.model1Path == state.model2Path || state.model2Path.isBlank()
                val targetPath = if (!isSame) state.model2Path else state.model1Path
                val targetName = if (!isSame) state.model2Name else state.model1Name
                if (!loadOrKeepModel(targetPath)) {
                    addMessage("system", "Failed to load $targetName", _ui.value.phase)
                    return@launch
                }
                // Fetch the file content for context
                val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
                val fileContent = InventStorage.readGeneratedFile(projectDir, filePath) ?: "[File not found on disk]"
                addMessage("system", "[Context: $filePath]", _ui.value.phase)
                // Run coder model with file context
                val response = runInference(
                    systemPrompt = "You are the coder for this project. The user is asking about a specific file. " +
                        "Answer clearly and concisely. If they ask for changes, describe what needs to change.",
                    userMessage = "File: $filePath\n\n```\n$fileContent\n```\n\nUser question: $userText",
                    history = buildConversationHistory(excludeLast = 2),
                    onStream = { partial ->
                        _ui.value = _ui.value.copy(streamingResponse = partial)
                    }
                )
                _ui.value = _ui.value.copy(streamingResponse = "")
                val trimmed = response.trim()
                if (trimmed.isNotEmpty()) {
                    addMessage("coder", trimmed, _ui.value.phase)
                } else {
                    addMessage("coder", "I'm not sure about that. Could you clarify?", _ui.value.phase)
                }
            } catch (e: Exception) {
                addMessage("system", "Error: ${e.message}", _ui.value.phase)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Unload engines on background thread — NEVER block the main thread
        viewModelScope.launch(Dispatchers.IO) {
            withTimeout(5000L) {
                engineManager.unloadAll()
            }
        }
    }
}
