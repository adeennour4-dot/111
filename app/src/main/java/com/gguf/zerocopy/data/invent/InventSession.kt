package com.gguf.zerocopy.data.invent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// ─── ZCP Protocol Schema ───────────────────────────────────────────────────

/** Per-file spec: tells Model 2 exactly what this file should contain. */
data class FileSpec(
    val path: String = "",
    val description: String = "",
    val imports: String = "",
    val classes: String = "",
    val functions: String = "",
    val dependencies: List<String> = emptyList(),
    val estimatedTokens: Int = 0,
    val continuationOf: String = ""
)

/** A debug/fix session: which file, what was wrong, what was changed. */
data class DebugSession(
    val filePath: String,
    val problem: String,
    val originalCode: String = "",        // kept for small inline display; full content on disk
    val fixedCode: String = "",             // kept for small inline display; full content on disk
    val timestamp: Long = System.currentTimeMillis()
)

data class ZcpProtocol(
    val version: Int = 1,
    val projectName: String = "",
    val platform: List<String> = emptyList(),
    val language: List<String> = emptyList(),
    val framework: String = "",
    val coreIdea: String = "",
    val mainFeatures: List<String> = emptyList(),
    val uniquePoint: String = "",
    val viable: Boolean = true,
    val viabilityNote: String = "",
    val searchIntents: List<SearchIntent> = emptyList(),
    val searchResults: List<SearchResult> = emptyList(),
    val fileTree: List<FileNode> = emptyList(),
    val chunks: List<String> = emptyList(),
    val model2ContextSize: Int = 4096,
    val offlineMode: Boolean = false,
    val checksum: String = "",
    val phase: InventPhase = InventPhase.QUESTIONING,
    val mergeCount: Int = 0,
    // Per-file specs (keyed by file path)
    val fileSpecs: Map<String, FileSpec> = emptyMap(),
    // Generated code paths (content stored on disk, loaded on demand)
    val generatedFiles: List<String> = emptyList(),
    // Debug history
    val debugSessions: List<DebugSession> = emptyList()
)

data class SearchIntent(
    val topic: String,
    val platform: String,
    val question: String,
    val category: String,
    val offline: Boolean = false
)

data class SearchResult(
    val intent: SearchIntent,
    val content: String,
    val domain: String,
    val verified: Boolean = true
)

data class FileNode(
    val path: String,
    val isDir: Boolean,
    val description: String = ""
)

enum class InventPhase {
    QUESTIONING,
    SEARCHING,
    PLANNING,
    CONFIRMING,
    GENERATING,
    REPLANNING,
    FINALIZING,
    DONE,
    DEBUGGING
}

// ─── Session State ──────────────────────────────────────────────────────────
data class InventSessionState(
    val sessionId: String,
    val phase: InventPhase,
    val model1Path: String,
    val model1Name: String,
    val model2Path: String,
    val model2Name: String,
    val researcherPath: String,
    val researcherName: String,
    val model1ContextSize: Int,
    val model2ContextSize: Int,
    val searchRound: Int = 0,
    val mergeCount: Int = 0,
    val offlineMode: Boolean = false,
    val sameModelMode: Boolean = false,
    val chatTemplate: String = "auto",
    val messages: List<InventMessage> = emptyList(),
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0
)

data class InventMessage(
    val role: String, // "model1", "model2", "researcher", "user", "system"
    val content: String,
    val phase: InventPhase,
    val thinkingContent: String = ""
)

// ─── Domain Registry ────────────────────────────────────────────────────────
data class DomainEntry(
    val category: String,
    val domain: String,
    val lastUpdated: String = ""
)

val DEFAULT_DOMAIN_REGISTRY = listOf(
    DomainEntry("android", "developer.android.com", "2025-01"),
    DomainEntry("android", "kotlinlang.org", "2025-01"),
    DomainEntry("python", "docs.python.org", "2025-01"),
    DomainEntry("python", "pypi.org", "2025-01"),
    DomainEntry("flutter", "pub.dev", "2025-01"),
    DomainEntry("flutter", "flutter.dev", "2025-01"),
    DomainEntry("web", "developer.mozilla.org", "2025-01"),
    DomainEntry("web", "npmjs.com", "2025-01"),
    DomainEntry("ios", "developer.apple.com", "2025-01"),
    DomainEntry("linux", "man7.org", "2025-01"),
    DomainEntry("linux", "archlinux.org", "2025-01"),
    DomainEntry("rust", "crates.io", "2025-01"),
    DomainEntry("rust", "docs.rs", "2025-01"),
    DomainEntry("database", "sqlite.org", "2025-01"),
    DomainEntry("database", "postgresql.org", "2025-01"),
    DomainEntry("ai", "huggingface.co", "2025-01"),
    DomainEntry("ai", "github.com/ggerganov/llama.cpp", "2025-01")
)

