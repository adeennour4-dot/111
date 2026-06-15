package com.gguf.zerocopy.ui.sessions

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.data.repository.ChatSession
import com.gguf.zerocopy.ui.theme.ZcColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(onSessionSelected: (String) -> Unit, onBack: () -> Unit) {
  val app = ZeroCopyApp.instance
  val sessions by app.chatRepository.sessions.collectAsState(initial = emptyList())
  var showNewDialog by remember { mutableStateOf(false) }
  var newName by remember { mutableStateOf("") }
  var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
  var renameName by remember { mutableStateOf("") }
  var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Chat Sessions", fontWeight = FontWeight.Bold, color = ZcColors.Text) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = ZcColors.Text2)
          }
        },
        actions = {
          IconButton(onClick = { showNewDialog = true }) {
            Icon(Icons.Filled.Add, "New Chat", tint = ZcColors.Accent)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ZcColors.Bg)
      )
    },
    containerColor = ZcColors.Bg
  ) { pad ->
    Box(modifier = Modifier.padding(pad).fillMaxSize()) {
      if (sessions.isEmpty()) {
        Column(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            Icons.Outlined.Chat,
            null,
            modifier = Modifier.size(48.dp),
            tint = ZcColors.Text3
          )
          Spacer(Modifier.height(16.dp))
          Text("No chat sessions", color = ZcColors.Text3, fontSize = 16.sp)
          Text("Tap + to start a new chat", color = ZcColors.Text3, fontSize = 13.sp)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(sessions, key = { it.id }) { session ->
            SessionCard(
              session = session,
              onClick = { onSessionSelected(session.id) },
              onRename = {
                renameTarget = session
                renameName = session.name
              },
              onDelete = { deleteTarget = session }
            )
          }
        }
      }
    }
  }

  if (showNewDialog) {
    AlertDialog(
      onDismissRequest = { showNewDialog = false },
      containerColor = ZcColors.Card,
      title = { Text("New Chat", color = ZcColors.Text) },
      text = {
        OutlinedTextField(
          value = newName,
          onValueChange = { newName = it },
          label = { Text("Session name (optional)") },
          singleLine = true,
          shape = RoundedCornerShape(10.dp)
        )
      },
      confirmButton = {
        TextButton(onClick = {
          app.chatRepository.createSession(newName.ifEmpty { null })
          showNewDialog = false
          newName = ""
        }) { Text("Create", color = ZcColors.Accent) }
      },
      dismissButton = {
        TextButton(onClick = {
          showNewDialog = false
          newName = ""
        }) { Text("Cancel", color = ZcColors.Text2) }
      }
    )
  }

  renameTarget?.let { session ->
    AlertDialog(
      onDismissRequest = { renameTarget = null },
      containerColor = ZcColors.Card,
      title = { Text("Rename Session", color = ZcColors.Text) },
      text = {
        OutlinedTextField(
          value = renameName,
          onValueChange = { renameName = it },
          label = { Text("New name") },
          singleLine = true,
          shape = RoundedCornerShape(10.dp)
        )
      },
      confirmButton = {
        TextButton(onClick = {
          app.chatRepository.renameSession(session.id, renameName)
          renameTarget = null
        }) { Text("Rename", color = ZcColors.Accent) }
      },
      dismissButton = {
        TextButton(onClick = { renameTarget = null }) {
          Text("Cancel", color = ZcColors.Text2)
        }
      }
    )
  }

  deleteTarget?.let { session ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      containerColor = ZcColors.Card,
      title = { Text("Delete Session?", color = ZcColors.Text) },
      text = {
        Text("Delete \"${session.name}\" and all its messages?", color = ZcColors.Text2)
      },
      confirmButton = {
        TextButton(onClick = {
          app.chatRepository.deleteSession(session.id)
          deleteTarget = null
        }) { Text("Delete", color = ZcColors.Red) }
      },
      dismissButton = {
        TextButton(onClick = { deleteTarget = null }) {
          Text("Cancel", color = ZcColors.Text2)
        }
      }
    )
  }
}

@Composable
private fun SessionCard(
  session: ChatSession,
  onClick: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit
) {
  val dateStr = SimpleDateFormat(
    "MMM d, HH:mm",
    Locale.getDefault()
  ).format(Date(session.lastMessageAt))

  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = ZcColors.CardLight
  ) {
    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(
        Icons.Outlined.Chat,
        null,
        modifier = Modifier.size(32.dp),
        tint = ZcColors.Accent2
      )
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(
          session.name,
          color = ZcColors.Text,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1
        )
        Row {
          Text(dateStr, fontSize = 10.sp, color = ZcColors.Text3, fontFamily = FontFamily.Monospace)
          Text(" · ", fontSize = 10.sp, color = ZcColors.Text3)
          Text(
            "${session.messageCount} msgs",
            fontSize = 10.sp,
            color = ZcColors.Text3,
            fontFamily = FontFamily.Monospace
          )
        }
      }
      IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.Edit, "Rename", tint = ZcColors.Accent, modifier = Modifier.size(18.dp))
      }
      IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.Delete, "Delete", tint = ZcColors.Red, modifier = Modifier.size(18.dp))
      }
    }
  }
}
