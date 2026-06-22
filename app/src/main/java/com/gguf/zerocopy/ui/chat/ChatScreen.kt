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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.gguf.zerocopy.domain.inference.TokenCallback
import com.gguf.zerocopy.domain.rag.RagEngine
import com.gguf.zerocopy.ui.chat.components.ChatBubble
import com.gguf.zerocopy.ui.chat.components.DeleteConfirmDialog
import com.gguf.zerocopy.ui.chat.components.ExportSessionDialog
import com.gguf.zerocopy.ui.chat.components.InputBar
import com.gguf.zerocopy.ui.chat.components.PromptSuggestions
import com.gguf.zerocopy.ui.chat.components.getFileName
import com.gguf.zerocopy.ui.theme.currentPalette
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
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
  onCloud: () -> Unit
) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val colors = currentPalette()
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val snackbarHostState = remember { SnackbarHostState() }

  val engine = app.engineManager.getActiveEngine()
  val hasVision = engine?.hasVisionCapability == true

  // Use a stable chatId that doesn't reset on recomposition
  var chatId by remember { mutableStateOf(sessionId) }
  
  // Initialize chatId from sessionId parameter only once
  LaunchedEffect(sessionId) {
    if (sessionId != null) {
      chatId = sessionId
      app.chatRepository.selectSession(sessionId)
      SettingsManager.currentSessionId = sessionId
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
  var showExportDialog by remember { mutableStateOf(false) }
  var deleteMsgIndex by remember { mutableIntStateOf(-1) }
  var showStreamingThinking by remember { mutableStateOf(false) }

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

  fun buildConversationPrompt(currentUserText: String, useReasoning: Boolean): String {
    return if (useReasoning) {
      "Use <think> tags for step-by-step reasoning before answering.\n\n$currentUserText"
    } else currentUserText
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

    inferenceActive = true
    isInferring = true
    streamedContent = ""
    streamedTokens = 0
    streamedTps = 0f

    scope.launch {
      // ── 1. Process attachments on IO thread ──────────────────────────────
      val savedPaths   = mutableListOf<String>()
      var attachType: AttachmentType? = null
      val ragEngine    = app.ragEngine
      var ragContext   = ""

      if (uris.isNotEmpty()) {
        withContext(Dispatchers.IO) {
          uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri) ?: ""
            when {
              mime.startsWith("image/") -> {
                // Save for vision; also ingest into RAG for OCR text
                saveAttachmentToStorage(uri)?.let { path ->
                  savedPaths.add(path)
                  if (attachType == null) attachType = AttachmentType.IMAGE
                }
                ragEngine.ingest(uri, context) // adds OCR text to chunk store
              }
              mime == "application/pdf" -> {
                ragEngine.ingest(uri, context)
                if (attachType == null) attachType = AttachmentType.DOCUMENT
              }
              mime.startsWith("text/") -> {
                ragEngine.ingest(uri, context)
                if (attachType == null) attachType = AttachmentType.DOCUMENT
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
      }

      // ── 2. RAG retrieval: fetch relevant chunks for user query ────────────
      if (ragEngine.hasDocuments) {
        withContext(Dispatchers.IO) {
          ragContext = ragEngine.retrieve(text)
        }
      }

      // ── 3. Build final prompt ─────────────────────────────────────────────
      val finalPrompt = buildString {
        append(text)
        if (ragContext.isNotEmpty()) {
          appendLine()
          appendLine()
          append(ragContext)
        }
      }

      // ── 4. Save user message ──────────────────────────────────────────────
      val userMsg = ChatMessage(
        role           = MessageRole.USER,
        content        = if (ragContext.isEmpty()) text else finalPrompt,
        attachmentPath = savedPaths.firstOrNull(),
        attachmentType = attachType
      )
      app.chatRepository.addMessage(id, userMsg)

      // ── 5. Run inference ──────────────────────────────────────────────────
      val startTime   = System.currentTimeMillis()
      val rawResponse = StringBuilder()
      val currentChatId = id

      val callback = object : TokenCallback {
        override fun onToken(token: String) {
          if (!inferenceActive) return
          rawResponse.append(token)
          streamedContent = rawResponse.toString()
          streamedTokens++
          val elapsed = (System.currentTimeMillis() - startTime) / 1000f
          if (elapsed > 0) streamedTps = streamedTokens / elapsed
        }
        override fun onDone() {
          if (!inferenceActive) return
          val elapsed = (System.currentTimeMillis() - startTime) / 1000f
          val tpsVal  = if (elapsed > 0) streamedTokens / elapsed else 0f
          val raw = rawResponse.toString()
          if (raw.isNotEmpty()) {
            app.chatRepository.addMessage(
              currentChatId,
              ChatMessage(role = MessageRole.ASSISTANT, content = raw, tps = tpsVal, tokens = streamedTokens)
            )
          }
          streamedContent  = ""
          inferenceActive  = false
          isInferring      = false
        }
        override fun onError(error: String) {
          if (!inferenceActive) return
          inferenceActive = false
          isInferring     = false
          streamedContent = ""
          scope.launch { snackbarHostState.showSnackbar("Inference error: $error") }
        }
        override fun onKvUsage(percent: Int) {}
        override fun onTokensGenerated(count: Int) { streamedTokens = count }
      }

      try {
        withContext(Dispatchers.IO) {
          val allHistory = app.chatRepository.getMessages(currentChatId)
          val historyPairs = allHistory.dropLast(1).map { msg ->
            msg.role.name.lowercase() to msg.content
          }
          activeEngine.restoreHistory(historyPairs)
          val prompt = buildConversationPrompt(finalPrompt, reasoningEnabled)
          if (savedPaths.isNotEmpty() && activeEngine.hasVisionCapability) {
            activeEngine.executeInferenceWithImage(prompt, savedPaths.first(), callback)
          } else {
            activeEngine.executeInference(prompt, callback)
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("ChatScreen", "Exception during inference: ${e.message}")
        if (inferenceActive) {
          inferenceActive = false
          isInferring     = false
          streamedContent = ""
        }
      }
    }
  }
  fun stopInference() {
    inferenceActive = false
    isInferring = false
    engine?.abortInference()
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

  // Observe sessions StateFlow for reactive updates
  val sessions by app.chatRepository.sessions.collectAsState()
  val sessionName = remember(chatId, sessions) {
    if (chatId != null) {
      sessions.find { it.id == chatId }?.name ?: "New Chat"
    } else "New Chat"
  }

  Scaffold(
    topBar = {
      Surface(color = colors.Bg) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
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
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              color = colors.Text,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (modelName.isNotEmpty()) {
              Text(
                text = modelName,
                fontSize = 10.sp,
                color = colors.Accent2,
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
          IconButton(onClick = onCloud) {
            Icon(Icons.Outlined.Cloud, contentDescription = "Cloud", tint = colors.Text2)
          }
        }
      }
    },
    bottomBar = {
      Column {
        if (messages.isEmpty() && !isInferring) {
          PromptSuggestions(suggestions = suggestions, onSelect = { text ->
            sendMessage(text, emptyList(), emptyList())
          })
        }
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
      if (messages.isEmpty() && !isInferring) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Spacer(Modifier.height(48.dp))
          Text(
            text = "Start a conversation",
            color = colors.Text3,
            fontSize = 15.sp
          )
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
            key = { idx, msg -> "${msg.role.name}_${msg.timestamp}_$idx" }
          ) { idx, msg ->
            val isLastAssistant = !isInferring &&
              msg.role == MessageRole.ASSISTANT &&
              idx == messages.size - 1
            val canRegenerate = isLastAssistant && idx > 0 &&
              messages[idx - 1].role == MessageRole.USER

            val thinking = extractThinking(msg.content)
            val display = removeThinking(msg.content)

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

          if (isInferring) {
            item(key = "streaming") {
              val thinking = extractThinking(streamedContent)
              val display = removeThinking(streamedContent)
              ChatBubble(
                content = display,
                role = MessageRole.ASSISTANT,
                timestamp = System.currentTimeMillis(),
                tps = streamedTps,
                tokens = streamedTokens,
                isLoading = streamedContent.isEmpty(),
                isStreaming = streamedContent.isNotEmpty(),
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