// ─── ZCP File Manager ───────────────────────────────────────────────────────
object InventStorage {
    private const val DIR = "invent_sessions"
    private const val DOMAIN_FILE = "domain_registry.json"

    fun getDir(ctx: Context): File {
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveZcp(ctx: Context, sessionId: String, zcp: ZcpProtocol) {
        // Write search result content to disk, keep only metadata in ZCP.
        // IMPORTANT: only write to disk if content is non-empty to avoid overwriting
        // previously saved files with empty strings (the content is already stripped
        // on the first save, so subsequent saves would lose data).
        val searchDir = File(getDir(ctx), "zcp_${sessionId}_sr")
        searchDir.mkdirs()
        val metaResults = zcp.searchResults.mapIndexed { i, sr ->
            if (sr.content.isNotEmpty()) {
                File(searchDir, "$i.txt").writeText(sr.content)
            }
            SearchResult(sr.intent, "", sr.domain, sr.verified)
        }
        val stripped = zcp.copy(searchResults = metaResults)
        val json = zcpToJson(stripped)
        val checksum = sha256(json)
        val withChecksum = zcpToJson(stripped.copy(checksum = checksum))
        File(getDir(ctx), "zcp_${sessionId}.json").writeText(withChecksum)
    }

    fun loadZcp(ctx: Context, sessionId: String): ZcpProtocol? {
        val f = File(getDir(ctx), "zcp_${sessionId}.json")
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            val obj = JSONObject(text)
            val base = jsonToZcp(obj)
            val savedChecksum = obj.optString("checksum", "")
            if (savedChecksum.isNotEmpty()) {
                val withoutChecksum = zcpToJson(base.copy(checksum = ""))
                if (sha256(withoutChecksum) != savedChecksum) {
                    // Checksum mismatch — could be old format, still return data
                    android.util.Log.w("InventStorage", "ZCP checksum mismatch for $sessionId (format change), returning anyway")
                }
            }
            // Load search result content from disk
            val searchDir = File(getDir(ctx), "zcp_${sessionId}_sr")
            val loadedResults = base.searchResults.mapIndexed { i, sr ->
                val srFile = File(searchDir, "$i.txt")
                val content = if (srFile.exists()) srFile.readText() else sr.content
                SearchResult(sr.intent, content, sr.domain, sr.verified)
            }
            base.copy(searchResults = loadedResults)
        } catch (e: Exception) {
            android.util.Log.e("InventStorage", "Failed to load ZCP", e)
            null
        }
    }

