package com.gguf.zerocopy.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.local.SettingsManager
import com.gguf.zerocopy.data.repository.AttachmentType
import com.gguf.zerocopy.data.repository.ChatMessage
import com.gguf.zerocopy.data.repository.MessageRole
import com.gguf.zerocopy.domain.inference.TokenCallback
import com.gguf.zerocopy.ui.models.ModelSelectionSheet
import com.gguf.zerocopy.ui.theme.ZcColors
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  modelPath: String,
  modelName: String,
  sessionId: String?,
  onModelSelected: (String, String) -> Unit,
  onSettings: () -> Unit,
  onSessions: () -> Unit,
  onStore: () -> Unit
) {
  val context = LocalContext.current
  val app = ZeroCopyApp.instance
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

  val engine = app.engineManager.getActiveEngine()

  var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
  var prompt by remember { mutableStateOf("") }
  var isInferring by remember { mutableStateOf(false) }
  var streamedText by remember { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var kvUsage by remember { mutableIntStateOf(0) }
  var tps by remember { mutableFloatStateOf(0f) }
  var statusText by remember { mutableStateOf(modelName.ifEmpty { "No model" }) }
  var attachmentUri by remember { mutableStateOf<Uri?>(null) }
  var showModelSheet by remember { mutableStateOf(false) }
  var isListening by remember { mutableStateOf(false) }
  var isSpeaking by remember { mutableStateOf(false) }
  var showPromptSuggestions by remember { mutableStateOf(false) }
  var showModelInfoDialog by remember { mutableStateOf(false) }
  var deleteConfirmMsg by remember { mutableStateOf<ChatMessage?>(null) }
  val tts = remember {
    mutableStateOf<TextToSpeech?>(null)
  }

  DisposableEffect(Unit) {
    val ttsInstance = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) tts.value?.language = Locale.getDefault()
    }
    tts.value = ttsInstance
    onDispose { ttsInstance.stop(); ttsInstance.shutdown() }
  }

  val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
      if (!results.isNullOrEmpty()) {
        prompt = results[0]
      }
    }
    isListening = false
  }

  val chatId = remember {
    if (sessionId != null) {
      SettingsManager.currentSessionId = sessionId
      sessionId
    } else {
      val savedId = SettingsManager.currentSessionId
      if (savedId.isNotEmpty() && app.chatRepository.sessionExists(savedId)) {
        savedId
      } else {
        val newSession = app.chatRepository.createSession("Chat - $modelName")
        SettingsManager.currentSessionId = newSession.id
        newSession.id
      }
    }
  }

  LaunchedEffect(chatId) {
    if (sessionId != null) {
      app.chatRepository.selectSession(sessionId)
      SettingsManager.currentSessionId = sessionId
    }
    messages = app.chatRepository.getMessages(chatId)
  }

  LaunchedEffect(isInferring) {
    if (!isInferring) return@LaunchedEffect
    val start = System.currentTimeMillis()
    var firstSeen = false
    isProcessing = true
    while (isInferring) {
      delay(30)
      val text = engine?.readPartialStream().orEmpty()
      if (text.isNotEmpty()) {
        streamedText = if (streamedText.isEmpty() || isProcessing) text else streamedText + text
        if (!firstSeen) {
          firstSeen = true
          isProcessing = false
        }
      }
      val elapsed = (System.currentTimeMillis() - start) / 1000f
      val tok = engine?.getTokensGenerated() ?: 0
      if (elapsed > 0) tps = tok / elapsed
      kvUsage = engine?.getKvUsage() ?: 0

      val done = engine?.isInferenceDone() ?: true
      if (done) {
        delay(60)
        val final = engine?.readTokenStream().orEmpty()
        val ft = engine?.getTokensGenerated() ?: 0
        if (final.isNotEmpty()) {
          val msg = ChatMessage(
            MessageRole.ASSISTANT,
            final,
            tps = if (elapsed > 0) ft / elapsed else 0f,
            tokens = ft
          )
          messages = messages + msg
          app.chatRepository.addMessage(chatId, msg)
        }
        streamedText = ""
        isInferring = false
        isProcessing = false
      }
    }
  }

  LaunchedEffect(messages.size, isInferring) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
  }

  val imagePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) attachmentUri = result.data?.data
    }

  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val name = getFileName(context, uri)
          statusText = "Importing..."
          scope.launch(Dispatchers.IO) {
            val result = app.modelRepository.importUri(uri, name)
            if (result.isSuccess) {
              val model = result.getOrThrow()
              val engine = app.engineManager.selectEngineForFormat(model.path)
              engine.config = SettingsManager.toConfig()
              engine.repeatPenalty = SettingsManager.toRepeatPenalty()
              engine.systemPrompt = SettingsManager.systemPrompt
              val loadResult = engine.loadModel(model.path)
              withContext(Dispatchers.Main) {
                if (loadResult.isSuccess) {
                  app.modelRepository.markUsed(model.id)
                  onModelSelected(model.path, model.name)
                  statusText = model.name
                } else {
                  statusText = "Failed: ${loadResult.exceptionOrNull()?.message}"
                }
              }
            } else {
              statusText = "Import failed"
            }
          }
        }
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column(modifier = Modifier.clickable { showModelSheet = true }) {
            Text(
              "ZeroCopy",
              fontWeight = FontWeight.Black,
              fontSize = 16.sp,
              fontFamily = FontFamily.Monospace,
              color = ZcColors.Accent
            )
            Text(
              statusText,
              fontSize = 10.sp,
              color = if (engine?.isModelLoaded == true) ZcColors.Accent2 else ZcColors.Amber,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.clickable {
                if (engine?.modelInfo != null) showModelInfoDialog = true
              }
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = { showModelSheet = true }) {
            Icon(Icons.Outlined.SmartToy, "Select Model", tint = ZcColors.Text2)
          }
        },
        actions = {
          if (engine?.isModelLoaded == true) {
            if (isInferring) {
              AssistChip(
                onClick = { engine.abortInference() },
                label = { Text("Stop", fontSize = 11.sp) },
                colors = AssistChipDefaults.assistChipColors(
                  containerColor = ZcColors.Red.copy(alpha = 0.2f),
                  labelColor = ZcColors.Red
                )
              )
            }
            AssistChip(
              onClick = {},
              label = { Text("$kvUsage%", fontSize = 11.sp) },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = if (kvUsage > 80) ZcColors.Red.copy(alpha = 0.2f) else ZcColors.Accent2.copy(alpha = 0.15f),
                labelColor = if (kvUsage > 80) ZcColors.Red else ZcColors.Accent2
              )
            )
          }
          var showExportDialog by remember { mutableStateOf(false) }
          IconButton(onClick = { showExportDialog = true }) {
            Icon(Icons.Filled.Share, "Export", tint = ZcColors.Text2)
          }
          if (showExportDialog) {
            androidx.compose.material3.AlertDialog(
              onDismissRequest = { showExportDialog = false },
              title = { Text("Export Chat", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
              text = {
                Column {
                  androidx.compose.material3.TextButton(
                    onClick = {
                      showExportDialog = false
                      val exportText = app.chatRepository.exportSession(chatId)
                      Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, exportText)
                      }.let { context.startActivity(Intent.createChooser(it, "Share as Text")) }
                    },
                    modifier = Modifier.fillMaxWidth()
                  ) { Text("Export as Text", fontSize = 14.sp) }
                  androidx.compose.material3.TextButton(
                    onClick = {
                      showExportDialog = false
                      val msgs = app.chatRepository.getMessages(chatId)
                      val jsonArr = org.json.JSONArray()
                      msgs.forEach { m ->
                        jsonArr.put(org.json.JSONObject().apply {
                          put("role", m.role.name.lowercase())
                          put("content", m.content)
                          put("timestamp", m.timestamp)
                          if (m.tps > 0f) put("tps", m.tps.toDouble())
                          if (m.tokens > 0) put("tokens", m.tokens)
                        })
                      }
                      Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, jsonArr.toString(2))
                      }.let { context.startActivity(Intent.createChooser(it, "Export JSON")) }
                    },
                    modifier = Modifier.fillMaxWidth()
                  ) { Text("Export as JSON", fontSize = 14.sp) }
                }
              },
              confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showExportDialog = false }) {
                  Text("Cancel")
                }
              }
            )
          }
          IconButton(onClick = onSessions) {
            Icon(Icons.Outlined.Chat, "Sessions", tint = ZcColors.Text2)
          }
          IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Tune, "Settings", tint = ZcColors.Text2)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ZcColors.Bg)
      )
    },
    containerColor = ZcColors.Bg
  ) { pad ->
    Column(modifier = Modifier.padding(pad).fillMaxSize()) {
      Box(modifier = Modifier.weight(1f)) {
        if (engine?.isModelLoaded != true) {
          Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              Icons.Outlined.SmartToy,
              null,
              modifier = Modifier.size(64.dp),
              tint = ZcColors.Accent.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
              "Tap the icon above to select a model",
              color = ZcColors.Text3,
              fontSize = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            AssistChip(
              onClick = { showModelSheet = true },
              label = { Text("Choose Model", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = ZcColors.Accent.copy(alpha = 0.15f),
                labelColor = ZcColors.Accent
              )
            )
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            items(messages) { msg ->
              val idx = messages.indexOf(msg)
              val isLastAssistant = !isInferring && msg.role == MessageRole.ASSISTANT && idx == messages.size - 1
              val isRegenerateAllowed = isLastAssistant && idx > 0 && messages[idx - 1].role == MessageRole.USER
              ChatBubble(
                msg, clip,
                onSpeak = { text -> tts.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) },
                onRegenerate = if (isRegenerateAllowed) {{

                  val userMsg = messages[idx - 1]
                  messages = messages.toMutableList().also { it.removeAt(idx) }
                  app.chatRepository.deleteMessage(chatId, idx)

                  streamedText = ""
                  isInferring = true
                  scope.launch(Dispatchers.IO) {
                    val cb = object : TokenCallback {
                      override fun onToken(token: String) {}
                      override fun onDone() {}
                      override fun onError(error: String) {}
                      override fun onKvUsage(percent: Int) {}
                      override fun onTokensGenerated(count: Int) {}
                    }
                    engine?.let {
                      var prompt = userMsg.content
                      if (userMsg.attachmentPath != null) {
                        prompt = "[Image attached: ${userMsg.attachmentPath}]\n$prompt"
                      }
                      it.executeInference(prompt, cb)
                    }
                  }
                }} else null,
                onDelete = { deleteConfirmMsg = msg }
              )
            }
            if (isInferring && streamedText.isNotEmpty()) {
              item { StreamingBubble(streamedText, false) }
            } else if (isInferring && isProcessing) {
              item { StreamingBubble("", true) }
            }
          }
        }
      }

      if (engine?.isModelLoaded == true) {
        PromptSuggestions(
          expanded = showPromptSuggestions,
          onToggle = { showPromptSuggestions = !showPromptSuggestions },
          onSelect = { prompt = it }
        )
        InputBar(
          prompt = prompt,
          onPromptChange = { prompt = it },
          isInferring = isInferring,
          attachmentFileName = attachmentUri?.lastPathSegment?.substringAfterLast('/'),
          isListening = isListening,
          isSpeaking = isSpeaking,
          onSend = {
            if (prompt.isNotBlank()) {
              val msg = prompt
              val attach = attachmentUri
              prompt = ""
              streamedText = ""
              isInferring = true
              val attachmentName = attach?.lastPathSegment?.substringAfterLast('/')
              val userMsg = if (attach != null) {
                ChatMessage(MessageRole.USER, msg, attachmentPath = attachmentName, attachmentType = AttachmentType.IMAGE)
              } else {
                ChatMessage(MessageRole.USER, msg)
              }
              messages = messages + userMsg
              app.chatRepository.addMessage(chatId, userMsg)
              attachmentUri = null

              scope.launch(Dispatchers.IO) {
                val cb = object : TokenCallback {
                  override fun onToken(token: String) {}
                  override fun onDone() {}
                  override fun onError(error: String) {}
                  override fun onKvUsage(percent: Int) {}
                  override fun onTokensGenerated(count: Int) {}
                }
                var fullPrompt = msg
                if (attach != null) {
                  val savedPath = saveImageToAppStorage(context, attach)
                  if (savedPath != null && engine.mmprojPath.isNotEmpty()) {
                    engine.executeInferenceWithImage(fullPrompt, savedPath, cb)
                  } else {
                    engine.executeInference("[Image attached: $attachmentName]\n$msg", cb)
                  }
                } else {
                  engine.executeInference(fullPrompt, cb)
                }
              }
            }
          },
          onStop = { engine.abortInference() },
          onImage = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE)
              type = "image/*"
            }
            imagePicker.launch(intent)
          },
          onVoice = {
            isListening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
              putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
              putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message...")
            }
            try { speechLauncher.launch(intent) }
            catch (_: Exception) { isListening = false }
          },
          onSpeak = { text ->
            tts.value?.let { t ->
              if (!isSpeaking) {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                isSpeaking = true
              }
            }
          }
        )
      }
    }
  }

  if (showModelSheet) {
    ModelSelectionSheet(
      onDismiss = { showModelSheet = false },
      onModelSelected = { path, name ->
        showModelSheet = false
        statusText = name
        onModelSelected(path, name)
      },
      onStore = {
        showModelSheet = false
        onStore()
      }
    )
  }

  deleteConfirmMsg?.let { msg ->
    val idx = messages.indexOf(msg)
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { deleteConfirmMsg = null },
      title = { Text("Delete message?", fontSize = 16.sp) },
      text = { Text("This cannot be undone.", fontSize = 14.sp, color = ZcColors.Text2) },
      confirmButton = {
        androidx.compose.material3.TextButton(onClick = {
          if (idx >= 0) {
            messages = messages.toMutableList().also { it.removeAt(idx) }
            app.chatRepository.deleteMessage(chatId, idx)
          }
          deleteConfirmMsg = null
        }) { Text("Delete", color = ZcColors.Red) }
      },
      dismissButton = {
        androidx.compose.material3.TextButton(onClick = { deleteConfirmMsg = null }) { Text("Cancel") }
      }
    )
  }

  if (showModelInfoDialog && engine?.modelInfo != null) {
    val info = engine.modelInfo!!
    val params = when {
      info.nParams >= 1_000_000_000 -> "%.1fB".format(info.nParams / 1e9)
      info.nParams >= 1_000_000 -> "%.0fM".format(info.nParams / 1e6)
      else -> "${info.nParams}"
    }
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { showModelInfoDialog = false },
      title = { Text(engine.engineName, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
      text = {
        Column {
          listOf(
            "Architecture" to info.arch,
            "Parameters" to params,
            "Quantization" to info.quantization,
            "Context Length" to info.contextLength.toString(),
            "Embedding Size" to info.nEmbeds.toString(),
            "Layers" to info.nLayers.toString(),
            "Vocabulary" to info.vocabSize.toString()
          ).forEach { (label, value) ->
            if (value.isNotEmpty() && value != "0") {
              Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("$label: ", fontSize = 13.sp, color = ZcColors.Text2, modifier = Modifier.weight(1f))
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
              }
            }
          }
        }
      },
      confirmButton = {
        androidx.compose.material3.TextButton(onClick = { showModelInfoDialog = false }) { Text("OK") }
      }
    )
  }
}

