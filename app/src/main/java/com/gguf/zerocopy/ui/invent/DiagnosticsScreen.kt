package com.gguf.zerocopy.ui.invent

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.BuildConfig
import com.gguf.zerocopy.data.repository.LocalModel
import java.io.File
import org.json.JSONObject

private val DbgBg = Color(0xFF0B0D12)
private val DbgCard = Color(0xFF14171F)
private val DbgLine = Color(0xFF262B38)
private val DbgAccent = Color(0xFFFFD166)
private val DbgText = Color(0xFFB9C1D0)

/** In-app diagnostics: version, device, RAM/storage, native libs, model list, logcat tail. */
@Composable
fun DiagnosticsScreen(
    models: List<LocalModel>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var text by remember { mutableStateOf("collecting…") }

    LaunchedEffect(Unit) {
        text = buildDiagnostics(context, models)
    }

    Column(Modifier.fillMaxSize().background(DbgBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = DbgText, modifier = Modifier.size(18.dp))
            }
            Text("DIAGNOSTICS", fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DbgAccent)
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("zerocopy-diagnostics", text))
                },
                shape = MaterialTheme.shapes.small,
                color = DbgCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, DbgLine)
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContentCopy, null, tint = DbgAccent, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", fontSize = 10.sp, color = DbgText, fontFamily = FontFamily.Monospace)
                }
            }
        }
        HorizontalDivider(color = DbgLine, thickness = 0.5.dp)
        Text(
            text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = DbgText,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

private fun buildDiagnostics(context: Context, models: List<LocalModel>): String {
    val sb = StringBuilder()
    sb.appendLine("═══ ZeroCopy diagnostics ═══")
    sb.appendLine("version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
    sb.appendLine("flavor: ${if (BuildConfig.IS_COMPAT_BUILD) "compatibility" else "standard"}")
    sb.appendLine("package: ${context.packageName}")
    sb.appendLine("android: ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
    sb.appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
    sb.appendLine()
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        sb.appendLine("free RAM: ${mi.availMem / 1048576} MB / total ${mi.totalMem / 1048576} MB")
    } catch (_: Exception) { sb.appendLine("free RAM: (unavailable)") }
    try {
        val st = StatFs(context.filesDir.path)
        sb.appendLine("internal storage free: ${st.availableBytes / 1048576} MB")
    } catch (_: Exception) { sb.appendLine("internal storage: (unavailable)") }
    sb.appendLine()
    val libs = runCatching {
        File(context.applicationInfo.nativeLibraryDir).listFiles()?.map { it.name }?.sorted()
    }.getOrNull()
    sb.appendLine("native libs: ${libs?.joinToString(", ") ?: "none"}")
    sb.appendLine()
    try {
        if (com.gguf.zerocopy.domain.inference.NativeBridge.nativeLibLoaded) {
            val nd = JSONObject(com.gguf.zerocopy.domain.inference.NativeBridge.getNativeDiagnosticsNative())
            sb.appendLine("llama bridge: ${nd.optString("bridge", "?")} (${nd.optString("arch_profile", "?")})")
            val feats = nd.optString("cpu_features", "?").trim()
            sb.appendLine("cpu: ${if (feats.isBlank()) "(no features line)" else feats}")
            val cores = nd.optInt("cores", 0)
            val big = if (nd.has("big_cores")) nd.optInt("big_cores", 0) else -1
            sb.appendLine("cores: $cores" + if (big >= 0) " (big: $big)" else "")
            sb.appendLine("device RAM: ${nd.optLong("ram_mb", 0)} MB")
            if (nd.optBoolean("model_loaded", false)) {
                sb.appendLine("model: ${nd.optString("model_path", "?")}")
                sb.appendLine("  n_params=${nd.optLong("n_params", 0)} ctx=${nd.optInt("n_ctx", 0)} flash_attn=${nd.optBoolean("flash_attn", false)}")
            } else {
                sb.appendLine("model: not loaded")
            }
        } else {
            sb.appendLine("llama bridge: not loaded")
        }
    } catch (_: Exception) {
        sb.appendLine("llama bridge: (unavailable)")
    }
    sb.appendLine()
    sb.appendLine("models on device: ${models.size}")
    models.take(12).forEach { m ->
        sb.appendLine("  • ${m.name} — ${m.sizeFormatted} (${m.format})")
    }
    if (models.size > 12) sb.appendLine("  … +${models.size - 12} more")
    sb.appendLine()
    // Logcat tail filtered to this process (works without extra permissions).
    try {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500"))
        val lines = proc.inputStream.bufferedReader().use { it.readLines() }
        val own = lines.filter { it.contains(context.packageName) }.takeLast(140)
        sb.appendLine("── logcat tail (own package) ──")
        if (own.isEmpty()) sb.appendLine("(no matching lines)")
        own.forEach { sb.appendLine(it) }
    } catch (_: Exception) {
        sb.appendLine("── logcat tail ── (unavailable)")
    }
    return sb.toString()
}