    fun saveSession(ctx: Context, state: InventSessionState) {
        val obj = JSONObject().apply {
            put("sessionId", state.sessionId)
            put("phase", state.phase.name)
            put("model1Path", state.model1Path)
            put("model1Name", state.model1Name)
            put("model2Path", state.model2Path)
            put("model2Name", state.model2Name)
            put("researcherPath", state.researcherPath)
            put("researcherName", state.researcherName)
            put("model1ContextSize", state.model1ContextSize)
            put("model2ContextSize", state.model2ContextSize)
            put("searchRound", state.searchRound)
            put("mergeCount", state.mergeCount)
            put("offlineMode", state.offlineMode)
            put("sameModelMode", state.sameModelMode)
            put("currentFileIndex", state.currentFileIndex)
            put("totalFiles", state.totalFiles)
            put("chatTemplate", state.chatTemplate)
            val msgs = JSONArray()
            state.messages.forEach { m ->
                msgs.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    put("phase", m.phase.name)
                    put("thinkingContent", m.thinkingContent)
                })
            }
            put("messages", msgs)
        }
        File(getDir(ctx), "state_${state.sessionId}.json").writeText(obj.toString())
    }

    fun loadSession(ctx: Context, sessionId: String): InventSessionState? {
        val f = File(getDir(ctx), "state_${sessionId}.json")
        if (!f.exists()) return null
        return try {
            val obj = JSONObject(f.readText())
            val msgs = obj.optJSONArray("messages") ?: JSONArray()
            val msgList = (0 until msgs.length()).map { i ->
                val m = msgs.getJSONObject(i)
                InventMessage(
                    role = m.getString("role"),
                    content = m.getString("content"),
                    phase = InventPhase.valueOf(m.optString("phase", "QUESTIONING")),
                    thinkingContent = m.optString("thinkingContent", "")
                )
            }
            InventSessionState(
                sessionId = obj.getString("sessionId"),
                phase = InventPhase.valueOf(obj.getString("phase")),
                model1Path = obj.getString("model1Path"),
                model1Name = obj.getString("model1Name"),
                model2Path = obj.getString("model2Path"),
                model2Name = obj.getString("model2Name"),
                researcherPath = obj.getString("researcherPath"),
                researcherName = obj.getString("researcherName"),
                model1ContextSize = obj.getInt("model1ContextSize"),
                model2ContextSize = obj.getInt("model2ContextSize"),
                searchRound = obj.optInt("searchRound", 0),
                mergeCount = obj.optInt("mergeCount", 0),
                offlineMode = obj.optBoolean("offlineMode", false),
                sameModelMode = obj.optBoolean("sameModelMode", false),
                messages = msgList,
                currentFileIndex = obj.optInt("currentFileIndex", 0),
                totalFiles = obj.optInt("totalFiles", 0),
                chatTemplate = obj.optString("chatTemplate", "auto")
            )
        } catch (e: Exception) { null }
    }

    fun deleteSession(ctx: Context, sessionId: String) {
        File(getDir(ctx), "zcp_${sessionId}.json").delete()
        File(getDir(ctx), "state_${sessionId}.json").delete()
        File(getDir(ctx), "searchlog_${sessionId}.json").delete()
        // Also delete search result files
        File(getDir(ctx), "zcp_${sessionId}_sr").deleteRecursively()
        // Also delete generated projects
        File(ctx.filesDir, "invent_projects/$sessionId").deleteRecursively()
    }

    fun listSessions(ctx: Context): List<String> {
        return getDir(ctx).listFiles()
            ?.filter { it.name.startsWith("state_") }
            ?.map { it.name.removePrefix("state_").removeSuffix(".json") }
            ?: emptyList()
    }

    fun saveSearchLog(ctx: Context, sessionId: String, log: String) {
        File(getDir(ctx), "searchlog_${sessionId}.json").writeText(log)
    }

    fun deleteSearchLog(ctx: Context, sessionId: String) {
        File(getDir(ctx), "searchlog_${sessionId}.json").delete()
    }

    fun saveDomainRegistry(ctx: Context, entries: List<DomainEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("category", e.category)
                put("domain", e.domain)
                put("lastUpdated", e.lastUpdated)
            })
        }
        File(ctx.filesDir, DOMAIN_FILE).writeText(arr.toString(2))
    }

    fun loadDomainRegistry(ctx: Context): List<DomainEntry> {
        val f = File(ctx.filesDir, DOMAIN_FILE)
        if (!f.exists()) return DEFAULT_DOMAIN_REGISTRY
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DomainEntry(o.getString("category"), o.getString("domain"), o.optString("lastUpdated", ""))
            }
        } catch (e: Exception) { DEFAULT_DOMAIN_REGISTRY }
    }

    fun resolveDomainsForCategory(ctx: Context, category: String): List<String> {
        return loadDomainRegistry(ctx)
            .filter { it.category.equals(category, ignoreCase = true) }
            .map { it.domain }
            .take(2)
    }

    /**
     * Compresses conversation history when the context limit is near.
     * Removes older messages beyond a threshold and replaces them with
     * a compact summary, keeping only the most recent [keepRecent] messages
     * intact.  This prevents runaway context growth in single-model mode.
     */
    fun compressMessages(
        messages: List<InventMessage>,
        maxMessages: Int = 30,
        keepRecent: Int = 8
    ): List<InventMessage> {
        if (messages.size <= maxMessages) return messages

        val recent = messages.takeLast(keepRecent)
        val toCompress = messages.dropLast(keepRecent)

        // Count roles in the compressed segment
        val userCount = toCompress.count { it.role == "user" }
        val asstCount = toCompress.count { it.role == "assistant" }

        val summary = InventMessage(
            role = "system",
            content = "[Earlier conversation compressed: $userCount user turns, " +
                "$asstCount assistant turns — key decisions preserved above.]",
            phase = toCompress.lastOrNull()?.phase ?: InventPhase.DONE,
            thinkingContent = ""
        )
        return listOf(summary) + recent
    }

    /**
     * Resolve [filePath] relative to [projectDir], throwing [SecurityException]
     * if the result escapes the project directory (path traversal prevention).
     * Also rejects null bytes and absolute paths in [filePath].
     */
    private fun resolveSafe(projectDir: File, filePath: String): File {
        require(filePath.indexOf('\u0000') < 0) { "Null byte in file path" }
        require(!File(filePath).isAbsolute) { "Absolute path not allowed: $filePath" }
        val canonicalRoot = projectDir.canonicalFile
        val target = File(projectDir, filePath).canonicalFile
        val rootPath = if (canonicalRoot.path.endsWith(File.separator)) canonicalRoot.path else canonicalRoot.path + File.separator
        if (!target.path.startsWith(rootPath) && target != canonicalRoot) {
            throw SecurityException("Path traversal attempt: $filePath")
        }
        return target
    }

    // ── Project directory helpers ─────────────────────────────────────────────

    fun getProjectDir(ctx: Context, sessionId: String, projectName: String): File {
        val dir = resolveSafe(ctx.filesDir, "invent_projects/${projectName.ifEmpty { sessionId }}")
        dir.mkdirs()
        return dir
    }

    fun writeGeneratedFile(projectDir: File, filePath: String, content: String) {
        val f = resolveSafe(projectDir, filePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun readGeneratedFile(projectDir: File, filePath: String): String? {
        val f = resolveSafe(projectDir, filePath)
        return if (f.exists()) f.readText() else null
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun zcpToJson(zcp: ZcpProtocol): String {
        val obj = JSONObject().apply {
            put("version", zcp.version)
            put("projectName", zcp.projectName)
            put("platform", JSONArray(zcp.platform))
            put("language", JSONArray(zcp.language))
            put("framework", zcp.framework)
            put("coreIdea", zcp.coreIdea)
            put("mainFeatures", JSONArray(zcp.mainFeatures))
            put("uniquePoint", zcp.uniquePoint)
            put("viable", zcp.viable)
            put("viabilityNote", zcp.viabilityNote)
            put("model2ContextSize", zcp.model2ContextSize)
            put("offlineMode", zcp.offlineMode)
            put("checksum", zcp.checksum)
            put("phase", zcp.phase.name)
            put("mergeCount", zcp.mergeCount)
            val intents = JSONArray()
            zcp.searchIntents.forEach { si ->
                intents.put(JSONObject().apply {
                    put("topic", si.topic)
                    put("platform", si.platform)
                    put("question", si.question)
                    put("category", si.category)
                    put("offline", si.offline)
                })
            }
            put("searchIntents", intents)
            val results = JSONArray()
            zcp.searchResults.forEach { sr ->
                results.put(JSONObject().apply {
                    put("content", sr.content)
                    put("domain", sr.domain)
                    put("verified", sr.verified)
                    put("intentTopic", sr.intent.topic)
                })
            }
            put("searchResults", results)
            val tree = JSONArray()
            zcp.fileTree.forEach { fn ->
                tree.put(JSONObject().apply {
                    put("path", fn.path)
                    put("isDir", fn.isDir)
                    put("description", fn.description)
                })
            }
            put("fileTree", tree)
            put("chunks", JSONArray(zcp.chunks))
            // File specs
            val specsObj = JSONObject()
            zcp.fileSpecs.forEach { (path, spec) ->
                specsObj.put(path, JSONObject().apply {
                    put("path", spec.path)
                    put("description", spec.description)
                    put("imports", spec.imports)
                    put("classes", spec.classes)
                    put("functions", spec.functions)
                    put("dependencies", JSONArray(spec.dependencies))
                    if (spec.estimatedTokens > 0) put("estimatedTokens", spec.estimatedTokens)
                    if (spec.continuationOf.isNotEmpty()) put("continuationOf", spec.continuationOf)
                })
            }
            put("fileSpecs", specsObj)
            // Generated file paths (content is on disk)
            put("generatedFiles", JSONArray(zcp.generatedFiles))
            // Debug sessions
            val debugArr = JSONArray()
            zcp.debugSessions.forEach { ds ->
                debugArr.put(JSONObject().apply {
                    put("filePath", ds.filePath)
                    put("problem", ds.problem)
                    put("originalCode", ds.originalCode)
                    put("fixedCode", ds.fixedCode)
                    put("timestamp", ds.timestamp)
                })
            }
            put("debugSessions", debugArr)
        }
        return obj.toString()
    }

    private fun jsonToZcp(obj: JSONObject): ZcpProtocol {
        fun jsonArrayToList(arr: JSONArray?) = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
        val intentsArr = obj.optJSONArray("searchIntents") ?: JSONArray()
        val intents = (0 until intentsArr.length()).map { i ->
            val o = intentsArr.getJSONObject(i)
            SearchIntent(o.getString("topic"), o.getString("platform"), o.getString("question"), o.getString("category"), o.optBoolean("offline", false))
        }
        val resultsArr = obj.optJSONArray("searchResults") ?: JSONArray()
        val results = (0 until resultsArr.length()).map { i ->
            val o = resultsArr.getJSONObject(i)
            SearchResult(SearchIntent(o.optString("intentTopic",""),"","",""), o.getString("content"), o.getString("domain"), o.optBoolean("verified", true))
        }
        val treeArr = obj.optJSONArray("fileTree") ?: JSONArray()
        val tree = (0 until treeArr.length()).map { i ->
            val o = treeArr.getJSONObject(i)
            FileNode(o.getString("path"), o.getBoolean("isDir"), o.optString("description",""))
        }
        // File specs
        val specsObj = obj.optJSONObject("fileSpecs")
        val fileSpecs = if (specsObj != null) {
            val m = mutableMapOf<String, FileSpec>()
            val keys = specsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val o = specsObj.getJSONObject(key)
                val deps = o.optJSONArray("dependencies")
                m[key] = FileSpec(
                    path = o.optString("path", key),
                    description = o.optString("description", ""),
                    imports = o.optString("imports", ""),
                    classes = o.optString("classes", ""),
                    functions = o.optString("functions", ""),
                    dependencies = if (deps != null) (0 until deps.length()).map { deps.getString(it) } else emptyList(),
                    estimatedTokens = o.optInt("estimatedTokens", 0),
                    continuationOf = o.optString("continuationOf", "")
                )
            }
            m
        } else emptyMap()
        // Generated file paths (content is on disk)
        // Handle both old format (JSONObject of key-value pairs) and new format (JSONArray of paths)
        val generatedFiles = try {
            val genObj = obj.optJSONObject("generatedFiles")
            if (genObj != null) {
                // Old format: Map<String, String> — just extract keys
                val keys = genObj.keys()
                val list = mutableListOf<String>()
                while (keys.hasNext()) list.add(keys.next())
                list
            } else {
                val genArr = obj.optJSONArray("generatedFiles")
                if (genArr != null) {
                    (0 until genArr.length()).map { genArr.getString(it) }
                } else emptyList()
            }
        } catch (_: Exception) { emptyList() }
        // Debug sessions
        val debugArr = obj.optJSONArray("debugSessions") ?: JSONArray()
        val debugSessions = (0 until debugArr.length()).map { i ->
            val o = debugArr.getJSONObject(i)
            DebugSession(
                filePath = o.getString("filePath"),
                problem = o.getString("problem"),
                originalCode = o.getString("originalCode"),
                fixedCode = o.getString("fixedCode"),
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        }
        return ZcpProtocol(
            version = obj.optInt("version", 1),
            projectName = obj.optString("projectName",""),
            platform = jsonArrayToList(obj.optJSONArray("platform")),
            language = jsonArrayToList(obj.optJSONArray("language")),
            framework = obj.optString("framework",""),
            coreIdea = obj.optString("coreIdea",""),
            mainFeatures = jsonArrayToList(obj.optJSONArray("mainFeatures")),
            uniquePoint = obj.optString("uniquePoint",""),
            viable = obj.optBoolean("viable", true),
            viabilityNote = obj.optString("viabilityNote",""),
            searchIntents = intents,
            searchResults = results,
            fileTree = tree,
            chunks = jsonArrayToList(obj.optJSONArray("chunks")),
            model2ContextSize = obj.optInt("model2ContextSize", 4096),
            offlineMode = obj.optBoolean("offlineMode", false),
            checksum = obj.optString("checksum",""),
            phase = try { InventPhase.valueOf(obj.optString("phase","QUESTIONING")) } catch(e:Exception){ InventPhase.QUESTIONING },
            mergeCount = obj.optInt("mergeCount", 0),
            fileSpecs = fileSpecs,
            generatedFiles = generatedFiles,
            debugSessions = debugSessions
        )
    }
}
