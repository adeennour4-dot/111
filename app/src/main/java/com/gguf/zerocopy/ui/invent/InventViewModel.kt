package com.gguf.zerocopy.ui.invent

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.invent.*
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
    val error: String = ""
)

class InventViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val engineManager get() = ZeroCopyApp.instance.engineManager

    private val _ui = MutableStateFlow(InventUiState())
    val ui: StateFlow<InventUiState> = _ui

    // expose for delete confirm toggle from screen
    fun setShowDeleteConfirm(v: Boolean) { _ui.value = _ui.value.copy(showDeleteConfirm = v) }

    private var sessionState: InventSessionState? = null
    private var zcp: ZcpProtocol = ZcpProtocol()
    private var sessionId: String = ""

    // ── Setup ────────────────────────────────────────────────────────────────

    fun setupSession(
        model1Path: String, model1Name: String,
        model2Path: String, model2Name: String,
        researcherPath: String, researcherName: String,
        offlineMode: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Resume existing incomplete session if present
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
                        fileTree = savedZcp.fileTree,
                        searchRound = saved.searchRound,
                        mergeCount = saved.mergeCount
                    )
                    return@launch
                }
            }

            // Read context sizes from GGUF headers — pure file parsing, no model needed
            val m1Ctx = GgufMetaReader.readContextLength(model1Path)
            val m2Ctx = GgufMetaReader.readContextLength(model2Path)

            sessionId = UUID.randomUUID().toString().take(8)
            zcp = ZcpProtocol(model2ContextSize = m2Ctx, offlineMode = offlineMode)

            sessionState = InventSessionState(
                sessionId = sessionId,
                phase = InventPhase.QUESTIONING,
                model1Path = model1Path,
                model1Name = model1Name,
                model2Path = model2Path,
                model2Name = model2Name,
                researcherPath = researcherPath,
                researcherName = researcherName,
                model1ContextSize = m1Ctx,
                model2ContextSize = m2Ctx,
                offlineMode = offlineMode
            )

            InventStorage.saveSession(ctx, sessionState!!)
            InventStorage.saveZcp(ctx, sessionId, zcp)

            _ui.value = _ui.value.copy(
                phase = InventPhase.QUESTIONING,
                sessionId = sessionId,
                model1Name = model1Name,
                model2Name = model2Name,
                researcherName = researcherName,
                offlineMode = offlineMode
            )

            startModel1Questioning()
        }
    }

    // ── Phase 1: Model 1 Questions ───────────────────────────────────────────

    private suspend fun startModel1Questioning() {
        val state = sessionState ?: return
        setSwap("Loading ${state.model1Name}…")
        withContext(Dispatchers.IO) {
            engineManager.unloadAll()
            engineManager.selectEngineForFormat(state.model1Path)
            engineManager.getActiveEngine()?.loadModel(state.model1Path)
        }
        setSwap("")

        val firstQuestion = runInference(
            systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
            userMessage = "Start by asking the user the first question about their project. ONE question only."
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
        val response = runInference(
            systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
            userMessage = userText,
            history = buildConversationHistory()
        )
        if (response.contains("[INFO_COMPLETE]", ignoreCase = true) ||
            response.contains("[READY_TO_SEARCH]", ignoreCase = true)) {
            addMessage("model1",
                response.replace("[INFO_COMPLETE]", "").replace("[READY_TO_SEARCH]", "").trim(),
                InventPhase.QUESTIONING)
            triggerSearchPhase()
        } else {
            addMessage("model1", response, InventPhase.QUESTIONING)
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

        val zcpRaw = runInference(
            systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
            userMessage = "Based on everything discussed, write the complete ZCP protocol. Include §APP, §IDEA, §VIABLE, all §SEARCH intents, and §TREE blocks.",
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
            withContext(Dispatchers.IO) {
                engineManager.unloadAll()
                engineManager.selectEngineForFormat(state.researcherPath)
                engineManager.getActiveEngine()?.loadModel(state.researcherPath)
            }
            setSwap("")

            val extracted = runInference(
                systemPrompt = "You are a precise information extractor. Fill given slots with exact values from the provided content. Output ONLY slot:value pairs. No explanations.",
                userMessage = buildResearcherPrompt(fetchedContent, zcp.searchIntents)
            )

            InventStorage.saveSearchLog(ctx, sessionId, extracted)
            withContext(Dispatchers.IO) { engineManager.unloadAll() }

            setSwap("Loading ${state.model1Name} to review results…")
            withContext(Dispatchers.IO) {
                engineManager.selectEngineForFormat(state.model1Path)
                engineManager.getActiveEngine()?.loadModel(state.model1Path)
            }
            setSwap("")

            val reviewResponse = runInference(
                systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
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
        withContext(Dispatchers.IO) {
            engineManager.unloadAll()
            engineManager.selectEngineForFormat(state.model1Path)
            engineManager.getActiveEngine()?.loadModel(state.model1Path)
        }
        setSwap("")

        val usableCtx = (zcp.model2ContextSize * 0.7).toInt()
        val plan = runInference(
            systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
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
        setSwap("Loading ${state.model2Name}…")
        withContext(Dispatchers.IO) {
            engineManager.unloadAll()
            engineManager.selectEngineForFormat(state.model2Path)
            engineManager.getActiveEngine()?.loadModel(state.model2Path)
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

    private suspend fun runInference(
        systemPrompt: String,
        userMessage: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        _ui.value = _ui.value.copy(isGenerating = true)
        val sb = StringBuilder()

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

    private fun buildModel1SystemPrompt(model2Ctx: Int): String = """
You are a project planning AI. Gather all information about a software project through conversation.
Ask ONE question at a time. Cover: platform, language/framework, core idea, main features, unique point.
When you have complete information, output [INFO_COMPLETE] then write ZCP.

ZCP format:
§APP{name:X|platform:X|language:X|framework:X}
§IDEA{core:X|features:X,Y,Z|unique:X}
§VIABLE{status:yes/no|note:X}
§SEARCH{topic:X|platform:X|question:X|category:X}
§TREE{path:X|type:dir/file|desc:X}

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
        append("<|system|>\n$system\n")
        history.forEach { (role, content) -> append("<|$role|>\n$content\n") }
        append("<|user|>\n$user\n<|assistant|>\n")
    }

    private fun buildConversationHistory(): List<Pair<String, String>> =
        _ui.value.messages.takeLast(10).map { it.role to it.content }

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
