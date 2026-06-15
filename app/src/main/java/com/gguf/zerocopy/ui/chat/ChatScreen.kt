package com.gguf.zerocopy.ui.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
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
import com.gguf.zerocopy.data.repository.AttachmentType
import com.gguf.zerocopy.data.repository.ChatMessage
import com.gguf.zerocopy.data.repository.MessageRole
import com.gguf.zerocopy.domain.inference.TokenCallback
import com.gguf.zerocopy.ui.theme.ZcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  modelPath: String,
  modelName: String,
  sessionId: String?,
  onBack: () -> Unit,
  onSettings: () -> Unit,
  onModels: () -> Unit,
  onSessions: () -> Unit
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

  val chatId = sessionId ?: remember { app.chatRepository.createSession("Chat - $modelName").id }

  LaunchedEffect(chatId) {
    if (sessionId != null) app.chatRepository.selectSession(sessionId)
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
            tps = if (elapsed >
              0
            ) {
              ft / elapsed
            } else {
              0f
            },
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
    rememberLauncherForActivityResult(
      ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        attachmentUri = result.data?.data
      }
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
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
              color =
              if (engine?.isModelLoaded ==
                true
              ) {
                ZcColors.Accent2
              } else {
                ZcColors.Text3
              },
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = {
            engine?.unloadModel()
            onBack()
          }) { Icon(Icons.Outlined.ArrowBack, "Back", tint = ZcColors.Text2) }
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
              colors =
              AssistChipDefaults.assistChipColors(
                containerColor = if (kvUsage >
                  80
                ) {
                  ZcColors.Red.copy(alpha = 0.2f)
                } else {
                  ZcColors.Accent2.copy(alpha = 0.15f)
                },
                labelColor = if (kvUsage > 80) ZcColors.Red else ZcColors.Accent2
              )
            )
          }
          IconButton(onClick = onSessions) {
            Icon(Icons.Outlined.Chat, "Sessions", tint = ZcColors.Text2)
          }
          IconButton(onClick = onModels) {
            Icon(Icons.Filled.List, "Models", tint = ZcColors.Text2)
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
              modifier = Modifier.size(48.dp),
              tint = ZcColors.Text3
            )
            Spacer(Modifier.height(16.dp))
            Text("No model loaded", color = ZcColors.Text3, fontSize = 16.sp)
            Text("Go back and select a model", color = ZcColors.Text3, fontSize = 13.sp)
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            items(messages) { msg -> ChatBubble(msg, clip) }
            if (isInferring && streamedText.isNotEmpty()) {
              item { StreamingBubble(streamedText, false) }
            } else if (isInferring && isProcessing) {
              item { StreamingBubble("", true) }
            }
          }
        }
      }

      if (engine?.isModelLoaded == true) {
        InputBar(
          prompt = prompt,
          onPromptChange = { prompt = it },
          isInferring = isInferring,
          attachmentFileName = attachmentUri?.lastPathSegment?.substringAfterLast('/'),
          onSend = {
            if (prompt.isNotBlank()) {
              val msg = prompt
              val attach = attachmentUri
              prompt = ""
              streamedText = ""
              isInferring = true
              val attachmentName = attach?.lastPathSegment?.substringAfterLast('/')
              val userMsg =
                if (attach != null) {
                  ChatMessage(
                    MessageRole.USER,
                    msg,
                    attachmentPath = attachmentName,
                    attachmentType = AttachmentType.IMAGE
                  )
                } else {
                  ChatMessage(MessageRole.USER, msg)
                }
              messages = messages + userMsg
              app.chatRepository.addMessage(chatId, userMsg)
              attachmentUri = null

              scope.launch(Dispatchers.IO) {
                val cb =
                  object : TokenCallback {
                    override fun onToken(token: String) {}

                    override fun onDone() {}

                    override fun onError(error: String) {}

                    override fun onKvUsage(percent: Int) {}

                    override fun onTokensGenerated(count: Int) {}
                  }
                var fullPrompt = msg
                if (attach != null) {
                  fullPrompt = "[Image attached: $attachmentName]\n$msg"
                }
                engine.executeInference(fullPrompt, cb)
              }
            }
          },
          onStop = { engine.abortInference() },
          onImage = {
            val intent =
              Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
              }
            imagePicker.launch(intent)
          }
        )
      }
    }
  }
}

