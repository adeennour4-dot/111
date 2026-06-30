package com.gguf.zerocopy.ui.invent

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.invent.*
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.domain.invent.GgufMetaReader
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.TokenCallback
import com.gguf.zerocopy.domain.inference.ToolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    // Token usage tracking
    val totalTokensUsed: Int = 0,
    // Stats
    val totalLines: Int = 0,
    val totalGeneratedBytes: Long = 0,
    val debugSessionCount: Int = 0,
    // Session list
    val sessions: List<SessionInfo> = emptyList(),
    val showSessionList: Boolean = false,
    // Status bar
    val currentModelLabel: String = "",
    val processLabel: String = "",
    // Model loading states for Invent
    val plannerLoaded: Boolean = false,
    val researcherLoaded: Boolean = false,
    val coderLoaded: Boolean = false
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

    fun setShowDeleteConfirm(v: Boolean) { _ui.value = _ui.value.copy(showDeleteConfirm = v) }

    private var sessionState: InventSessionState? = null
    private var zcp: ZcpProtocol = ZcpProtocol()
    private var sessionId: String = ""

    /** Select a model tab (0=Planner, 1=Researcher, 2=Coder) and optionally load it. */
    fun selectModelTab(tab: Int) {
        val state = sessionState ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val path = when (tab) {
                0 -> state.model1Path
                1 -> state.researcherPath
                2 -> state.model2Path
                else -> return@launch
            }
            if (path.isNotEmpty()) {
                val ok = loadOrKeepModel(path)
                _ui.value = _ui.value.copy(
                    plannerLoaded = tab == 0 && ok,
                    researcherLoaded = tab == 1 && ok,
                    coderLoaded = tab == 2 && ok
                )
            }
        }
    }

    private fun clearToolManagerOnEngines() {
      engineManager.llamaCpp.setToolManager(null)
      engineManager.mnn.setToolManager(null)
      engineManager.liteRt.setToolManager(null)
    }

    /** Set a ToolManager on the active engine so it can use web search. */
    private fun enableSearchOnEngine() {
      val eng = engineManager.getActiveEngine()
      if (eng != null && eng.getToolManager() == null) {
        eng.setToolManager(toolManager)
      }
    }

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

    fun toggleSessionList() {
      if (!_ui.value.showSessionList) refreshSessionList()
      _ui.value = _ui.value.copy(showSessionList = !_ui.value.showSessionList)
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
            model1Name = saved.model1Name,
            model2Name = saved.model2Name,
            researcherName = saved.researcherName,
            offlineMode = saved.offlineMode,
            sameModelMode = saved.sameModelMode,
            fileTree = savedZcp.fileTree,
            searchRound = saved.searchRound,
            mergeCount = saved.mergeCount,
            currentFileIndex = saved.currentFileIndex,
            totalFiles = saved.totalFiles,
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
      var lines = 0L
      var bytes = 0L
      for (path in zcp.generatedFiles) {
        val content = InventStorage.readGeneratedFile(projectDir, path)
        if (content != null) {
          lines += content.count { it == '\n' } + 1
          bytes += content.length.toLong()
        }
      }
      _ui.value = _ui.value.copy(
        totalLines = lines.toInt(),
        totalGeneratedBytes = bytes,
        debugSessionCount = zcp.debugSessions.size
      )
    }

    private fun getProjectDir(): java.io.File {
        val sid = sessionId.ifEmpty { "_" }
        return InventStorage.getProjectDir(ctx, sid, zcp.projectName)
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    fun setupSession(
        model1Path: String, model1Name: String,
        model2Path: String, model2Name: String,
        researcherPath: String, researcherName: String,
        offlineMode: Boolean,
        sameModelMode: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            clearToolManagerOnEngines()

            // Find the most recent in-progress session (skip DONE/DEBUGGING for auto-load)
            val existing = InventStorage.listSessions(ctx)
                .mapNotNull { sid ->
                    val s = InventStorage.loadSession(ctx, sid)
                    if (s != null && s.phase != InventPhase.DONE && s.phase != InventPhase.DEBUGGING) sid to s else null
                }
                .sortedByDescending { (_, s) -> s.messages.lastOrNull()?.content?.length ?: 0 }
                .firstOrNull()?.first

            if (existing != null) {
                val saved = InventStorage.loadSession(ctx, existing)
                val savedZcp = InventStorage.loadZcp(ctx, existing)
                if (saved != null && savedZcp != null) {
                    sessionId = existing
                    sessionState = saved
                    zcp = savedZcp
                    _ui.value = _ui.value.copy(
                        phase = saved.phase,
                        messages = saved.messages,
                        sessionId = existing,
                        model1Name = saved.model1Name,
                        model2Name = saved.model2Name,
                        researcherName = saved.researcherName,
                        offlineMode = saved.offlineMode,
                        sameModelMode = saved.sameModelMode,
                        fileTree = savedZcp.fileTree,
                        searchRound = saved.searchRound,
                        mergeCount = saved.mergeCount,
                        currentFileIndex = saved.currentFileIndex,
                        totalFiles = saved.totalFiles,
                        debugMode = saved.phase == InventPhase.DEBUGGING,
                        zipReady = saved.phase == InventPhase.DONE || saved.phase == InventPhase.DEBUGGING
                    )
                    computeStats()
                    if (saved.phase == InventPhase.GENERATING) resumeGeneration()
                    if (saved.phase == InventPhase.FINALIZING) finishGeneration()
                    return@launch
                }
            }

            val m1Ctx = GgufMetaReader.readContextLength(model1Path).let { if (it <= 0) 2048 else it }
            val m2Ctx = if (sameModelMode) m1Ctx
                        else GgufMetaReader.readContextLength(model2Path).let { if (it <= 0) 2048 else it }

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
                sessionId = sessionId,
                phase = InventPhase.QUESTIONING,
                model1Path = model1Path,
                model1Name = model1Name,
                model2Path = if (sameModelMode) model1Path else model2Path,
                model2Name = if (sameModelMode) model1Name else model2Name,
                researcherPath = researcherPath,
                researcherName = researcherName,
                model1ContextSize = m1Ctx,
                model2ContextSize = m2Ctx,
                offlineMode = offlineMode,
                sameModelMode = sameModelMode
            )

            InventStorage.saveSession(ctx, sessionState!!)
            InventStorage.saveZcp(ctx, sessionId, zcp)

            _ui.value = _ui.value.copy(
                phase = InventPhase.QUESTIONING,
                sessionId = sessionId,
                model1Name = model1Name,
                model2Name = if (sameModelMode) model1Name else model2Name,
                researcherName = researcherName,
                offlineMode = offlineMode,
                sameModelMode = sameModelMode
            )

            startModel1Questioning()
        }
    }

    // ── Phase 1: Model 1 Questions ───────────────────────────────────────────

    private suspend fun startModel1Questioning() {
        val state = sessionState ?: return
        setSwap("Loading ${state.model1Name}…")
        val ok = loadOrKeepModel(state.model1Path)
        if (!ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return }
        _ui.value = _ui.value.copy(plannerLoaded = true)
        setSwap("")

        val firstQuestion = runInference(
            systemPrompt = buildQuestioningPrompt(),
            userMessage = "Hi! I want to build a software project and need your help planning it. Please start by asking me the first question — one question only."
        )
        addMessage("model1", firstQuestion, InventPhase.QUESTIONING)
    }

    fun sendUserMessage(text: String, planWithSearch: Boolean = false, thinkTag: Boolean = false) {
        if (_ui.value.isGenerating) return
        val processed = buildString {
            append(text)
            if (planWithSearch) append("\n[SEARCH enabled]")
            if (thinkTag) append("\n[THINK]")
        }
        addMessage("user", processed, _ui.value.phase)
        viewModelScope.launch(Dispatchers.IO) {
            when (_ui.value.phase) {
                InventPhase.QUESTIONING -> handleQuestioningReply(text)
                InventPhase.DEBUGGING -> handleDebuggingReply(text)
                else -> {}
            }
        }
    }

    private suspend fun handleQuestioningReply(userText: String) {
        val history = buildConversationHistory()
        val response = runInference(
            systemPrompt = buildQuestioningPrompt(),
            userMessage = userText,
            history = history
        )

        val lowerResp = response.lowercase()
        val isDone = response.contains("[INFO_COMPLETE]", ignoreCase = true) ||
            response.contains("[READY_TO_SEARCH]", ignoreCase = true) ||
            (lowerResp.contains("have all") && lowerResp.contains("information")) ||
            (lowerResp.contains("ready to") && (lowerResp.contains("plan") || lowerResp.contains("search")))

        val cleaned = response
            .replace("[INFO_COMPLETE]", "", ignoreCase = true)
            .replace("[READY_TO_SEARCH]", "", ignoreCase = true)
            .trim()

        addMessage("model1", cleaned, InventPhase.QUESTIONING)

        if (isDone) triggerSearchPhase()
    }

    fun onSearchButtonPressed() {
        if (_ui.value.isGenerating) return
        viewModelScope.launch(Dispatchers.IO) { triggerSearchPhase() }
    }

    // ── Phase 2: Write ZCP + Search ──────────────────────────────────────────

    private suspend fun triggerSearchPhase() {
        updatePhase(InventPhase.SEARCHING)
        val state = sessionState ?: return

        val zcpRaw = runInference(
            systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
            userMessage = "Based on everything we discussed, write the complete ZCP protocol now. Include §APP, §IDEA, §VIABLE, all §SEARCH intents, and §TREE blocks.",
            history = buildConversationHistory()
        )

        zcp = parseZcpFromModel1(zcpRaw, zcp)
        InventStorage.saveZcp(ctx, sessionId, zcp)
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
            val researcherOk = loadOrKeepModel(state.researcherPath)
            if (!researcherOk) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load researcher"); return }
            setSwap("")

            val extracted = runInference(
                systemPrompt = "You are a precise information extractor. Fill given slots with exact values from the provided content. Output ONLY slot:value pairs. No explanations.",
                userMessage = buildResearcherPrompt(fetchedContent, zcp.searchIntents)
            )
            InventStorage.saveSearchLog(ctx, sessionId, extracted)

            setSwap("Loading ${state.model1Name} to review results…")
            val model1Ok = loadOrKeepModel(state.model1Path)
            if (!model1Ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return }
            setSwap("")

            val reviewResponse = runInference(
                systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
                userMessage = "Search results:\n$extracted\n\nDo you have all info needed? If yes output [SEARCH_DONE]. If not, output new §SEARCH blocks only."
            )

            if (reviewResponse.contains("[SEARCH_DONE]", ignoreCase = true) || round >= maxRounds) {
                zcp = zcp.copy(searchResults = parseSearchResults(extracted, zcp.searchIntents))
                InventStorage.saveZcp(ctx, sessionId, zcp)
                withContext(Dispatchers.IO) { engineManager.unloadAll() }
                startFilePlanning()
                break
            } else {
                val newIntents = parseSearchIntents(reviewResponse)
                if (newIntents.isNotEmpty()) {
                    zcp = zcp.copy(searchIntents = zcp.searchIntents + newIntents)
                    InventStorage.saveZcp(ctx, sessionId, zcp)
                }
                withContext(Dispatchers.IO) { engineManager.unloadAll() }
            }
        }
    }

    // ── Phase 3: File-by-File ZCP Planning ────────────────────────────────────

    private suspend fun startFilePlanning() {
        val state = sessionState ?: return
        updatePhase(InventPhase.PLANNING)
        setSwap("Loading ${state.model1Name} for planning…")
        val ok = loadOrKeepModel(state.model1Path)
        if (!ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name} for planning"); return }
        setSwap("")

        val usableCtx = (zcp.model2ContextSize * 0.7).toInt()
        val plan = runInference(
            systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
            userMessage = "You have all information. First write the complete project file tree using §TREE blocks. Then for EACH file (not directory), write a §FILEZCP block describing what that file should contain — its imports, classes, functions, and how it connects to other files.\n\nFormat:\n§TREE{path:X|type:dir/file|desc:X}\n§FILEZCP{path:X|description:X|imports:X|classes:X|functions:X|dependencies:Y,Z}\n\nEach §FILEZCP must be self-contained so a separate coder model can implement it independently."
        )

        val fileTree = parseFileTree(plan)
        val fileSpecs = parseFileSpecs(plan)
        zcp = zcp.copy(fileTree = fileTree, fileSpecs = fileSpecs, phase = InventPhase.PLANNING)
        InventStorage.saveZcp(ctx, sessionId, zcp)
        InventStorage.deleteSearchLog(ctx, sessionId)

        addMessage("model1", plan, InventPhase.PLANNING)
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        loadModel2ForConfirmation()
    }

    // ── Phase 4a: Model 2 Confirms Plan ──────────────────────────────────────

    private suspend fun loadModel2ForConfirmation() {
        val state = sessionState ?: return
        updatePhase(InventPhase.CONFIRMING)

        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name

        setSwap("Loading $targetName (coder review)…")
        val ok = loadOrKeepModel(targetPath)
        if (!ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return }
        setSwap("")

        val understanding = runInference(
            systemPrompt = "You are a senior software engineer. Read the project plan and describe your full understanding — which files you'll build, the architecture, and any concerns.",
            userMessage = "Read this project spec and describe your understanding:\n\n${buildZcpSummaryForModel2()}"
        )

        addMessage("model2", understanding, InventPhase.CONFIRMING)
        _ui.value = _ui.value.copy(showSureButtons = true)
        saveCurrentState()
    }

    fun onSure() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showSureButtons = false)
            startFileGeneration()
        }
    }

    fun onNotSure() {
        if (_ui.value.mergeCount >= 2) {
            _ui.value = _ui.value.copy(error = "2 merge attempts reached. Consider starting fresh.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showSureButtons = false)
            val newMergeCount = _ui.value.mergeCount + 1
            zcp = zcp.copy(
                phase = InventPhase.QUESTIONING,
                mergeCount = newMergeCount,
                searchResults = emptyList(),
                fileTree = emptyList(),
                chunks = emptyList(),
                fileSpecs = emptyMap(),
                generatedFiles = emptyList()
            )
            InventStorage.saveZcp(ctx, sessionId, zcp)
            _ui.value = _ui.value.copy(
                phase = InventPhase.QUESTIONING,
                mergeCount = newMergeCount,
                fileTree = emptyList()
            )
            addMessage("system", "Let's refine the plan. Tell me what needs to change.", InventPhase.QUESTIONING)
        }
    }

    // ── Phase 4b: File-by-File Code Generation ────────────────────────────────

    /** Rough token estimate: ~1 token per 4 chars, plus overhead per field. */
    private fun estimateFileTokens(spec: FileSpec): Int {
        val base = spec.description.length / 4
        val imports = spec.imports.length / 4
        val classes = spec.classes.length / 4
        val funcs = spec.functions.length / 4
        val deps = spec.dependencies.sumOf { it.length / 4 }
        // Prompt overhead: ~120 tokens for the code gen template
        // Dependency summaries: ~40 tokens per dependency
        val overhead = 120 + spec.dependencies.size * 40
        return base + imports + classes + funcs + deps + overhead
    }

    /**
     * Before generating, check if any file spec exceeds Model 2's token budget.
     * If so, enter REPLANNING: load Model 1 to split the oversized file into
     * smaller files that together implement the same functionality.
     */
    private suspend fun checkAndReplanIfNeeded(): Boolean {
        val state = sessionState ?: return false
        val filesToGenerate = zcp.fileTree.filter { !it.isDir }
        if (filesToGenerate.isEmpty()) return false

        // Get Model 2's effective maxNewTokens
        val m2Path = if (state.sameModelMode || state.model1Path == state.model2Path)
            state.model1Path else state.model2Path
        val perModelCfg = SettingsManager.getModelTokenConfig(m2Path)
        val maxNew = perModelCfg?.maxNew ?: SettingsManager.maxTokens
        // Safety margin: leave 256 tokens for the code gen prompt
        val budget = maxNew - 256
        if (budget <= 128) return false

        // Find files that exceed the budget
        val oversized = filesToGenerate.filter { node ->
            val spec = zcp.fileSpecs[node.path] ?: FileSpec(path = node.path, description = node.description)
            val est = estimateFileTokens(spec)
            est > budget
        }

        if (oversized.isEmpty()) return false

        // Enter replanning phase
        updatePhase(InventPhase.REPLANNING)
        val oversizedPaths = oversized.joinToString(", ") { it.path }
        addMessage("system", "⚠ Some files exceed Model 2's token budget (max $maxNew tokens). Replanning: $oversizedPaths", InventPhase.REPLANNING)

        setSwap("Replanning oversized files…")
        val ok = loadOrKeepModel(state.model1Path)
        if (!ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name} for replanning"); return false }
        setSwap("")

        // Build a focused prompt for Model 1: show the oversized file spec and ask for splits
        val oversizedDetails = oversized.joinToString("\n\n") { node ->
            val spec = zcp.fileSpecs[node.path] ?: FileSpec(path = node.path, description = node.description)
            """
[OVERSIZE] ${node.path}
  Description: ${spec.description}
  Imports: ${spec.imports}
  Classes: ${spec.classes}
  Functions: ${spec.functions}
  Dependencies: ${spec.dependencies.joinToString(", ")}
  Estimated tokens: ${estimateFileTokens(spec)} (budget: $budget)
""".trimIndent()
        }

        val smallFiles = filesToGenerate.filter { node ->
            val spec = zcp.fileSpecs[node.path] ?: FileSpec(path = node.path, description = node.description)
            estimateFileTokens(spec) <= budget
        }
        val smallSummary = smallFiles.joinToString("\n") { "[OK] ${it.path}" }

        val replanPrompt = """
The following files in the project are too large for the coder model to generate in one pass.
The coder can generate at most $budget tokens per file (context limit: $maxNew tokens).

OVERSIZED FILES (need splitting):
$oversizedDetails

FILES THAT FIT (leave unchanged):
$smallSummary

Current file tree:
${zcp.fileTree.joinToString("\n") { "  ${if (it.isDir) "[DIR]" else "[FILE]"} ${it.path}" }}

For EACH oversized file above, split it into smaller files that together implement
the same functionality. Output the COMPLETE new file tree including ALL files
(small + unchanged + new split files). Then output a §FILEZCP block for EVERY
file in the tree (including unchanged ones — copy their existing spec).

Use exactly these formats:
§TREE{path:X|type:dir/file|desc:X}
§FILEZCP{path:X|description:X|imports:X|classes:X|functions:X|dependencies:Y,Z|estimatedTokens:N}

IMPORTANT: The estimatedTokens field should reflect the NEW estimated size of each split file.
Each split file must be under $budget tokens.
""".trimIndent()

        val replanResult = runInference(
            systemPrompt = "You are a senior software architect. Split oversized files into smaller, focused files that each fit within the coder's token budget. Output ONLY the ZCP file tree and specs.",
            userMessage = replanPrompt,
            expectedModelPath = state.model1Path
        )

        // Parse the new file tree and specs
        val newTree = parseFileTree(replanResult)
        val newSpecs = parseFileSpecs(replanResult)

        if (newTree.isEmpty()) {
            // Parsing failed — log and continue with original plan
            addMessage("system", "⚠ Replanning failed to produce a valid file tree — continuing with original plan.", InventPhase.REPLANNING)
            withContext(Dispatchers.IO) { engineManager.unloadAll() }
            updatePhase(InventPhase.GENERATING)
            return false
        }

        // Merge: keep old specs for files that weren't changed, use new specs for everything
        val mergedSpecs = mutableMapOf<String, FileSpec>()
        newTree.filter { !it.isDir }.forEach { node ->
            val newSpec = newSpecs[node.path]
            if (newSpec != null) {
                mergedSpecs[node.path] = newSpec
            } else {
                // Keep old spec if it exists and wasn't mentioned in the replan
                val oldSpec = zcp.fileSpecs[node.path]
                if (oldSpec != null) mergedSpecs[node.path] = oldSpec
            }
        }
        // Also copy over any old specs for files that are still in the tree but not in newSpecs
        zcp.fileSpecs.forEach { (path, spec) ->
            if (newTree.any { it.path == path } && !mergedSpecs.containsKey(path)) {
                mergedSpecs[path] = spec
            }
        }

        zcp = zcp.copy(fileTree = newTree, fileSpecs = mergedSpecs)
        InventStorage.saveZcp(ctx, sessionId, zcp)

        addMessage("model1", replanResult, InventPhase.REPLANNING)
        addMessage("system", "✅ File tree updated: ${newTree.count { !it.isDir }} files (${oversized.size} split, ${smallFiles.size} kept)", InventPhase.REPLANNING)

        // Update UI
        _ui.value = _ui.value.copy(
            fileTree = newTree,
            totalFiles = newTree.count { !it.isDir },
            currentFileIndex = 0
        )
        sessionState = sessionState?.copy(
            totalFiles = newTree.count { !it.isDir },
            currentFileIndex = 0
        )
        saveCurrentState()

        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        updatePhase(InventPhase.GENERATING)
        return true
    }

    private suspend fun startFileGeneration() {
        val state = sessionState ?: return
        updatePhase(InventPhase.GENERATING)

        val filesToGenerate = zcp.fileTree.filter { !it.isDir }
        if (filesToGenerate.isEmpty()) { finishGeneration(); return }

        // Re-read after possible replanning (simple check, skip if already generated)
        val finalFiles = zcp.fileTree.filter { !it.isDir }
        if (finalFiles.isEmpty()) { finishGeneration(); return }

        val totalFiles = finalFiles.size
        sessionState = sessionState?.copy(totalFiles = totalFiles, currentFileIndex = 0)
        saveCurrentState()
        _ui.value = _ui.value.copy(totalFiles = totalFiles, currentFileIndex = 0, fileTree = zcp.fileTree)

        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name

        // First, create all directories from the file tree
        zcp.fileTree.filter { it.isDir }.forEach { node ->
          File(projectDir, node.path).mkdirs()
        }

        for ((idx, fileNode) in finalFiles.withIndex()) {
            if (_ui.value.phase != InventPhase.GENERATING) break

            val fileSpec = zcp.fileSpecs[fileNode.path] ?: FileSpec(path = fileNode.path, description = fileNode.description)

            sessionState = sessionState?.copy(currentFileIndex = idx + 1)
            _ui.value = _ui.value.copy(currentFileIndex = idx + 1, currentFileName = fileNode.path)
            saveCurrentState()

            // Load Model 2 with FRESH context + search enabled
            setSwap("Generating ${fileNode.path} (${idx + 1}/$totalFiles)…")
            val m2Ok = loadOrKeepModel(targetPath)
            if (!m2Ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return }

            // Enable web search for Model 2 (coder)
            enableSearchOnEngine()

            val generatedCode = generateCodeWithSearch(fileSpec, projectDir)
            if (generatedCode == null) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to generate ${fileNode.path}"); return }

            // Save generated code (content on disk, only path in ZCP)
            InventStorage.writeGeneratedFile(projectDir, fileNode.path, generatedCode)
            zcp = zcp.copy(generatedFiles = zcp.generatedFiles + fileNode.path)
            InventStorage.saveZcp(ctx, sessionId, zcp)

            // Clear search + unload Model 2
            clearToolManagerOnEngines()
            withContext(Dispatchers.IO) { engineManager.unloadAll() }

            addMessage("system", "✓ Generated ${fileNode.path} (${generatedCode.count { it == '\n' } + 1} lines)", InventPhase.GENERATING)
        }

        finishGeneration()
    }

    /**
     * Run Model 2 inference with search capability.
     * If the model outputs [SEARCH: query], we run the web search,
     * inject results, and re-run until no more search markers.
     */
    private suspend fun generateCodeWithSearch(spec: FileSpec, projectDir: java.io.File): String? {
        val state = sessionState ?: return null
        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name

        val codeGenPrompt = buildCodeGenPrompt(spec, zcp)

        // First attempt
        var result = runInference(
            systemPrompt = "You are a senior software engineer. You have web search available — use [SEARCH: your query] if you need to look up APIs, syntax, or libraries. After searching, continue generating code. Output ONLY the code for this file. No explanations.",
            userMessage = codeGenPrompt,
            expectedModelPath = targetPath
        )

        // Handle search markers (up to 3 rounds)
        var searchRounds = 0
        val searchMarker = Regex("""\[SEARCH:\s*(.+?)]""", RegexOption.IGNORE_CASE)
        while (searchRounds < 3) {
            val match = searchMarker.find(result)
            if (match == null) break
            searchRounds++

            val query = match.groupValues[1].trim()
            setSwap("Coder searching: $query…")

            val searchResults = runWebSearch(query)
            if (searchResults == null) {
                // Search failed, remove marker and continue
                result = result.replace(match.value, "")
                continue
            }

            // Re-run with search results injected into the prompt
            val searchAugmentedPrompt = buildString {
                appendLine("Web search results for \"$query\":")
                appendLine(searchResults)
                appendLine()
                appendLine("---")
                appendLine()
                appendLine(codeGenPrompt)
            }

            val m2Ok = loadOrKeepModel(targetPath)
            if (!m2Ok) return null
            enableSearchOnEngine()

            result = runInference(
                systemPrompt = "You are a senior software engineer. Use the web search results above to write production-ready code. Output ONLY the code for this file. No explanations.",
                userMessage = searchAugmentedPrompt,
                expectedModelPath = targetPath
            )
        }

        // Remove any remaining search markers from output
        result = result.replace(searchMarker, "").trim()
        return result.ifEmpty { null }
    }

    private fun runWebSearch(query: String): String? {
        val tm = ZeroCopyApp.instance.toolManager
        val args = org.json.JSONObject().apply {
            put("query", query)
            put("num_results", 3)
        }
        val call = com.gguf.zerocopy.domain.inference.ToolCall("invent_${System.currentTimeMillis()}", "web_search", args)
        return try {
            val res = tm.executeTool(call)
            val text = res.result.trim()
            if (text.isBlank() || text.startsWith("Error", true) || text.startsWith("No results", true)) null
            else text
        } catch (e: Exception) { null }
    }

    private suspend fun resumeGeneration() {
        val filesToGenerate = zcp.fileTree.filter { !it.isDir }
        val startFrom = _ui.value.currentFileIndex.coerceAtLeast(0)
        val state = sessionState ?: return
        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name
        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)

        // Check remaining files for replanning
        val remaining = filesToGenerate.drop(startFrom).filter { !zcp.generatedFiles.contains(it.path) }
        if (remaining.isNotEmpty()) {
            updatePhase(InventPhase.GENERATING)
            val replanned = checkAndReplanIfNeeded()
            if (!replanned && _ui.value.phase != InventPhase.GENERATING) return
        }

        // Re-read after possible replanning
        val finalFiles = zcp.fileTree.filter { !it.isDir }
        val finalStartFrom = _ui.value.currentFileIndex.coerceAtLeast(0)
        val totalFiles = finalFiles.size
        _ui.value = _ui.value.copy(totalFiles = totalFiles)

        for (idx in finalStartFrom until finalFiles.size) {
            val fileNode = finalFiles[idx]
            if (_ui.value.phase != InventPhase.GENERATING) break
            if (zcp.generatedFiles.contains(fileNode.path)) continue

            val fileSpec = zcp.fileSpecs[fileNode.path] ?: FileSpec(path = fileNode.path, description = fileNode.description)
            sessionState = sessionState?.copy(currentFileIndex = idx + 1)
            _ui.value = _ui.value.copy(currentFileIndex = idx + 1, currentFileName = fileNode.path)
            saveCurrentState()

            setSwap("Generating ${fileNode.path} (${idx + 1}/$totalFiles)…")
            val m2Ok = loadOrKeepModel(targetPath)
            if (!m2Ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return }
            enableSearchOnEngine()

            val generatedCode = generateCodeWithSearch(fileSpec, projectDir)
            if (generatedCode == null) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to generate ${fileNode.path}"); return }

            InventStorage.writeGeneratedFile(projectDir, fileNode.path, generatedCode)
            zcp = zcp.copy(generatedFiles = zcp.generatedFiles + fileNode.path)
            InventStorage.saveZcp(ctx, sessionId, zcp)

            clearToolManagerOnEngines()
            withContext(Dispatchers.IO) { engineManager.unloadAll() }
            addMessage("system", "✓ Generated ${fileNode.path} (${generatedCode.count { it == '\n' } + 1} lines)", InventPhase.GENERATING)
        }
        finishGeneration()
    }

    private suspend fun finishGeneration() {
        runFinalizeStep()
        zcp = zcp.copy(phase = InventPhase.DONE)
        InventStorage.saveZcp(ctx, sessionId, zcp)
        updatePhase(InventPhase.DONE)
        computeStats()
        _ui.value = _ui.value.copy(zipReady = true)
        addMessage("system", "✓ All ${zcp.generatedFiles.size} files generated. Ready to export .zip!", InventPhase.DONE)
    }


    private suspend fun runFinalizeStep() {
        val state = sessionState ?: return
        if (zcp.generatedFiles.isEmpty()) return
        updatePhase(InventPhase.FINALIZING)
        _ui.value = _ui.value.copy(currentModelLabel = state.model1Name, processLabel = "Reading project…")
        setSwap("Loading ${state.model1Name} for final review…")
        val ok = loadOrKeepModel(state.model1Path)
        if (!ok) { setSwap(""); _ui.value = _ui.value.copy(currentModelLabel = "", processLabel = ""); return }
        setSwap("")
        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
        val filePaths = zcp.generatedFiles.sorted().filter { it != "README.txt" }
        val summaries = mutableListOf<String>()
        for ((idx, path) in filePaths.withIndex()) {
            _ui.value = _ui.value.copy(processLabel = "Reading $path (${idx + 1}/${filePaths.size})…")
            val content = InventStorage.readGeneratedFile(projectDir, path) ?: continue
            val preview = content.take(2000)
            val lineCount = content.count { it == '\n' } + 1
            val kvBefore = engineManager.getActiveEngine()?.getKvUsage() ?: 0
            if (kvBefore > 80 && summaries.isNotEmpty()) {
                val summaryRestore = summaries.joinToString("\n")
                addMessage("system", "[Context at ${kvBefore}% — compacting. Retaining ${summaries.size} file summaries.]", InventPhase.FINALIZING)
                resetEngineContext(state.model1Path)
                runInference(systemPrompt = "You have summarized these files so far:\n$summaryRestore\n\nContinue reading.", userMessage = "Resuming. Next file to read: $path", expectedModelPath = state.model1Path)
                continue
            }
            val readPrompt = buildString {
                appendLine("Read this file and output \$FILE_READ\$ plus one summary line.")
                appendLine("--- $path ($lineCount lines) ---")
                appendLine(preview)
                if (content.length > 2000) appendLine("...[truncated, full content on disk]")
            }
            val response = runInference(
                systemPrompt = "Read the file and output \$FILE_READ\$ plus one summary line.",
                userMessage = readPrompt,
                expectedModelPath = state.model1Path
            )
            val summaryLine = response.takeLast(200).replace(Regex("\\\$FILE_READ\\\$"), "").trim().take(150)
            summaries.add("$path: $summaryLine")
        }
        // After reading all files, research build instructions and write README
        _ui.value = _ui.value.copy(processLabel = "Researching build instructions…")
        val platform = zcp.platform.joinToString(", ").ifEmpty { "general" }
        val language = zcp.language.joinToString(", ").ifEmpty { "unknown" }
        val framework = zcp.framework.ifEmpty { "none" }
        val searchQuery = "How to build and compile $language $framework project for $platform"
        setSwap("Researching build instructions…")
        val buildInstructions = runWebSearch(searchQuery) ?: "No web search results available."
        setSwap("")
        // Write README
        _ui.value = _ui.value.copy(processLabel = "Writing README.txt…")
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
            appendLine("--- Files Generated ---")
            filePaths.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("--- File Summaries ---")
            summaries.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("--- How to Build ---")
            appendLine(buildInstructions.take(3000))
            appendLine()
            appendLine("--- Prerequisites ---")
            appendLine("You may need to download the following:")
            if (language.contains("kotlin", true) || language.contains("java", true) || platform.contains("android", true)) {
                appendLine("  - Android Studio (developer.android.com/studio)")
                appendLine("  - Android SDK (bundled with Android Studio)")
                appendLine("  - JDK 17+")
            }
            if (language.contains("python", true)) {
                appendLine("  - Python 3.10+ (python.org)")
                appendLine("  - pip install -r requirements.txt (if provided)")
            }
            if (language.contains("javascript", true) || language.contains("typescript", true)) {
                appendLine("  - Node.js 18+ (nodejs.org)")
                appendLine("  - npm install (then npm run build)")
            }
            if (framework.contains("flutter", true)) {
                appendLine("  - Flutter SDK (flutter.dev)")
                appendLine("  - Dart SDK (bundled with Flutter)")
            }
            if (framework.contains("react", true)) {
                appendLine("  - Node.js 18+")
                appendLine("  - npm install && npm start")
            }
            appendLine()
            appendLine("--- Quick Start ---")
            appendLine("1. Install prerequisites listed above")
            appendLine("2. Open a terminal in this directory")
            appendLine("3. Follow the build instructions for your specific platform")
            appendLine("4. Refer to the individual file summaries for architecture details")
            appendLine()
            appendLine("Generated by ZeroCopy Invent — adeennour4-dot/111")
        }
        InventStorage.writeGeneratedFile(projectDir, "README.txt", readmeContent)
        if (!zcp.generatedFiles.contains("README.txt")) {
            zcp = zcp.copy(generatedFiles = zcp.generatedFiles + "README.txt")
        }
        InventStorage.saveZcp(ctx, sessionId, zcp)
        addMessage("system", "✅ README.txt written with build instructions for $platform", InventPhase.FINALIZING)
        _ui.value = _ui.value.copy(currentModelLabel = "", processLabel = "")
        withContext(Dispatchers.IO) { engineManager.unloadAll() }
    }

    private fun resetEngineContext(modelPath: String) {
        try {
            engineManager.getActiveEngine()?.resetContext()
        } catch (_: Exception) {}
    }
    // ── Phase 5: Export .ZIP ─────────────────────────────────────────────────

    fun exportProjectZip(): java.io.File? {
        if (_ui.value.phase != InventPhase.DONE && _ui.value.phase != InventPhase.DEBUGGING) return null
        return try {
            val projectName = zcp.projectName.ifEmpty { sessionId }.ifEmpty { "invent_project" }
            val zipDir = java.io.File(ctx.cacheDir, "invent_exports").also { it.mkdirs() }
            val zipFile = java.io.File(zipDir, "${projectName}.zip")

            val projectDir = getProjectDir()
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
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

                // All generated files with actual code (read from disk)
                for (path in zcp.generatedFiles) {
                    val code = InventStorage.readGeneratedFile(projectDir, path)
                    if (code != null) {
                        zos.putNextEntry(ZipEntry(path))
                        zos.write(code.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }
            }
            zipFile
        } catch (e: Exception) {
            android.util.Log.e("InventViewModel", "Failed to create zip: ${e.message}")
            null
        }
    }

    private fun buildZcpExportJson(): String {
        return org.json.JSONObject().apply {
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
                treeArr.put(org.json.JSONObject().apply {
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
                    debugArr.put(org.json.JSONObject().apply {
                        put("filePath", ds.filePath)
                        put("problem", ds.problem)
                        put("timestamp", ds.timestamp)
                    })
                }
                put("debugSessions", debugArr)
            }
        }.toString(2)
    }

    // ── Phase 6: Debugging ───────────────────────────────────────────────────

    fun startDebugging() {
        viewModelScope.launch(Dispatchers.IO) {
            updatePhase(InventPhase.DEBUGGING)
            _ui.value = _ui.value.copy(debugMode = true)

            val state = sessionState ?: return@launch
            setSwap("Loading ${state.model1Name}…")
            val ok = loadOrKeepModel(state.model1Path)
            if (!ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}"); return@launch }
            setSwap("")

            addMessage("model1", "I'm ready for debugging. Tell me which file has an issue and describe the problem.", InventPhase.DEBUGGING)
        }
    }

    fun exitDebugging() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(debugMode = false)
            zcp = zcp.copy(phase = InventPhase.DONE)
            InventStorage.saveZcp(ctx, sessionId, zcp)
            updatePhase(InventPhase.DONE)
            _ui.value = _ui.value.copy(zipReady = true)
            computeStats()
            addMessage("system", "✓ Debugging complete. Export .zip to get the fixed files.", InventPhase.DONE)
        }
    }

    private suspend fun handleDebuggingReply(userText: String) {
        val state = sessionState ?: return
        val history = buildConversationHistory()

        val diagnosis = runInference(
            systemPrompt = "You are a debugging assistant. Identify which file has the bug and what needs to change. Use §FILE{path:X} to specify the file. If the user says 'done' or 'exit', just say [DEBUG_DONE].",
            userMessage = userText,
            history = history
        )

        if (diagnosis.contains("[DEBUG_DONE]", ignoreCase = true)) {
            exitDebugging()
            return
        }

        addMessage("model1", diagnosis, InventPhase.DEBUGGING)

        val filePath = Regex("§FILE\\{path:([^}]+)\\}").find(diagnosis)?.groupValues?.get(1)?.trim()
        if (filePath == null || !zcp.generatedFiles.contains(filePath)) {
            addMessage("system", "Could not identify which file to fix. Use the exact file path.", InventPhase.DEBUGGING)
            return
        }

        val projectDir = InventStorage.getProjectDir(ctx, sessionId, zcp.projectName)
        val originalCode = InventStorage.readGeneratedFile(projectDir, filePath) ?: ""
        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath = if (!isSame) state.model2Path else state.model1Path
        val targetName = if (!isSame) state.model2Name else state.model1Name

        setSwap("Loading $targetName to fix $filePath…")
        val m2Ok = loadOrKeepModel(targetPath)
        if (!m2Ok) { setSwap(""); _ui.value = _ui.value.copy(error = "Failed to load $targetName"); return }
        enableSearchOnEngine()

        val fixPrompt = buildString {
            appendLine("Fix the following bug in this file.")
            appendLine("Problem: $userText")
            appendLine()
            appendLine("Current code:")
            appendLine("```")
            appendLine(originalCode)
            appendLine("```")
            appendLine()
            appendLine("You have web search available — use [SEARCH: query] if you need to look up anything.")
            appendLine("Output ONLY the fixed code. No explanations.")
        }

        val fixedCode = runInference(
            systemPrompt = "You are a senior software engineer fixing a bug. Use web search if needed via [SEARCH: query]. Output ONLY the corrected code.",
            userMessage = fixPrompt,
            expectedModelPath = targetPath
        )

        // Handle any search markers in the fix response
        val searchMarker = Regex("""\[SEARCH:\s*(.+?)]""", RegexOption.IGNORE_CASE)
        val finalCode = if (searchMarker.containsMatchIn(fixedCode)) {
            val query = searchMarker.find(fixedCode)!!.groupValues[1].trim()
            val searchResults = runWebSearch(query)
            if (searchResults != null) {
                val redoPrompt = buildString {
                    appendLine("Web search results for \"$query\":")
                    appendLine(searchResults)
                    appendLine()
                    appendLine("---")
                    appendLine()
                    appendLine(fixPrompt)
                }
                runInference(
                    systemPrompt = "You are a senior software engineer fixing a bug. Use the search results to write the fix. Output ONLY the corrected code.",
                    userMessage = redoPrompt,
                    expectedModelPath = targetPath
                ).replace(searchMarker, "").trim()
            } else {
                fixedCode.replace(searchMarker, "").trim()
            }
        } else {
            fixedCode.trim()
        }

        InventStorage.writeGeneratedFile(projectDir, filePath, finalCode)
        if (!zcp.generatedFiles.contains(filePath)) {
            zcp = zcp.copy(generatedFiles = zcp.generatedFiles + filePath)
        }
        zcp = zcp.copy(
            debugSessions = zcp.debugSessions + com.gguf.zerocopy.data.invent.DebugSession(
                filePath = filePath,
                problem = userText,
                originalCode = originalCode,
                fixedCode = finalCode
            )
        )
        InventStorage.saveZcp(ctx, sessionId, zcp)
        InventStorage.writeGeneratedFile(projectDir, filePath, finalCode)

        withContext(Dispatchers.IO) { engineManager.unloadAll() }
        clearToolManagerOnEngines()
        computeStats()

        setSwap("Loading ${state.model1Name}…")
        loadOrKeepModel(state.model1Path)
        setSwap("")

        addMessage("system", "✓ Fixed $filePath. Tell me about any other bugs, or say 'done' to finish.", InventPhase.DEBUGGING)
        saveCurrentState()
    }

    fun startNewSession(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            engineManager.unloadAll()
            _ui.value = InventUiState()
            sessionState = null
            zcp = ZcpProtocol()
            sessionId = ""
            onDone()
        }
    }

    fun onDeleteConfirmed() {
        viewModelScope.launch(Dispatchers.IO) {
            InventStorage.deleteSession(ctx, sessionId)
            engineManager.unloadAll()
            _ui.value = InventUiState()
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

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

    private var lastCompactionNotified: Long = 0

    private fun checkCompactionAndNotify(historySize: Int, compactedSize: Int, phase: InventPhase) {
        if (compactedSize < historySize && phase == InventPhase.QUESTIONING) {
            val now = System.currentTimeMillis()
            if (now - lastCompactionNotified > 30_000) {  // at most once per 30s
                lastCompactionNotified = now
                val dropped = historySize - compactedSize
                addMessage("system",
                    "⚠ Context limit reached — $dropped oldest messages compacted. " +
                    "Go to Models → gear icon ⚙ to increase context size.",
                    phase)
            }
        }
    }

    private suspend fun runInference(
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        expectedModelPath: String? = null
    ): String = withContext(Dispatchers.IO) {
        _ui.value = _ui.value.copy(isGenerating = true)
        val sb = StringBuilder()

        if (expectedModelPath != null && !ensureEngineReady(expectedModelPath)) {
            val reloaded = reloadEngineFor(expectedModelPath)
            if (!reloaded) {
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
            override fun onTokensGenerated(count: Int) {
                streamedTokens = count
            }
        }

        try { engine.executeInference(fullPrompt, callback) }
        catch (e: Exception) { sb.append("[ERROR: ${e.message}]") }

        _ui.value = _ui.value.copy(isGenerating = false)
        sb.toString().trim()
    }

    // ── Prompt Builders ───────────────────────────────────────────────────────

    private fun buildQuestioningPrompt(): String = """
You are a friendly software project advisor. Your job is to understand what the user wants to build.

Rules:
- Ask ONE short, clear question at a time.
- Listen carefully to the answers before asking the next question.
- Topics to cover: what the app does, who it's for, platform, language/framework, key features, unique aspects.
- Keep questions conversational — like a developer colleague chatting.
- Once you have enough info (5-8 questions), say "Great, I think I have everything I need."
- Do NOT output JSON, ZCP, or any structured format during this phase.
- ONE question only per turn.
""".trimIndent()

    private fun buildPlanningPrompt(model2Ctx: Int): String = """
You are a senior software architect and project planner.

ZCP output format:
§APP{name:X|platform:X|language:X|framework:X}
§IDEA{core:X|features:X,Y,Z|unique:X}
§VIABLE{status:yes/no|note:X}
§SEARCH{topic:X|platform:X|question:X|category:X}
§TREE{path:X|type:dir/file|desc:X}
§FILEZCP{path:X|description:X|imports:X|classes:X|functions:X|dependencies:Y,Z}

Model 2 (coder) context: $model2Ctx tokens.
""".trimIndent()

    private fun buildCodeGenPrompt(spec: FileSpec, projectZcp: ZcpProtocol): String = buildString {
        appendLine("ZCP v${projectZcp.version} — File Spec")
        appendLine("========================================")
        appendLine()
        appendLine("Project: ${projectZcp.projectName}")
        appendLine("Platform: ${projectZcp.platform.joinToString(", ")}")
        appendLine("Language: ${projectZcp.language.joinToString(", ")}")
        appendLine("Framework: ${projectZcp.framework}")
        if (projectZcp.coreIdea.isNotEmpty()) appendLine("Core Idea: ${projectZcp.coreIdea}")
        if (projectZcp.mainFeatures.isNotEmpty()) appendLine("Features: ${projectZcp.mainFeatures.joinToString(", ")}")
        appendLine()
        appendLine("--- File to Implement ---")
        appendLine("Path: ${spec.path}")
        appendLine("Description: ${spec.description}")
        if (spec.imports.isNotEmpty()) appendLine("Imports: ${spec.imports}")
        if (spec.classes.isNotEmpty()) appendLine("Classes: ${spec.classes}")
        if (spec.functions.isNotEmpty()) appendLine("Functions: ${spec.functions}")
        if (spec.dependencies.isNotEmpty()) appendLine("Depends on: ${spec.dependencies.joinToString(", ")}")
        appendLine()
        if (spec.dependencies.isNotEmpty()) {
            appendLine("Dependency interfaces:")
            spec.dependencies.forEach { dep ->
                val depSpec = projectZcp.fileSpecs[dep]
                if (depSpec != null) {
                    appendLine("  • $dep → ${depSpec.description}")
                    if (depSpec.classes.isNotEmpty()) appendLine("    Classes: ${depSpec.classes}")
                    if (depSpec.functions.isNotEmpty()) appendLine("    Functions: ${depSpec.functions}")
                }
            }
            appendLine()
        }
        appendLine("---")
        appendLine()
        appendLine("Web search is available. If you need to look up APIs, syntax, or libraries,")
        appendLine("output [SEARCH: your query] and you'll get results.")
        appendLine()
        appendLine("Now write the complete code for this file. Production-ready, no explanations:")
    }

    private fun buildResearcherPrompt(content: Map<String, String>, intents: List<SearchIntent>): String {
        val sb = StringBuilder("Extract the following from fetched content:\n\n")
        intents.forEachIndexed { i, intent ->
            sb.append("SLOT_${i + 1}: ${intent.question} (${intent.category})\n")
            sb.append("Content: ${content[intent.category]?.take(1500) ?: "No content"}\n\n")
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
        }
        appendLine("}")
    }

    /** Rough token estimate: ~3.5 chars per token for code/text. */
    private fun estimateTokens(text: String): Int = (text.length / 3.5f).toInt() + 1

    private fun buildPromptWithInfo(
        system: String,
        history: List<Pair<String, String>>,
        user: String
    ): Pair<String, Int> {
        val availableCtx = SettingsManager.nCtx.coerceAtLeast(1024)
        val maxNew = SettingsManager.maxTokens.coerceAtLeast(64)
        val budget = availableCtx - maxNew - 256

        var compactedHistory = history
        val estimatedTotal = estimateTokens(system) + history.sumOf { (r, c) -> estimateTokens("$r $c") } + estimateTokens(user)
        if (estimatedTotal > budget) {
            val mutable = history.toMutableList()
            while (mutable.isNotEmpty()) {
                val testTotal = estimateTokens(system) + mutable.sumOf { (r, c) -> estimateTokens("$r $c") } + estimateTokens(user)
                if (testTotal <= budget) break
                if (mutable.size > 1) mutable.removeAt(0) else break
            }
            if (mutable.size < history.size) {
                val droppedCount = history.size - mutable.size
                mutable.add(0, "system" to "[Earlier conversation compacted — $droppedCount messages removed to fit context window]")
            }
            compactedHistory = mutable
        }

        val prompt = buildString {
            appendLine("<|im_start|>system")
            appendLine(system)
            appendLine("<|im_end|>")
            compactedHistory.forEach { (role, content) ->
                val mappedRole = if (role == "user") "user" else if (role == "system") "system" else "assistant"
                appendLine("<|im_start|>$mappedRole")
                val truncated = if (content.length > 16_000) content.take(16_000) + "…" else content
                appendLine(truncated)
                appendLine("<|im_end|>")
            }
            appendLine("<|im_start|>user")
            val truncatedUser = if (user.length > 16_000) user.take(16_000) + "…" else user
            appendLine(truncatedUser)
            appendLine("<|im_end|>")
            append("<|im_start|>assistant")
        }
        return prompt to compactedHistory.size
    }

    private fun buildConversationHistory(): List<Pair<String, String>> =
        _ui.value.messages
            .filter { it.role != "system" }
            .takeLast(20)
            .map { msg ->
                val role = if (msg.role == "user") "user" else "assistant"
                role to msg.content
            }

    // ── Parsers ───────────────────────────────────────────────────────────────

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
        Regex("§TREE\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2)
                p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            FileNode(path = kv["path"] ?: "", isDir = kv["type"] == "dir", description = kv["desc"] ?: "")
        }.filter { it.path.isNotEmpty() }.toList()

    private fun parseFileSpecs(raw: String): Map<String, FileSpec> {
        val specs = mutableMapOf<String, FileSpec>()
        Regex("§FILEZCP\\{([^}]+)\\}").findAll(raw).forEach { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2)
                p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            val path = kv["path"] ?: return@forEach
            if (path.isNotEmpty()) {
                specs[path] = FileSpec(
                    path = path,
                    description = kv["description"] ?: "",
                    imports = kv["imports"] ?: "",
                    classes = kv["classes"] ?: "",
                    functions = kv["functions"] ?: "",
                    dependencies = (kv["dependencies"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    estimatedTokens = (kv["estimatedTokens"] ?: "0").toIntOrNull() ?: 0,
                    continuationOf = kv["continuationOf"] ?: ""
                )
            }
        }
        return specs
    }

    private fun parseSearchIntents(raw: String): List<SearchIntent> =
        Regex("§SEARCH\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val p = it.split(":", limit = 2)
                p[0].trim() to (p.getOrNull(1)?.trim() ?: "")
            }
            SearchIntent(kv["topic"] ?: "", kv["platform"] ?: "", kv["question"] ?: "", kv["category"] ?: "general")
        }.toList()

    private fun parseSearchResults(extracted: String, intents: List<SearchIntent>): List<SearchResult> =
        intents.mapIndexed { i, intent ->
            val content = Regex("SLOT_${i + 1}:\\s*(.+)", RegexOption.IGNORE_CASE)
                .find(extracted)?.groupValues?.get(1)?.trim() ?: ""
            SearchResult(intent, content, intent.category, true)
        }

    // ── URL Fetcher ───────────────────────────────────────────────────────────

    private suspend fun fetchSearchContent(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        zcp.searchIntents.forEach { intent ->
            val domains = InventStorage.resolveDomainsForCategory(ctx, intent.category)
            domains.forEach { domain ->
                if (!result.containsKey(intent.category)) {
                    try {
                        val conn = URL("https://$domain").openConnection()
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        val text = conn.getInputStream().bufferedReader().readText()
                        result[intent.category] = text
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ")
                            .take(3000)
                    } catch (e: Exception) {
                        result[intent.category] = "[fetch failed: ${e.message}]"
                    }
                }
            }
        }
        result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun addMessage(role: String, content: String, phase: InventPhase) {
        val updated = _ui.value.messages + InventMessage(role, content, phase)
        _ui.value = _ui.value.copy(messages = updated)
        sessionState = sessionState?.copy(messages = updated)
        saveCurrentState()
    }

    private fun updatePhase(phase: InventPhase) {
        _ui.value = _ui.value.copy(phase = phase)
        sessionState = sessionState?.copy(phase = phase)
        saveCurrentState()
    }

    private fun updateSearchRound(round: Int) {
        _ui.value = _ui.value.copy(searchRound = round)
        sessionState = sessionState?.copy(searchRound = round)
        saveCurrentState()
    }

    private fun setSwap(info: String) { _ui.value = _ui.value.copy(swapInfo = info) }

    private fun saveCurrentState() {
        sessionState?.let { InventStorage.saveSession(ctx, it) }
        // Sync projectName from ZCP to UI
        if (zcp.projectName.isNotEmpty() && _ui.value.projectName != zcp.projectName) {
            _ui.value = _ui.value.copy(projectName = zcp.projectName)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) { engineManager.unloadAll() }
    }
}
