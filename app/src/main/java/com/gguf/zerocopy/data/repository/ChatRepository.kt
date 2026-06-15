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
  var messageCount: Int = 0
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

class ChatRepository(context: Context) {
  private val sessionsDir = File(context.filesDir, "sessions").also { it.mkdirs() }
  private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
  val sessions = _sessions.asStateFlow()
  var currentSessionId: String? = null
    private set

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
              messageCount = j.optInt("messageCount", 0)
            )
          } catch (_: Exception) {
            null
          }
        }.sortedByDescending { it.lastMessageAt }
  }

  fun createSession(name: String? = null): ChatSession {
    val session = ChatSession(name = name ?: generateSessionName())
    saveSessionMeta(session)
    currentSessionId = session.id
    loadSessions()
    return session
  }

  fun selectSession(id: String) {
    currentSessionId = id
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
            it != "null" &&
              it.isNotEmpty()
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

  fun addMessage(sessionId: String, message: ChatMessage) {
    val messages = getMessages(sessionId).toMutableList()
    messages.add(message)
    saveMessages(sessionId, messages)
    val metaFile = File(sessionsDir, "${sessionId}_meta.json")
    if (metaFile.exists()) {
      try {
        val j = JSONObject(metaFile.readText())
        j.put("lastMessageAt", message.timestamp)
        j.put("messageCount", messages.size)
        metaFile.writeText(j.toString())
      } catch (_: Exception) {
      }
    }
    loadSessions()
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

  fun deleteSession(sessionId: String) {
    File(sessionsDir, "${sessionId}_meta.json").delete()
    File(sessionsDir, "${sessionId}_messages.json").delete()
    if (currentSessionId == sessionId) currentSessionId = null
    loadSessions()
  }

  fun exportSession(sessionId: String): String {
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
      sb.appendLine()
    }
    return sb.toString()
  }

  private fun saveMessages(sessionId: String, messages: List<ChatMessage>) {
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
    File(sessionsDir, "${sessionId}_messages.json").writeText(json.toString())
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
        }.toString()
    )
  }

  private fun generateSessionName(): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return "Chat ${sdf.format(Date())}"
  }
}