private fun saveImageToAppStorage(context: Context, uri: Uri): String? {
  return try {
    val dir = File(context.filesDir, "images").also { it.mkdirs() }
    val name = "img_${System.currentTimeMillis()}.jpg"
    val file = File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
      FileOutputStream(file).use { output -> input.copyTo(output) }
    }
    file.absolutePath
  } catch (_: Exception) { null }
}

private fun getFileName(context: Context, uri: Uri): String {
  var name = "model.gguf"
  context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) {
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0) cursor.getString(idx)?.let { if (it.isNotEmpty()) name = it }
    }
  }
  if ('.' !in name) {
    val mime = context.contentResolver.getType(uri)
    name += when {
      mime?.contains("gguf") == true || mime == "application/octet-stream" -> ".gguf"
      mime?.contains("tensorflow") == true || mime?.contains("tflite") == true -> ".tflite"
      mime?.contains("litert") == true -> ".litertlm"
      else -> ".gguf"
    }
  }
  return name
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
  msg: ChatMessage,
  clip: ClipboardManager,
  onSpeak: (String) -> Unit = {},
  onRegenerate: (() -> Unit)? = null,
  onDelete: (() -> Unit)? = null
) {
  val isUser = msg.role == MessageRole.USER
  val isLatestAssistant = !isUser && onRegenerate != null
  var showMenu by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.Bottom
  ) {
    if (!isUser) {
      Box(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(ZcColors.Accent),
        contentAlignment = Alignment.Center
      ) {
        Text("Z", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
      }
      Spacer(Modifier.width(8.dp))
    }
    Column(modifier = Modifier.widthIn(max = 300.dp)) {
      Surface(
        modifier = Modifier
          .clip(RoundedCornerShape(topStart = if (isUser) 18.dp else 6.dp, topEnd = if (isUser) 6.dp else 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
          .combinedClickable(
            onClick = { clip.setPrimaryClip(ClipData.newPlainText("msg", msg.content)) },
            onLongClick = { showMenu = true }
          ),
        color = if (isUser) ZcColors.UserBg else ZcColors.Card
      ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
          if (msg.attachmentPath != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.Image, null, modifier = Modifier.size(16.dp), tint = ZcColors.Purple)
              Spacer(Modifier.width(4.dp))
              Text(msg.attachmentPath, fontSize = 11.sp, color = ZcColors.Purple, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(4.dp))
          }
          if (!isUser) ThinkingContent(msg.content) else MarkdownText(msg.content)
        }
      }
      if (!isUser) {
        Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
          if (msg.tps > 0) {
            Text(
              "%.1f t/s · %d tok".format(msg.tps, msg.tokens),
              fontSize = 9.sp,
              color = ZcColors.Text3,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.weight(1f)
            )
          } else {
            Spacer(Modifier.weight(1f))
          }
          IconButton(onClick = { onSpeak(msg.content) }, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Outlined.VolumeUp, "Speak", tint = ZcColors.Text3, modifier = Modifier.size(14.dp))
          }
          if (isLatestAssistant) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(20.dp)) {
              Icon(Icons.Filled.Refresh, "Regenerate", tint = ZcColors.Accent, modifier = Modifier.size(14.dp))
            }
          }
          IconButton(onClick = { clip.setPrimaryClip(ClipData.newPlainText("msg", msg.content)) }, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Outlined.ContentCopy, "Copy", tint = ZcColors.Text3, modifier = Modifier.size(12.dp))
          }
        }
      }
      if (showMenu) {
        androidx.compose.material3.AlertDialog(
          onDismissRequest = { showMenu = false },
          title = { Text("Message", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
          text = {
            Column {
              androidx.compose.material3.TextButton(
                onClick = { clip.setPrimaryClip(ClipData.newPlainText("msg", msg.content)); showMenu = false },
                modifier = Modifier.fillMaxWidth()
              ) {
                Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp), tint = ZcColors.Text2)
                Spacer(Modifier.width(8.dp))
                Text("Copy", fontSize = 14.sp, modifier = Modifier.weight(1f))
              }
              if (onDelete != null) {
                androidx.compose.material3.TextButton(
                  onClick = { showMenu = false; onDelete() },
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp), tint = ZcColors.Red)
                  Spacer(Modifier.width(8.dp))
                  Text("Delete", fontSize = 14.sp, color = ZcColors.Red, modifier = Modifier.weight(1f))
                }
              }
            }
          },
          confirmButton = {
            androidx.compose.material3.TextButton(onClick = { showMenu = false }) { Text("Cancel") }
          }
        )
      }
    }
    if (isUser) {
      Spacer(Modifier.width(8.dp))
      Box(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(ZcColors.Accent2),
        contentAlignment = Alignment.Center
      ) {
        Text("U", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
fun ThinkingContent(content: String) {
  val pattern = remember { Regex("(?:<think>|<think>\\n?)(.*?)(?:</think>|</think>\\n?)", RegexOption.DOT_MATCHES_ALL) }
  val match = remember(content) { pattern.find(content) }
  if (match != null) {
    val think = match.groupValues[1].trim()
    val rest = content.substring(match.range.last + 1).trim()
    var open by remember { mutableStateOf(false) }
    Column {
      Surface(
        onClick = { open = !open },
        shape = RoundedCornerShape(8.dp),
        color = ZcColors.ThinkBg,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
      ) {
        Column(modifier = Modifier.padding(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lightbulb, null, modifier = Modifier.size(12.dp), tint = ZcColors.Purple)
            Spacer(Modifier.width(4.dp))
            Text(if (open) "Thinking" else "Thinking...", fontSize = 10.sp, color = ZcColors.Purple, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
          }
          AnimatedVisibility(open) {
            MarkdownText(think, modifier = Modifier.padding(top = 4.dp), style = LocalTextStyle.current.copy(fontSize = 11.sp), textColor = ZcColors.Text3)
          }
        }
      }
      if (rest.isNotEmpty()) MarkdownText(rest, modifier = Modifier.padding(top = 4.dp))
    }
  } else {
    MarkdownText(content)
  }
}

@Composable
fun StreamingBubble(text: String, processing: Boolean) {
  if (processing) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
      horizontalArrangement = Arrangement.Start,
      verticalAlignment = Alignment.Bottom
    ) {
      Box(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(ZcColors.Accent),
        contentAlignment = Alignment.Center
      ) {
        Text("Z", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
      }
      Spacer(Modifier.width(8.dp))
      Surface(
        modifier = Modifier.clip(RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)),
        color = ZcColors.Card
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), color = ZcColors.Accent2, strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
          Text("Processing prompt...", fontSize = 12.sp, color = ZcColors.Text2)
        }
      }
    }
    return
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.Start,
    verticalAlignment = Alignment.Bottom
  ) {
    Box(
      modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(ZcColors.Accent),
      contentAlignment = Alignment.Center
    ) {
      Text("Z", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.width(8.dp))
    Surface(
      modifier = Modifier.clip(RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)),
      color = ZcColors.Card
    ) {
      Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (text.isNotEmpty()) MarkdownText(text)
      }
    }
  }
}

