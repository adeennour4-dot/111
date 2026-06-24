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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ModelServer(val port: Int = 8080) {
  private val tag = "ModelServer"
  private var serverSocket: ServerSocket? = null
  private var executor: ExecutorService? = null
  private val running = AtomicBoolean(false)
  private val serverBound = AtomicBoolean(false)
  private var serverStartTime = 0L
  private var autoModelPath: String = ""
  private var autoModelName: String = ""
  private val rateLimiter = TokenBucket(capacity = 60, refillPerSec = 2)
  private val authFailTracker = ConcurrentHashMap<String, AuthFailEntry>()
  private val clientExecutor = Executors.newCachedThreadPool()

  @Volatile
  var notificationSetter: ((String) -> Unit)? = null

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
    executor?.submit {
      val app = ZeroCopyApp.instance
      var engine = app.engineManager.getActiveEngine()
      if (engine == null && autoModelPath.isNotEmpty()) {
        engine = app.engineManager.selectEngineForFormat(autoModelPath)
      }
      val modelName = if (engine?.isModelLoaded != true && autoModelPath.isNotEmpty()) {
        val modelInfo = app.modelRepository.models.value.find { it.path == autoModelPath }
        if (modelInfo != null) {
          engine?.let { e ->
            e.config = com.gguf.zerocopy.data.local.SettingsManager.toConfig()
            e.systemPrompt = com.gguf.zerocopy.data.local.SettingsManager.systemPrompt
            e.repeatPenalty = com.gguf.zerocopy.data.local.SettingsManager.toRepeatPenalty()
            val result = runBlocking { e.loadModel(modelInfo.path) }
            if (result.isSuccess) {
              app.modelRepository.markUsed(modelInfo.id)
              Log.i(tag, "Auto-loaded model: ${modelInfo.name}")
              modelInfo.name
            } else {
              Log.w(tag, "Failed to auto-load model: ${result.exceptionOrNull()?.message}")
              null
            }
          }
        } else {
          Log.w(tag, "Auto-model not found in repository: $autoModelPath")
          null
        }
      } else {
        engine?.loadedModelPath?.substringAfterLast('/') ?: autoModelName.ifEmpty { "Unknown" }
      }
      val ip = getLocalIp()
      var waited = 0
      while (!serverBound.get() && waited < 5000) {
        Thread.sleep(100); waited += 100
      }
      notificationSetter?.invoke("Running on http://$ip:$port - Model: ${modelName ?: "Not loaded"}")
    }
    Log.i(tag, "Server starting on port $port")
  }

  fun stop() {
    running.set(false)
    serverBound.set(false)
    try { serverSocket?.close() } catch (_: Exception) {}
    try { executor?.shutdownNow() } catch (_: Exception) {}
    try { clientExecutor?.shutdownNow() } catch (_: Exception) {}
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
      serverBound.set(true)
      Log.i(tag, "Server listening on 0.0.0.0:$port")
      while (running.get()) {
        try {
          val client = serverSocket?.accept() ?: break
          clientExecutor?.submit { handleClient(client) }
        } catch (_: Exception) {
          if (!running.get()) break
          Thread.sleep(100)
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Server error: ${e.message}")
      running.set(false)
      serverBound.set(false)
    }
  }

  private fun authFailKey(ip: String): String = ip

  private fun isBanned(ip: String): Boolean {
    val entry = authFailTracker[authFailKey(ip)] ?: return false
    if (entry.banUntil > System.currentTimeMillis()) return true
    if (entry.banUntil > 0 && System.currentTimeMillis() >= entry.banUntil) {
      authFailTracker.remove(authFailKey(ip))
    }
    return false
  }

  private fun recordAuthFail(ip: String) {
    val key = authFailKey(ip)
    synchronized(authFailTracker) {
      val entry = authFailTracker.getOrPut(key) { AuthFailEntry() }
      entry.failCount++
      if (entry.failCount >= 3) {
        entry.banUntil = System.currentTimeMillis() + 3_600_000
        Log.w(tag, "Banned IP $ip for 1 hour (3 auth failures)")
      }
    }
  }

  // SECURITY NOTE: Auth token is stored in plain text SharedPreferences.
  // For production use, consider encrypting with EncryptedSharedPreferences.
  private fun checkAuth(headers: Map<String, String>): Boolean {
    if (!com.gguf.zerocopy.data.local.SettingsManager.serverAuthEnabled) return true
    val expected = com.gguf.zerocopy.data.local.SettingsManager.serverAuthToken
    if (expected.isBlank()) return true
    val auth = headers["authorization"] ?: return false
    if (!auth.startsWith("Bearer ")) return false
    val token = auth.substring(7)
    return MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())
  }

  private fun checkRateLimit(ip: String): Boolean = rateLimiter.tryConsume(ip)

  private fun handleClient(client: Socket) {
    val ip = client.inetAddress?.hostAddress ?: "unknown"
    val startTime = System.currentTimeMillis()
    try {
      client.use { socket ->
        socket.soTimeout = 60000
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
          var total = 0
          while (total < contentLength) {
            val read = reader.read(chars, total, contentLength - total)
            if (read < 0) break
            total += read
          }
          String(chars, 0, total)
        } else ""

        val out = socket.getOutputStream()

        if (method == "OPTIONS") {
          respond(out, 204, "")
          return
        }

        if (isBanned(ip)) {
          respond(out, 429, """{"error":"rate_limit_exceeded","message":"Too many requests"}""")
          emitAudit(ip, method, path, 429, startTime)
          return
        }

        if (path.startsWith("/v1/") && !checkAuth(headers)) {
          recordAuthFail(ip)
          respond(out, 401, """{"error":"authentication_error","message":"Invalid API key. Use Authorization: Bearer <token>","type":"invalid_request_error"}""")
          emitAudit(ip, method, path, 401, startTime)
          return
        }

        if (path.startsWith("/v1/") && !checkRateLimit(ip)) {
          respond(out, 429, """{"error":"rate_limit_exceeded","message":"Rate limit exceeded. Try again later.","type":"rate_limit_error"}""")
          emitAudit(ip, method, path, 429, startTime)
          return
        }

when {
  path == "/" || path == "" -> handleIndex(out)
  path == "/health" -> handleHealth(out)
  path == "/v1/models" -> handleModels(out)
  path == "/v1/chat/completions" && method == "POST" -> handleChatCompletions(out, body, ip)
  path.startsWith("/v1/") -> respond(out, 404, """{"error":"not_found","message":"Endpoint not found","type":"invalid_request_error"}""")
  else -> respond(out, 404, """{"error":"not_found","message":"Not found"}""")
}
        emitAudit(ip, method, path, 200, startTime)
      }
    } catch (e: Exception) {
      Log.w(tag, "Client error from $ip: ${e.message}")
      emitAudit(ip, "ERR", "error", 500, startTime)
    }
  }

  private fun emitAudit(ip: String, method: String, path: String, status: Int, start: Long) {
    val elapsed = System.currentTimeMillis() - start
    if (elapsed > 100) {
      Log.i(tag, "[${method}] $path -> $status (${elapsed}ms) from $ip")
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
<title>ZeroCopy AI Inference Server</title>
<style>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600;800&family=JetBrains+Mono:wght@400;600&display=swap');
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Inter',system-ui,sans-serif;background:#06060E;color:#E8E8F0;min-height:100vh;display:flex;flex-direction:column;overflow-x:hidden}
::selection{background:#7C73FF44;color:#fff}
.glow-bg{position:fixed;top:-50%;left:-50%;width:200%;height:200%;pointer-events:none;z-index:0}
.glow-bg .orb1{position:absolute;top:15%;left:25%;width:700px;height:700px;background:radial-gradient(circle,rgba(124,115,255,0.08) 0%,transparent 70%);animation:orbPulse 7s ease-in-out infinite}
.glow-bg .orb2{position:absolute;bottom:5%;right:15%;width:600px;height:600px;background:radial-gradient(circle,rgba(0,230,168,0.06) 0%,transparent 70%);animation:orbPulse 9s ease-in-out infinite reverse}
.glow-bg .orb3{position:absolute;top:40%;left:60%;width:400px;height:400px;background:radial-gradient(circle,rgba(255,190,11,0.04) 0%,transparent 70%);animation:orbPulse 11s ease-in-out infinite 2s}
@keyframes orbPulse{0%,100%{transform:scale(1);opacity:.6}50%{transform:scale(1.3);opacity:1}}
@keyframes float{0%,100%{transform:translateY(0)}50%{transform:translateY(-8px)}}
header{position:relative;z-index:1;padding:28px 20px 18px;text-align:center;background:linear-gradient(180deg,rgba(124,115,255,0.12) 0%,transparent 100%);border-bottom:1px solid rgba(124,115,255,0.08)}
header h1{font-size:28px;font-weight:800;letter-spacing:4px;background:linear-gradient(135deg,#7C73FF,#00E6A8 50%,#FFBE0B);-webkit-background-clip:text;-webkit-text-fill-color:transparent;text-shadow:none;animation:float 4s ease-in-out infinite}
header p{font-size:11px;color:#6A6A8A;margin-top:6px;letter-spacing:1.5px;font-weight:300}
header .version-badge{display:inline-block;background:rgba(124,115,255,0.12);border:1px solid rgba(124,115,255,0.15);border-radius:20px;padding:3px 12px;font-size:9px;color:#7C73FF;margin-top:6px;letter-spacing:1px}
.container{position:relative;z-index:1;max-width:880px;margin:0 auto;padding:16px;flex:1;display:flex;flex-direction:column;gap:10px;width:100%}
.card{background:linear-gradient(135deg,rgba(26,26,46,0.92) 0%,rgba(18,18,30,0.96) 100%);border-radius:16px;padding:18px;border:1px solid rgba(124,115,255,0.08);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);transition:border-color .3s,box-shadow .3s}
.card:hover{border-color:rgba(124,115,255,0.2);box-shadow:0 0 40px rgba(124,115,255,0.04)}
.card h3{font-size:10px;color:#6A6A8A;text-transform:uppercase;letter-spacing:1.5px;margin-bottom:12px;font-weight:600}
.stat-row{display:flex;gap:10px;flex-wrap:wrap}
.stat{background:rgba(32,32,58,0.6);border-radius:12px;padding:12px 16px;border:1px solid rgba(124,115,255,0.06);min-width:110px;flex:1;transition:background .3s}
.stat:hover{background:rgba(40,40,68,0.7)}
.stat .label{color:#5C5C78;font-size:9px;text-transform:uppercase;letter-spacing:.5px;margin-bottom:2px}
.stat .val{color:#E8E8F0;font-size:14px;font-weight:600;font-family:'JetBrains Mono',monospace}
.val-small{font-size:10px!important;color:#8A8AAA!important;word-break:break-all}
.status-dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;vertical-align:middle}
.status-dot.on{background:#00E6A8;box-shadow:0 0 12px rgba(0,230,168,0.6);animation:glow-dot 2s ease-in-out infinite}
.status-dot.off{background:#FFBE0B;box-shadow:0 0 12px rgba(255,190,11,0.3)}
.status-dot.error{background:#FF5E5E;box-shadow:0 0 12px rgba(255,94,94,0.3)}
@keyframes glow-dot{0%,100%{opacity:1}50%{opacity:.4}}
.section-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
@media(max-width:640px){.section-grid{grid-template-columns:1fr}}
.endpoint{background:rgba(18,18,30,0.6);border-radius:8px;padding:8px 12px;margin-bottom:4px;font-family:'JetBrains Mono',monospace;font-size:11px;border:1px solid rgba(124,115,255,0.04);display:flex;align-items:center;gap:8px;transition:background .2s}
.endpoint:hover{background:rgba(32,32,58,0.8)}
.endpoint .method{display:inline-block;padding:2px 8px;border-radius:4px;font-weight:700;font-size:9px;letter-spacing:.5px}
.method.get{background:rgba(0,85,170,0.3);color:#5BB8FF;box-shadow:0 0 12px rgba(0,85,170,0.15)}
.method.post{background:rgba(0,170,85,0.3);color:#00E6A8;box-shadow:0 0 12px rgba(0,170,85,0.15)}
.method.del{background:rgba(170,0,0,0.3);color:#FF5E5E;box-shadow:0 0 12px rgba(170,0,0,0.15)}
.endpoint .desc{color:#6A6A8A;font-size:10px;font-family:'Inter',sans-serif;margin-left:auto}
.endpoint .path{color:#E8E8F0}
code{background:rgba(124,115,255,0.1);padding:2px 8px;border-radius:6px;font-size:11px;color:#BB86FC;font-family:'JetBrains Mono',monospace;border:1px solid rgba(124,115,255,0.08)}
.chat-area{flex:1;display:flex;flex-direction:column;background:linear-gradient(180deg,rgba(18,18,30,0.95) 0%,rgba(10,10,20,0.98) 100%);border-radius:16px;overflow:hidden;min-height:300px;border:1px solid rgba(124,115,255,0.08)}
.messages{flex:1;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:8px;scroll-behavior:smooth}
.messages::-webkit-scrollbar{width:4px}
.messages::-webkit-scrollbar-track{background:transparent}
.messages::-webkit-scrollbar-thumb{background:rgba(124,115,255,0.2);border-radius:4px}
.messages::-webkit-scrollbar-thumb:hover{background:rgba(124,115,255,0.4)}
.msg{padding:10px 16px;border-radius:16px;max-width:85%;font-size:13px;line-height:1.65;animation:fadeIn .3s ease}
@keyframes fadeIn{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
.msg.user{background:linear-gradient(135deg,rgba(124,115,255,0.2) 0%,rgba(124,115,255,0.1) 100%);align-self:flex-end;border-bottom-right-radius:4px;border:1px solid rgba(124,115,255,0.15);box-shadow:0 0 20px rgba(124,115,255,0.05)}
.msg.assistant{background:rgba(32,32,58,0.6);align-self:flex-start;border-bottom-left-radius:4px;border:1px solid rgba(124,115,255,0.06)}
.msg.system{background:rgba(255,190,11,0.08);align-self:center;border:1px solid rgba(255,190,11,0.1);font-size:11px;color:#FFBE0B;max-width:65%;text-align:center;padding:8px 14px}
.msg.assistant p{margin:4px 0}
.msg.assistant code{background:rgba(0,0,0,0.3);padding:1px 5px;border-radius:4px;font-size:12px;color:#BB86FC}
.msg.assistant pre{background:rgba(0,0,0,0.4);padding:12px;border-radius:10px;margin:8px 0;overflow-x:auto;border:1px solid rgba(124,115,255,0.06)}
.msg.assistant pre code{background:none;padding:0;border:none;font-size:12px;color:#C8C8E0}
.msg.assistant ul,.msg.assistant ol{margin:6px 0;padding-left:20px}
.msg.assistant li{margin:2px 0}
.msg.assistant h1,.msg.assistant h2,.msg.assistant h3,.msg.assistant h4{margin:10px 0 4px;color:#E8E8F0}
.msg.assistant blockquote{border-left:2px solid rgba(124,115,255,0.3);padding-left:10px;margin:6px 0;color:#8A8AAA}
.msg.assistant table{border-collapse:collapse;margin:8px 0;font-size:12px;width:100%}
.msg.assistant th,.msg.assistant td{border:1px solid rgba(124,115,255,0.1);padding:6px 10px;text-align:left}
.msg.assistant th{background:rgba(124,115,255,0.08)}
.msg.assistant a{color:#7C73FF;text-decoration:underline}
.input-area{display:flex;gap:8px;padding:12px 16px;background:rgba(26,26,46,0.85);border-top:1px solid rgba(124,115,255,0.08);backdrop-filter:blur(8px)}
.input-area input{flex:1;background:rgba(18,18,30,0.8);border:1px solid rgba(124,115,255,0.12);border-radius:24px;padding:10px 18px;color:#E8E8F0;font-size:13px;outline:none;font-family:'Inter',sans-serif;transition:all .3s}
.input-area input:focus{border-color:#7C73FF;box-shadow:0 0 20px rgba(124,115,255,0.1)}
.input-area input::placeholder{color:#4A4A6A}
.input-area button{background:linear-gradient(135deg,#7C73FF,#6A62E0);border:none;border-radius:24px;padding:10px 24px;color:white;font-weight:600;cursor:pointer;font-size:13px;transition:all .3s;font-family:'Inter',sans-serif;box-shadow:0 0 20px rgba(124,115,255,0.2);white-space:nowrap}
.input-area button:hover{transform:translateY(-1px);box-shadow:0 0 30px rgba(124,115,255,0.35)}
.input-area button:active{transform:translateY(0)}
.input-area button:disabled{opacity:.4;cursor:not-allowed;transform:none;box-shadow:none}
.input-area .stop-btn{background:linear-gradient(135deg,#FF5E5E,#CC4444);box-shadow:0 0 20px rgba(255,94,94,0.2)}
.input-area .stop-btn:hover{box-shadow:0 0 30px rgba(255,94,94,0.35)}
.badges{display:flex;gap:6px;flex-wrap:wrap;margin-top:2px}
.badge{background:rgba(32,32,58,0.5);border-radius:10px;padding:5px 14px;font-size:10px;color:#6A6A8A;font-family:'JetBrains Mono',monospace;border:1px solid rgba(124,115,255,0.04);transition:all .2s}
.badge:hover{background:rgba(40,40,68,0.6);border-color:rgba(124,115,255,0.1)}
.badge a{color:#7C73FF;text-decoration:none}
.badge a:hover{text-decoration:underline}
footer{position:relative;z-index:1;text-align:center;padding:14px;color:#4A4A6A;font-size:10px;letter-spacing:.5px;border-top:1px solid rgba(124,115,255,0.05)}
.typing-dots{display:inline-flex;gap:4px;align-items:center;padding:6px 0}
.typing-dots span{width:6px;height:6px;border-radius:50%;background:#7C73FF;animation:typing 1.4s ease-in-out infinite;box-shadow:0 0 6px rgba(124,115,255,0.3)}
.typing-dots span:nth-child(2){animation-delay:.2s}
.typing-dots span:nth-child(3){animation-delay:.4s}
@keyframes typing{0%,60%,100%{opacity:.3;transform:scale(.8)}30%{opacity:1;transform:scale(1.2)}}
.example-box{background:rgba(18,18,30,0.6);border-radius:10px;padding:12px 14px;font-size:10px;color:#5C5C78;font-family:'JetBrains Mono',monospace;line-height:1.9;border:1px solid rgba(124,115,255,0.04);overflow-x:auto;margin-top:8px}
.example-box .comment{color:#4A4A6A}
.example-box .keyword{color:#BB86FC}
.example-box .string{color:#00E6A8}
.example-box .method-highlight{color:#FFBE0B}
.example-box strong{color:#8A8AAA}
.toolbar{display:flex;gap:6px;padding:8px 14px;background:rgba(26,26,46,0.5);border-bottom:1px solid rgba(124,115,255,0.04)}
.toolbar-btn{background:transparent;border:none;color:#6A6A8A;padding:4px 10px;border-radius:6px;font-size:10px;cursor:pointer;font-family:'Inter',sans-serif;transition:all .2s}
.toolbar-btn:hover{background:rgba(124,115,255,0.1);color:#E8E8F0}
.toolbar-btn.active{background:rgba(124,115,255,0.15);color:#7C73FF}
.model-select{background:rgba(18,18,30,0.8);border:1px solid rgba(124,115,255,0.1);border-radius:6px;padding:3px 8px;color:#E8E8F0;font-size:10px;font-family:'JetBrains Mono',monospace;outline:none;margin-left:auto}
.model-select option{background:#1A1A2E}
.thinking{display:none;margin:4px 0;padding:8px 12px;background:rgba(124,115,255,0.05);border-radius:8px;border-left:2px solid rgba(124,115,255,0.2);font-size:11px;color:#8A8AAA;font-style:italic;animation:fadeIn .3s ease}
.thinking.show{display:block}
.copy-btn{position:absolute;top:6px;right:6px;background:rgba(124,115,255,0.1);border:none;color:#6A6A8A;padding:2px 8px;border-radius:4px;font-size:8px;cursor:pointer;font-family:'Inter',sans-serif;opacity:0;transition:opacity .2s}
pre:hover .copy-btn{opacity:1}
.copy-btn:hover{background:rgba(124,115,255,0.2);color:#E8E8F0}
.collapse-btn{background:none;border:none;color:#6A6A8A;cursor:pointer;font-size:10px;padding:2px 6px;border-radius:4px;transition:all .2s}
.collapse-btn:hover{background:rgba(124,115,255,0.1);color:#E8E8F0}
</style>
</head>
<body>
<div class="glow-bg">
<div class="orb1"></div>
<div class="orb2"></div>
<div class="orb3"></div>
</div>
<header>
<h1>ZEROCOPY AI</h1>
<p>ADEENNOUR4-DOT &bull; LOCAL INFERENCE SERVER &bull; OPENAI-COMPATIBLE</p>
<div class="version-badge">v1.3 &bull; ${getServerUrl()}</div>
</header>
<div class="container">
<div class="card">
<h3>Server Status</h3>
<div class="stat-row">
<div class="stat"><div class="label">Model</div><div class="val"><span class="status-dot ${if (loaded) "on" else "off"}"></span>${if (loaded) modelName else "Not loaded"}</div></div>
<div class="stat"><div class="label">Uptime</div><div class="val">${uptime / 3600}h ${(uptime % 3600) / 60}m ${uptime % 60}s</div></div>
<div class="stat"><div class="label">Auth</div><div class="val"><span class="status-dot ${if (authEnabled) "on" else "off"}"></span>${if (authEnabled) "Enabled" else "Disabled"}</div></div>
<div class="stat"><div class="label">Endpoint</div><div class="val val-small">${getServerUrl()}</div></div>
</div>
</div>

<div class="section-grid">
<div class="card">
<h3>API Reference</h3>
<div class="endpoint"><span class="method get">GET</span><span class="path">/</span><span class="desc">Web UI</span></div>
<div class="endpoint"><span class="method get">GET</span><span class="path"><a href="/health" style="color:#E8E8F0;text-decoration:none">/health</a></span><span class="desc">Health check</span></div>
<div class="endpoint"><span class="method get">GET</span><span class="path"><a href="/v1/models" style="color:#E8E8F0;text-decoration:none">/v1/models</a></span><span class="desc">List models</span></div>
<div class="endpoint"><span class="method post">POST</span><span class="path">/v1/chat/completions</span><span class="desc">Chat completions</span></div>
</div>

<div class="card">
<h3>Quick Start</h3>
<div class="example-box">
<strong class="comment"># List models</strong><br>
<span class="method-highlight">curl</span> ${getServerUrl()}/models<br><br>
<strong class="comment"># Chat completion (streaming)</strong><br>
<span class="method-highlight">curl</span> ${getServerUrl()}/chat/completions \<br>
${authHeader}  -H <span class="string">"Content-Type: application/json"</span> \<br>
  -d <span class="string">'{<br>
&nbsp;&nbsp;"model": "${if (loaded) modelName else "model"}",<br>
&nbsp;&nbsp;"messages": [{"role": "user", "content": "Hello!"}],<br>
&nbsp;&nbsp;"stream": true<br>
}'</span><br><br>

</div>
</div>
</div>

<div class="chat-area">
<div class="toolbar">
<span style="color:#6A6A8A;font-size:10px;font-weight:600;letter-spacing:.5px">CHAT</span>
<button class="toolbar-btn" onclick="clearChat()">Clear</button>
<button class="toolbar-btn" onclick="toggleThinking()">Show Thinking</button>
</div>
<div class="messages" id="messages"></div>
<div class="input-area">
<input type="text" id="prompt" placeholder="Type a message..." autofocus>
<button id="sendBtn" onclick="send()" ${if (!loaded) "disabled" else ""}>&#x27A4;</button>
</div>
</div>

<div class="badges">
<div class="badge"><a href="/">/</a></div>
<div class="badge"><a href="/health">/health</a></div>
<div class="badge"><a href="/v1/models">/v1/models</a></div>
<div class="badge">POST /v1/chat/completions</div>

<div class="badge" style="color:#7C73FF">${getServerUrl()}</div>
</div>
</div>

<footer>ZeroCopy AI Server v1.3 &mdash; Built with &lt;3 by adeennour4-dot</footer>

<script>
const msgs=document.getElementById('messages');
const prompt=document.getElementById('prompt');
const btn=document.getElementById('sendBtn');
const history=[];
let inferring=false;
let showThinking=true;
let abortController=null;

prompt.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}});

function esc(t){const d=document.createElement('div');d.textContent=t;return d.innerHTML}

function md(t){
  let r=t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  r=r.replace(/```(\w*)\n?([\s\S]*?)```/g,'<pre><code class="lang-$1">$2</code></pre>');
  r=r.replace(/`([^`]+)`/g,'<code>$1</code>');
  r=r.replace(/\*\*\*([^*]+)\*\*\*/g,'<i><b>$1</b></i>');
  r=r.replace(/\*\*([^*]+)\*\*/g,'<b>$1</b>');
  r=r.replace(/\*([^*]+)\*/g,'<i>$1</i>');
  r=r.replace(/~~([^~]+)~~/g,'<s>$1</s>');
  r=r.replace(/^(#{1,6})\s+(.+)$/gm,function(m,h,t){return '<h'+h.length+'>'+t+'</h'+h.length+'>'});
  r=r.replace(/^> (.+)$/gm,'<blockquote>$1</blockquote>');
  r=r.replace(/^- (.+)$/gm,'<li>$1</li>');
  r=r.replace(/^\d+\.\s+(.+)$/gm,'<li>$1</li>');
  r=r.replace(/\n/g,'<br>');
  return r;
}

function addMsg(role,content){
  const d=document.createElement('div');d.className='msg '+role;
  if(role==='assistant'){d.innerHTML=md(content)}
  else{d.textContent=content}
  msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight;
}

const typingDots=()=>{
  const d=document.createElement('div');d.className='msg assistant';
  d.innerHTML='<div class="typing-dots"><span></span><span></span><span></span></div>';
  return d;
};

function clearChat(){
  msgs.innerHTML='';
  history.length=0;
  addMsg('system','Chat cleared');
}

function toggleThinking(){showThinking=!showThinking;}

async function send(){
  const text=prompt.value.trim();
  if(!text||inferring)return;
  prompt.value='';
  history.push({role:'user',content:text});
  addMsg('user',text);
  inferring=true;
  btn.disabled=true;
  btn.innerHTML='&#9632;';
  btn.className='stop-btn';
  const td=typingDots();msgs.appendChild(td);msgs.scrollTop=msgs.scrollHeight;
  let full='';
  let thinking='';
  let inThinking=false;
  abortController=new AbortController();
  try{
    const r=await fetch('/v1/chat/completions',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({messages:history,stream:true}),
      signal:abortController.signal
    });
    if(!r.ok){
      const e=await r.json();
      td.remove();
      addMsg('system','Error: '+(e.message||e.error||r.status));
      history.pop();
      return;
    }
    td.remove();
    const mdDiv=document.createElement('div');
    mdDiv.className='msg assistant';
    msgs.appendChild(mdDiv);
    const reader=r.body.getReader();
    const dec=new TextDecoder();
    let buf='';
    while(true){
      const{done,value}=await reader.read();
      if(done)break;
      buf+=dec.decode(value,{stream:true});
      const lines=buf.split('\n');
      buf=lines.pop()||'';
      for(const line of lines){
        if(line.startsWith('data: ')){
          const data=line.slice(6);
          if(data==='[DONE]')continue;
          try{
            const j=JSON.parse(data);
            const c=j.choices?.[0]?.delta?.content;
            if(c){
              full+=c;
              // rough thinking detection
              if(c.includes('\\boxed')||c.includes('\\text')||(full.startsWith('Let me')||full.startsWith('I need'))){
                inThinking=true;
              }
              if(inThinking&&full.length<500){thinking+=c}
              mdDiv.innerHTML=md(full);
            }
          }catch(e){}
        }
      }
      msgs.scrollTop=msgs.scrollHeight;
    }
    history.push({role:'assistant',content:full});
  }catch(e){
    if(e.name==='AbortError')return;
    td.remove();
    addMsg('system','Error: '+e.message);
    history.pop();
  }
  inferring=false;
  btn.disabled=false;
  btn.innerHTML='&#x27A4;';
  btn.className='';
  abortController=null;
}
</script>
</body>
</html>"""
    respond(out, 200, html, "text/html; charset=utf-8")
  }

  private fun handleHealth(out: OutputStream) {
    val app = ZeroCopyApp.instance
    val engine = app.engineManager.getActiveEngine()
    respond(out, 200, """{"status":"ok","model_loaded":${engine?.isModelLoaded == true},"version":"1.3"}""")
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

  private fun handleChatCompletions(out: OutputStream, body: String, ip: String) {
    try {
      val app = ZeroCopyApp.instance
      val engine = app.engineManager.getActiveEngine()
      if (engine?.isModelLoaded != true) {
        respond(out, 503, """{"error":"model_unavailable","message":"No model loaded","type":"server_error"}""")
        return
      }
      val json = org.json.JSONObject(body)
      val messages = json.optJSONArray("messages")
      val prompt = if (messages != null && messages.length() > 0) {
        buildPrompt(messages)
      } else {
        json.optString("prompt", json.optString("input", ""))
      }
      if (prompt.isNullOrBlank()) {
        respond(out, 400, """{"error":"invalid_request","message":"messages or prompt is required","type":"invalid_request_error"}""")
        return
      }
      val stream = json.optBoolean("stream", false)

      val inferenceId = "chat_${System.currentTimeMillis()}"
      val modelName = engine.loadedModelPath?.substringAfterLast('/') ?: "unknown"

      if (stream) {
        streamResponse(out, inferenceId, modelName, engine, prompt)
      } else {
        syncResponse(out, inferenceId, modelName, engine, prompt)
      }
    } catch (e: Exception) {
      respond(out, 500, """{"error":"server_error","message":"${e.message?.replace("\"","'") ?: "Internal error"}","type":"internal_error"}""")
    }
  }

  private fun streamResponse(out: OutputStream, id: String, model: String, engine: com.gguf.zerocopy.domain.inference.InferenceEngine, prompt: String) {
    val headers = "HTTP/1.1 200 OK\r\n" +
      "Content-Type: text/event-stream\r\n" +
      "Cache-Control: no-cache\r\n" +
      "Connection: keep-alive\r\n" +
      "Access-Control-Allow-Origin: *\r\n" +
      "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
      "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
      "\r\n"
    try {
      out.write(headers.toByteArray())
      out.flush()

      val done = AtomicBoolean(false)
      val errorRef = arrayOfNulls<String>(1)
      val fullText = StringBuilder()

      runBlocking {
        engine.executeInference(prompt, object : TokenCallback {
          override fun onToken(token: String) {
            fullText.append(token)
            val escaped = token
              .replace("\\", "\\\\")
              .replace("\"", "\\\"")
              .replace("\n", "\\n")
              .replace("\r", "\\r")
              .replace("\t", "\\t")
            val chunk = """{"id":"$id","object":"chat.completion.chunk","created":${System.currentTimeMillis()/1000},"model":"$model","choices":[{"index":0,"delta":{"content":"$escaped"},"finish_reason":null}]}"""
            try {
              out.write("data: $chunk\n\n".toByteArray())
              out.flush()
            } catch (_: Exception) {}
          }
          override fun onDone() { done.set(true) }
          override fun onError(error: String) { errorRef[0] = error; done.set(true) }
          override fun onKvUsage(percent: Int) {}
          override fun onTokensGenerated(count: Int) {}
        })
      }

      var waited = 0
      while (!done.get() && waited < 120_000) {
        Thread.sleep(50)
        waited += 50
      }

      val finish = """{"id":"$id","object":"chat.completion.chunk","created":${System.currentTimeMillis()/1000},"model":"$model","choices":[{"index":0,"delta":{},"finish_reason":"${if (errorRef[0] != null) "error" else "stop"}"}]}"""
      out.write("data: $finish\n\n".toByteArray())
      out.write("data: [DONE]\n\n".toByteArray())
      out.flush()
    } catch (_: Exception) {}
  }

  private fun syncResponse(out: OutputStream, id: String, model: String, engine: com.gguf.zerocopy.domain.inference.InferenceEngine, prompt: String) {
    val resultBuilder = StringBuilder()
    val done = AtomicBoolean(false)
    var errorMsg: String? = null
    val tokCount = AtomicLong(0)

    runBlocking {
      engine.executeInference(prompt, object : TokenCallback {
        override fun onToken(token: String) { resultBuilder.append(token) }
        override fun onDone() { done.set(true) }
        override fun onError(error: String) { errorMsg = error; done.set(true) }
        override fun onKvUsage(percent: Int) {}
        override fun onTokensGenerated(count: Int) { tokCount.set(count.toLong()) }
      })
    }

    var waited = 0
    while (!done.get() && waited < 120_000) {
      Thread.sleep(100)
      waited += 100
    }

    if (errorMsg != null) {
      respond(out, 500, """{"error":"inference_error","message":"${errorMsg?.replace("\"","'") ?: "Inference failed"}","type":"server_error"}""")
      return
    }

    val response = resultBuilder.toString()
    val json = """{"id":"$id","object":"chat.completion","created":${System.currentTimeMillis()/1000},"model":"$model","choices":[{"index":0,"message":{"role":"assistant","content":${jsonEncode(response)}},"finish_reason":"stop"}],"usage":{"total_tokens":${tokCount.get()}}}"""
    respond(out, 200, json)
  }

  private fun buildPrompt(messages: org.json.JSONArray): String {
    val sb = StringBuilder()
    for (i in 0 until messages.length()) {
      val msg = messages.getJSONObject(i)
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
    return messages.getJSONObject(messages.length() - 1).optString("content", "")
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
      200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
      404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
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

  private class TokenBucket(val capacity: Int, val refillPerSec: Int) {
    private val buckets = ConcurrentHashMap<String, BucketState>()

    fun tryConsume(key: String): Boolean {
      val now = System.nanoTime()
      val state = buckets.computeIfAbsent(key) { BucketState(capacity.toDouble(), now) }
      synchronized(state) {
        val elapsed = (now - state.lastRefill) / 1_000_000_000.0
        state.tokens = (state.tokens + elapsed * refillPerSec).coerceAtMost(capacity.toDouble())
        state.lastRefill = now
        if (state.tokens >= 1) {
          state.tokens -= 1
          return true
        }
        return false
      }
    }

    private class BucketState(var tokens: Double, var lastRefill: Long)
  }

  private class AuthFailEntry(
    @Volatile var failCount: Int = 0,
    @Volatile var banUntil: Long = 0
  )
}
