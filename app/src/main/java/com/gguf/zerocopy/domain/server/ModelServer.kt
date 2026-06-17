package com.gguf.zerocopy.domain.server

import android.util.Log
import com.gguf.zerocopy.ZeroCopyApp
import com.gguf.zerocopy.domain.inference.TokenCallback
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ModelServer(private val port: Int = 8080) {
  private val tag = "ModelServer"
  private var serverSocket: ServerSocket? = null
  private var executor: ExecutorService? = null
  private val running = AtomicBoolean(false)
  private var serverStartTime = 0L
  private var autoModelPath: String = ""
  private var autoModelName: String = ""

  val isRunning: Boolean get() = running.get()

  fun setAutoModel(path: String, name: String) {
    autoModelPath = path
    autoModelName = name
  }

  fun start() {
    if (running.get()) return
    running.set(true)
    serverStartTime = System.currentTimeMillis()
    executor = Executors.newFixedThreadPool(4)
    executor?.submit { runServer() }
    // Auto-load model if configured and no model is currently loaded
    executor?.submit {
      val app = ZeroCopyApp.instance
      val engine = app.engineManager.getActiveEngine()
      if (engine?.isModelLoaded != true && autoModelPath.isNotEmpty()) {
        val modelInfo = app.modelRepository.models.value.find { it.path == autoModelPath }
        if (modelInfo != null) {
          engine?.let { e ->
            e.config = com.gguf.zerocopy.data.local.SettingsManager.toConfig()
            e.systemPrompt = com.gguf.zerocopy.data.local.SettingsManager.systemPrompt
            e.repeatPenalty = com.gguf.zerocopy.data.local.SettingsManager.toRepeatPenalty()
            val result = e.loadModel(modelInfo.path)
            if (result.isSuccess) {
              app.modelRepository.markUsed(modelInfo.id)
              Log.i(tag, "Auto-loaded model: ${modelInfo.name}")
            } else {
              Log.w(tag, "Failed to auto-load model: ${result.exceptionOrNull()?.message}")
            }
          }
        } else {
          Log.w(tag, "Auto-model not found in repository: $autoModelPath")
        }
      }
    }
    Log.i(tag, "Server starting on port $port")
  }

  fun stop() {
    running.set(false)
    try { serverSocket?.close() } catch (_: Exception) {}
    try { executor?.shutdownNow() } catch (_: Exception) {}
    executor = null
    serverSocket = null
    Log.i(tag, "Server stopped")
  }

  fun getLocalIp(): String = try {
    NetworkInterface.getNetworkInterfaces()?.asSequence()
      ?.flatMap { it.inetAddresses.asSequence() }
      ?.find { !it.isLoopbackAddress && it is InetAddress && it.hostAddress?.contains('.') == true }
      ?.hostAddress ?: "127.0.0.1"
  } catch (_: Exception) { "127.0.0.1" }

  fun getServerUrl(): String = "http://${getLocalIp()}:$port/v1"

  private fun runServer() {
    try {
      serverSocket = ServerSocket()
      serverSocket?.reuseAddress = true
      serverSocket?.bind(InetSocketAddress(port))
      Log.i(tag, "Server listening on 0.0.0.0:$port")

      while (running.get()) {
        try {
          val client = serverSocket?.accept() ?: break
          executor?.submit { handleClient(client) }
        } catch (_: Exception) {
          if (running.get()) break
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Server error: ${e.message}")
      running.set(false)
    }
  }

  private fun checkAuth(headers: Map<String, String>): Boolean {
    if (!com.gguf.zerocopy.data.local.SettingsManager.serverAuthEnabled) return true
    val expected = com.gguf.zerocopy.data.local.SettingsManager.serverAuthToken
    if (expected.isBlank()) return true
    val auth = headers["authorization"] ?: return false
    return auth == "Bearer $expected" || auth.equals("Bearer $expected", ignoreCase = true)
  }

  private fun handleClient(client: Socket) {
    try {
      client.use { socket ->
        socket.soTimeout = 30000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val rawPath = parts[1]
        val path = URLDecoder.decode(rawPath.split("?").first(), "UTF-8")

        val headers = mutableMapOf<String, String>()
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
          val colon = line.indexOf(':')
          if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
          }
          line = reader.readLine()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0 && (method == "POST" || method == "PUT")) {
          val chars = CharArray(contentLength)
          reader.read(chars, 0, contentLength)
          String(chars)
        } else ""

        val out = socket.getOutputStream()

        // CORS preflight
        if (method == "OPTIONS") {
          respond(out, 204, "", "text/plain")
          return
        }

        // Auth check for API endpoints
        if (path.startsWith("/v1/") && !checkAuth(headers)) {
          respond(out, 401, """{"error":"Unauthorized","message":"Invalid or missing API key. Use Authorization: Bearer <token>"}""")
          return
        }

        when {
          path == "/" || path == "" -> handleIndex(out)
          path == "/health" -> handleHealth(out)
          path == "/v1/models" -> handleModels(out)
          path == "/v1/chat/completions" && method == "POST" -> handleChatCompletions(out, body)
          path.startsWith("/v1/") -> handleApiV1(out, path, method, body)
          else -> respond(out, 404, "{\"error\":\"Not found\"}")
        }
      }
    } catch (e: Exception) {
      Log.w(tag, "Client error: ${e.message}")
    }
  }

  private fun handleIndex(out: OutputStream) {
    val app = ZeroCopyApp.instance
    val engine = app.engineManager.getActiveEngine()
    val loaded = engine?.isModelLoaded == true
    val modelName = engine?.loadedModelPath?.substringAfterLast('/') ?: "No model loaded"
    val uptime = (System.currentTimeMillis() - serverStartTime) / 1000
    val authEnabled = com.gguf.zerocopy.data.local.SettingsManager.serverAuthEnabled

    val authHeader = if (authEnabled) "  -H \"Authorization: Bearer YOUR_API_KEY\"\\\n" else ""
    val html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ZeroCopy AI - Local Inference Server</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:system-ui,sans-serif;background:#08080F;color:#EEEEF5;min-height:100vh;display:flex;flex-direction:column}
header{background:linear-gradient(135deg,#7C73FF,#00E6A8);padding:20px;text-align:center}
header h1{font-size:22px;font-weight:800;letter-spacing:2px}
header p{font-size:12px;opacity:.8;margin-top:2px}
.container{max-width:800px;margin:0 auto;padding:16px;flex:1;display:flex;flex-direction:column;gap:12px}
.card{background:#1A1A2E;border-radius:12px;padding:16px}
.card h3{font-size:11px;color:#9A9AB0;text-transform:uppercase;letter-spacing:1px;margin-bottom:8px}
.stat-row{display:flex;gap:12px;flex-wrap:wrap}
.stat{background:#20203A;border-radius:8px;padding:8px 12px}
.stat .label{color:#5C5C78;font-size:10px}
.stat .val{color:#EEEEF5;font-size:13px;font-weight:600}
.endpoint{background:#12121E;border-radius:8px;padding:10px 12px;margin-bottom:6px;font-family:monospace;font-size:12px}
.endpoint .method{display:inline-block;padding:2px 6px;border-radius:4px;font-weight:700;font-size:10px;margin-right:8px}
.method.get{background:#0055AA;color:white}
.method.post{background:#00AA55;color:white}
.endpoint .desc{color:#9A9AB0;font-size:11px}
code{background:#20203A;padding:2px 6px;border-radius:4px;font-size:11px;color:#BB86FC}
.chat-area{flex:1;display:flex;flex-direction:column;background:#12121E;border-radius:12px;overflow:hidden;min-height:200px}
.messages{flex:1;overflow-y:auto;padding:12px;display:flex;flex-direction:column;gap:6px}
.msg{padding:8px 12px;border-radius:12px;max-width:80%;font-size:13px;line-height:1.5}
.msg.user{background:#2D2B55;align-self:flex-end;border-bottom-right-radius:3px}
.msg.assistant{background:#20203A;align-self:flex-start;border-bottom-left-radius:3px}
.input-area{display:flex;gap:6px;padding:10px;background:#1A1A2E}
.input-area input{flex:1;background:#12121E;border:1px solid #2A2A45;border-radius:20px;padding:8px 12px;color:#EEEEF5;font-size:13px;outline:none}
.input-area input:focus{border-color:#7C73FF}
.input-area button{background:#7C73FF;border:none;border-radius:20px;padding:8px 16px;color:white;font-weight:600;cursor:pointer;font-size:13px}
.input-area button:hover{background:#6A62E0}
.input-area button:disabled{opacity:.5;cursor:not-allowed}
.badge{background:#20203A;border-radius:6px;padding:4px 10px;font-size:10px;color:#9A9AB0;font-family:monospace}
.badge a{color:#7C73FF;text-decoration:none}
footer{text-align:center;padding:12px;color:#5C5C78;font-size:10px}
</style>
</head>
<body>
<header>
<h1>ZeroCopy AI</h1>
<p>by adeennour4-dot &mdash; Local On-Device Inference Server</p>
</header>
<div class="container">
<div class="card">
<h3>Server Status</h3>
<div class="stat-row">
<div class="stat"><div class="label">Model</div><div class="val" style="color:${if (loaded) "#00E6A8" else "#FFBE0B"}">${if (loaded) modelName else "Not loaded"}</div></div>
<div class="stat"><div class="label">Uptime</div><div class="val">${uptime / 60}m ${uptime % 60}s</div></div>
<div class="stat"><div class="label">Auth</div><div class="val" style="color:${if (authEnabled) "#FFBE0B" else "#00E6A8"}">${if (authEnabled) "Enabled" else "Off"}</div></div>
<div class="stat"><div class="label">API Base</div><div class="val" style="font-size:11px">${getServerUrl()}</div></div>
</div>
</div>

<div class="card">
<h3>API Reference</h3>
<div class="endpoint"><span class="method get">GET</span><a href="/v1/models" style="color:#EEEEF5;text-decoration:none">/v1/models</a> <span class="desc">List available models</span></div>
<div class="endpoint"><span class="method post">POST</span>/v1/chat/completions <span class="desc">Send a chat message</span></div>
<div style="margin-top:8px;font-size:11px;color:#5C5C78;font-family:monospace;line-height:1.8">
<strong style="color:#9A9AB0">Example:</strong><br>
curl ${getServerUrl()}/chat/completions \ <br>
-H "Content-Type: application/json" \ <br>
${authHeader}-d '{ <br>
&nbsp;&nbsp;"model": "${modelName}", <br>
&nbsp;&nbsp;"messages": [{"role": "user", "content": "Hello!"}], <br>
&nbsp;&nbsp;"stream": false <br>
}'
</div>
</div>

<div class="chat-area">
<div class="messages" id="messages"></div>
<div class="input-area">
<input type="text" id="prompt" placeholder="Type a message..." onkeydown="if(event.key==='Enter') send()">
<button id="sendBtn" onclick="send()" ${if (!loaded) "disabled" else ""}>Send</button>
</div>
</div>

<div style="display:flex;gap:6px;flex-wrap:wrap">
<div class="badge"><a href="/v1/models">/v1/models</a></div>
<div class="badge">POST /v1/chat/completions</div>
<div class="badge"><a href="/health">/health</a></div>
<div class="badge">API: ${getServerUrl()}</div>
</div>
</div>
<footer>ZeroCopy v1.0.0 &mdash; adeennour4-dot/111</footer>
<script>
const msgs=document.getElementById('messages');
const prompt=document.getElementById('prompt');
const btn=document.getElementById('sendBtn');
let inferring=false;
function addMsg(role,content){const d=document.createElement('div');d.className='msg '+role;d.textContent=content;msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight;}
async function send(){const text=prompt.value.trim();if(!text||inferring)return;prompt.value='';addMsg('user',text);inferring=true;btn.disabled=true;btn.textContent='...';const md=document.createElement('div');md.className='msg assistant';msgs.appendChild(md);msgs.scrollTop=msgs.scrollHeight;try{const r=await fetch('/v1/chat/completions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({messages:[{role:'user',content:text}],stream:false})});if(!r.ok){const e=await r.json();md.textContent='Error: '+(e.message||e.error||r.status);return;}const d=await r.json();md.textContent=d.choices?.[0]?.message?.content||d.response||JSON.stringify(d);}catch(e){md.textContent='Error: '+e.message;}inferring=false;btn.disabled=false;btn.textContent='Send';}
</script>
</body>
</html>"""
    respond(out, 200, html, "text/html; charset=utf-8")
  }

  private fun handleHealth(out: OutputStream) {
    val app = ZeroCopyApp.instance
    val engine = app.engineManager.getActiveEngine()
    respond(out, 200, """{"status":"ok","model_loaded":${engine?.isModelLoaded == true},"version":"1.0.0"}""")
  }

  private fun handleModels(out: OutputStream) {
    val app = ZeroCopyApp.instance
    val models = app.modelRepository.models.value
    val engine = app.engineManager.getActiveEngine()
    val jsonModels = models.joinToString(",") { m ->
      """{"id":"${m.id}","object":"model","name":"${m.name}","format":"${m.format}","engine":"${m.engine.id}","size":"${m.sizeFormatted}","loaded":${engine?.loadedModelPath == m.path}}"""
    }
    respond(out, 200, """{"object":"list","data":[$jsonModels]}""")
  }

  private fun handleChatCompletions(out: OutputStream, body: String) {
    try {
      val app = ZeroCopyApp.instance
      val engine = app.engineManager.getActiveEngine()
      if (engine?.isModelLoaded != true) {
        respond(out, 503, """{"error":"No model loaded"}""")
        return
      }

      val prompt = extractPrompt(body)
      if (prompt.isNullOrBlank()) {
        respond(out, 400, """{"error":"prompt is required"}""")
        return
      }

      val resultBuilder = StringBuilder()
      val done = AtomicBoolean(false)
      var errorMsg: String? = null

      runBlocking {
        engine.executeInference(prompt, object : TokenCallback {
          override fun onToken(token: String) { resultBuilder.append(token) }
          override fun onDone() { done.set(true) }
          override fun onError(error: String) { errorMsg = error; done.set(true) }
          override fun onKvUsage(percent: Int) {}
          override fun onTokensGenerated(count: Int) {}
        })
      }

      // Wait for completion with timeout
      var waited = 0
      while (!done.get() && waited < 120_000) {
        Thread.sleep(100)
        waited += 100
      }

      if (errorMsg != null) {
        respond(out, 500, """{"error":"$errorMsg"}""")
        return
      }

      val response = engine.readTokenStream().ifEmpty { resultBuilder.toString() }
      val json = """{"id":"chat_${System.currentTimeMillis()}","object":"chat.completion","created":${System.currentTimeMillis()/1000},"model":"${engine.loadedModelPath?.substringAfterLast('/') ?: "unknown"}","choices":[{"index":0,"message":{"role":"assistant","content":${jsonEncode(response)}},"finish_reason":"stop"}],"usage":{"total_tokens":${engine.getTokensGenerated()}}}"""
      respond(out, 200, json)
    } catch (e: Exception) {
      respond(out, 500, """{"error":"${e.message?.replace("\"","'") ?: "Internal error"}"}""")
    }
  }

  private fun handleApiV1(out: OutputStream, path: String, method: String, body: String) {
    respond(out, 404, """{"error":"Endpoint not found"}""")
  }

  private fun extractPrompt(body: String): String? {
    return try {
      val json = org.json.JSONObject(body)
      val msgs = json.optJSONArray("messages")
      if (msgs != null && msgs.length() > 0) {
        val sb = StringBuilder()
        for (i in 0 until msgs.length()) {
          val msg = msgs.getJSONObject(i)
          val role = msg.optString("role", "user")
          val content = msg.optString("content", "")
          if (content.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("<|im_start|>$role\n$content<|im_end|>")
          }
        }
        if (sb.isNotEmpty()) {
          sb.append("\n<|im_start|>assistant\n")
          return sb.toString()
        }
        msgs.getJSONObject(msgs.length() - 1).optString("content", "")
      } else {
        json.optString("prompt", json.optString("input", ""))
      }
    } catch (_: Exception) { null }
  }

  private fun jsonEncode(s: String): String {
    val escaped = s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "\"$escaped\""
  }

  private fun respond(out: OutputStream, code: Int, body: String, contentType: String = "application/json") {
    val status = when (code) {
      200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
      else -> "Unknown"
    }
    val response = "HTTP/1.1 $code $status\r\n" +
      "Content-Type: $contentType\r\n" +
      "Content-Length: ${body.toByteArray().size}\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
      "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
      "Connection: close\r\n" +
      "\r\n" +
      body
    try { out.write(response.toByteArray()); out.flush() } catch (_: Exception) {}
  }
}