@Composable
fun InputBar(
  prompt: String,
  onPromptChange: (String) -> Unit,
  isInferring: Boolean,
  attachmentFileName: String?,
  isListening: Boolean,
  isSpeaking: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
  onImage: () -> Unit,
  onVoice: () -> Unit,
  onSpeak: (String) -> Unit
) {
  Surface(color = ZcColors.Surface, shadowElevation = 8.dp) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      if (attachmentFileName != null) {
        Surface(
          modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
          shape = RoundedCornerShape(8.dp),
          color = ZcColors.CardLight
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(Icons.Outlined.Image, null, modifier = Modifier.size(14.dp), tint = ZcColors.Purple)
            Spacer(Modifier.width(4.dp))
            Text(attachmentFileName, fontSize = 11.sp, color = ZcColors.Purple, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
          }
        }
      }
      OutlinedTextField(
        value = prompt,
        onValueChange = onPromptChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(if (isListening) "Listening..." else "Message...", color = ZcColors.Text3, fontSize = 14.sp) },
        enabled = !isInferring && !isListening,
        maxLines = 5,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (!isInferring && prompt.isNotBlank()) onSend() }),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ZcColors.Accent.copy(alpha = 0.5f),
          unfocusedBorderColor = if (isListening) ZcColors.Purple.copy(alpha = 0.5f) else ZcColors.Border,
          focusedContainerColor = ZcColors.Card,
          unfocusedContainerColor = ZcColors.Card,
          focusedTextColor = ZcColors.Text,
          unfocusedTextColor = ZcColors.Text,
          cursorColor = ZcColors.Accent
        ),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
      )
      Spacer(Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onImage, modifier = Modifier.size(36.dp)) {
          Icon(Icons.Outlined.Image, "Attach Image", tint = ZcColors.Purple, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onVoice, modifier = Modifier.size(36.dp)) {
          Icon(Icons.Outlined.Mic, "Voice Input", tint = if (isListening) ZcColors.Purple else ZcColors.Text2, modifier = Modifier.size(18.dp))
        }
        if (isSpeaking) {
          IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.VolumeUp, "Speaking", tint = ZcColors.Accent2, modifier = Modifier.size(18.dp))
          }
        }
        Spacer(Modifier.weight(1f))
        val enabled = prompt.isNotBlank() && !isInferring
        FilledIconButton(
          onClick = if (isInferring) onStop else onSend,
          enabled = enabled || isInferring,
          modifier = Modifier.size(40.dp),
          shape = CircleShape,
          colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isInferring) ZcColors.Red else ZcColors.Accent,
            disabledContainerColor = ZcColors.Card
          )
        ) {
          if (isInferring) {
            Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(18.dp))
          } else {
            Icon(
              Icons.AutoMirrored.Filled.Send,
              "Send",
              tint = if (enabled) Color.White else ZcColors.Text3,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
fun PromptSuggestions(expanded: Boolean, onToggle: () -> Unit, onSelect: (String) -> Unit) {
  val suggestions = listOf(
    "Summarize this thread so far",
    "Explain like I'm 5",
    "Write a poem about AI",
    "Give me 5 project ideas"
  )
  AnimatedVisibility(visible = expanded) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = ZcColors.Surface,
      shadowElevation = 4.dp
    ) {
      Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          suggestions.take(3).forEach { s ->
            AssistChip(
              onClick = { onSelect(s) },
              label = { Text(s, maxLines = 1, fontSize = 10.sp) },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = ZcColors.CardLight,
                labelColor = ZcColors.Text2
              ),
              shape = RoundedCornerShape(16.dp)
            )
          }
        }
      }
    }
  }
}
