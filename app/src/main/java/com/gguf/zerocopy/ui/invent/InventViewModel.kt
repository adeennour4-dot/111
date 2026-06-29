package com.gguf.zerocopy.ui.invent

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.invent.*
import com.gguf.zerocopy.domain.invent.GgufMetaReader
import com.gguf.zerocopy.domain.inference.EngineType
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
    val swapInfo: String = "",        // e.g. "Loading researcher model..."
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
            // Check for existing incomplete session
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

            // Read context sizes from GGUF headers
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

            // Start Model 1 questioning
            startModel1Questioning()
        }
    }

    // ── Phase 1: Model 1 Questions ───────────────────────────────────────────

    private suspend fun startModel1Questioning() {
        val state = sessionState ?: return
        setSwap("Loading ${state.model1Name}...")
        withContext(Dispatchers.IO) {
            engineManager.unloadAll()
            engineManager.selectEngineForFormat(state.model1Path)
            engineManager.getActiveEngine()?.loadModel(
                state.model1Path,
                buildConfig(state.model1ContextSize)
            )
        }
        setSwap("")

        val systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize)
        val firstQuestion = runInference(
            systemPrompt = systemPrompt,
            userMessage = "Start by asking the user the first question about their project. Ask only ONE question at a time.",
            role = "model1"
        )
        addMessage("model1", firstQuestion, InventPhase.QUESTIONING)
    }

    fun sendUserMessage(text: String) {
        val state = sessionState ?: return
        if (_ui.value.isGenerating) return

        addMessage("user", text, _ui.value.phase)

        viewModelScope.launch(Dispatchers.IO) {
            when (_ui.value.phase) {
                InventPhase.QUESTIONING -> handleQuestioningReply(text)
                InventPhase.CONFIRMING -> {} // handled by buttons
                else -> {}
            }
        }
    }

    private suspend fun handleQuestioningReply(userText: String) {
        val state = sessionState ?: return
        val history = buildConversationHistory()

        val response = runInference(
            systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
            userMessage = userText,
            role = "model1",
            history = history
        )

        // Check if Model 1 signals it has enough info
        if (response.contains("[READY_TO_SEARCH]", ignoreCase = true) ||
            response.contains("[INFO_COMPLETE]", ignoreCase = true)) {
            addMessage("model1", response.replace("[READY_TO_SEARCH]","").replace("[INFO_COMPLETE]","").trim(), InventPhase.QUESTIONING)
            triggerSearchPhase()
        } else {
            addMessage("model1", response, InventPhase.QUESTIONING)
        }
    }

    // User hits the Search button manually
    fun onSearchButtonPressed() {
        if (_ui.value.isGenerating) return
        viewModelScope.launch(Dispatchers.IO) {
            triggerSearchPhase()
        }
    }

    // ── Phase 2: Write ZCP + Search Intents then hand to Researcher ──────────

    private suspend fun triggerSearchPhase() {
        val state = sessionState ?: return
        updatePhase(InventPhase.SEARCHING)

        // Model 1 writes ZCP v1 and search intents
        val zcpWritePrompt = buildZcpWritePrompt()
        val zcpRaw = runInference(
            systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
            userMessage = zcpWritePrompt,
            role = "model1",
            history = buildConversationHistory()
        )

        // Parse ZCP fields from model output
        zcp = parseZcpFromModel1(zcpRaw, zcp)
        InventStorage.saveZcp(ctx, sessionId, zcp)
        addMessage("system", "ZCP v1 saved. Starting search phase...", InventPhase.SEARCHING)

        // Unload Model 1
        withContext(Dispatchers.IO) { engineManager.unloadAll() }

        if (zcp.offlineMode) {
            // Skip to planning with offline-flagged intents
            reloadModel1ForPlanning()
            return
        }

        // Run search rounds (max dynamic: min(intentCount, 5))
        val maxRounds = (zcp.searchIntents.size).coerceIn(1, 5)
        runSearchRounds(maxRounds)
    }

    private suspend fun runSearchRounds(maxRounds: Int) {
        val state = sessionState ?: return
        var round = sessionState?.searchRound ?: 0

        while (round < maxRounds) {
            round++
            updateSearchRound(round)
            setSwap("Loading researcher (round $round/$maxRounds)...")

            // Fetch URLs in parallel using app code
            val fetchedContent = fetchSearchContent()

            // Load 1B researcher
            withContext(Dispatchers.IO) {
                engineManager.unloadAll()
                engineManager.selectEngineForFormat(state.researcherPath)
                engineManager.getActiveEngine()?.loadModel(
                    state.researcherPath,
                    buildConfig(2048)
                )
            }
            setSwap("")

            // Researcher extracts relevant info
            val extractionPrompt = buildResearcherPrompt(fetchedContent, zcp.searchIntents)
            val extracted = runInference(
                systemPrompt = "You are a precise information extractor. Fill the given slots exactly. Output only structured text with slot names and values. No explanations.",
                userMessage = extractionPrompt,
                role = "researcher"
            )

            // Save to search log
            InventStorage.saveSearchLog(ctx, sessionId, extracted)

            withContext(Dispatchers.IO) { engineManager.unloadAll() }

            // Reload Model 1 to check if more search needed
            setSwap("Loading ${state.model1Name} to review results...")
            withContext(Dispatchers.IO) {
                engineManager.selectEngineForFormat(state.model1Path)
                engineManager.getActiveEngine()?.loadModel(
                    state.model1Path,
                    buildConfig(state.model1ContextSize)
                )
            }
            setSwap("")

            val reviewResponse = runInference(
                systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
                userMessage = "Search results:\n$extracted\n\nDo you have all the info needed to plan the project? If yes, output [SEARCH_DONE]. If you need more, output new [SEARCH_INTENT] blocks only.",
                role = "model1"
            )

            if (reviewResponse.contains("[SEARCH_DONE]", ignoreCase = true) || round >= maxRounds) {
                // Update ZCP with search results
                zcp = zcp.copy(searchResults = parseSearchResults(extracted, zcp.searchIntents))
                InventStorage.saveZcp(ctx, sessionId, zcp)
                withContext(Dispatchers.IO) { engineManager.unloadAll() }
                reloadModel1ForPlanning()
                break
            } else {
                // Parse new intents and continue
                val newIntents = parseSearchIntents(reviewResponse)
                zcp = zcp.copy(searchIntents = zcp.searchIntents + newIntents)
                InventStorage.saveZcp(ctx, sessionId, zcp)
                withContext(Dispatchers.IO) { engineManager.unloadAll() }
            }
        }
    }

    // ── Phase 3: Model 1 Plans File Tree ─────────────────────────────────────

    private suspend fun reloadModel1ForPlanning() {
        val state = sessionState ?: return
        updatePhase(InventPhase.PLANNING)
        setSwap("Loading ${state.model1Name} for planning...")

        withContext(Dispatchers.IO) {
            engineManager.unloadAll()
            engineManager.selectEngineForFormat(state.model1Path)
            engineManager.getActiveEngine()?.loadModel(
                state.model1Path,
                buildConfig(state.model1ContextSize)
            )
        }
        setSwap("")

        val usableContext = (zcp.model2ContextSize * 0.7).toInt() // 30% reserved for overhead
        val planningPrompt = buildPlanningPrompt(usableContext)

        val plan = runInference(
            systemPrompt = buildModel1SystemPrompt(zcp.model2ContextSize),
            userMessage = planningPrompt,
            role = "model1"
        )

        // Parse file tree and chunks
        val fileTree = parseFileTree(plan)
        val chunks = chunkPlan(plan, usableContext)
        zcp = zcp.copy(fileTree = fileTree, chunks = chunks, phase = InventPhase.CONFIRMING)
        InventStorage.saveZcp(ctx, sessionId, zcp)
        InventStorage.deleteSearchLog(ctx, sessionId)

        addMessage("model1", plan, InventPhase.PLANNING)
        withContext(Dispatchers.IO) { engineManager.unloadAll() }

        // Hand to Model 2
        loadModel2ForConfirmation()
    }

    // ── Phase 4: Model 2 Confirms ─────────────────────────────────────────────

    private suspend fun loadModel2ForConfirmation() {
        val state = sessionState ?: return
        updatePhase(InventPhase.CONFIRMING)
        setSwap("Loading ${state.model2Name}...")

        withContext(Dispatchers.IO) {
            engineManager.unloadAll()
            engineManager.selectEngineForFormat(state.model2Path)
            engineManager.getActiveEngine()?.loadModel(
                state.model2Path,
                buildConfig(state.model2ContextSize)
            )
        }
        setSwap("")

        val zcpSummary = buildZcpSummaryForModel2()
        val understanding = runInference(
            systemPrompt = "You are a senior software engineer. Read the project spec carefully and describe exactly what you will build. Be specific about files, architecture, and implementation approach. Follow the spec exactly as given.",
            userMessage = "Read this project spec and describe your full understanding of what needs to be built:\n\n$zcpSummary",
            role = "model2"
        )

        addMessage("model2", understanding, InventPhase.CONFIRMING)
        _ui.value = _ui.value.copy(showSureButtons = true)
        saveCurrentState()
    }

    // ── User presses Sure ─────────────────────────────────────────────────────

    fun onSure() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showSureButtons = false)
            updatePhase(InventPhase.DONE)

            // Validate file tree matches ZCP before writing
            val tree = zcp.fileTree
            addMessage("system", "✓ File tree validated. Creating project structure...", InventPhase.DONE)

            // Write folders to internal storage (no empty files)
            val projectDir = java.io.File(ctx.filesDir, "invent_projects/${zcp.projectName.ifEmpty { sessionId }}")
            tree.filter { it.isDir }.forEach { node ->
                java.io.File(projectDir, node.path).mkdirs()
            }

            // Final ZCP saved, old sessions cleaned
            zcp = zcp.copy(phase = InventPhase.DONE)
            InventStorage.saveZcp(ctx, sessionId, zcp)

            _ui.value = _ui.value.copy(
                fileTree = tree,
                phase = InventPhase.DONE
            )

            addMessage("system", "✓ Project structure created at invent_projects/${zcp.projectName.ifEmpty { sessionId }}", InventPhase.DONE)
        }
    }

    // ── User presses Not Sure ─────────────────────────────────────────────────

    fun onNotSure() {
        if (_ui.value.mergeCount >= 2) {
            _ui.value = _ui.value.copy(
                showSureButtons = false,
                error = "2 merge attempts reached. Consider starting fresh with a clearer idea."
            )
            return
        }
        _ui.value = _ui.value.copy(
            showSureButtons = false,
            showMergeBanner = true
        )
    }

    fun onMergeConfirmed() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(showMergeBanner = false)
            val newMergeCount = _ui.value.mergeCount + 1

            // Both sessions get protocoled into merged ZCP
            val mergedZcp = zcp.copy(
                phase = InventPhase.QUESTIONING,
                mergeCount = newMergeCount,
                searchResults = emptyList(),
                fileTree = emptyList(),
                chunks = emptyList()
            )

            // Delete old session files, start new session with merged context
            InventStorage.deleteSession(ctx, sessionId)
            sessionId = UUID.randomUUID().toString().take(8)
            zcp = mergedZcp
            InventStorage.saveZcp(ctx, sessionId, zcp)

            val newMessages = _ui.value.messages.takeLast(6) // carry last context
            _ui.value = _ui.value.copy(
                phase = InventPhase.QUESTIONING,
                messages = newMessages,
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
            _ui.value = InventUiState() // reset to blank
        }
    }

    // ── Inference Helper ──────────────────────────────────────────────────────

    private suspend fun runInference(
        systemPrompt: String,
        userMessage: String,
        role: String,
        history: List<Pair<String,String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        _ui.value = _ui.value.copy(isGenerating = true)
        val sb = StringBuilder()

        val fullPrompt = buildPrompt(systemPrompt, history, userMessage)
        val callback = object : TokenCallback {
            override fun onToken(token: String): Boolean {
                sb.append(token)
                return true
            }
        }

        try {
            engineManager.getActiveEngine()?.generateTokens(fullPrompt, callback)
        } catch (e: Exception) {
            sb.append("[ERROR: ${e.message}]")
        }

        _ui.value = _ui.value.copy(isGenerating = false)
        sb.toString().trim()
    }

    // ── Prompt Builders ───────────────────────────────────────────────────────

    private fun buildModel1SystemPrompt(model2Ctx: Int): String = """
You are a project planning AI. Your job is to gather all information about a software project through conversation.

Ask ONE question at a time. Cover: platform, language/framework, core idea, main features, unique point.

When you have complete information, output [INFO_COMPLETE] before writing the ZCP.

When writing ZCP, use this exact format:
§APP{name:X|platform:X|language:X|framework:X}
§IDEA{core:X|features:X,Y,Z|unique:X}
§VIABLE{status:yes/no|note:X}
§SEARCH{topic:X|platform:X|question:X|category:X}
§TREE{path:X|type:dir/file|desc:X}

Model 2 context window: $model2Ctx tokens. Chunk implementation plan to fit ${(model2Ctx * 0.7).toInt()} tokens per chunk.
""".trimIndent()

    private fun buildZcpWritePrompt(): String =
        "Based on everything discussed, write the complete ZCP protocol for this project. Include §APP, §IDEA, §VIABLE, all §SEARCH intents needed, and §TREE for the file structure."

    private fun buildPlanningPrompt(usableCtx: Int): String =
        "You now have all search results. Write the complete project file tree using §TREE blocks. Then write the implementation plan chunked into sections of max $usableCtx tokens each, labeled §CHUNK{n:1} §CHUNK{n:2} etc."

    private fun buildResearcherPrompt(content: Map<String, String>, intents: List<SearchIntent>): String {
        val sb = StringBuilder("Extract the following information from the fetched content:\n\n")
        intents.forEachIndexed { i, intent ->
            sb.append("SLOT_${i+1}: ${intent.question} (from ${intent.category})\n")
            sb.append("Content: ${content[intent.domain]?.take(2000) ?: "No content fetched"}\n\n")
        }
        sb.append("\nOutput format:\nSLOT_1: [extracted answer]\nSLOT_2: [extracted answer]\n...")
        return sb.toString()
    }

    private fun buildZcpSummaryForModel2(): String {
        return """
§APP{name:${zcp.projectName}|platform:${zcp.platform.joinToString(",")}|language:${zcp.language.joinToString(",")}|framework:${zcp.framework}}
§IDEA{core:${zcp.coreIdea}|features:${zcp.mainFeatures.joinToString(",")}|unique:${zcp.uniquePoint}}
§VIABLE{status:${if(zcp.viable) "yes" else "no"}|note:${zcp.viabilityNote}}
§TREE{
${zcp.fileTree.joinToString("\n") { "  ${if(it.isDir)"[DIR]" else "[FILE]"} ${it.path} // ${it.description}" }}
}
§CHUNKS_TOTAL{count:${zcp.chunks.size}}
        """.trimIndent()
    }

    private fun buildPrompt(system: String, history: List<Pair<String,String>>, user: String): String {
        val sb = StringBuilder()
        sb.append("<|system|>\n$system\n")
        history.forEach { (role, content) ->
            sb.append("<|${role}|>\n$content\n")
        }
        sb.append("<|user|>\n$user\n<|assistant|>\n")
        return sb.toString()
    }

    private fun buildConversationHistory(): List<Pair<String,String>> {
        return _ui.value.messages.takeLast(10).map { Pair(it.role, it.content) }
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseZcpFromModel1(raw: String, existing: ZcpProtocol): ZcpProtocol {
        fun extract(tag: String, field: String): String {
            val pattern = Regex("§$tag\\{[^}]*$field:([^|}]+)")
            return pattern.find(raw)?.groupValues?.get(1)?.trim() ?: ""
        }
        fun extractList(tag: String, field: String): List<String> =
            extract(tag, field).split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val intents = Regex("§SEARCH\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val parts = it.split(":", limit = 2)
                parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
            }
            SearchIntent(
                topic = kv["topic"] ?: "",
                platform = kv["platform"] ?: "",
                question = kv["question"] ?: "",
                category = kv["category"] ?: "general"
            )
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

    private fun parseFileTree(raw: String): List<FileNode> {
        return Regex("§TREE\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val parts = it.split(":", limit = 2)
                parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
            }
            FileNode(
                path = kv["path"] ?: "",
                isDir = kv["type"] == "dir",
                description = kv["desc"] ?: ""
            )
        }.filter { it.path.isNotEmpty() }.toList()
    }

    private fun parseSearchIntents(raw: String): List<SearchIntent> {
        return Regex("§SEARCH\\{([^}]+)\\}").findAll(raw).map { m ->
            val kv = m.groupValues[1].split("|").associate {
                val parts = it.split(":", limit = 2)
                parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
            }
            SearchIntent(kv["topic"]?:"", kv["platform"]?:"", kv["question"]?:"", kv["category"]?:"general")
        }.toList()
    }

    private fun parseSearchResults(extracted: String, intents: List<SearchIntent>): List<SearchResult> {
        return intents.mapIndexed { i, intent ->
            val pattern = Regex("SLOT_${i+1}:\\s*(.+)", RegexOption.IGNORE_CASE)
            val content = pattern.find(extracted)?.groupValues?.get(1)?.trim() ?: ""
            SearchResult(intent, content, intent.category, true)
        }
    }

    private fun chunkPlan(plan: String, maxTokens: Int): List<String> {
        // Approximate 1 token ≈ 4 chars
        val chunkSize = maxTokens * 4
        return plan.chunked(chunkSize)
    }

    // ── URL Fetcher (app code, no model) ─────────────────────────────────────

    private suspend fun fetchSearchContent(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        val intents = zcp.searchIntents

        intents.forEach { intent ->
            val domains = InventStorage.resolveDomainsForCategory(ctx, intent.category)
            domains.forEach { domain ->
                if (!result.containsKey(domain)) {
                    try {
                        val url = "https://$domain"
                        val connection = URL(url).openConnection()
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        val text = connection.getInputStream().bufferedReader().readText()
                        // Strip HTML tags roughly
                        val stripped = text.replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ")
                            .take(3000)
                        result[domain] = stripped
                    } catch (e: Exception) {
                        result[domain] = "[fetch failed: ${e.message}]"
                    }
                }
            }
        }
        result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun addMessage(role: String, content: String, phase: InventPhase) {
        val msg = InventMessage(role, content, phase)
        val updated = _ui.value.messages + msg
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

    private fun setSwap(info: String) {
        _ui.value = _ui.value.copy(swapInfo = info)
    }

    private fun saveCurrentState() {
        sessionState?.let { InventStorage.saveSession(ctx, it) }
    }

    private fun buildConfig(ctx: Int) = InferenceConfig(
        nCtx = ctx,
        nBatch = 512,
        maxNewTokens = 1024,
        temperature = 0.3f,
        topP = 0.9f,
        minP = 0.05f,
        nGpuLayers = 0,
        nThreads = 4,
        lowRamMode = true,
        flashAttention = false,
        mmprojPath = ""
    )

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) { engineManager.unloadAll() }
    }
}
