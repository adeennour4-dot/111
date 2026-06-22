package com.gguf.zerocopy.data.repository

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ChatSession(
  val id: String = "session_${System.currentTimeMillis()}",
  val name: String = "Chat ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}",
  val createdAt: Long = System.currentTimeMillis(),
  var lastMessageAt: Long = System.currentTimeMillis(),
  var messageCount: Int = 0,
  var modelPath: String = "",
  var modelName: String = ""
)

data class ChatMessage(
  val role: MessageRole,
  val content: String,
  val timestamp: Long = System.currentTimeMillis(),
  val tps: Float = 0f,
  val tokens: Int = 0,
  val attachmentPath: String? = null,
  val attachmentType: AttachmentType? = null
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }

enum class AttachmentType { IMAGE, AUDIO, DOCUMENT }

class ChatRepository(private val context: Context) {

  private val sessionsDir = File(context.filesDir, "sessions").also { it.mkdirs() }
  private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
  val sessions = _sessions.asStateFlow()

  private val _currentSessionId = MutableStateFlow<String?>(null)
  val currentSessionIdFlow = _currentSessionId.asStateFlow()
  var currentSessionId: String? = null
    private set

  private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val currentMessages = _currentMessages.asStateFlow()

  // Single lock for file I/O — NOT held while emitting StateFlow values.
  // Using a simple ReentrantReadWriteLock; never call loadSessions() while
  // holding a write lock (would deadlock since loadSessions acquires read).
  private val lock = ReentrantReadWriteLock()

  init { loadSessions() }

  // ── Session list ──────────────────────────────────────────────────────────

  /** Re-read all _meta.json files and push the sorted list to the StateFlow. */
  private fun loadSessions() {
    // Read under read lock, then emit outside the lock.
    val loaded = lock.read {
      (sessionsDir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith("_meta.json") }
        .mapNotNull { file ->
          try {
            val j = JSONObject(file.readText())
            ChatSession(
              id            = j.getString("id"),
              name          = j.getString("name"),
              createdAt     = j.getLong("createdAt"),
              lastMessageAt = j.getLong("lastMessageAt"),
              messageCount  = j.optInt("messageCount", 0),
              modelPath     = j.optString("modelPath", ""),
              modelName     = j.optString("modelName", "")
            )
          } catch (_: Exception) { null }
        }
        .sortedByDescending { it.lastMessageAt }
    }
    _sessions.value = loaded   // safe: StateFlow emission is thread-safe
  }

  /** Call from UI when the session list needs to be fresh (e.g. before showing it). */
  fun refreshSessions() = loadSessions()

  // ── CRUD ──────────────────────────────────────────────────────────────────

  fun createSession(
    name: String? = null,
    modelPath: String = "",
    modelName: String = ""
  ): ChatSession {
    val session = ChatSession(
      name      = name ?: generateSessionName(),
      modelPath = modelPath,
      modelName = modelName
    )
    saveSessionMeta(session)
    currentSessionId = session.id
    _currentSessionId.value = session.id
    _currentMessages.value  = emptyList()
    loadSessions()
    return session
  }

  fun selectSession(id: String) {
    currentSessionId         = id
    _currentSessionId.value  = id
    _currentMessages.value   = getMessages(id)
  }