@Composable
fun ChatBubble(msg: ChatMessage, clip: ClipboardManager) {
  val isUser = msg.role == MessageRole.USER
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
        Text(
          "Z",
          fontSize = 11.sp,
          color = Color.White,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace
        )
      }
      Spacer(Modifier.width(8.dp))
    }
    Column(modifier = Modifier.widthIn(max = 300.dp)) {
      Surface(
        modifier =
        Modifier
          .clip(
            RoundedCornerShape(
              topStart = if (isUser) 18.dp else 6.dp,
              topEnd = if (isUser) 6.dp else 18.dp,
              bottomStart = 18.dp,
              bottomEnd = 18.dp
            )
          ).clickable {
            clip.setPrimaryClip(ClipData.newPlainText("msg", msg.content))
          },
        color = if (isUser) ZcColors.UserBg else ZcColors.Card
      ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
          if (msg.attachmentPath != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                Icons.Outlined.Image,
                null,
                modifier = Modifier.size(16.dp),
                tint = ZcColors.Purple
              )
              Spacer(Modifier.width(4.dp))
              Text(
                msg.attachmentPath,
                fontSize = 11.sp,
                color = ZcColors.Purple,
                fontFamily = FontFamily.Monospace
              )
            }
            Spacer(Modifier.height(4.dp))
          }
          if (!isUser) {
            ThinkingContent(msg.content)
          } else {
            MarkdownText(msg.content)
          }
        }
      }
      if (!isUser && msg.tps > 0) {
        Text(
          "%.1f t/s · %d tok".format(msg.tps, msg.tokens),
          modifier = Modifier.padding(start = 4.dp, top = 2.dp),
          fontSize = 9.sp,
          color = ZcColors.Text3,
          fontFamily = FontFamily.Monospace
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
  val pattern =
    remember {
      Regex("(?:<think>|<think>\\n?)(.*?)(?:</think>|</think>\\n?)", RegexOption.DOT_MATCHES_ALL)
    }
  val match = remember(content) { pattern.find(content) }
  if (match != null) {
    val think = match.groupValues[1].trim()
    val rest = content.substring(match.range.last + 1).trim()
    var open by remember { mutableStateOf(false) }
    Column {
      Surface(
        onClick = {
          open = !open
        },
        shape = RoundedCornerShape(
          8.dp
        ),
        color = ZcColors.ThinkBg,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
      ) {
        Column(modifier = Modifier.padding(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Outlined.Lightbulb,
              null,
              modifier = Modifier.size(12.dp),
              tint = ZcColors.Purple
            )
            Spacer(Modifier.width(4.dp))
            Text(
              if (open) "Thinking" else "Thinking...",
              fontSize = 10.sp,
              color = ZcColors.Purple,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.SemiBold
            )
          }
          AnimatedVisibility(open) {
            MarkdownText(
              think,
              modifier = Modifier.padding(top = 4.dp),
              style = LocalTextStyle.current.copy(fontSize = 11.sp),
              textColor = ZcColors.Text3
            )
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
        Text(
          "Z",
          fontSize = 11.sp,
          color = Color.White,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace
        )
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
          CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = ZcColors.Accent2,
            strokeWidth = 2.dp
          )
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
      Text(
        "Z",
        fontSize = 11.sp,
        color = Color.White,
        fontWeight = FontWeight.Black,
        fontFamily = FontFamily.Monospace
      )
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
  onSend: () -> Unit,
  onStop: () -> Unit,
  onImage: () -> Unit
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
            Icon(
              Icons.Outlined.Image,
              null,
              modifier = Modifier.size(14.dp),
              tint = ZcColors.Purple
            )
            Spacer(Modifier.width(4.dp))
            Text(
              attachmentFileName,
              fontSize = 11.sp,
              color = ZcColors.Purple,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
      OutlinedTextField(
        value = prompt,
        onValueChange = onPromptChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Message...", color = ZcColors.Text3, fontSize = 14.sp) },
        enabled = !isInferring,
        maxLines = 5,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
          if (!isInferring &&
            prompt.isNotBlank()
          ) {
            onSend()
          }
        }),
        colors =
        OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ZcColors.Accent.copy(alpha = 0.5f),
          unfocusedBorderColor = ZcColors.Border,
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
          Icon(
            Icons.Outlined.Image,
            "Attach Image",
            tint = ZcColors.Purple,
            modifier = Modifier.size(18.dp)
          )
        }
        Spacer(Modifier.weight(1f))
        val enabled = prompt.isNotBlank() && !isInferring
        FilledIconButton(
          onClick = if (isInferring) onStop else onSend,
          enabled = enabled || isInferring,
          modifier = Modifier.size(40.dp),
          shape = CircleShape,
          colors =
          IconButtonDefaults.filledIconButtonColors(
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
