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

  val isRunning: Boolean get() = running.get()

  fun start() {
    if (running.get()) return
    running.set(true)
    executor = Executors.newFixedThreadPool(4)
    executor?.submit { runServer() }
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

  fun getServerUrl(): String = "http://${getLocalIp()}:$port"

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

        when {
          path == "/" || path == "" -> handleIndex(socket.getOutputStream())
          path == "/health" -> handleHealth(socket.getOutputStream())
          path == "/v1/models" -> handleModels(socket.getOutputStream())
          path == "/v1/chat/completions" && method == "POST" -> handleChatCompletions(socket.getOutputStream(), body)
          path.startsWith("/v1/") -> handleApiV1(socket.getOutputStream(), path, method, body)
          else -> respond(socket.getOutputStream(), 404, "{\"error\":\"Not found\"}")
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

    val html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ZeroCopy AI - Local Inference Server</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: 'Segoe UI', system-ui, sans-serif; background: #08080F; color: #EEEEF5; min-height: 100vh; display: flex; flex-direction: column; }
  header { background: linear-gradient(135deg, #7C73FF, #00E6A8); padding: 24px; text-align: center; }
  header h1 { font-size: 24px; font-weight: 800; letter-spacing: 2px; }
  header p { font-size: 13px; opacity: 0.8; margin-top: 4px; }
  .container { max-width: 800px; margin: 0 auto; padding: 24px; flex: 1; display: flex; flex-direction: column; }
  .status { background: #1A1A2E; border-radius: 12px; padding: 16px; margin-bottom: 16px; }
  .status .label { color: #9A9AB0; font-size: 11px; text-transform: uppercase; letter-spacing: 1px; }
  .status .value { color: ${if (loaded) "#00E6A8" else "#FFBE0B"}; font-size: 14px; font-weight: 600; margin-top: 4px; }
  .chat-area { flex: 1; display: flex; flex-direction: column; background: #12121E; border-radius: 12px; overflow: hidden; }
  .messages { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 8px; }
  .msg { padding: 10px 14px; border-radius: 14px; max-width: 80%; font-size: 14px; line-height: 1.5; }
  .msg.user { background: #2D2B55; align-self: flex-end; border-bottom-right-radius: 4px; }
  .msg.assistant { background: #1A1A2E; align-self: flex-start; border-bottom-left-radius: 4px; }
  .input-area { display: flex; gap: 8px; padding: 12px; background: #1A1A2E; }
  .input-area input { flex: 1; background: #12121E; border: 1px solid #2A2A45; border-radius: 24px; padding: 10px 16px; color: #EEEEF5; font-size: 14px; outline: none; }
  .input-area input:focus { border-color: #7C73FF; }
  .input-area button { background: #7C73FF; border: none; border-radius: 24px; padding: 10px 20px; color: white; font-weight: 600; cursor: pointer; font-size: 14px; }
  .input-area button:hover { background: #6A62E0; }
  .input-area button:disabled { opacity: 0.5; cursor: not-allowed; }
  .info { margin-top: 12px; display: flex; gap: 8px; flex-wrap: wrap; }
  .badge { background: #20203A; border-radius: 8px; padding: 6px 12px; font-size: 11px; color: #9A9AB0; font-family: monospace; }
  .badge a { color: #7C73FF; text-decoration: none; }
  .badge a:hover { text-decoration: underline; }
  footer { text-align: center; padding: 16px; color: #5C5C78; font-size: 11px; }
</style>
</head>
<body>
<header>
  <h1>ZeroCopy AI</h1>
  <p>by adeennour4-dot — Local On-Device Inference Server</p>
</header>
<div class="container">
  <div class="status">
    <div class="label">Model Status</div>
    <div class="value">${if (loaded) "Loaded: $modelName" else "No model loaded — select one on your phone"}</div>
  </div>
  <div class="chat-area">
    <div class="messages" id="messages"></div>
    <div class="input-area">
      <input type="text" id="prompt" placeholder="Type a message..." onkeydown="if(event.key==='Enter') send()">
      <button id="sendBtn" onclick="send()" ${if (!loaded) "disabled" else ""}>Send</button>
    </div>
  </div>
  <div class="info">
    <div class="badge">API: <a href="/v1/models">/v1/models</a></div>
    <div class="badge">POST <a href="#">/v1/chat/completions</a></div>
    <div class="badge"><a href="/health">/health</a></div>
  </div>
</div>
<footer>ZeroCopy v1.0.0 — adeennour4-dot/111</footer>
<script>
const msgs = document.getElementById('messages');
const prompt = document.getElementById('prompt');
const btn = document.getElementById('sendBtn');
let inferring = false;

function addMsg(role, content) {
  const div = document.createElement('div');
  div.className = 'msg ' + role;
  div.textContent = content;
  msgs.appendChild(div);
  msgs.scrollTop = msgs.scrollHeight;
}

async function send() {
  const text = prompt.value.trim();
  if (!text || inferring) return;
  prompt.value = '';
  addMsg('user', text);
  inferring = true;
  btn.disabled = true;
  btn.textContent = '...';

  const msgDiv = document.createElement('div');
  msgDiv.className = 'msg assistant';
  msgs.appendChild(msgDiv);
  msgs.scrollTop = msgs.scrollHeight;

  try {
    const resp = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: text, stream: false })
    });
    const data = await resp.json();
    const content = data.choices?.[0]?.message?.content || data.response || JSON.stringify(data);
    msgDiv.textContent = content;
  } catch(e) {
    msgDiv.textContent = 'Error: ' + e.message;
  }
  inferring = false;
  btn.disabled = false;
  btn.textContent = 'Send';
}
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
      // OpenAI format
      val msgs = json.optJSONArray("messages")
      if (msgs != null && msgs.length() > 0) {
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
      200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
      else -> "Unknown"
    }
    val response = "HTTP/1.1 $code $status\r\n" +
      "Content-Type: $contentType\r\n" +
      "Content-Length: ${body.toByteArray().size}\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
      "Access-Control-Allow-Headers: Content-Type\r\n" +
      "Connection: close\r\n" +
      "\r\n" +
      body
    try { out.write(response.toByteArray()); out.flush() } catch (_: Exception) {}
  }
}
