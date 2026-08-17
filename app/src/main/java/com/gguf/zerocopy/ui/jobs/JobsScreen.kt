package com.gguf.zerocopy.ui.jobs
import com.gguf.zerocopy.ui.theme.ZcShape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.domain.inference.JobManager
import com.gguf.zerocopy.ui.theme.currentPalette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(onBack: () -> Unit) {
    val colors = currentPalette()
    val jobManager = ZeroCopyApp.instance.jobManager
    var jobs by remember { mutableStateOf(jobManager.activeJobs()) }

    // Poll for job updates every second
    LaunchedEffect(Unit) {
        while (true) {
            jobs = jobManager.activeJobs()
            delay(1000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Running Jobs", fontWeight = FontWeight.Bold, color = colors.Text) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, "Close", tint = colors.Text2)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.Bg)
            )
        },
        containerColor = colors.Bg
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            if (jobs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No active jobs",
                        fontSize = 14.sp,
                        color = colors.Text2,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Background tasks (model load, inference, downloads)\nwill appear here.",
                        fontSize = 11.sp,
                        color = colors.Text3,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(jobs, key = { it.id }) { job ->
                        JobCard(job, onCancel = { jobManager.cancel(job.id) })
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    shape = ZcShape.Pill,
                    onClick = { jobManager.cancelAll() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Cancel All", color = colors.Red, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: JobManager.TrackedJob, onCancel: () -> Unit) {
    val colors = currentPalette()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ZcShape.Lg,
        colors = CardDefaults.cardColors(containerColor = colors.Card)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                iconForCategory(job.category),
                contentDescription = null,
                tint = colors.Accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.label,
                    fontSize = 13.sp,
                    color = colors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Started ${timeFormat.format(Date(job.startTimeMs))}",
                    fontSize = 10.sp,
                    color = colors.Text3,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel ${job.label}",
                    tint = colors.Red,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.Accent,
            trackColor = colors.Border.copy(alpha = 0.3f)
        )
    }
}

private fun iconForCategory(cat: JobManager.JobCategory): ImageVector = when (cat) {
    JobManager.JobCategory.MODEL_LOAD -> Icons.Outlined.SmartToy
    JobManager.JobCategory.INFERENCE -> Icons.Outlined.Memory
    JobManager.JobCategory.DOWNLOAD -> Icons.Outlined.Download
    JobManager.JobCategory.EXPORT -> Icons.Outlined.TextSnippet
    JobManager.JobCategory.OTHER -> Icons.Outlined.TextSnippet
}