  fun getMessages(sessionId: String): List<ChatMessage> {
    val file = File(sessionsDir, "${sessionId}_messages.json")
    if (!file.exists()) return emptyList()
    return lock.read {
      try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
          val obj = arr.getJSONObject(i)
          ChatMessage(
            role           = MessageRole.valueOf(obj.getString("role").uppercase()),
            content        = obj.getString("content"),
            timestamp      = obj.optLong("timestamp", System.currentTimeMillis()),
            tps            = obj.optDouble("tps", 0.0).toFloat(),
            tokens         = obj.optInt("tokens", 0),
            attachmentPath = obj.optString("attachment", null)
                               ?.takeIf { it != "null" && it.isNotEmpty() },
            attachmentType = obj.optString("attachmentType", null)
                               ?.takeIf { it != "null" && it.isNotEmpty() }
                               ?.let { AttachmentType.valueOf(it) }
          )
        }
      } catch (_: Exception) { emptyList() }
    }
  }

  fun addMessage(sessionId: String, message: ChatMessage) {
    // Read messages without holding write lock (getMessages uses read lock)
    val messages = getMessages(sessionId).toMutableList()
    messages.add(message)
    lock.write {
      atomicWrite(sessionId, messages)
      val metaFile = File(sessionsDir, "${sessionId}_meta.json")
      if (metaFile.exists()) {
        try {
          val j = JSONObject(metaFile.readText())
          j.put("lastMessageAt", message.timestamp)
          j.put("messageCount", messages.size)
          metaFile.writeText(j.toString())
        } catch (_: Exception) {}
      }
    }
    // Emit outside the lock
    if (sessionId == currentSessionId) {
      _currentMessages.value = messages
    }
    // Don't call loadSessions() here — lazy refresh when session list opens
  }

  fun renameSession(sessionId: String, name: String) {
    lock.write {
      val file = File(sessionsDir, "${sessionId}_meta.json")
      if (!file.exists()) return
      try {
        val j = JSONObject(file.readText())
        j.put("name", name)
        file.writeText(j.toString())
      } catch (_: Exception) { return }
    }
    loadSessions() // safe — called after write lock released
  }

  fun deleteSession(sessionId: String) {
    // Collect attachment paths without holding write lock
    val attachPaths = getMessages(sessionId).mapNotNull { it.attachmentPath }
    var wasCurrentSession = false
    lock.write {
      attachPaths.forEach { File(it).delete() }
      File(sessionsDir, "${sessionId}_meta.json").delete()
      File(sessionsDir, "${sessionId}_messages.json").delete()
      if (currentSessionId == sessionId) {
        wasCurrentSession        = true
        currentSessionId         = null
        _currentSessionId.value  = null
      }
    }
    if (wasCurrentSession) _currentMessages.value = emptyList()
    loadSessions()
  }

  fun deleteMessage(sessionId: String, index: Int) {
    // Read messages WITHOUT the lock first, then write under lock
    val current = getMessages(sessionId).toMutableList()
    if (index < 0 || index >= current.size) return
    current[index].attachmentPath?.let { File(it).delete() }
    current.removeAt(index)
    lock.write { atomicWrite(sessionId, current) }
    if (sessionId == currentSessionId) {
      _currentMessages.value = current
    }
  }

  fun sessionExists(sessionId: String): Boolean =
    File(sessionsDir, "${sessionId}_meta.json").exists()

  // ── Export ────────────────────────────────────────────────────────────────

  fun exportSession(sessionId: String): File? {
    val messages = getMessages(sessionId)
    val session  = _sessions.value.find { it.id == sessionId }
    val sb = StringBuilder()
    sb.appendLine("=== ZeroCopy Chat Export ===")
    sb.appendLine("Session: ${session?.name ?: sessionId}")
    sb.appendLine("Messages: ${messages.size}\n")
    messages.forEach { msg ->
      val ts = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
      sb.appendLine("[$ts] ${msg.role.name}:")
      sb.appendLine(msg.content)
      if (msg.attachmentPath != null) sb.appendLine("[Attachment: ${msg.attachmentPath}]")
      sb.appendLine("<end>")
    }
    return try {
      val exportDir = File(context.cacheDir, "exports").also { it.mkdirs() }
      val file = File(exportDir, "${sessionId}_export.txt")
      file.writeText(sb.toString())
      file
    } catch (_: Exception) { null }
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  /** Atomic write via temp file rename (prevents corrupt JSON on crash). */
  private fun atomicWrite(sessionId: String, messages: List<ChatMessage>) {
    val json = JSONArray()
    messages.forEach { msg ->
      json.put(JSONObject().apply {
        put("role",      msg.role.name.lowercase())
        put("content",   msg.content)
        put("timestamp", msg.timestamp)
        put("tps",       msg.tps.toDouble())
        put("tokens",    msg.tokens)
        if (msg.attachmentPath != null) put("attachment",     msg.attachmentPath)
        if (msg.attachmentType != null) put("attachmentType", msg.attachmentType.name)
      })
    }
    val file = File(sessionsDir, "${sessionId}_messages.json")
    val tmp  = File(sessionsDir, "${sessionId}_messages.json.tmp")
    tmp.writeText(json.toString())
    tmp.renameTo(file)
  }

  private fun saveSessionMeta(session: ChatSession) {
    File(sessionsDir, "${session.id}_meta.json").writeText(
      JSONObject().apply {
        put("id",            session.id)
        put("name",          session.name)
        put("createdAt",     session.createdAt)
        put("lastMessageAt", session.lastMessageAt)
        put("messageCount",  session.messageCount)
        if (session.modelPath.isNotEmpty()) put("modelPath", session.modelPath)
        if (session.modelName.isNotEmpty()) put("modelName", session.modelName)
      }.toString()
    )
  }

  private fun generateSessionName(): String =
    "Chat ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}"
}
