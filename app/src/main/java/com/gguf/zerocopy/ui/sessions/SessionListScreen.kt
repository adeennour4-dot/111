package com.gguf.zerocopy.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import com.gguf.zerocopy.ui.theme.currentPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
  onSessionSelected: (ChatSession) -> Unit,
  onBack: () -> Unit
) {
  val colors = currentPalette()
  val app = ZeroCopyApp.instance
  val sessions by app.chatRepository.sessions.collectAsState(initial = emptyList())
  var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
  var renameName by remember { mutableStateOf("") }
  var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Sessions", fontWeight = FontWeight.Bold, color = colors.Text) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = colors.Text2)
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = {
          val session = app.chatRepository.createSession()
          onSessionSelected(session)
        },
        containerColor = colors.Accent,
        contentColor = colors.Bg,
        shape = RoundedCornerShape(16.dp)
      ) {
        Icon(Icons.Filled.Add, "New Chat")
      }
    },
    containerColor = colors.Bg
  ) { pad ->
    Box(modifier = Modifier.padding(pad).fillMaxSize()) {
      if (sessions.isEmpty()) {
        Column(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            Icons.Outlined.AddComment,
            null,
            modifier = Modifier.size(48.dp),
            tint = colors.Text3
          )
          Spacer(Modifier.height(16.dp))
          Text("No sessions yet", color = colors.Text3, fontSize = 16.sp)
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
              onSelect = { onSessionSelected(session) },
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

  renameTarget?.let { session ->
    AlertDialog(
      onDismissRequest = { renameTarget = null },
      containerColor = colors.Card,
      title = { Text("Rename Session", color = colors.Text) },
      text = {
        OutlinedTextField(
          value = renameName,
          onValueChange = { renameName = it },
          label = { Text("Session name") },
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.Accent,
            unfocusedBorderColor = colors.Border,
            focusedTextColor = colors.Text,
            unfocusedTextColor = colors.Text,
            cursorColor = colors.Accent
          )
        )
      },
      confirmButton = {
        TextButton(onClick = {
          if (renameName.isNotBlank()) {
            app.chatRepository.renameSession(session.id, renameName)
          }
          renameTarget = null
        }) { Text("Rename", color = colors.Accent) }
      },
      dismissButton = {
        TextButton(onClick = { renameTarget = null }) {
          Text("Cancel", color = colors.Text2)
        }
      }
    )
  }

  deleteTarget?.let { session ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      containerColor = colors.Card,
      title = { Text("Delete Session?", color = colors.Text) },
      text = {
        Text(
          "Delete \"${session.name}\" and all its messages? This cannot be undone.",
          color = colors.Text2
        )
      },
      confirmButton = {
        TextButton(onClick = {
          app.chatRepository.deleteSession(session.id)
          deleteTarget = null
        }) { Text("Delete", color = colors.Red) }
      },
      dismissButton = {
        TextButton(onClick = { deleteTarget = null }) {
          Text("Cancel", color = colors.Text2)
        }
      }
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
  session: ChatSession,
  onSelect: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit
) {
  val colors = currentPalette()
  var showMenu by remember { mutableStateOf(false) }

  val dateStr = remember(session.lastMessageAt) {
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(session.lastMessageAt))
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(
        onClick = onSelect,
        onLongClick = { showMenu = true }
      ),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = colors.CardLight),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Box {
      Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            session.name,
            color = colors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
          )
          Spacer(Modifier.height(4.dp))
          Row {
            Text(dateStr, fontSize = 10.sp, color = colors.Text3, fontFamily = FontFamily.Monospace)
            Text(" · ", fontSize = 10.sp, color = colors.Text3)
            Text(
              "${session.messageCount} msg${if (session.messageCount != 1) "s" else ""}",
              fontSize = 10.sp,
              color = colors.Text3,
              fontFamily = FontFamily.Monospace
            )
            if (session.modelName.isNotEmpty()) {
              Text(" · ", fontSize = 10.sp, color = colors.Text3)
              Text(
                session.modelName,
                fontSize = 10.sp,
                color = colors.Accent2,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
              )
            }
          }
        }
      }

      DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
      ) {
        DropdownMenuItem(
          text = { Text("Rename", fontSize = 14.sp) },
          onClick = {
            showMenu = false
            onRename()
          },
          leadingIcon = {
            Icon(
              Icons.Filled.DriveFileRenameOutline,
              null,
              modifier = Modifier.size(18.dp),
              tint = colors.Accent
            )
          }
        )
        DropdownMenuItem(
          text = { Text("Delete", fontSize = 14.sp, color = colors.Red) },
          onClick = {
            showMenu = false
            onDelete()
          },
          leadingIcon = {
            Icon(
              Icons.Filled.Delete,
              null,
              modifier = Modifier.size(18.dp),
              tint = colors.Red
            )
          }
        )
      }
    }
  }
}
