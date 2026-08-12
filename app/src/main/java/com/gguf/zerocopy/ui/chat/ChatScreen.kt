package com.gguf.zerocopy.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning

import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.AttachmentType
import com.gguf.zerocopy.data.repository.ChatMessage
import com.gguf.zerocopy.data.repository.MessageRole
import com.gguf.zerocopy.domain.inference.TokenCallback
import com.gguf.zerocopy.domain.inference.ToolManager
import com.gguf.zerocopy.domain.rag.RagEngine
import com.gguf.zerocopy.ui.chat.components.ChatBubble
import com.gguf.zerocopy.ui.chat.components.DeleteConfirmDialog
import com.gguf.zerocopy.ui.chat.components.ExportSessionDialog
import com.gguf.zerocopy.ui.chat.components.InputBar
import com.gguf.zerocopy.ui.chat.components.getFileName
import com.gguf.zerocopy.ui.components.FuturisticFont
import com.gguf.zerocopy.ui.components.GradientBubbleBox
import com.gguf.zerocopy.ui.components.IdentityBorderBrush
import com.gguf.zerocopy.ui.components.IdentityCyan
import com.gguf.zerocopy.ui.components.IdentityGreen
import com.gguf.zerocopy.ui.components.IdentityPurple
import com.gguf.zerocopy.ui.components.IdentitySweepBrush
import com.gguf.zerocopy.ui.theme.currentPalette
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
  modelPath: String,
  modelName: String,
  sessionId: String?,
  onModelSelected: (path: String, name: String) -> Unit,
  onSessions: () -> Unit,
  onCloud: () -> Unit
) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val colors = currentPalette()
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val snackbarHostState = remember { SnackbarHostState() }
  @Suppress("UNUSED_PARAMETER")

  val engine = app.engineManager.getActiveEngine()
  val hasVision = engine?.hasVisionCapability == true

  var chatId by remember { mutableStateOf(sessionId) }
  var inferenceActive by remember { mutableStateOf(false) }
  var isInferring by remember { mutableStateOf(false) }
  var streamedContent by remember { mutableStateOf("") }
  var streamedTokens by remember { mutableIntStateOf(0) }
  var streamedTps by remember { mutableFloatStateOf(0f) }

  var kvUsagePercent by remember { mutableIntStateOf(0) }
  var showStreamingThinking by remember { mutableStateOf(false) }
  var reasoningPhaseActive by remember { mutableStateOf(false) }

  fun startNewChat() {
    chatId = null
    inferenceActive = false
    isInferring = false
    streamedContent = ""
    streamedTokens = 0
    streamedTps = 0f
    kvUsagePercent = 0
    showStreamingThinking = false
    reasoningPhaseActive = false
    // Actually create a new session in the repository
    app.chatRepository.createSession(modelPath = modelPath, modelName = modelName)
    chatId = app.chatRepository.currentSessionId
  }

  var attachmentUris by remember { mutableStateOf(listOf<Uri>()) }
  var attachmentFileNames by remember { mutableStateOf(listOf<String>()) }

  LaunchedEffect(sessionId) {
    if (sessionId != null) {
      chatId = sessionId
      app.chatRepository.selectSession(sessionId)
      SettingsManager.currentSessionId = sessionId
      // Clear any stale attachments from previous sessions
      attachmentUris = emptyList()
      attachmentFileNames = emptyList()
      // Reset the engine's KV cache so the new session starts fresh
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        app.engineManager.getActiveEngine()?.resetContext()
      }
    }
  }

  val messages by app.chatRepository.currentMessages.collectAsState()
  var cameraImageUriStr by rememberSaveable { mutableStateOf("") }
  var reasoningEnabled by remember { mutableStateOf(SettingsManager.reasoningEnabled) }
  var ragEnabled by remember { mutableStateOf(SettingsManager.ragEnabled) }
  var webSearchEnabled by remember { mutableStateOf(SettingsManager.webSearchEnabled) }

  // Set ToolManager on the active engine whenever search toggle OR engine changes
  LaunchedEffect(webSearchEnabled, engine) {
    val eng = engine ?: return@LaunchedEffect
    if (webSearchEnabled) {
      if (eng.getToolManager() == null) eng.setToolManager(ToolManager())
    } else {
      eng.setToolManager(null)
    }
    SettingsManager.webSearchEnabled = webSearchEnabled
  }
  var showExportDialog by remember { mutableStateOf(false) }

  // ── Text-to-Speech ────────────────────────────────────────────────────────
  var tts by remember { mutableStateOf<TextToSpeech?>(null) }
  var isSpeaking by remember { mutableStateOf(false) }
  androidx.compose.runtime.DisposableEffect(Unit) {
    val t = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) {
        tts?.language = Locale.getDefault()
      }
    }
    tts = t
    onDispose { t.stop(); t.shutdown() }
  }

  fun speakText(text: String) {
    val t = tts ?: return
    if (isSpeaking) {
      t.stop()
      isSpeaking = false
    } else {
      t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(id: String?) { isSpeaking = true }
        override fun onDone(id: String?) { isSpeaking = false }
        override fun onError(id: String?) { isSpeaking = false }
      })
      t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zc_tts")
      isSpeaking = true
    }
  }

  var deleteMsgIndex by remember { mutableIntStateOf(-1) }

  val suggestions = remember {
    listOf(
      "What can you do?",
      "Help me write a Python script",
      "Explain how neural networks work",
      "Write a creative short story",
      "Summarize the key features of Kotlin"
    )
  }

  val thinkRegex = remember { Regex("<think>([\\s\\S]*?)</think>") }

  // Duration of the initial reasoning phase (live stream shown in thinking section)
  val reasoningPhaseDurationMs = 3000L

  fun extractThinking(content: String): String? =
    thinkRegex.find(content)?.groupValues?.getOrNull(1)

  fun removeThinking(content: String): String =
    content.replace(thinkRegex, "").trim()

  val cameraLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK && cameraImageUriStr.isNotEmpty()) {
      val uri = Uri.parse(cameraImageUriStr)
      attachmentUris = attachmentUris + uri
      attachmentFileNames = attachmentFileNames + getFileName(context, uri)
    }
  }

  val docPickLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
    uris.forEach { uri ->
      attachmentUris = attachmentUris + uri
      attachmentFileNames = attachmentFileNames + getFileName(context, uri)
    }
  }

  fun launchCamera() {
    val photoFile = File(context.filesDir, "attachments").also { it.mkdirs() }
      .let { File(it, "camera_${System.nanoTime()}.jpg") }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    cameraImageUriStr = uri.toString()
    cameraLauncher.launch(
      Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    )
  }

  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) launchCamera()
    else scope.launch { snackbarHostState.showSnackbar("Camera permission denied") }
  }

  fun saveAttachmentToStorage(uri: Uri): String? = try {
    val dir = File(context.filesDir, "attachments").also { it.mkdirs() }
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when {
      mime.contains("png") -> ".png"
      mime.contains("webp") -> ".webp"
      mime.contains("gif") -> ".gif"
      mime.contains("bmp") -> ".bmp"
      else -> ".jpg"
    }
    val name = "att_${System.currentTimeMillis()}$ext"
    val file = File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
      FileOutputStream(file).use { output -> input.copyTo(output) }
    }
    file.absolutePath
  } catch (_: Exception) {
    null
  }

  fun buildConversationPrompt(currentUserText: String, useReasoning: Boolean, useRag: Boolean, useSearch: Boolean = false): String {
    var prompt = currentUserText
    if (useRag && currentUserText.contains("[Relevant document excerpts]")) {
      prompt = "Use the document excerpts above to answer the user's question. If the excerpts don't contain the answer, say so.\n\n$prompt"
    }
    if (useReasoning) {
      if (useSearch) {
        // Web search runs automatically (Kotlin-side, not model-emitted tool
        // calls — tiny GGUF models never emit them). The model just needs to
        // reason over the results it will be shown.
        prompt = "The web search for this question runs automatically before you answer. " +
                 "When you receive the search results, reason step by step and write your " +
                 "reasoning between <think> and </think> tags, then give your final answer " +
                 "using ONLY the search results for facts.\n\n$prompt"
      } else {
        // Directive reasoning prompt — works with ANY model, and Gemma-style
        // models (fine-tuned on <think> tags) reliably follow the explicit
        // instruction to write reasoning between the tags.
        prompt = "Think step by step before answering. Write your reasoning between " +
                 "<think> and </think> tags, then give your final answer after the closing tag.\n\n$prompt"
      }
    }
    return prompt
  }

  fun sendMessage(text: String, uris: List<Uri>, names: List<String>) {
    android.util.Log.d("ChatScreen", "sendMessage called with chatId: $chatId")
    val id = chatId ?: run {
      val s = app.chatRepository.createSession(modelPath = modelPath, modelName = modelName)
      SettingsManager.currentSessionId = s.id
      chatId = s.id
      s.id
    }

    val activeEngine = engine
    if (activeEngine?.isModelLoaded != true) {
      scope.launch { snackbarHostState.showSnackbar("No model loaded") }
      return
    }

    // Use AtomicBoolean to safely coordinate flushJob vs onDone/onError
    val runningFlag = AtomicBoolean(true)
    inferenceActive = true
    isInferring = true
    streamedContent = ""
    streamedTokens = 0
    streamedTps = 0f
    reasoningPhaseActive = reasoningEnabled
    if (reasoningEnabled) {
      showStreamingThinking = true
    } else {
      showStreamingThinking = false
    }

    val tokenBuffer = AtomicReference("")
    val startTime   = System.currentTimeMillis()
    val rawResponse = StringBuilder()

    val flushJob = scope.launch {
      // Use the local runningFlag (set by onDone/onError) OR the global
      // inferenceActive (set by stopInference) as exit conditions.
      while ((runningFlag.get() && inferenceActive) || tokenBuffer.get().isNotEmpty()) {
        val buffered = tokenBuffer.getAndSet("")
        if (buffered.isNotEmpty()) {
          streamedContent += buffered
          val elapsed = (System.currentTimeMillis() - startTime) / 1000f
          if (elapsed > 0) streamedTps = streamedTokens / elapsed
        }
        delay(16L)
      }
    }

    scope.launch {
      val savedPaths   = mutableListOf<String>()
      var attachType: AttachmentType? = null
      val ragEngine    = app.ragEngine
      var ragContext   = ""

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
                      snackbarHostState.showSnackbar("Document too large — only first portion indexed")
                    }
                  }
                }
                mime == "application/pdf" -> {
                  if (attachType == null) attachType = AttachmentType.DOCUMENT
                  try {
                    ragEngine.ingest(uri, context)
                  } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                      snackbarHostState.showSnackbar("PDF too large — only first portion indexed")
                    }
                  }
                }
                mime.startsWith("text/") -> {
                  if (attachType == null) attachType = AttachmentType.DOCUMENT
                  try { ragEngine.ingest(uri, context) } catch (_: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                      snackbarHostState.showSnackbar("File too large — only first portion indexed")
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
          // Top-level OOM guard — clears chunks added so far and shows error
          ragEngine.clear()
          withContext(Dispatchers.Main) {
            snackbarHostState.showSnackbar("Not enough memory to process this document")
            inferenceActive = false
            isInferring = false
          }
          return@launch
        }
      }

      if (ragEngine.hasDocuments && ragEnabled) {
        withContext(Dispatchers.IO) {
          try {
            ragContext = ragEngine.retrieve(
              text,
              maxChunks = com.gguf.zerocopy.data.local.SettingsManager.ragMaxChunks,
              maxChars = com.gguf.zerocopy.data.local.SettingsManager.ragMaxChars,
              minScore = com.gguf.zerocopy.data.local.SettingsManager.ragMinScore
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
        role           = MessageRole.USER,
        content        = text,
        attachmentPath = savedPaths.firstOrNull(),
        attachmentType = attachType
      )
      app.chatRepository.addMessage(id, userMsg)

      val currentChatId = id

      val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
      val callback = object : TokenCallback {
        override fun onToken(token: String) {
          if (!runningFlag.get()) return
          rawResponse.append(token)
          tokenBuffer.getAndUpdate { it + token }
          streamedTokens++
        }
        override fun onDone() {
          // Signal flushJob to stop BEFORE manipulating state (atomic, no main thread delay)
          runningFlag.set(false)
          val elapsed = (System.currentTimeMillis() - startTime) / 1000f
          val tpsVal  = if (elapsed > 0) streamedTokens / elapsed else 0f
          val raw = rawResponse.toString()
          mainHandler.post {
            // Always save partial response, even if inference was aborted
            if (raw.isNotEmpty()) {
              app.chatRepository.addMessage(
                currentChatId,
                ChatMessage(role = MessageRole.ASSISTANT, content = raw, tps = tpsVal, tokens = streamedTokens)
              )
            }
            inferenceActive  = false
            isInferring      = false
            kvUsagePercent   = 0
            reasoningPhaseActive = false
            streamedContent  = ""
          }
        }
        override fun onError(error: String) {
          runningFlag.set(false)
          mainHandler.post {
            inferenceActive = false
            isInferring     = false
            reasoningPhaseActive = false
            streamedContent = ""
            scope.launch { snackbarHostState.showSnackbar("Inference error: $error") }
          }
        }
        override fun onKvUsage(percent: Int) { mainHandler.post { kvUsagePercent = percent } }
        override fun onTokensGenerated(count: Int) { streamedTokens = count }
      }

      try {
        withContext(Dispatchers.IO) {
          val allHistory = app.chatRepository.getMessages(currentChatId)
          val historyPairs = allHistory.dropLast(1).map { msg ->
            msg.role.name.lowercase() to msg.content
          }
          activeEngine.restoreHistory(historyPairs)
          val prompt = buildConversationPrompt(finalPrompt, reasoningEnabled, ragContext.isNotEmpty(), webSearchEnabled)
          // Pass original user text as searchQuery so ToolAwareInference uses
          // the clean query (without reasoning/RAG instructions) for web search.
          if (savedPaths.isNotEmpty() && activeEngine.hasVisionCapability) {
            activeEngine.executeInferenceWithImage(prompt, savedPaths.first(), callback)
          } else {
            activeEngine.executeInference(prompt, callback, searchQuery = text)
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("ChatScreen", "Exception during inference: ${e.message}")
        runningFlag.set(false)
        if (inferenceActive) {
          inferenceActive = false
          isInferring     = false
          streamedContent = ""
        }
      }
    }
  }

  val speechLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
      val text = results?.firstOrNull()?.trim()
      if (!text.isNullOrEmpty()) {
        sendMessage(text, emptyList(), emptyList())
      }
    }
  }

  fun stopInference() {
    inferenceActive = false
    isInferring = false
    engine?.abortInference()
    // streamedContent is left intact until onDone() fires, then saved
    // to the repository and cleared. This preserves the partial response.
  }

  fun copyToClipboard(text: String) {
    clipboard.setPrimaryClip(ClipData.newPlainText("chat", text))
    scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
  }

  fun handleRegenerate(userMsgIndex: Int) {
    val id = chatId ?: return
    val allMsgs = app.chatRepository.getMessages(id)
    val userMsg = allMsgs.getOrNull(userMsgIndex) ?: return
    app.chatRepository.deleteMessage(id, userMsgIndex + 1)
    sendMessage(userMsg.content, emptyList(), emptyList())
  }

  fun handleDelete(index: Int) {
    val id = chatId ?: return
    if (index < 0) return
    app.chatRepository.deleteMessage(id, index)
    deleteMsgIndex = -1
  }

  fun handleExportText() {
    val id = chatId ?: return
    scope.launch(Dispatchers.IO) {
      val file = app.chatRepository.exportSession(id)
      if (file != null) {
        try {
          val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
          withContext(Dispatchers.Main) {
            context.startActivity(
              Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_STREAM, uri)
                  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share as Text"
              )
            )
          }
        } catch (_: Exception) {
          withContext(Dispatchers.Main) {
            clipboard.setPrimaryClip(ClipData.newPlainText("chat", file.readText()))
          }
        }
      }
      withContext(Dispatchers.Main) { showExportDialog = false }
    }
  }

  fun handleExportJson() {
    val id = chatId ?: return
    scope.launch(Dispatchers.IO) {
      try {
        val msgs = app.chatRepository.getMessages(id)
        val jsonArr = JSONArray()
        msgs.forEach { m ->
          jsonArr.put(
            JSONObject().apply {
              put("role", m.role.name.lowercase())
              put("content", m.content)
              put("timestamp", m.timestamp)
              if (m.tps > 0f) put("tps", m.tps.toDouble())
              if (m.tokens > 0) put("tokens", m.tokens)
            }
          )
        }
        val exportDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val jsonFile = File(exportDir, "chat_$id.json")
        jsonFile.writeText(jsonArr.toString(2))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", jsonFile)
        withContext(Dispatchers.Main) {
          context.startActivity(
            Intent.createChooser(
              Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              },
              "Export JSON"
            )
          )
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          clipboard.setPrimaryClip(ClipData.newPlainText("chat", e.message ?: "Export failed"))
        }
      }
      withContext(Dispatchers.Main) { showExportDialog = false }
    }
  }

  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  LaunchedEffect(streamedContent) {
    if (isInferring && streamedContent.isNotEmpty()) {
      listState.animateScrollToItem(messages.size)
    }
  }

  // Accessibility announcement for inference state
  if (isInferring) {
    com.gguf.zerocopy.ui.common.AccessibilityAnnouncement("Generating response")
  }

  // Auto-end reasoning phase after a timeout (shows model's initial output as
  // thinking, then reveals as answer moving forward).
  LaunchedEffect(reasoningPhaseActive) {
    if (reasoningPhaseActive) {
      delay(reasoningPhaseDurationMs)
      reasoningPhaseActive = false
      // Keep showStreamingThinking true so the ThinkingContent remains visible
      // (collapsed by default) for the user to expand and inspect.
    }
  }

  // Scroll to the latest message when inference finishes (streaming item removed)
  LaunchedEffect(isInferring) {
    if (!isInferring && messages.isNotEmpty()) {
      delay(50) // brief delay to let the list recompose after the streaming item is removed
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  val sessions by app.chatRepository.sessions.collectAsState()
  val sessionName = remember(chatId, sessions) {
    if (chatId != null) {
      sessions.find { it.id == chatId }?.name ?: "New Chat"
    } else "New Chat"
  }


  Scaffold(
    topBar = {
      Surface(color = colors.Bg) {
        Column {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Live status dot: teal = model ready, amber = generating, red = none loaded
          Box(
            Modifier.size(7.dp).clip(CircleShape).background(
              when {
                isInferring -> colors.Amber
                engine?.isModelLoaded == true -> colors.Accent2
                else -> colors.Red
              }
            )
          )
          Spacer(Modifier.width(8.dp))
          // Session name — the same gradient bubble as the input bubble
          Box(Modifier.weight(1f)) {
            GradientBubbleBox(
              circulating = isInferring,
              bubbleColor = colors.Card,
              shape = RoundedCornerShape(22.dp)
            ) {
              Text(
                text = sessionName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.Text,
                fontFamily = FuturisticFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)
              )
            }
          }
          if (modelName.isNotEmpty()) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = colors.Accent2.copy(alpha = 0.10f),
              border = BorderStroke(0.2.dp, colors.Accent2.copy(alpha = 0.25f)),
              modifier = Modifier.padding(end = 6.dp)
            ) {
              Text(
                text = modelName,
                fontSize = 8.5.sp,
                color = colors.Accent2,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }
          // New session — black pill with the cyan→purple identity ring (app-icon style)
          val newOrbPulse = rememberInfiniteTransition(label = "newOrb")
          val orbScale by newOrbPulse.animateFloat(
            initialValue = 0.96f, targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "newOrbScale"
          )
          Box(
            modifier = Modifier
              .size(30.dp)
              .clip(CircleShape)
              .graphicsLayer { scaleX = orbScale; scaleY = orbScale }
              .background(colors.Card)
              .border(0.2.dp, IdentitySweepBrush, CircleShape)
              .clickable { startNewChat() },
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Filled.Add, "New conversation", tint = if (colors.Bg.luminance() < 0.5f) IdentityCyan else IdentityPurple, modifier = Modifier.size(16.dp))
          }
          Spacer(Modifier.width(6.dp))
          // Sessions — circular
          ChatToolCircle(
            icon = Icons.Outlined.History,
            label = "Sessions",
            active = true,
            accent = IdentityPurple,
            onClick = { app.chatRepository.refreshSessions(); onSessions() }
          )
          Spacer(Modifier.width(6.dp))
          // Export — black pill + identity ring, matching the "+" orb
          Box(
            modifier = Modifier
              .size(30.dp)
              .clip(CircleShape)
              .background(colors.Card)
              .border(0.2.dp, IdentitySweepBrush, CircleShape)
              .clickable { if (chatId != null) showExportDialog = true },
            contentAlignment = Alignment.Center
          ) {
            Icon(
              Icons.Outlined.Share,
              "Export",
              tint = if (chatId != null && messages.isNotEmpty())
                (if (colors.Bg.luminance() < 0.5f) IdentityCyan else IdentityPurple) else colors.Text3,
              modifier = Modifier.size(15.dp)
            )
          }
          Spacer(Modifier.width(6.dp))
          // Unload — circular
          if (engine?.isModelLoaded == true) {
            ChatToolCircle(
              icon = Icons.Outlined.Close,
              label = "Unload model",
              active = true,
              accent = colors.Red,
              onClick = {
                scope.launch {
                  engine?.unloadModel()
                  startNewChat()
                }
              }
            )
          }
        }
      }
      }
    },
    bottomBar = {
      Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        // Small circles above the input bubble: think / search / clip
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          ChatToolCircle(
            icon = if (reasoningEnabled) Icons.Filled.Psychology else Icons.Outlined.Psychology,
            label = "Thinking",
            active = reasoningEnabled,
            accent = IdentityGreen,
            onClick = {
              reasoningEnabled = !reasoningEnabled
              SettingsManager.reasoningEnabled = reasoningEnabled
            }
          )
          ChatToolCircle(
            icon = if (webSearchEnabled) Icons.Filled.Search else Icons.Outlined.Search,
            label = "Web search",
            active = webSearchEnabled,
            accent = IdentityCyan,
            onClick = { webSearchEnabled = !webSearchEnabled }
          )
          ChatToolCircle(
            icon = Icons.Filled.AttachFile,
            label = "Attach",
            active = attachmentUris.isNotEmpty(),
            accent = IdentityPurple,
            onClick = {
              if (hasVision) {
                docPickLauncher.launch(arrayOf("image/*", "text/plain", "text/markdown", "application/pdf"))
              } else {
                docPickLauncher.launch(arrayOf("text/plain", "text/markdown", "application/pdf"))
              }
            }
          )
          Spacer(Modifier.weight(1f))
        }
        InputBar(
          onSend = { text, uris, names -> sendMessage(text, uris, names) },
          onStop = { stopInference() },
          isInferring = isInferring,
          attachmentUris = attachmentUris,
          attachmentFileNames = attachmentFileNames,
          onRemoveAttachment = { idx ->
            attachmentUris = attachmentUris.toMutableList().also { it.removeAt(idx) }
            attachmentFileNames = attachmentFileNames.toMutableList().also { it.removeAt(idx) }
          }
        )
      }
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = colors.Bg
  ) { pad ->
    Box(
      modifier = Modifier
        .padding(pad)
        .fillMaxSize()
    ) {
      if (kvUsagePercent > 50) {
        LinearProgressIndicator(
          progress = { kvUsagePercent / 100f },
          modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .height(2.dp)
            .align(Alignment.TopCenter),
          color = when {
            kvUsagePercent >= 90 -> colors.Red
            kvUsagePercent >= 75 -> colors.Amber
            else -> colors.Accent2
          },
          trackColor = colors.Border
        )
      }
      // Context limit warning banner
      if (kvUsagePercent >= 85) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(top = 4.dp, start = 8.dp, end = 8.dp),
          shape = RoundedCornerShape(8.dp),
          color = colors.Red.copy(alpha = 0.08f),
          border = BorderStroke(0.2.dp, colors.Red.copy(alpha = 0.3f))
        ) {
          Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Filled.Warning, null, tint = colors.Red, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
              Text("Context limit approaching", fontSize = 10.sp,
                color = colors.Red, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
              Text("Tap to increase context size in model settings",
                fontSize = 9.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.Filled.Settings, null, tint = colors.Red.copy(0.6f),
              modifier = Modifier.size(14.dp))
          }
        }
      }
      if (messages.isEmpty() && !isInferring) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          // Glowing chat emblem
          Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
            Box(
              Modifier.size(88.dp).background(
                Brush.radialGradient(listOf(colors.Accent.copy(alpha = 0.16f), colors.Accent.copy(alpha = 0f))),
                CircleShape
              )
            )
            Box(
              Modifier.size(54.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(colors.GradientStart, colors.GradientEnd))),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Filled.Chat, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
          }
          Spacer(Modifier.height(18.dp))
          Text(
            text = if (engine?.isModelLoaded == true) "Start a conversation"
                   else "No model loaded",
            color = if (engine?.isModelLoaded == true) colors.Text else colors.Amber,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = if (engine?.isModelLoaded == true)
              "Ask anything — attach files, search the web, or think step by step."
            else "Tap the model name at the top to load one",
            color = colors.Text3,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
          )
        }
      } else {
        /** Calm entrance for each chat message: fade in + a slight rise. */
        @Composable
        fun Modifier.messageEnter(): Modifier {
            val alpha = remember { Animatable(0f) }
            val dy = remember { Animatable(10f) }
            LaunchedEffect(Unit) {
                alpha.animateTo(1f, tween(260))
                dy.animateTo(0f, tween(260))
            }
            return this.graphicsLayer { this.alpha = alpha.value; translationY = dy.value }
        }

        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
          contentPadding = PaddingValues(top = 4.dp, bottom = 0.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          itemsIndexed(
            items = messages,
            key = { idx, msg -> "${msg.role.name}_${msg.timestamp}_$idx" }
          ) { idx, msg ->
            val isLastAssistant = !isInferring &&
              msg.role == MessageRole.ASSISTANT &&
              idx == messages.size - 1
            val canRegenerate = isLastAssistant && idx > 0 &&
              messages[idx - 1].role == MessageRole.USER

            val thinking = extractThinking(msg.content)
            val display = removeThinking(msg.content)

            Box(Modifier.fillMaxWidth().messageEnter()) {
            ChatBubble(
              content = display,
              role = msg.role,
              timestamp = msg.timestamp,
              tps = msg.tps,
              tokens = msg.tokens,
              attachmentPath = msg.attachmentPath,
              attachmentType = msg.attachmentType,
              thinkingContent = thinking,
              onCopy = { copyToClipboard(display) },
              onDelete = { deleteMsgIndex = idx },
              onRegenerate = if (canRegenerate) {
                { handleRegenerate(idx - 1) }
              } else null
            )
            }
          }

          if (isInferring) {
            item(key = "streaming") {
              val thinking = extractThinking(streamedContent)
              val display = removeThinking(streamedContent)
              // When reasoning is enabled and in the initial thinking phase,
              // show the live stream in the thinking section too — works with
              // ALL models, not just those that output <think> tags.
              val reasoningLive = if (reasoningEnabled && reasoningPhaseActive) {
                streamedContent
              } else null
              Box(Modifier.fillMaxWidth().messageEnter()) {
              ChatBubble(
                content = display,
                role = MessageRole.ASSISTANT,
                timestamp = System.currentTimeMillis(),
                tps = streamedTps,
                tokens = streamedTokens,
                isLoading = streamedContent.isEmpty(),
                isStreaming = streamedContent.isNotEmpty(),
                thinkingContent = thinking ?: reasoningLive,
                showThinking = showStreamingThinking || reasoningPhaseActive,
                reasoningBadge = reasoningEnabled,
                onToggleThinking = { showStreamingThinking = !showStreamingThinking }
              )
              }
            }
          }
        }
      }
    }
  }

  if (showExportDialog) {
    ExportSessionDialog(
      onDismiss = { showExportDialog = false },
      onShareText = { handleExportText() },
      onShareJson = { handleExportJson() }
    )
  }

  if (deleteMsgIndex >= 0) {
    DeleteConfirmDialog(
      onDismiss = { deleteMsgIndex = -1 },
      onConfirm = { handleDelete(deleteMsgIndex) }
    )
  }
}

/** Small circular chat toolbar toggle (think / search / clip) above the bubbles. */
@Composable
private fun ChatToolCircle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val colors = currentPalette()
    val borderBrush = if (active) IdentityBorderBrush
        else Brush.linearGradient(listOf(IdentityCyan.copy(alpha = 0.35f), IdentityPurple.copy(alpha = 0.35f)))
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) accent.copy(alpha = 0.14f) else colors.Card,
        border = BorderStroke(0.2.dp, borderBrush),
        modifier = Modifier.size(30.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) accent else colors.Text3, modifier = Modifier.size(15.dp))
        }
    }
}
