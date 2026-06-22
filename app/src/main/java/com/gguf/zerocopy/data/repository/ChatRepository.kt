package com.gguf.zerocopy.data.repository

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

  init {
    loadSessions()
  }

  private fun loadSessions() {
    _sessions.value =
      (sessionsDir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith("_meta.json") }
        .mapNotNull { file ->
          try {
            val j = JSONObject(file.readText())
            ChatSession(
              id = j.getString("id"),
              name = j.getString("name"),
              createdAt = j.getLong("createdAt"),
              lastMessageAt = j.getLong("lastMessageAt"),
              messageCount = j.optInt("messageCount", 0),
              modelPath = j.optString("modelPath", ""),
              modelName = j.optString("modelName", "")
            )
          } catch (_: Exception) {
            null
          }
        }.sortedByDescending { it.lastMessageAt }
  }

  fun createSession(
    name: String? = null,
    modelPath: String = "",
    modelName: String = ""
  ): ChatSession {
    android.util.Log.d("ChatRepository", "Creating new session with model: $modelName")
    val session = ChatSession(
      name = name ?: generateSessionName(),
      modelPath = modelPath,
      modelName = modelName
    )
    saveSessionMeta(session)
    currentSessionId = session.id
    _currentSessionId.value = session.id
    _currentMessages.value = emptyList()
    loadSessions()
    android.util.Log.d("ChatRepository", "Session created: ${session.id}")
    return session
  }

  fun selectSession(id: String) {
    android.util.Log.d("ChatRepository", "selectSession: $id")
    currentSessionId = id
    _currentSessionId.value = id
    _currentMessages.value = getMessages(id)
  }

  fun getMessages(sessionId: String): List<ChatMessage> {
    val file = File(sessionsDir, "${sessionId}_messages.json")
    if (!file.exists()) return emptyList()
    return try {
      val arr = JSONArray(file.readText())
      (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        ChatMessage(
          role = MessageRole.valueOf(obj.getString("role").uppercase()),
          content = obj.getString("content"),
          timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
          tps = obj.optDouble("tps", 0.0).toFloat(),
          tokens = obj.optInt("tokens", 0),
          attachmentPath = obj.optString("attachment", null).takeIf {
            it != "null" && it.isNotEmpty()
          },
          attachmentType =
          obj
            .optString(
              "attachmentType",
              null
            ).takeIf { it != "null" && it.isNotEmpty() }
            ?.let { AttachmentType.valueOf(it) }
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun replaceMessage(sessionId: String, index: Int, message: ChatMessage, persist: Boolean = true) {
    if (sessionId != currentSessionId) {
      val messages = getMessages(sessionId).toMutableList()
      if (index < 0 || index >= messages.size) return
      messages[index] = message
      atomicWrite(sessionId, messages)
      return
    }
    val list = _currentMessages.value.toMutableList()
    if (index < 0 || index >= list.size) return
    list[index] = message
    _currentMessages.value = list
    if (persist) atomicWrite(sessionId, list)
  }

  fun addMessage(sessionId: String, message: ChatMessage) {
    android.util.Log.d("ChatRepository", "addMessage to session: $sessionId, role: ${message.role}")
    if (sessionId != currentSessionId) {
      val messages = getMessages(sessionId).toMutableList()
      messages.add(message)
      atomicWrite(sessionId, messages)
      updateSessionMeta(sessionId, message.timestamp, messages.size)
      return
    }
    val updated = _currentMessages.value + message
    _currentMessages.value = updated
    atomicWrite(sessionId, updated)
    updateSessionMeta(sessionId, message.timestamp, updated.size)
  }

  fun renameSession(sessionId: String, name: String) {
    val file = File(sessionsDir, "${sessionId}_meta.json")
    if (file.exists()) {
      try {
        val j = JSONObject(file.readText())
        j.put("name", name)
        file.writeText(j.toString())
        loadSessions()
      } catch (_: Exception) {
      }
    }
  }

  fun updateSessionModel(sessionId: String, modelPath: String, modelName: String) {
    val file = File(sessionsDir, "${sessionId}_meta.json")
    if (file.exists()) {
      try {
        val j = JSONObject(file.readText())
        j.put("modelPath", modelPath)
        j.put("modelName", modelName)
        file.writeText(j.toString())
        _sessions.value = _sessions.value.map {
          if (it.id == sessionId) it.copy(modelPath = modelPath, modelName = modelName)
          else it
        }
      } catch (_: Exception) {}
    }
  }

  fun refreshSessions() {
    loadSessions()
  }

  fun deleteSession(sessionId: String) {
    val msgs = getMessages(sessionId)
    msgs.forEach { msg ->
      msg.attachmentPath?.let { File(it).delete() }
    }
    File(sessionsDir, "${sessionId}_meta.json").delete()
    File(sessionsDir, "${sessionId}_messages.json").delete()
    if (currentSessionId == sessionId) {
      currentSessionId = null
      _currentSessionId.value = null
      _currentMessages.value = emptyList()
    }
    loadSessions()
  }

  fun sessionExists(sessionId: String): Boolean =
    File(sessionsDir, "${sessionId}_meta.json").exists()

  fun deleteMessage(sessionId: String, index: Int) {
    if (sessionId != currentSessionId) {
      val messages = getMessages(sessionId).toMutableList()
      if (index < 0 || index >= messages.size) return
      messages[index].attachmentPath?.let { File(it).delete() }
      messages.removeAt(index)
      atomicWrite(sessionId, messages)
      return
    }
    val list = _currentMessages.value.toMutableList()
    if (index < 0 || index >= list.size) return
    list[index].attachmentPath?.let { File(it).delete() }
    list.removeAt(index)
    _currentMessages.value = list
    atomicWrite(sessionId, list)
  }

  fun importSession(jsonContent: String): ChatSession? {
    return try {
      val arr = JSONArray(jsonContent)
      val session = createSession()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val msg = ChatMessage(
          role = MessageRole.valueOf(obj.getString("role").uppercase()),
          content = obj.getString("content"),
          timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
          tps = obj.optDouble("tps", 0.0).toFloat(),
          tokens = obj.optInt("tokens", 0)
        )
        addMessage(session.id, msg)
      }
      session
    } catch (_: Exception) {
      null
    }
  }

  fun exportSession(sessionId: String): File? {
    val messages = getMessages(sessionId)
    val session = _sessions.value.find { it.id == sessionId }
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
    } catch (_: Exception) {
      null
    }
  }

  private fun atomicWrite(sessionId: String, messages: List<ChatMessage>) {
    val json = JSONArray()
    messages.forEach { msg ->
      json.put(
        JSONObject().apply {
          put("role", msg.role.name.lowercase())
          put("content", msg.content)
          put("timestamp", msg.timestamp)
          put("tps", msg.tps.toDouble())
          put("tokens", msg.tokens)
          if (msg.attachmentPath != null) put("attachment", msg.attachmentPath)
          if (msg.attachmentType != null) put("attachmentType", msg.attachmentType.name)
        }
      )
    }
    val file = File(sessionsDir, "${sessionId}_messages.json")
    val tmp = File(sessionsDir, "${sessionId}_messages.json.tmp")
    tmp.writeText(json.toString())
    tmp.renameTo(file)
  }

  private fun updateSessionMeta(sessionId: String, timestamp: Long, messageCount: Int) {
    val file = File(sessionsDir, "${sessionId}_meta.json")
    if (file.exists()) {
      try {
        val j = JSONObject(file.readText())
        j.put("lastMessageAt", timestamp)
        j.put("messageCount", messageCount)
        file.writeText(j.toString())
      } catch (_: Exception) {}
    }
    _sessions.value = _sessions.value.map {
      if (it.id == sessionId) it.copy(lastMessageAt = timestamp, messageCount = messageCount)
      else it
    }.sortedByDescending { it.lastMessageAt }
  }

  private fun saveSessionMeta(session: ChatSession) {
    File(sessionsDir, "${session.id}_meta.json").writeText(
      JSONObject()
        .apply {
          put("id", session.id)
          put("name", session.name)
          put("createdAt", session.createdAt)
          put("lastMessageAt", session.lastMessageAt)
          put("messageCount", session.messageCount)
          if (session.modelPath.isNotEmpty()) put("modelPath", session.modelPath)
          if (session.modelName.isNotEmpty()) put("modelName", session.modelName)
        }.toString()
    )
  }

  private fun generateSessionName(): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return "Chat ${sdf.format(Date())}"
  }
}
