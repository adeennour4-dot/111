package com.gguf.zerocopy.ui.chat

import com.gguf.zerocopy.data.repository.AttachmentType
import com.gguf.zerocopy.data.repository.ChatMessage
import com.gguf.zerocopy.data.repository.MessageRole
import com.gguf.zerocopy.domain.inference.InferenceEngine
import com.gguf.zerocopy.domain.inference.TokenCallback
import com.gguf.zerocopy.domain.rag.RagEngine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface InferenceState {
  data object Idle : InferenceState
  data class Preparing(val step: String = "Preparing…") : InferenceState
  data class Streaming(
    val content: String,
    val tokens: Int,
    val tps: Float,
    val thinkingContent: String? = null,
    val showThinking: Boolean = false,
    val kvUsagePercent: Int = 0
  ) : InferenceState
  data class Completed(val fullResponse: String, val tokens: Int, val tps: Float) : InferenceState
  data class Error(val message: String) : InferenceState
  data class Stopped(val partialContent: String) : InferenceState
}

class InferenceController(
  private val scope: CoroutineScope,
  private val engine: InferenceEngine,
  private val ragEngine: RagEngine,
  private val chatRepository: com.gguf.zerocopy.data.repository.ChatRepository,
  private val context: android.content.Context,
  private val settingsManager: com.gguf.zerocopy.data.local.SettingsManager,
  private val modelPath: String,
  private val modelName: String,
  private val modelReasoningOk: Boolean,
  private val onMessageSent: (String) -> Unit,
  private val onError: (String) -> Unit,
) {
  private val _state = MutableStateFlow<InferenceState>(InferenceState.Idle)
  val state = _state.asStateFlow()

  private var currentJob: Job? = null
  private var flushJob: Job? = null
  private var runningFlag = AtomicBoolean(false)
  private var currentChatId: String? = null
  private var stopRequested = false

  fun sendMessage(
    text: String,
    uris: List<android.net.Uri>,
    names: List<String>,
    chatId: String?,
    reasoningEnabled: Boolean,
    ragEnabled: Boolean,
    webSearchEnabled: Boolean
  ) {
    stopCurrentInference()
    
    currentJob = scope.launch {
      val id = chatId ?: run {
        val s = chatRepository.createSession(modelPath = modelPath, modelName = modelName)
        settingsManager.currentSessionId = s.id
        s.id
      }
      currentChatId = id

      if (!engine.isModelLoaded) {
        _state.value = InferenceState.Error("No model loaded")
        onError("No model loaded")
        return@launch
      }

      runningFlag.set(true)
      stopRequested = false
      _state.value = InferenceState.Preparing("Processing attachments…")

      val savedPaths = mutableListOf<String>()
      var attachType: AttachmentType? = null
      var ragContext = ""

      if (uris.isNotEmpty()) {
        try {
          withContext(Dispatchers.IO) {
            uris.forEach { uri ->
              val mime = context.contentResolver.getType(uri) ?: ""
              when {
                mime.startsWith("image/") -> {
                  saveAttachmentToStorage(uri)?.let { path ->
                    savedPaths.add(path)
                    if (attachType == null) attachType = AttachmentType.IMAGE
                  }
                  try { ragEngine.ingest(uri, context) } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                      onError("Document too large — only first portion indexed")
                    }
                  }
                }
                mime == "application/pdf" -> {
                  if (attachType == null) attachType = AttachmentType.DOCUMENT
                  try { ragEngine.ingest(uri, context) } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                      onError("PDF too large — only first portion indexed")
                    }
                  }
                }
                mime.startsWith("text/") -> {
                  if (attachType == null) attachType = AttachmentType.DOCUMENT
                  try { ragEngine.ingest(uri, context) } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                      onError("File too large — only first portion indexed")
                    }
                  }
                }
                mime.startsWith("audio/") -> {
                  if (attachType == null) attachType = AttachmentType.AUDIO
                }
                else -> {
                  if (attachType == null) attachType = AttachmentType.DOCUMENT
                }
              }
            }
          }
        } catch (oom: OutOfMemoryError) {
          ragEngine.clear()
          _state.value = InferenceState.Error("Not enough memory to process this document")
          onError("Not enough memory to process this document")
          return@launch
        }
      }

      if (ragEngine.hasDocuments && ragEnabled) {
        _state.value = InferenceState.Preparing("Retrieving context…")
        withContext(Dispatchers.IO) {
          try {
            ragContext = ragEngine.retrieve(
              text,
              maxChunks = settingsManager.ragMaxChunks,
              maxChars = settingsManager.ragMaxChars,
              minScore = settingsManager.ragMinScore
            )
          } catch (_: OutOfMemoryError) {
            ragContext = ""
          }
        }
      }

      val finalPrompt = buildString {
        append(text)
        if (ragContext.isNotEmpty()) {
          appendLine()
          appendLine()
          append(ragContext)
        }
      }

      val userMsg = ChatMessage(
        role = MessageRole.USER,
        content = text,
        attachmentPath = savedPaths.firstOrNull(),
        attachmentType = attachType
      )
      chatRepository.addMessage(id, userMsg)

      val tokenBuffer = AtomicReference("")
      val startTime = System.currentTimeMillis()
      val rawResponse = StringBuilder()
      var streamedTokens = 0

      val reasoningActive = reasoningEnabled && modelReasoningOk
      val initialThinking = if (reasoningActive) true else false

      _state.value = InferenceState.Streaming(
        content = "",
        tokens = 0,
        tps = 0f,
        showThinking = initialThinking
      )

      flushJob = scope.launch {
        while ((runningFlag.get() && !stopRequested) || tokenBuffer.get().isNotEmpty()) {
          val buffered = tokenBuffer.getAndSet("")
          if (buffered.isNotEmpty()) {
            rawResponse.append(buffered)
            streamedTokens += buffered.length
            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            val tps = if (elapsed > 0) streamedTokens.toFloat() / elapsed else 0f
            
            val thinking = extractThinking(rawResponse.toString())
            val display = removeThinking(rawResponse.toString())
            val showThinkingNow = reasoningActive || thinking != null
            
            _state.value = InferenceState.Streaming(
              content = display,
              tokens = streamedTokens,
              tps = tps,
              thinkingContent = thinking,
              showThinking = showThinkingNow
            )
          }
          delay(16L)
        }
      }

      val callback = object : TokenCallback {
        override fun onToken(token: String) {
          if (!runningFlag.get() || stopRequested) return
          tokenBuffer.getAndUpdate { it + token }
        }
        override fun onDone() {
          runningFlag.set(false)
          val elapsed = (System.currentTimeMillis() - startTime) / 1000f
          val tpsVal = if (elapsed > 0) streamedTokens.toFloat() / elapsed else 0f
          val raw = rawResponse.toString()
          
          scope.launch(Dispatchers.Main) {
            if (raw.isNotEmpty()) {
              chatRepository.addMessage(
                id,
                ChatMessage(role = MessageRole.ASSISTANT, content = raw, tps = tpsVal, tokens = streamedTokens)
              )
            }
            if (stopRequested) {
              _state.value = InferenceState.Stopped(raw)
            } else {
              _state.value = InferenceState.Completed(raw, streamedTokens, tpsVal)
            }
            onMessageSent(raw)
          }
        }
        override fun onError(error: String) {
          runningFlag.set(false)
          scope.launch(Dispatchers.Main) {
            _state.value = InferenceState.Error(error)
            onError(error)
          }
        }
        override fun onKvUsage(percent: Int) {
          scope.launch(Dispatchers.Main) {
            val current = _state.value
            if (current is InferenceState.Streaming) {
              _state.value = current.copy(kvUsagePercent = percent)
            }
          }
        }
        override fun onTokensGenerated(count: Int) {
          streamedTokens = count
        }
      }

      try {
        withContext(Dispatchers.IO) {
          val allHistory = chatRepository.getMessages(id)
          val historyPairs = allHistory.dropLast(1).map { msg ->
            msg.role.name.lowercase() to msg.content
          }
          engine.restoreHistory(historyPairs)
          val prompt = buildConversationPrompt(
            finalPrompt,
            reasoningEnabled && modelReasoningOk,
            ragContext.isNotEmpty(),
            webSearchEnabled && modelReasoningOk
          )
          if (savedPaths.isNotEmpty() && engine.hasVisionCapability) {
            engine.executeInferenceWithImage(prompt, savedPaths.first(), callback)
          } else {
            engine.executeInference(prompt, callback, searchQuery = text)
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("InferenceController", "Exception during inference: ${e.message}")
        runningFlag.set(false)
        _state.value = InferenceState.Error(e.message ?: "Unknown error")
        onError(e.message ?: "Unknown error")
      }
    }
  }

  fun stopInference() {
    stopRequested = true
    runningFlag.set(false)
    engine.abortInference()
    
    flushJob?.cancel()
    flushJob = null
    
    val current = _state.value
    if (current is InferenceState.Streaming) {
      _state.value = InferenceState.Stopped(current.content)
    } else if (current is InferenceState.Preparing) {
      _state.value = InferenceState.Stopped("")
    }
  }

  private fun stopCurrentInference() {
    stopRequested = true
    runningFlag.set(false)
    currentJob?.cancel()
    flushJob?.cancel()
    engine.abortInference()
    currentJob = null
    flushJob = null
  }

  private fun extractThinking(content: String): String? {
    val thinkRegex = java.util.regex.Pattern.compile("\\u200b\\u200b([\\s\\S]*?)\\u200b\\u200b")
    val matcher = thinkRegex.matcher(content)
    if (matcher.find()) {
      return matcher.group(1)
    }
    return null
  }

  private fun removeThinking(content: String): String {
    val thinkRegex = Regex("\\u200b\\u200b[\\s\\S]*?\\u200b\\u200b")
    return content.replaceFirst(thinkRegex, "").trim()
  }

  private fun saveAttachmentToStorage(uri: android.net.Uri): String? = try {
    val dir = java.io.File(context.filesDir, "attachments").also { it.mkdirs() }
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when {
      mime.contains("png") -> ".png"
      mime.contains("webp") -> ".webp"
      mime.contains("gif") -> ".gif"
      mime.contains("bmp") -> ".bmp"
      else -> ".jpg"
    }
    val name = "att_${System.currentTimeMillis()}$ext"
    val file = java.io.File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
      java.io.FileOutputStream(file).use { output -> input.copyTo(output) }
    }
    file.absolutePath
  } catch (_: Exception) {
    null
  }

  private fun buildConversationPrompt(
    currentUserText: String,
    useReasoning: Boolean,
    useRag: Boolean,
    useSearch: Boolean = false
  ): String {
    var prompt = currentUserText
    if (useRag && currentUserText.contains("[Relevant document excerpts]")) {
      prompt = "Use the document excerpts above to answer the user's question. If the excerpts don't contain the answer, say so.\n\n$prompt"
    }
    if (useReasoning) {
      if (useSearch) {
        prompt = "The web search for this question runs automatically before you answer. " +
                 "When you receive the search results, reason step by step and write your " +
                 "reason tags, then give your final answer " +
                 "using ONLY the search results for facts.\n\n$prompt"
      } else {
        prompt = "Think step by step before answering. Write your reasoning between <think> " +
                 "tags, then give your final answer after the closing tag.\n\n$prompt"
      }
    }
    return prompt
  }
}