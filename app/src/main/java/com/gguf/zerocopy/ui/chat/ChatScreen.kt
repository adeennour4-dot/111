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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.AttachmentType
import com.gguf.zerocopy.data.repository.ChatMessage
import com.gguf.zerocopy.data.repository.MessageRole
import com.gguf.zerocopy.domain.media.TtsManager
import com.gguf.zerocopy.domain.media.VoiceInputHandler
import com.gguf.zerocopy.domain.ocr.PdfTextExtractor
import com.gguf.zerocopy.ui.chat.components.ChatBubble
import com.gguf.zerocopy.ui.chat.components.DeleteConfirmDialog
import com.gguf.zerocopy.ui.chat.components.ExportSessionDialog
import com.gguf.zerocopy.ui.chat.components.InputBar
import com.gguf.zerocopy.ui.chat.components.PromptSuggestions
import com.gguf.zerocopy.ui.chat.components.RagDocumentDialog
import com.gguf.zerocopy.ui.chat.components.getFileName
import com.gguf.zerocopy.ui.theme.currentPalette
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
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
  onSettings: () -> Unit,
  onSessions: () -> Unit,
  onRag: () -> Unit = {}
) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val colors = currentPalette()
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val snackbarHostState = remember { SnackbarHostState() }

  val hasVision = app.activeEngine.hasVision

  var chatId by remember { mutableStateOf(sessionId) }

  LaunchedEffect(sessionId) {
    if (sessionId != null && sessionId != chatId) {
      chatId = sessionId
    }
    chatId?.let {
      app.chatRepository.selectSession(it)
      SettingsManager.currentSessionId = it
    }
  }

  val messages by app.chatRepository.currentMessages.collectAsState()

  var isInferring by remember { mutableStateOf(false) }
  var streamedContent by remember { mutableStateOf("") }
  var streamedTokens by remember { mutableIntStateOf(0) }
  var streamedTps by remember { mutableFloatStateOf(0f) }
  var inferenceActive by remember { mutableStateOf(false) }
  var attachmentUris by remember { mutableStateOf(listOf<Uri>()) }
  var attachmentFileNames by remember { mutableStateOf(listOf<String>()) }
  var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
  var reasoningEnabled by remember { mutableStateOf(SettingsManager.reasoningEnabled) }
  var ragEnabled by remember { mutableStateOf(SettingsManager.ragEnabled) }
  var showExportDialog by remember { mutableStateOf(false) }
  var showRagDialog by remember { mutableStateOf(false) }
  var deleteMsgIndex by remember { mutableIntStateOf(-1) }
  var showStreamingThinking by remember { mutableStateOf(false) }
  var userSentCount by remember { mutableIntStateOf(0) }
  var isVoiceListening by remember { mutableStateOf(false) }
  var speakingMessageTimestamp by remember { mutableStateOf(0L) }
  val voiceHandler = remember { VoiceInputHandler(context) }
  val ttsManager = remember { TtsManager(context) }

  DisposableEffect(Unit) {
    onDispose {
      voiceHandler.destroy()
      ttsManager.destroy()
    }
  }

  val audioPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) voiceHandler.startListening()
    else scope.launch { snackbarHostState.showSnackbar("Microphone permission denied") }
  }

  voiceHandler.onResult = { text ->
    isVoiceListening = false
    if (text.isNotBlank()) {
      sendMessage(text, emptyList(), emptyList())
    }
  }
  voiceHandler.onError = { msg ->
    isVoiceListening = false
    scope.launch { snackbarHostState.showSnackbar(msg) }
  }

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

  fun extractThinking(content: String): String? {
    return thinkRegex.find(content)?.groupValues?.getOrNull(1)
  }

  fun removeThinking(content: String): String {
    return content.replace(thinkRegex, "").trim()
  }

  val cameraLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      cameraImageUri?.let { uri ->
        attachmentUris = attachmentUris + uri
        attachmentFileNames = attachmentFileNames + getFileName(context, uri)
      }
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
      .let { File(it, "camera_${System.currentTimeMillis()}.jpg") }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    cameraImageUri = uri
    cameraLauncher.launch(
      Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

  fun buildConversationPrompt(
    currentUserText: String, useReasoning: Boolean, history: List<ChatMessage>
  ): String {
    val historyBlocks = history.joinToString("\n") { msg ->
      when (msg.role) {
        MessageRole.USER -> "User: ${msg.content}"
        MessageRole.ASSISTANT -> "Assistant: ${msg.content}"
        MessageRole.SYSTEM -> "System: ${msg.content}"
      }
    }
    val reasoningPrefix = if (useReasoning) "Use <think> tags for step-by-step reasoning before answering.\n\n" else ""
    return if (historyBlocks.isNotEmpty()) {
      "$reasoningPrefix$historyBlocks\nUser: $currentUserText"
    } else {
      "${reasoningPrefix}User: $currentUserText"
    }
  }

  LaunchedEffect(modelPath) {
    if (modelPath.isEmpty()) return@LaunchedEffect
    if (!app.activeEngine.isLoaded) {
      app.activeEngine.load(path = modelPath, config = SettingsManager.toEngineConfig())
    }
    val cacheDir = File(context.filesDir, "prompt_cache").also { it.mkdirs() }.absolutePath
    app.activeEngine.setCacheDir(cacheDir)
    app.activeEngine.setStreamingLLM(
      sinkTokens = SettingsManager.kvSinkTokens,
      recentTokens = SettingsManager.kvRecentTokens,
      threshold = SettingsManager.kvEvictThreshold
    )
    app.activeEngine.setRagParams(topK = SettingsManager.ragTopK, minScore = SettingsManager.ragMinScore)
  }

  fun sendMessageNewEngine(sessionId: String, prompt: String, imagePaths: List<String>) {
    if (!app.activeEngine.isLoaded) {
      scope.launch { snackbarHostState.showSnackbar("New engine: model not loaded") }
      return
    }
    // Sync RAG state before generation
    app.activeEngine.setRagParams(topK = SettingsManager.ragTopK, minScore = SettingsManager.ragMinScore)
    app.activeEngine.ragEnabled = ragEnabled && app.activeEngine.numDocuments > 0

    inferenceActive = true
    isInferring = true
    streamedContent = ""
    streamedTokens = 0
    streamedTps = 0f
    val startTime = System.currentTimeMillis()

    scope.launch {
      val rawResponse = StringBuilder()
      val historyMsgs = app.chatRepository.getMessages(sessionId).filter { it.role != MessageRole.SYSTEM }
      val fp = buildConversationPrompt(prompt, reasoningEnabled, historyMsgs)
      val maxGenTokens = SettingsManager.maxTokens.coerceIn(64, 8192)
      val generateTimeoutMs = 300_000L
      app.activeEngine.generateFlow(fp, maxTokens = maxGenTokens)
        .catch { e ->
          android.util.Log.e("ChatScreen", "Flow error: ${e.message}")
          inferenceActive = false
          isInferring = false
          scope.launch { snackbarHostState.showSnackbar("Inference error: ${e.message}") }
        }
        .onCompletion {
          if (!inferenceActive) return@onCompletion
          val raw = rawResponse.toString()
          streamedContent = ""
          inferenceActive = false
          isInferring = false
          if (raw.isNotEmpty()) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            val tpsVal = if (elapsed > 0) streamedTokens / elapsed else 0f
            app.chatRepository.addMessage(
              sessionId,
              ChatMessage(
                role = MessageRole.ASSISTANT,
                content = raw,
                tps = tpsVal,
                tokens = streamedTokens
              )
            )
          }
        }
        .collect { token ->
          if (!inferenceActive) return@collect
          if (System.currentTimeMillis() - startTime > generateTimeoutMs) {
            inferenceActive = false
            isInferring = false
            app.activeEngine.stopGeneration()
            scope.launch { snackbarHostState.showSnackbar("Generation timed out after 5 min") }
            return@collect
          }
          rawResponse.append(token)
          streamedContent = rawResponse.toString()
          streamedTokens++
          val elapsed = (System.currentTimeMillis() - startTime) / 1000f
          if (elapsed > 0) streamedTps = streamedTokens / elapsed
        }
    }
  }

  fun sendMessage(text: String, uris: List<Uri>, names: List<String>) {
    android.util.Log.d("ChatScreen", "sendMessage called with chatId: $chatId")
    val id = chatId ?: run {
      android.util.Log.d("ChatScreen", "Creating new session")
      val s = app.chatRepository.createSession(
        modelPath = modelPath,
        modelName = modelName
      )
      SettingsManager.currentSessionId = s.id
      chatId = s.id
      android.util.Log.d("ChatScreen", "New session created: ${s.id}")
      s.id
    }

    val savedPaths = mutableListOf<String>()
    var attachType: AttachmentType? = null
    var extractedPdfText = ""
    val pdfExtractor = PdfTextExtractor(context)

    uris.forEach { uri ->
      val mime = context.contentResolver.getType(uri) ?: ""
      when {
        mime.startsWith("image/") -> {
          saveAttachmentToStorage(uri)?.let { path ->
            savedPaths.add(path)
            if (attachType == null) attachType = AttachmentType.IMAGE
          }
        }
        mime == "application/pdf" -> {
          val pdfText = pdfExtractor.extractText(uri) ?: ""
          if (pdfText.length > 50) {
            extractedPdfText = pdfText
          } else {
            extractedPdfText = "[PDF document attached: ${getFileName(context, uri)}]"
          }
          if (attachType == null) attachType = AttachmentType.DOCUMENT
        }
        mime == "text/plain" || mime == "text/markdown" -> {
          val docText = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().readText()
          } ?: ""
          if (docText.isNotEmpty()) {
            extractedPdfText = docText.take(50_000)
          }
          if (attachType == null) attachType = AttachmentType.DOCUMENT
        }
        else -> {
          if (attachType == null) {
            attachType = if (mime.startsWith("audio/")) AttachmentType.AUDIO else AttachmentType.DOCUMENT
          }
        }
      }
    }

    val finalPrompt = if (extractedPdfText.isNotEmpty()) {
      val pdfContent = "\n\n[Extracted PDF Content]\n$extractedPdfText"
      if (text.isNotEmpty()) "$text$pdfContent" else "Please analyze this PDF document:$pdfContent"
    } else text

    val userMsg = ChatMessage(
      role = MessageRole.USER,
      content = finalPrompt,
      attachmentPath = savedPaths.firstOrNull(),
      attachmentType = attachType
    )
    app.chatRepository.addMessage(id, userMsg)
    userSentCount++

    sendMessageNewEngine(id, finalPrompt, savedPaths)
  }

  fun stopInference() {
    inferenceActive = false
    isInferring = false
    app.activeEngine.stopGeneration()
    streamedContent = ""
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

  LaunchedEffect(userSentCount) {
    if (userSentCount > 0 && messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  LaunchedEffect(streamedContent) {
    if (isInferring && streamedContent.isNotEmpty() && messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size)
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
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(colors.Bg)
          .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp)
        ) {
          Text(
            text = sessionName,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          if (modelName.isNotEmpty()) {
            Text(
              text = modelName,
              fontSize = 10.sp,
              color = colors.Text3,
              fontFamily = FontFamily.Monospace
            )
          }
        }
        IconButton(onClick = onSessions) {
          Icon(Icons.Outlined.History, contentDescription = "Sessions", tint = colors.Text2)
        }
        IconButton(onClick = onSettings) {
          Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = colors.Text2)
        }
        
      }
    },
    bottomBar = {
      Column {
        InputBar(
          onSend = { text, uris, names -> sendMessage(text, uris, names) },
          onStop = { stopInference() },
          onCamera = {
            if (hasVision) {
              if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
              } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
              }
            } else {
              scope.launch { snackbarHostState.showSnackbar("Camera requires a vision model") }
            }
          },
          onAttach = {
            if (hasVision) {
              docPickLauncher.launch(arrayOf("image/*", "text/plain", "text/markdown", "application/pdf"))
            } else {
              docPickLauncher.launch(arrayOf("text/plain", "text/markdown", "application/pdf"))
            }
          },
          onVoiceInput = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
              isVoiceListening = true
              voiceHandler.startListening()
            } else {
              audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
          },
          isVoiceListening = isVoiceListening,
          isInferring = isInferring,
          attachmentUris = attachmentUris,
          attachmentFileNames = attachmentFileNames,
          onRemoveAttachment = { idx ->
            attachmentUris = attachmentUris.toMutableList().also { it.removeAt(idx) }
            attachmentFileNames = attachmentFileNames.toMutableList().also { it.removeAt(idx) }
          },
          reasoningEnabled = reasoningEnabled,
          onToggleReasoning = {
            reasoningEnabled = !reasoningEnabled
            SettingsManager.reasoningEnabled = reasoningEnabled
          },
          ragEnabled = ragEnabled,
          onToggleRag = { showRagDialog = true },
          ragDocCount = app.activeEngine.numDocuments
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
      if (messages.isEmpty() && !isInferring) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Spacer(Modifier.height(48.dp))
          Box(
            modifier = Modifier
              .size(72.dp)
              .clip(RoundedCornerShape(20.dp))
              .background(colors.Accent.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "ZC",
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
              color = colors.Accent,
              fontFamily = FontFamily.Monospace
            )
          }
          Spacer(Modifier.height(16.dp))
          Text(
            text = "ZeroCopy",
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            color = colors.Text,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 6.sp
          )
          Spacer(Modifier.height(8.dp))
          Text(
            text = "How can I help you?",
            color = colors.Text2,
            fontSize = 14.sp
          )
          Spacer(Modifier.height(28.dp))
          PromptSuggestions(suggestions = suggestions, onSelect = { text ->
            sendMessage(text, emptyList(), emptyList())
          })
          val ragDocs = app.activeEngine.numDocuments
          if (ragDocs > 0) {
            Spacer(Modifier.height(24.dp))
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(colors.Accent2.copy(alpha = 0.6f))
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = "$ragDocs document chunks indexed",
                fontSize = 11.sp,
                color = colors.Accent2.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      } else {
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          itemsIndexed(
            items = messages,
            key = { index, msg -> "msg_${msg.timestamp}" }
          ) { idx, msg ->
            AnimatedVisibility(
              visible = true,
              enter = fadeIn(animationSpec = tween(300)) +
                slideInVertically(animationSpec = tween(300)) { it / 8 }
            ) {
              val isLastAssistant = !isInferring &&
                msg.role == MessageRole.ASSISTANT &&
                idx == messages.size - 1
              val canRegenerate = isLastAssistant && idx > 0 &&
                messages[idx - 1].role == MessageRole.USER

              val thinking = extractThinking(msg.content)
              val display = removeThinking(msg.content)

              val isThisSpeaking = speakingMessageTimestamp == msg.timestamp
              ChatBubble(
                content = display,
                role = msg.role,
                timestamp = msg.timestamp,
                tps = msg.tps,
                tokens = msg.tokens,
                attachmentPath = msg.attachmentPath,
                attachmentType = msg.attachmentType,
                isSpeaking = isThisSpeaking,
                onSpeak = if (msg.role == MessageRole.ASSISTANT && display.isNotEmpty()) {
                  {
                    if (isThisSpeaking) {
                      ttsManager.stop()
                      speakingMessageTimestamp = 0L
                    } else {
                      ttsManager.ensureInit()
                      ttsManager.onDone = { speakingMessageTimestamp = 0L }
                      speakingMessageTimestamp = msg.timestamp
                      ttsManager.speak(display)
                    }
                  }
                } else null,
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
              val streamingTs = System.currentTimeMillis()
              ChatBubble(
                content = display,
                role = MessageRole.ASSISTANT,
                timestamp = streamingTs,
                tps = streamedTps,
                tokens = streamedTokens,
                isLoading = streamedContent.isEmpty(),
                isStreaming = streamedContent.isNotEmpty(),
                isSpeaking = speakingMessageTimestamp == streamingTs,
                onSpeak = if (display.isNotEmpty()) {
                  {
                    if (speakingMessageTimestamp == streamingTs) {
                      ttsManager.stop()
                      speakingMessageTimestamp = 0L
                    } else {
                      ttsManager.ensureInit()
                      ttsManager.onDone = { speakingMessageTimestamp = 0L }
                      speakingMessageTimestamp = streamingTs
                      ttsManager.speak(display)
                    }
                  }
                } else null,
                thinkingContent = thinking,
                showThinking = showStreamingThinking,
                onToggleThinking = { showStreamingThinking = !showStreamingThinking }
              )
            }
          }
        }
      }
    }
  }

  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        try {
          val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
          val session = app.chatRepository.importSession(text)
          if (session != null) {
            withContext(Dispatchers.Main) {
              chatId = session.id
              app.chatRepository.selectSession(session.id)
              scope.launch { snackbarHostState.showSnackbar("Session imported") }
            }
          }
        } catch (_: Exception) {}
      }
    }
  }

  if (showExportDialog) {
    ExportSessionDialog(
      onDismiss = { showExportDialog = false },
      onShareText = { handleExportText() },
      onShareJson = { handleExportJson() },
      onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) }
    )
  }

  if (deleteMsgIndex >= 0) {
    DeleteConfirmDialog(
      onDismiss = { deleteMsgIndex = -1 },
      onConfirm = { handleDelete(deleteMsgIndex) }
    )
  }

  if (showRagDialog) {
    RagDocumentDialog(
      engine = app.activeEngine,
      onDismiss = { showRagDialog = false },
      onDocumentsChanged = { /* doc count will update next frame */ },
      onManageLibrary = onRag
    )
  }
}
