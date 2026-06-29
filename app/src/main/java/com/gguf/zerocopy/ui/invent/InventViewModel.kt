package com.gguf.zerocopy.ui.invent

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.invent.*
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.LocalModel
import com.gguf.zerocopy.domain.invent.GgufMetaReader
import com.gguf.zerocopy.domain.inference.InferenceConfig
import com.gguf.zerocopy.domain.inference.TokenCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID

data class InventUiState(
    val phase: InventPhase = InventPhase.QUESTIONING,
    val messages: List<InventMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val swapInfo: String = "",
    val searchRound: Int = 0,
    val mergeCount: Int = 0,
    val showSureButtons: Boolean = false,
    val showMergeBanner: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val fileTree: List<FileNode> = emptyList(),
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val sessionId: String = "",
    val model1Name: String = "",
    val model2Name: String = "",
    val researcherName: String = "",
    val offlineMode: Boolean = false,
    val sameModelMode: Boolean = false,
    val error: String = ""
)

class InventViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val engineManager get() = ZeroCopyApp.instance.engineManager

    private val _ui = MutableStateFlow(InventUiState())
    val ui: StateFlow<InventUiState> = _ui

    fun setShowDeleteConfirm(v: Boolean) { _ui.value = _ui.value.copy(showDeleteConfirm = v) }

    private var sessionState: InventSessionState? = null
    private var zcp: ZcpProtocol = ZcpProtocol()
    private var sessionId: String = ""

    /**
     * Clear any lingering tool manager from ChatScreen so Invent's own
     * fully-formatted prompts don't get re-wrapped with tool preamble.
     */
    private fun clearToolManagerOnEngines() {
      engineManager.llamaCpp.setToolManager(null)
      engineManager.mnn.setToolManager(null)
      engineManager.liteRt.setToolManager(null)
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
            // 🛡️ clear any lingering tool manager from ChatScreen
            // This must happen BEFORE any inference — for both new AND restored sessions.
            clearToolManagerOnEngines()

            val existing = InventStorage.listSessions(ctx).firstOrNull()
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
                        mergeCount = saved.mergeCount
                    )
                    return@launch
                }
            }

            // Context size from GGUF header; TFLite models return 0 → default 2048
            val m1Ctx = GgufMetaReader.readContextLength(model1Path).let { if (it <= 0) 2048 else it }
            val m2Ctx = if (sameModelMode) m1Ctx
                        else GgufMetaReader.readContextLength(model2Path).let { if (it <= 0) 2048 else it }

            // Apply the model's native context size to the engine config so the
            // model isn't artificially limited by the default 2048.
            val userCtx = SettingsManager.nCtx  // user may have customized this
            val effectiveCtx = if (userCtx <= 0) m1Ctx else userCtx.coerceAtMost(m1Ctx)
            val userConfig = SettingsManager.toConfig()
            val tunedConfig = userConfig.copy(nCtx = effectiveCtx)
            // Apply config to ALL engines so TFLite / MNN models also get
            // the correct context size when loaded via loadOrKeepModel().
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
        if (!ok) {
            setSwap("")
            _ui.value = _ui.value.copy(
                error = "Failed to load ${state.model1Name}"
            )
            return
        }
        setSwap("")

        val firstQuestion = runInference(
            systemPrompt = buildQuestioningPrompt(),
            userMessage = "Hi! I want to build a software project and need your help planning it. Please start by asking me the first question — one question only."
        )
        addMessage("model1", firstQuestion, InventPhase.QUESTIONING)
    }

    fun sendUserMessage(text: String) {
        if (_ui.value.isGenerating) return
        addMessage("user", text, _ui.value.phase)
        viewModelScope.launch(Dispatchers.IO) {
            when (_ui.value.phase) {
                InventPhase.QUESTIONING -> handleQuestioningReply(text)
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

        // Check if the model signals it has enough info (from explicit trigger or keywords)
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

        if (isDone) {
            triggerSearchPhase()
        }
    }

    fun onSearchButtonPressed() {
        if (_ui.value.isGenerating) return
        viewModelScope.launch(Dispatchers.IO) { triggerSearchPhase() }
    }

    // ── Phase 2: Write ZCP + Search ──────────────────────────────────────────

    private suspend fun triggerSearchPhase() {
        updatePhase(InventPhase.SEARCHING)
        val state = sessionState ?: return

        // Switch to planning prompt now that we have enough context
        val zcpRaw = runInference(
            systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
            userMessage = "Based on everything we discussed, write the complete ZCP protocol now. Include §APP, §IDEA, §VIABLE, all §SEARCH intents, and §TREE blocks.",
            history = buildConversationHistory()
        )

        zcp = parseZcpFromModel1(zcpRaw, zcp)
        InventStorage.saveZcp(ctx, sessionId, zcp)
        addMessage("system", "ZCP v1 saved ✓  Starting research…", InventPhase.SEARCHING)

        withContext(Dispatchers.IO) { engineManager.unloadAll() }

        if (zcp.offlineMode) {
            reloadModel1ForPlanning()
            return
        }

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
            if (!researcherOk) {
                setSwap("")
                _ui.value = _ui.value.copy(error = "Failed to load researcher")
                return
            }
            setSwap("")

            val extracted = runInference(
                systemPrompt = "You are a precise information extractor. Fill given slots with exact values from the provided content. Output ONLY slot:value pairs. No explanations.",
                userMessage = buildResearcherPrompt(fetchedContent, zcp.searchIntents)
            )

            InventStorage.saveSearchLog(ctx, sessionId, extracted)

            setSwap("Loading ${state.model1Name} to review results…")
            val model1Ok = loadOrKeepModel(state.model1Path)
            if (!model1Ok) {
                setSwap("")
                _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name}")
                return
            }
            setSwap("")

            val reviewResponse = runInference(
                systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
                userMessage = "Search results:\n$extracted\n\nDo you have all info needed? If yes output [SEARCH_DONE]. If not, output new §SEARCH blocks only."
            )

            if (reviewResponse.contains("[SEARCH_DONE]", ignoreCase = true) || round >= maxRounds) {
                zcp = zcp.copy(searchResults = parseSearchResults(extracted, zcp.searchIntents))
                InventStorage.saveZcp(ctx, sessionId, zcp)
                withContext(Dispatchers.IO) { engineManager.unloadAll() }
                reloadModel1ForPlanning()
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

    // ── Phase 3: Planning ────────────────────────────────────────────────────

    private suspend fun reloadModel1ForPlanning() {
        val state = sessionState ?: return
        updatePhase(InventPhase.PLANNING)
        setSwap("Loading ${state.model1Name} for planning…")
        val ok = loadOrKeepModel(state.model1Path)
        if (!ok) {
            setSwap("")
            _ui.value = _ui.value.copy(error = "Failed to load ${state.model1Name} for planning")
            return
        }
        setSwap("")

        val usableCtx = (zcp.model2ContextSize * 0.7).toInt()
        val plan = runInference(
            systemPrompt = buildPlanningPrompt(zcp.model2ContextSize),
            userMessage = "You have all information. Write the complete project file tree using §TREE blocks. Then write implementation plan in §CHUNK{n:1} §CHUNK{n:2} sections, each max $usableCtx tokens."
        )

        val fileTree = parseFileTree(plan)
        val chunks = chunkPlan(plan, usableCtx)
        zcp = zcp.copy(fileTree = fileTree, chunks = chunks, phase = InventPhase.CONFIRMING)
        InventStorage.saveZcp(ctx, sessionId, zcp)
        InventStorage.deleteSearchLog(ctx, sessionId)

        addMessage("model1", plan, InventPhase.PLANNING)
        withContext(Dispatchers.IO) { engineManager.unloadAll() }

        loadModel2ForConfirmation()
    }

    // ── Phase 4: Model 2 Confirms ─────────────────────────────────────────────

    private suspend fun loadModel2ForConfirmation() {
        val state = sessionState ?: return
        updatePhase(InventPhase.CONFIRMING)

        // In same-model mode the planner IS the coder — no separate load needed
        val isSame = state.sameModelMode || state.model1Path == state.model2Path
        val targetPath: String
        val targetName: String
        if (!isSame) {
            targetPath = state.model2Path
            targetName = state.model2Name
        } else {
            targetPath = state.model1Path
            targetName = state.model1Name
        }

        setSwap("Loading $targetName (coder role)…")
        val ok = loadOrKeepModel(targetPath)
        if (!ok) {
            setSwap("")
            _ui.value = _ui.value.copy(error = "Failed to load $targetName for confirmation")
            return
        }
        setSwap("")

        val understanding = runInference(
            systemPrompt = "You are a senior software engineer. Read the project spec and describe exactly what you will build — files, architecture, implementation approach. Be specific. Follow the spec exactly.",
            userMessage = "Read this project spec and describe your full understanding:\n\n${buildZcpSummaryForModel2()}"
        )

        addMessage("model2", understanding, InventPhase.CONFIRMING)
        _ui.value = _ui.value.copy(showSureButtons = true)
        saveCurrentState()
    }

    // ── Sure / Not Sure ───────────────────────────────────────────────────────

    fun onSure() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showSureButtons = false)
            updatePhase(InventPhase.DONE)

            val projectDir = java.io.File(ctx.filesDir,
                "invent_projects/${zcp.projectName.ifEmpty { sessionId }}")
            zcp.fileTree.filter { it.isDir }.forEach { node ->
                java.io.File(projectDir, node.path).mkdirs()
            }

            zcp = zcp.copy(phase = InventPhase.DONE)
            InventStorage.saveZcp(ctx, sessionId, zcp)

            _ui.value = _ui.value.copy(fileTree = zcp.fileTree, phase = InventPhase.DONE)
            addMessage("system",
                "✓ Project structure created at invent_projects/${zcp.projectName.ifEmpty { sessionId }}",
                InventPhase.DONE)
        }
    }

    fun onNotSure() {
        if (_ui.value.mergeCount >= 2) {
            _ui.value = _ui.value.copy(
                showSureButtons = false,
                error = "2 merge attempts reached. Consider starting fresh with a clearer idea."
            )
            return
        }
        _ui.value = _ui.value.copy(showSureButtons = false, showMergeBanner = true)
    }

    fun onMergeConfirmed() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showMergeBanner = false)
            val newMergeCount = _ui.value.mergeCount + 1
            val mergedZcp = zcp.copy(
                phase = InventPhase.QUESTIONING,
                mergeCount = newMergeCount,
                searchResults = emptyList(),
                fileTree = emptyList(),
                chunks = emptyList()
            )
            InventStorage.deleteSession(ctx, sessionId)
            sessionId = UUID.randomUUID().toString().take(8)
            zcp = mergedZcp
            InventStorage.saveZcp(ctx, sessionId, zcp)

            _ui.value = _ui.value.copy(
                phase = InventPhase.QUESTIONING,
                messages = _ui.value.messages.takeLast(6),
                mergeCount = newMergeCount,
                showSureButtons = false,
                showMergeBanner = false,
                fileTree = emptyList()
            )
            withContext(Dispatchers.IO) { engineManager.unloadAll() }
            startModel1Questioning()
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

    /**
     * If [path] is already loaded (same engine, same model), just reset KV cache
     * instead of doing a full unload + reload.  Saves 2-5 seconds per transition.
     */
    private suspend fun loadOrKeepModel(path: String): Boolean {
        val engine = engineManager.getActiveEngine()
        if (engine != null && engine.isModelLoaded && engine.loadedModelPath == path) {
            // Same model already loaded — just clear the KV cache context
            withContext(Dispatchers.IO) { engine.resetContext() }
            return true
        }
        // Different model — do the full load
        return withContext(Dispatchers.IO) {
            try {
                engineManager.unloadAll()
                engineManager.selectEngineForFormat(path)
                val result = engineManager.getActiveEngine()?.loadModel(path)
                result?.isSuccess == true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Ensure the active engine has the right model loaded.
     * Returns true if inference can proceed, false if a model needs (re)loading.
     */
    private fun ensureEngineReady(expectedPath: String): Boolean {
        val engine = engineManager.getActiveEngine()
        if (engine != null && engine.isModelLoaded && engine.loadedModelPath == expectedPath) {
            return true  // correct model already loaded
        }
        return false
    }

    private suspend fun reloadEngineFor(path: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                engineManager.unloadAll()
                engineManager.selectEngineForFormat(path)
                val result = engineManager.getActiveEngine()?.loadModel(path)
                result?.isSuccess == true
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun runInference(
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        /**
         * If non-null, ensure this model is loaded before inference.
         * Prevents crashes when the engine was swapped by another screen
         * (e.g., ChatScreen) while Invent was paused.
         */
        expectedModelPath: String? = null
    ): String = withContext(Dispatchers.IO) {
        _ui.value = _ui.value.copy(isGenerating = true)
        val sb = StringBuilder()

        // If we expect a specific model but it's not loaded, reload it first.
        if (expectedModelPath != null && !ensureEngineReady(expectedModelPath)) {
            val reloaded = reloadEngineFor(expectedModelPath)
            if (!reloaded) {
                _ui.value = _ui.value.copy(isGenerating = false, error = "Failed to load $expectedModelPath")
                return@withContext "[Failed to load model]"
            }
        }

        val fullPrompt = buildPrompt(systemPrompt, history, userMessage)
        val engine = engineManager.getActiveEngine()

        if (engine == null) {
            _ui.value = _ui.value.copy(isGenerating = false)
            return@withContext "[No engine loaded]"
        }

        val callback = object : TokenCallback {
            override fun onToken(token: String) { sb.append(token) }
            override fun onDone() {}
            override fun onError(error: String) { sb.append("[ERROR: $error]") }
            override fun onKvUsage(percent: Int) {}
            override fun onTokensGenerated(count: Int) {}
        }

        try {
            engine.executeInference(fullPrompt, callback)
        } catch (e: Exception) {
            sb.append("[ERROR: ${e.message}]")
        }

        _ui.value = _ui.value.copy(isGenerating = false)
        sb.toString().trim()
    }

    // ── Prompt Builders ───────────────────────────────────────────────────────

    /**
     * Lightweight conversational prompt for the QUESTIONING phase.
     * No ZCP schema here — small models stop following a conversation when they
     * see the output format in the same prompt.
     */
    private fun buildQuestioningPrompt(): String = """
You are a friendly software project advisor. Your job is to understand what the user wants to build.

Rules:
- Ask ONE short, clear question at a time. Never ask multiple questions at once.
- Listen carefully to the answers before asking the next question.
- Topics to cover (in any natural order): what the app does, who it's for, which platform (Android/Web/iOS/etc.), preferred language or framework, key features, anything that makes it unique.
- Keep your questions conversational — like a developer colleague chatting, not a form.
- Once you feel you have enough to plan the project (usually 5–8 questions), say something like "Great, I think I have everything I need. When you're ready, hit the search button and I'll start planning!"
- Do NOT output JSON, ZCP, or any structured format during this phase.
- Do NOT list multiple questions. ONE question only per turn.
""".trimIndent()

    /**
     * Full ZCP-aware prompt used only during SEARCHING, PLANNING, and CONFIRMING phases.
     */
    private fun buildPlanningPrompt(model2Ctx: Int): String = """
You are a senior software architect and project planner.

ZCP output format:
§APP{name:X|platform:X|language:X|framework:X}
§IDEA{core:X|features:X,Y,Z|unique:X}
§VIABLE{status:yes/no|note:X}
§SEARCH{topic:X|platform:X|question:X|category:X}
§TREE{path:X|type:dir/file|desc:X}
§CHUNK{n:1}...implementation details...§CHUNK{n:2}...

Model 2 context: $model2Ctx tokens. Chunk plan to fit ${(model2Ctx * 0.7).toInt()} tokens per §CHUNK.
""".trimIndent()

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
        zcp.fileTree.forEach {
            appendLine("  ${if (it.isDir) "[DIR]" else "[FILE]"} ${it.path} // ${it.description}")
        }
        appendLine("}")
        appendLine("§CHUNKS_TOTAL{count:${zcp.chunks.size}}")
    }

    private fun buildPrompt(
        system: String,
        history: List<Pair<String, String>>,
        user: String
    ): String = buildString {
        // Use a general-purpose chat-template format that works with most
        // instruction-tuned GGUF models (ChatML-derived).
        appendLine("<|im_start|>system")
        appendLine(system)
        appendLine("<|im_end|>")
        history.forEach { (role, content) ->
            val mappedRole = if (role == "user") "user" else "assistant"
            appendLine("<|im_start|>$mappedRole")
            // Truncate excessively long history entries to prevent OOM
            val truncated = if (content.length > 16_000) content.take(16_000) + "…" else content
            appendLine(truncated)
            appendLine("<|im_end|>")
        }
        appendLine("<|im_start|>user")
        // Also truncate user message to prevent prompt overflow
        val truncatedUser = if (user.length > 16_000) user.take(16_000) + "…" else user
        appendLine(truncatedUser)
        appendLine("<|im_end|>")
        append("<|im_start|>assistant")
    }

    /**
     * Maps internal roles to standard prompt roles so the model's KV cache
     * doesn't get corrupted by unknown role tokens like <|model1|>.
     * "model1" / "model2" / "researcher" → "assistant"
     * "user" → "user"
     * "system" messages are skipped (already baked into system prompt)
     */
    private fun buildConversationHistory(): List<Pair<String, String>> =
        _ui.value.messages
            .filter { it.role != "system" }
            .takeLast(10)
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

    private fun chunkPlan(plan: String, maxTokens: Int): List<String> =
        plan.chunked(maxTokens * 4) // ~4 chars per token

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
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) { engineManager.unloadAll() }
    }
}
