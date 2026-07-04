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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val chatStarted: Boolean = false
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

    /** Single source of truth: session state with embedded ZCP data saved atomically. */
    private var sessionState: InventSessionState? = null
        set(value) {
            field = value
            value?.let { persistSessionState(it) }
        }
    private var zcp: ZcpProtocol = ZcpProtocol()
        set(value) {
            field = value
            sessionState?.let { persistZcp(value) }
        }
    private var sessionId: String = ""
    /** Saved original paths for model mode switching. */
    private var savedOriginalPaths = mutableMapOf<String, String>()

    // ── Atomic persistence ─────────────────────────────────────────────────

    private fun persistSessionState(state: InventSessionState) {
        viewModelScope.launch(Dispatchers.IO) {
            InventStorage.saveSession(ctx, state)
        }
    }

    private fun persistZcp(z: ZcpProtocol) {
        viewModelScope.launch(Dispatchers.IO) {
            InventStorage.saveZcp(ctx, sessionId, z)
        }
    }

    /** Save both session state and ZCP atomically. */
    private fun saveAllState() {
        val s = sessionState ?: return
        val sid = sessionId
        val z = zcp
        viewModelScope.launch(Dispatchers.IO) {
            InventStorage.saveSession(ctx, s)
            InventStorage.saveZcp(ctx, sid, z)
        }
    }

    fun setShowDeleteConfirm(v: Boolean) { _ui.value = _ui.value.copy(showDeleteConfirm = v) }

    fun toggleSameModelMode() {
        val state = sessionState ?: return
        val newMode = !state.sameModelMode
        val newState = if (newMode) {
            state.copy(sameModelMode = true, model2Path = state.model1Path, model2Name = state.model1Name)
        } else {
            state.copy(sameModelMode = false)
        }
        sessionState = newState
        _ui.value = _ui.value.copy(sameModelMode = newMode, model2Name = newState.model2Name)
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
        _ui.value = _ui.value.copy(modelMode = mode)

        val plannerP = savedOriginalPaths["planner"]?.takeIf { it.isNotEmpty() } ?: state.model1Path
        val plannerN = savedOriginalPaths["plannerName"]?.takeIf { it.isNotEmpty() } ?: state.model1Name
        val coderP = savedOriginalPaths["coder"]?.takeIf { it.isNotEmpty() } ?: state.model2Path
        val coderN = savedOriginalPaths["coderName"]?.takeIf { it.isNotEmpty() } ?: state.model2Name
        val resP = savedOriginalPaths["researcher"]?.takeIf { it.isNotEmpty() } ?: state.researcherPath
        val resN = savedOriginalPaths["researcherName"]?.takeIf { it.isNotEmpty() } ?: state.researcherName

        val newState = when (mode) {
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
        sessionState = newState
        _ui.value = _ui.value.copy(
            researcherName = newState.researcherName,
            model1Name = newState.model1Name,
            model2Name = newState.model2Name,
            sameModelMode = newState.sameModelMode
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
                val cur = _ui.value
                _ui.value = _ui.value.copy(
                    plannerLoaded = cur.plannerLoaded || ((useForAll || tab == 0) && ok),
                    researcherLoaded = cur.researcherLoaded || ((useForAll || tab == 1) && ok),
                    coderLoaded = cur.coderLoaded || ((useForAll || tab == 2) && ok),
                    model1Name = if (ok && (useForAll || tab == 0) && name.isNotEmpty()) name else cur.model1Name,
                    model2Name = if (ok && (useForAll || tab == 2) && name.isNotEmpty()) name else cur.model2Name,
                    researcherName = if (ok && (useForAll || tab == 1) && name.isNotEmpty()) name else cur.researcherName
                )
                if (ok) {
                    val newState = sessionState?.let { s ->
                        if (useForAll) s.copy(
                            model1Path = path, model1Name = name,
                            researcherPath = path, researcherName = name,
                            model2Path = path, model2Name = name
                        ) else when (tab) {
                            0 -> { val s2 = s.copy(model1Path = path, model1Name = name); if (s.sameModelMode) s2.copy(model2Path = path, model2Name = name) else s2 }
                            1 -> s.copy(researcherPath = path, researcherName = name)
                            2 -> { val s2 = s.copy(model2Path = path, model2Name = name); if (s.sameModelMode) s2.copy(model1Path = path, model1Name = name) else s2 }
                            else -> s
                        }
                    }
                    sessionState = newState
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
        _ui.value = _ui.value.copy(sessions = list)
    }

    fun switchToSession(targetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = InventStorage.loadSession(ctx, targetId)
            val savedZcp = InventStorage.loadZcp(ctx, targetId)
            if (saved != null && savedZcp != null) {
                engineManager.unloadAll()
                sessionId = targetId
                sessionState = saved
                zcp = savedZcp
                _ui.value = _ui.value.copy(
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

    private fun getProjectDir(): File {
        val sid = sessionId.ifEmpty { "_" }
        return InventStorage.getProjectDir(ctx, sid, zcp.projectName)
    }

    // ── Setup ──────────────────────────────────────────────────────────────

    fun setupSession(
        model1Path: String, model1Name: String,
        model2Path: String, model2Name: String,
        researcherPath: String, researcherName: String,
        offlineMode: Boolean, sameModelMode: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
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

            // Resume most advanced in-progress session
            val phaseOrder = InventPhase.values().toList()
            val existing = InventStorage.listSessions(ctx)
                .mapNotNull { sid ->
                    val s = InventStorage.loadSession(ctx, sid)
                    if (s != null && s.phase != InventPhase.DONE && s.phase != InventPhase.DEBUGGING) sid to s else null
                }
                .sortedByDescending { (_, s) -> phaseOrder.indexOf(s.phase) }
                .firstOrNull()?.first

            if (existing != null) {
                val saved = InventStorage.loadSession(ctx, existing)
                val savedZcp = InventStorage.loadZcp(ctx, existing)
                if (saved != null && savedZcp != null) {
                    sessionId = existing; sessionState = saved; zcp = savedZcp
                    _ui.value = _ui.value.copy(
                        phase = saved.phase, messages = saved.messages, sessionId = existing,
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
                        zipReady = saved.phase == InventPhase.DONE || saved.phase == InventPhase.DEBUGGING
                    )
                    computeStats()
                    if (saved.phase == InventPhase.GENERATING) resumeGeneration()
                    if (saved.phase == InventPhase.FINALIZING) finishGeneration()
                    return@launch
                }
            }

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

            sessionId = UUID.randomUUID().toString().take(8)
            zcp = ZcpProtocol(model2ContextSize = m2Ctx, offlineMode = offlineMode)
            sessionState = InventSessionState(
                sessionId = sessionId, phase = InventPhase.QUESTIONING,
                model1Path = m1p, model1Name = m1n,
                model2Path = if (sameModelMode) m1p else m2p,
                model2Name = if (sameModelMode) m1n else m2n,
                researcherPath = rp, researcherName = rn,
                model1ContextSize = m1Ctx, model2ContextSize = m2Ctx,
                offlineMode = offlineMode, sameModelMode = sameModelMode
            )
            saveAllState()

            _ui.value = _ui.value.copy(
                phase = InventPhase.QUESTIONING, sessionId = sessionId,
                model1Name = m1n, model2Name = if (sameModelMode) m1n else m2n,
                researcherName = rn, offlineMode = offlineMode, sameModelMode = sameModelMode,
                modelMode = when { sameModelMode && rp == m1p -> ModelMode.SINGLE
                    sameModelMode -> ModelMode.DUAL; else -> ModelMode.TRIPLE }
            )
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
        val opening = runInference(
            systemPrompt = buildQuestioningPrompt(),
            userMessage = "Hi! I want to build a software project. Please help me plan it by asking about my requirements — one question at a time."
        )
        addMessage("model1", opening, InventPhase.QUESTIONING)
    }

    fun sendUserMessage(text: String, planWithSearch: Boolean = false, thinkTag: Boolean = false) {
        if (_ui.value.isGenerating) return

        // If in plan review mode, treat as a clarification → go back to Q&A
        if (_ui.value.showPlanReview) {
            onPlanClarify(text)
            return
        }

        // Parse attachments for tech stack hints
        var effectiveText = text
        if (text.startsWith("[Attached:")) {
            val hints = extractAttachedFileHints(text)
            if (hints.isNotEmpty()) effectiveText = "$text\n\n[From attached files, I noticed: $hints]"
        }
        val processed = buildString {
            append(effectiveText)
            if (planWithSearch) append("\n[SEARCH enabled]")
            if (thinkTag) append("\n[THINK]")
        }
        addMessage("user", processed, _ui.value.phase)
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
        // Exclude last entry — it's the user message we just added via addMessage()
        val history = buildConversationHistory(excludeLast = 1)
        val response = runInference(
            systemPrompt = buildQuestioningPrompt(),
            userMessage = userText, history = history
        )
        addMessage("model1", response.trim(), InventPhase.QUESTIONING)
    }

    // ── Done button: generate plan ─────────────────────────────────────────

    fun onDonePressed() {
        if (_ui.value.isGenerating) return
        _ui.value = _ui.value.copy(isGenerating = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = sessionState ?: return@launch
                // Generate the full build plan (ZCP blocks)
                val planRaw = runInference(
                    systemPrompt = buildPlanningPrompt(zcp.model2ContextSize.coerceAtLeast(2048)),
                    userMessage = "Based on our full conversation, write the complete build plan now. Include §APP, §IDEA, §VIABLE, all §SEARCH intents, and every §TREE/§FILEZCP block for each file needed.",
                    history = buildConversationHistory()
                )
                zcp = parseZcpFromModel1(planRaw, zcp)

                // Format a readable summary for display
                val planSummary = buildString {
                    appendLine("══════════════ BUILD PLAN ══════════════")
                    appendLine("Project: ${zcp.projectName.ifEmpty {"<auto>"}}")
                    if (zcp.coreIdea.isNotEmpty()) {
                        appendLine()
                        appendLine("■ Core idea: ${zcp.coreIdea}")
                    }
                    if (zcp.mainFeatures.isNotEmpty()) {
                        appendLine()
                        appendLine("■ Key features:")
                        zcp.mainFeatures.forEach { appendLine("  • $it") }
                    }
                    if (zcp.fileTree.isNotEmpty()) {
                        appendLine()
                        appendLine("■ File structure (${zcp.fileTree.count {!it.isDir}} files):")
                        zcp.fileTree.filter { !it.isDir }.forEach {
                            appendLine("  • ${it.path} — ${it.description.take(80)}")
                        }
                    }
                    appendLine()
                    appendLine("─────────────────────────────────────────")
                    appendLine("Press ✓ Done again to start research & code generation,")
                    appendLine("or send a message to clarify any part.")
                }

                withContext(Dispatchers.Main) {
                    addMessage("system", planSummary, InventPhase.QUESTIONING)
                    _ui.value = _ui.value.copy(isGenerating = false, showPlanReview = true, pendingPlan = planSummary)
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(isGenerating = false, error = "Plan generation failed: ${e.message}")
            }
        }
    }

    fun onPlanApproved() {
        if (_ui.value.isGenerating) return
        _ui.value = _ui.value.copy(isGenerating = true, showPlanReview = false, pendingPlan = "")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                triggerSearchPhase(zcpAlreadyGenerated = true)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(isGenerating = false, error = "Research failed: ${e.message}")
            }
        }
    }

    fun onPlanClarify(text: String) {
        _ui.value = _ui.value.copy(showPlanReview = false, pendingPlan = "")
        addMessage("user", text, InventPhase.QUESTIONING)
        viewModelScope.launch(Dispatchers.IO) {
            handleQuestioningReply(text)
        }
    }

    // ── Phase 2: ZCP + Search ──────────────────────────────────────────────

    /**
     * @param zcpAlreadyGenerated When true (called from onPlanApproved), skip ZCP inference
     *   because it was already done in onDonePressed().
     */
    private suspend fun triggerSearchPhase(zcpAlreadyGenerated: Boolean = false) {
        updatePhase(InventPhase.SEARCHING)
        val state = sessionState ?: return

        if (!zcpAlreadyGenerated) {
            val zcpRaw = runInference(
                systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
                userMessage = "Based on everything we discussed, write the complete ZCP protocol now. Include §APP, §IDEA, §VIABLE, all §SEARCH intents, and §TREE blocks.",
                history = buildConversationHistory()
            )
            zcp = parseZcpFromModel1(zcpRaw, zcp)
        }

        addMessage("system", "ZCP v1 saved ✓  Starting research…", InventPhase.SEARCHING)
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        if (zcp.offlineMode) { startFilePlanning(); return }

        val maxRounds = zcp.searchIntents.size.coerceIn(1, 5)
        runSearchRounds(maxRounds)
    }

    private suspend fun runSearchRounds(maxRounds: Int) {
        val state = sessionState ?: return
        var round = sessionState?.searchRound ?: 0

        while (round < maxRounds) {
            round++
            updateSearchRound(round)
            setSwap("Fetching sources (round $round/$maxRounds)…")
            val fetchedContent = fetchSearchContent()

            setSwap("Loading ${state.researcherName}…")
            if (!loadOrKeepModel(state.researcherPath)) {
                setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load researcher"); return
            }
            setSwap("")

            val extracted = runInference(
                systemPrompt = "You are a precise information extractor. Fill given slots with exact values from the provided content. Output ONLY slot:value pairs. No explanations.",
                userMessage = buildResearcherPrompt(fetchedContent, zcp.searchIntents)
            )
            InventStorage.saveSearchLog(ctx, sessionId, extracted)

            setSwap("Loading ${state.model1Name} to review…")
            if (!loadOrKeepModel(state.model1Path)) {
                setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return
            }
            setSwap("")

            val reviewResponse = runInference(
                systemPrompt = "Review the search results. If you have enough info, output [SEARCH_DONE]. If not, output new §SEARCH blocks for what's missing.",
                userMessage = "Search results:\n$extracted\n\nDo you have all info needed? If yes output [SEARCH_DONE]. If not, output new §SEARCH blocks only.",
                expectedModelPath = state.model1Path
            )

            if (reviewResponse.contains("[SEARCH_DONE]", ignoreCase = true) || round >= maxRounds) {
                zcp = zcp.copy(searchResults = parseSearchResults(extracted, zcp.searchIntents))
                withContext(Dispatchers.IO) { engineManager.unloadAll() }
                startFilePlanning(); break
            } else {
                val newIntents = parseSearchIntents(reviewResponse)
                if (newIntents.isNotEmpty()) {
                    zcp = zcp.copy(searchIntents = zcp.searchIntents + newIntents)
                }
                withContext(Dispatchers.IO) { engineManager.unloadAll() }
            }
        }
    }

    // ── Phase 3: Planning ──────────────────────────────────────────────────

    private suspend fun startFilePlanning() {
        val state = sessionState ?: return
        updatePhase(InventPhase.PLANNING)
        setSwap("Loading ${state.model1Name} for planning…")
        if (!loadOrKeepModel(state.model1Path)) {
            setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return
        }
        setSwap("")

        val plan = runInference(
            systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
            userMessage = "You have all information. First write the complete project file tree using §TREE blocks. Then for EACH file (not directory), write a §FILEZCP block describing what that file should contain — its imports, classes, functions, and how it connects to other files.\n\nFormat:\n§TREE{path:X|type:dir/file|desc:X}\n§FILEZCP{path:X|description:X|imports:X|classes:X|functions:X|dependencies:Y,Z}\n\nEach §FILEZCP must be self-contained so a separate coder model can implement it independently."
        )

        var fileTree = parseFileTree(plan)
        var fileSpecs = parseFileSpecs(plan)

        // Validation: if parsing returned empty, retry with more explicit instructions
        if (fileTree.isEmpty()) {
            addMessage("system", "⚠ Could not parse file tree from planner output. Retrying with explicit format…", InventPhase.PLANNING)
            val retryPlan = runInference(
                systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
                userMessage = "Output ONLY machine-parseable §TREE and §FILEZCP blocks. No explanations, no markdown, no headers. Use EXACTLY this format:\n§TREE{path:src/main.kt|type:file|desc:Main entry}\n§FILEZCP{path:src/main.kt|description:Entry point|imports:android.os.Bundle|classes:MainActivity|functions:onCreate|dependencies:}\n\nNow write the complete file tree and specs:",
                expectedModelPath = state.model1Path
            )
            fileTree = parseFileTree(retryPlan)
            fileSpecs = parseFileSpecs(retryPlan)
            if (fileTree.isEmpty()) {
                addMessage("system", "⚠ Still could not parse. Creating an empty project structure — you can debug files later.", InventPhase.PLANNING)
                fileTree = listOf(FileNode("src/main.${zcp.language.firstOrNull()?.lowercase() ?: "kt"}", false, "Main file"))
            }
        }

        zcp = zcp.copy(fileTree = fileTree, fileSpecs = fileSpecs, phase = InventPhase.PLANNING)
        InventStorage.deleteSearchLog(ctx, sessionId)
        addMessage("model1", plan, InventPhase.PLANNING)
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        loadModel2ForConfirmation()
    }

    // ── Phase 4a: Confirmation ─────────────────────────────────────────────

    private suspend fun loadModel2ForConfirmation() {
        val state = sessionState ?: return
        updatePhase(InventPhase.CONFIRMING)
        val targetPath = if (state.sameModelMode || state.model1Path == state.model2Path) state.model1Path else state.model2Path
        val targetName = if (state.sameModelMode || state.model1Path == state.model2Path) state.model1Name else state.model2Name

        setSwap("Loading $targetName (coder review)…")
        if (!loadOrKeepModel(targetPath)) {
            setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return
        }
        setSwap("")

        val understanding = runInference(
            systemPrompt = "You are a senior software engineer. Read the project plan and describe your full understanding — which files you'll build, the architecture, and any concerns.",
            userMessage = "Read this project spec and describe your understanding:\n\n${buildZcpSummaryForModel2()}",
            expectedModelPath = targetPath
        )
        addMessage("model2", understanding, InventPhase.CONFIRMING)
        _ui.value = _ui.value.copy(showSureButtons = true)
    }

    fun onSure() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showSureButtons = false)
            startFileGeneration()
        }
    }

    fun onNotSure() {
        if (_ui.value.mergeCount >= 2) {
            _ui.value = _ui.value.copy(error = "2 merge attempts reached. Tap New Session (+) to restart with different models or settings.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showSureButtons = false)
            val newMergeCount = _ui.value.mergeCount + 1
            // Don't reset to QUESTIONING — keep the existing plan and let user give targeted feedback
            zcp = zcp.copy(mergeCount = newMergeCount)
            _ui.value = _ui.value.copy(mergeCount = newMergeCount)
            addMessage("system", "The coder didn't fully understand the plan. Tell me what needs clarification — be specific about which files or architecture aspects to adjust.", InventPhase.CONFIRMING)
            // Reload planner to get refined understanding
            val state = sessionState ?: return@launch
            setSwap("Loading ${state.model1Name} to refine plan…")
            if (!loadOrKeepModel(state.model1Path)) {
                setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return@launch
            }
            setSwap("")
            // Let the user send a clarifying message next
            _ui.value = _ui.value.copy(showSureButtons = false)
            savedOriginalPaths.clear() // Reset so next mode switch captures fresh paths
        }
    }

    // ── Phase 4b: File Generation ──────────────────────────────────────────

    private fun estimateFileTokens(spec: FileSpec): Int {
        // Better heuristic: ~1 token per 4 chars (more accurate for code)
        val contentLen = spec.description.length + spec.imports.length + spec.classes.length + spec.functions.length
        val depsLen = spec.dependencies.sumOf { it.length }
        val overhead = 150 + spec.dependencies.size * 50
        return (contentLen + depsLen) / 4 + overhead
    }

    private suspend fun checkAndReplanIfNeeded(): Boolean {
        val state = sessionState ?: return false
        val filesToGenerate = zcp.fileTree.filter { !it.isDir }
        if (filesToGenerate.isEmpty()) return false

        val m2Path = if (state.sameModelMode || state.model1Path == state.model2Path) state.model1Path else state.model2Path
        val perModelCfg = SettingsManager.getModelTokenConfig(m2Path)
        val maxNew = perModelCfg?.maxNew?.coerceAtLeast(256) ?: SettingsManager.maxTokens.coerceAtLeast(256)
        val budget = (maxNew * 0.8).toInt()
        if (budget <= 128) return false

        val oversized = filesToGenerate.filter { node ->
            val spec = zcp.fileSpecs[node.path] ?: FileSpec(path = node.path, description = node.description)
            estimateFileTokens(spec) > budget
        }
        if (oversized.isEmpty()) return false

        updatePhase(InventPhase.REPLANNING)
        addMessage("system", "⚠ ${oversized.size} file(s) exceed coder's token budget. Splitting…", InventPhase.REPLANNING)
        setSwap("Replanning oversized files…")
        if (!loadOrKeepModel(state.model1Path)) {
            setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return false
        }
        setSwap("")

        val oversizedDetails = oversized.joinToString("\n\n") { node ->
            val spec = zcp.fileSpecs[node.path] ?: FileSpec(path = node.path, description = node.description)
            buildString {
                appendLine("[OVERSIZE] ${node.path}")
                appendLine("  Description: ${spec.description}")
                appendLine("  Imports: ${spec.imports}")
                appendLine("  Estimated tokens: ~${estimateFileTokens(spec)} (budget: $budget)")
            }
        }

        val replanPrompt = buildString {
            appendLine("Split these oversized files for the coder model (budget: $budget tokens per file):")
            appendLine()
            appendLine(oversizedDetails)
            appendLine()
            appendLine("For each, output a new §TREE and §FILEZCP with the split files.")
            appendLine("Use §TREE{path:X|type:file|desc:X} and §FILEZCP{path:X|description:X|imports:X|classes:X|functions:X|dependencies:Y,Z|estimatedTokens:N}")
        }

        val replanResult = runInference(
            systemPrompt = "You are a senior software architect. Split oversized files into smaller files. Output ONLY §TREE and §FILEZCP blocks.",
            userMessage = replanPrompt, expectedModelPath = state.model1Path
        )

        val newTree = parseFileTree(replanResult)
        val newSpecs = parseFileSpecs(replanResult)

        if (newTree.isEmpty()) {
            addMessage("system", "⚠ Replanning parse failed — continuing with original plan.", InventPhase.REPLANNING)
            withContext(Dispatchers.IO) { engineManager.unloadAll() }
            updatePhase(InventPhase.GENERATING); return false
        }

        val mergedSpecs = mutableMapOf<String, FileSpec>()
        newTree.filter { !it.isDir }.forEach { node ->
            mergedSpecs[node.path] = newSpecs[node.path] ?: zcp.fileSpecs[node.path] ?: FileSpec(path = node.path)
        }
        zcp.fileSpecs.forEach { (path, spec) ->
            if (newTree.any { it.path == path } && !mergedSpecs.containsKey(path)) mergedSpecs[path] = spec
        }

        zcp = zcp.copy(fileTree = newTree, fileSpecs = mergedSpecs)
        addMessage("model1", replanResult, InventPhase.REPLANNING)
        _ui.value = _ui.value.copy(fileTree = newTree, totalFiles = newTree.count { !it.isDir }, currentFileIndex = 0)
        sessionState = sessionState?.copy(totalFiles = newTree.count { !it.isDir }, currentFileIndex = 0)
        saveAllState()
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        updatePhase(InventPhase.GENERATING)
        return true
    }

    private suspend fun startFileGeneration() {
        val state = sessionState ?: return
        updatePhase(InventPhase.GENERATING)

        val filesToGenerate = zcp.fileTree.filter { !it.isDir }
        if (filesToGenerate.isEmpty()) { finishGeneration(); return }

        // Keep model loaded across all file generations
        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name

        _ui.value = _ui.value.copy(totalFiles = filesToGenerate.size, currentFileIndex = 0)
        sessionState = sessionState?.copy(totalFiles = filesToGenerate.size, currentFileIndex = 0)
        saveAllState()

        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
        zcp.fileTree.filter { it.isDir }.forEach { File(projectDir, it.path).mkdirs() }

        // Load coder model ONCE for all files
        setSwap("Loading $targetName…")
        if (!loadOrKeepModel(targetPath)) {
            setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return
        }
        enableSearchOnEngine()
        setSwap("")

        for ((idx, fileNode) in filesToGenerate.withIndex()) {
            if (_ui.value.phase != InventPhase.GENERATING) break

            val fileSpec = zcp.fileSpecs[fileNode.path] ?: FileSpec(path = fileNode.path, description = fileNode.description)

            _ui.value = _ui.value.copy(currentFileIndex = idx + 1, currentFileName = fileNode.path)
            sessionState = sessionState?.copy(currentFileIndex = idx + 1)
            saveAllState()

            // Check if search is needed BEFORE inference to avoid wasting first pass
            val preSearchResults = checkAndRunPreSearch(fileSpec)
            val generatedCode = generateCodeWithPreSearch(fileSpec, projectDir, preSearchResults)
            if (generatedCode == null) {
                setSwap(""); _ui.value = _ui.value.copy(error = "Failed to generate ${fileNode.path}"); return
            }

            InventStorage.writeGeneratedFile(projectDir, fileNode.path, generatedCode)
            zcp = zcp.copy(generatedFiles = zcp.generatedFiles + fileNode.path)
            sessionState = sessionState?.copy(totalFiles = filesToGenerate.size)
            saveAllState()
            addMessage("system", "✓ Generated ${fileNode.path} (${generatedCode.count { it == '\n' } + 1} lines)", InventPhase.GENERATING)
        }

        // Unload only after all files are done
        clearToolManagerOnEngines()
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        finishGeneration()
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
    private suspend fun generateCodeWithPreSearch(spec: FileSpec, projectDir: File, preSearchResults: String?): String? {
        val codeGenPrompt = buildCodeGenPrompt(spec, zcp, projectDir)
        val fullPrompt = if (preSearchResults != null) {
            "Web research for this file:\n$preSearchResults\n\n---\n\n$codeGenPrompt"
        } else codeGenPrompt

        return runInference(
            systemPrompt = "You are a senior software engineer. Output ONLY the code for this file. No explanations, no markdown.",
            userMessage = fullPrompt
        ).let { result ->
            val cleaned = result.replace(Regex("""\[SEARCH:\s*(.+?)]""", RegexOption.IGNORE_CASE), "").trim()
            cleaned.ifEmpty { null }
        }
    }

    private suspend fun resumeGeneration() {
        val state = sessionState ?: return
        val filesToGenerate = zcp.fileTree.filter { !it.isDir }
        val startFrom = _ui.value.currentFileIndex.coerceAtLeast(0)
        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name
        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)

        val remaining = filesToGenerate.drop(startFrom).filter { !zcp.generatedFiles.contains(it.path) }
        if (remaining.isNotEmpty()) {
            updatePhase(InventPhase.GENERATING)
            checkAndReplanIfNeeded()
        }

        val finalFiles = zcp.fileTree.filter { !it.isDir }
        val finalStartFrom = _ui.value.currentFileIndex.coerceAtLeast(0)
        _ui.value = _ui.value.copy(totalFiles = finalFiles.size)

        // Load model once for all files
        setSwap("Loading $targetName…")
        if (!loadOrKeepModel(targetPath)) {
            setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return
        }
        enableSearchOnEngine()
        setSwap("")

        for (idx in finalStartFrom until finalFiles.size) {
            val fileNode = finalFiles[idx]
            if (_ui.value.phase != InventPhase.GENERATING) break
            if (zcp.generatedFiles.contains(fileNode.path)) continue

            val fileSpec = zcp.fileSpecs[fileNode.path] ?: FileSpec(path = fileNode.path, description = fileNode.description)
            _ui.value = _ui.value.copy(currentFileIndex = idx + 1, currentFileName = fileNode.path)
            sessionState = sessionState?.copy(currentFileIndex = idx + 1)
            saveAllState()

            val preSearch = checkAndRunPreSearch(fileSpec)
            val code = generateCodeWithPreSearch(fileSpec, projectDir, preSearch)
            if (code == null) {
                setSwap(""); _ui.value = _ui.value.copy(error = "Failed to generate ${fileNode.path}"); return
            }

            InventStorage.writeGeneratedFile(projectDir, fileNode.path, code)
            zcp = zcp.copy(generatedFiles = zcp.generatedFiles + fileNode.path)
            saveAllState()
            addMessage("system", "✓ Generated ${fileNode.path} (${code.count { it == '\n' } + 1} lines)", InventPhase.GENERATING)
        }

        clearToolManagerOnEngines()
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        finishGeneration()
    }

    private suspend fun finishGeneration() {
        runFinalizeStep()
        zcp = zcp.copy(phase = InventPhase.DONE)
        updatePhase(InventPhase.DONE)
        computeStats()
        _ui.value = _ui.value.copy(zipReady = true)
        addMessage("system", "✓ All ${zcp.generatedFiles.size} files generated. Ready to export!", InventPhase.DONE)
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
            _ui.value = _ui.value.copy(debugMode = false)
            zcp = zcp.copy(phase = InventPhase.DONE)
            updatePhase(InventPhase.DONE)
            _ui.value = _ui.value.copy(zipReady = true)
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
        zcp = zcp.copy(debugSessions = zcp.debugSessions + DebugSession(
            filePath = filePath, problem = userText, originalCode = originalCode, fixedCode = cleanCode
        ))
        saveAllState()

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
            engineManager.unloadAll()
            _ui.value = InventUiState()
            sessionState = null; zcp = ZcpProtocol(); sessionId = ""
            savedOriginalPaths.clear()
            onDone()
        }
    }

    fun restartConversation() {
        // Keep session, models, and mode — just clear messages and reset phase
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Save the current conversation under the old session ID
            saveAllState()
            // 2. Assign a new session ID so the old one is preserved in history
            val newId = UUID.randomUUID().toString().take(8)
            sessionId = newId
            val s = _ui.value
            // 3. Reset UI state with new session ID (explicitly clear isGenerating)
            _ui.value = s.copy(
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
            // 4. Reset ZCP protocol and session state
            zcp = ZcpProtocol(offlineMode = s.offlineMode)
            sessionState = sessionState?.let { st ->
                st.copy(
                    sessionId = newId,
                    phase = InventPhase.QUESTIONING,
                    messages = emptyList(),
                    searchRound = 0, mergeCount = 0,
                    currentFileIndex = 0, totalFiles = 0
                )
            }
            // 5. Save the fresh empty state
            saveAllState()
        }
    }

    fun saveCurrentSession() { saveAllState() }

    fun onDeleteConfirmed() {
        viewModelScope.launch(Dispatchers.IO) {
            InventStorage.deleteSession(ctx, sessionId)
            engineManager.unloadAll()
            _ui.value = InventUiState()
        }
    }

    // ── Inference ──────────────────────────────────────────────────────────

    private suspend fun loadOrKeepModel(path: String): Boolean {
        val engine = engineManager.getActiveEngine()
        if (engine != null && engine.isModelLoaded && engine.loadedModelPath == path) {
            withContext(Dispatchers.IO) { engine.resetContext() }
            return true
        }
        return withContext(Dispatchers.IO) {
            try {
                engineManager.unloadAll()
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
        expectedModelPath: String? = null
    ): String = withContext(Dispatchers.IO) {
        _ui.value = _ui.value.copy(isGenerating = true)
        val sb = StringBuilder()

        if (expectedModelPath != null && !ensureEngineReady(expectedModelPath)) {
            if (!reloadEngineFor(expectedModelPath)) {
                _ui.value = _ui.value.copy(isGenerating = false, error = "Failed to load $expectedModelPath")
                return@withContext "[Failed to load model]"
            }
        }

        val (fullPrompt, compacted) = buildPromptWithInfo(systemPrompt, history, userMessage)
        checkCompactionAndNotify(history.size, compacted, _ui.value.phase)
        val engine = engineManager.getActiveEngine()

        if (engine == null) {
            _ui.value = _ui.value.copy(isGenerating = false)
            return@withContext "[No engine loaded]"
        }

        var streamedTokens = 0
        val callback = object : TokenCallback {
            override fun onToken(token: String) { sb.append(token) }
            override fun onDone() {
                val current = _ui.value.totalTokensUsed
                _ui.value = _ui.value.copy(totalTokensUsed = current + streamedTokens)
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
        }

        _ui.value = _ui.value.copy(isGenerating = false)
        sb.toString().trim()
    }

    // ── Prompt Builders ────────────────────────────────────────────────────

    private fun buildQuestioningPrompt(): String = """
You are a curious detective interviewing a client about their project.

MANDATORY — You MUST follow these rules:
1. After EVERY answer from the client, ALWAYS ask a follow-up question.
2. NEVER stop asking questions on your own. Only stop when the client presses the "Done" button.
3. Ask ONE short, natural question at a time.
4. Every response MUST end with a question mark. Do NOT summarize, conclude, or give advice.
5. Dig deeper: ask "why?", "how?", "who for?", "what platform?", etc.
6. Cover what it does, audience, platform, tech stack, key features, and tricky parts.

Reminder: You will write the full blueprint AFTER the client presses Done. For now, just ask questions.""".trimIndent()

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

    /** Token estimate: ~1 token per 4 chars for code/text (better than /3.5 for code). */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1) + 1

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
            "system" -> "<｜begin▁of▁sentence｜>"
            "user" -> "<｜User｜>"
            "assistant" -> "<｜Assistant｜>"
            else -> "<｜User｜>"
        }, when (role) {
            "assistant" -> "<｜User｜>"
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
            if (mutable.size < history.size) {
                val dropped = history.size - mutable.size
                mutable.add(0, "system" to "↕ $dropped earlier messages compacted — key info preserved")
            }
            compactedHistory = mutable
        }

        val prompt = buildString {
            if (template == "deepseek") {
                val (sysH, _) = formatRole(template, "system")
                append(sysH); appendLine(system)
                compactedHistory.forEach { (role, content) ->
                    val mappedRole = if (role == "user") "user" else if (role == "system") "system" else "assistant"
                    val (h, f) = formatRole(template, mappedRole)
                    append(h); appendLine(content.take(16_000)); if (f.isNotEmpty()) append(f)
                }
                val (uH, uF) = formatRole(template, "user")
                append(uH); appendLine(user.take(16_000)); if (uF.isNotEmpty()) append(uF)
                append(formatRole(template, "assistant").first)
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

    private fun parseSearchIntents(raw: String): List<SearchIntent> =
        Regex("§SEARCH\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2); p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            SearchIntent(kv["topic"] ?: "", kv["platform"] ?: "", kv["question"] ?: "", kv["category"] ?: "general")
        }.toList()

    private fun parseSearchResults(extracted: String, intents: List<SearchIntent>): List<SearchResult> =
        intents.mapIndexed { i, intent ->
            val content = Regex("SLOT_${i + 1}:\\s*(.+)", RegexOption.IGNORE_CASE)
                .find(extracted)?.groupValues?.get(1)?.trim() ?: ""
            SearchResult(intent, content, intent.category, true)
        }

    // ── Search ─────────────────────────────────────────────────────────────

    /** Actual web search using ToolManager — not domain homepages. */
    private suspend fun fetchSearchContent(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        zcp.searchIntents.forEach { intent ->
            val key = intent.category
            if (!result.containsKey(key)) {
                val query = intent.question.ifEmpty { "${intent.topic} ${intent.platform}" }
                if (query.isNotBlank()) {
                    val args = JSONObject().apply { put("query", query); put("num_results", 3) }
                    val call = ToolCall("search_${System.currentTimeMillis()}", "web_search", args)
                    try {
                        val res = toolManager.executeTool(call)
                        val text = res.result.trim()
                        result[key] = if (text.isNotBlank() && !text.startsWith("Error", true) &&
                            !text.startsWith("No results", true) && !text.startsWith("Web search failed", true)
                        ) text.take(3000) else "[No search results for: $query]"
                    } catch (e: Exception) { result[key] = "[Search failed: ${e.message}]" }
                } else result[key] = "[No query available]"
            }
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

    private fun addMessage(role: String, content: String, phase: InventPhase) {
        val updated = _ui.value.messages + InventMessage(role, content, phase)
        val started = _ui.value.chatStarted || role == "model1" || role == "model2" || role == "researcher"
        _ui.value = _ui.value.copy(messages = updated, chatStarted = started)
        sessionState = sessionState?.copy(messages = updated)
    }

    private fun updatePhase(phase: InventPhase) {
        _ui.value = _ui.value.copy(phase = phase)
        sessionState = sessionState?.copy(phase = phase)
        saveAllState()
    }

    private fun updateSearchRound(round: Int) {
        _ui.value = _ui.value.copy(searchRound = round)
        sessionState = sessionState?.copy(searchRound = round)
        saveAllState()
    }

    private fun setSwap(info: String) { _ui.value = _ui.value.copy(swapInfo = info) }

    override fun onCleared() {
        super.onCleared()
        kotlinx.coroutines.runBlocking {
            withTimeout(2000L) { withContext(Dispatchers.IO) { engineManager.unloadAll() } }
        }
    }
}
