package com.gguf.zerocopy.domain.inference

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class ToolDefinition(val name: String, val description: String, val parameters: JSONObject)
data class ToolCall(val id: String, val name: String, val arguments: JSONObject)
data class ToolResult(val callId: String, val name: String, val result: String)

class ToolManager {
  private data class ToolEntry(val definition: ToolDefinition, val executor: (JSONObject) -> String)
  private val tools = mutableMapOf<String, ToolEntry>()

  init { registerBuiltinTools() }

  private fun registerBuiltinTools() {
    register("get_current_time", "Get the current date and time", JSONObject()) { getCurrentTime() }
    register("get_current_date", "Get the current date", JSONObject()) { getCurrentDate() }
    register(
      "web_search",
      "Search the web for current information. Use this for news, facts, prices, weather, or anything that may have changed recently.",
      JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
          put("query", JSONObject().apply {
            put("type", "string")
            put("description", "The search query string")
          })
          put("num_results", JSONObject().apply {
            put("type", "integer")
            put("description", "Number of results (1-10, default 5)")
          })
        })
        put("required", JSONArray(listOf("query")))
      }
    ) { args ->
      val query = args.optString("query", "").trim()
      val num = args.optInt("num_results", 5).coerceIn(1, 10)
      webSearch(query, num)
    }
    register(
      "calculate",
      "Evaluate a mathematical expression",
      JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
          put("expression", JSONObject().apply {
            put("type", "string")
            put("description", "Math expression, e.g. '2 + 2' or '(10 * 3) / 2'")
          })
        })
        put("required", JSONArray(listOf("expression")))
      }
    ) { args ->
      try { "Result: ${evaluateExpression(args.optString("expression", ""))}" }
      catch (e: Exception) { "Error: ${e.message}" }
    }
  }

  fun register(name: String, description: String, parameters: JSONObject, executor: (JSONObject) -> String) {
    tools[name] = ToolEntry(ToolDefinition(name, description, parameters), executor)
  }
  fun register(def: ToolDefinition, executor: (JSONObject) -> String) {
    tools[def.name] = ToolEntry(def, executor)
  }
  fun unregister(name: String) = tools.remove(name) != null
  fun clearAll() { tools.clear(); registerBuiltinTools() }
  fun hasTool(name: String) = tools.containsKey(name)
  fun getToolNames() = tools.keys.toList()
  fun getToolCount() = tools.size
  fun getToolDefinition(name: String) = tools[name]?.definition

  fun getToolDefinitionsJson(): String {
    val arr = JSONArray()
    tools.values.forEach { entry ->
      arr.put(JSONObject().apply {
        put("name", entry.definition.name)
        put("description", entry.definition.description)
        put("parameters", entry.definition.parameters)
      })
    }
    return arr.toString(2)
  }

  /**
   * Detects tool calls in ALL formats emitted by common open-weight models:
   *
   * 1. <tool_call>{"name":"web_search","arguments":{"query":"..."}}</tool_call>   ← Qwen3, Llama3.1+
   * 2. ```json\n{"name":"web_search","arguments":{...}}\n```                      ← many instruct models
   * 3. {"name":"web_search","arguments":{...}}                                     ← bare JSON
   * 4. {"function":"web_search","arguments":{...}}                                 ← some ChatML models
   * 5. {"type":"function","function":{"name":"web_search","arguments":{...}}}      ← OpenAI-style
   * 6. Partial matches: model outputs "web_search" as a string near a JSON block
   */
  fun parseToolCall(text: String): ToolCall? {
    // 1. <tool_call>...</tool_call>
    val tagRegex = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
    tagRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
      ?.let { tryParseJson(it) }
      ?.let { return it }

    // 2. ```json ... ``` or ``` ... ```
    val fenceRegex = Regex("```(?:json)?\\s*\\{(.*?)\\}\\s*```", RegexOption.DOT_MATCHES_ALL)
    fenceRegex.find(text)?.let { m ->
      tryParseJson("{${m.groupValues[1]}}")?.let { return it }
    }

    // 3 & 4 & 5. Extract first complete JSON object from the text
    extractFirstJsonObject(text)?.let { json ->
      tryParseJson(json)?.let { return it }
    }

    // 6. Fuzzy: model mentions a known tool name near any JSON
    for (toolName in tools.keys) {
      if (text.contains(toolName, ignoreCase = true)) {
        extractFirstJsonObject(text)?.let { json ->
          // Build a synthetic tool call with the args from the JSON
          return try {
            val obj = JSONObject(json)
            val args = obj.optJSONObject("arguments")
              ?: obj.optJSONObject("parameters")
              ?: obj.optJSONObject("args")
              ?: inferArgsFromText(text, toolName)
            ToolCall("call_${System.currentTimeMillis()}", toolName, args)
          } catch (_: Exception) { null }
        }
        // Even if no JSON, if tool name is mentioned and it's web_search, infer query from text
        if (toolName == "web_search") {
          val args = inferArgsFromText(text, toolName)
          if (args.length() > 0) return ToolCall("call_${System.currentTimeMillis()}", toolName, args)
        }
      }
    }
    return null
  }

  private fun tryParseJson(json: String): ToolCall? = try {
    val obj = JSONObject(json)
    // Format 5: {"type":"function","function":{"name":...,"arguments":...}}
    val inner = obj.optJSONObject("function")
    val resolved = inner ?: obj

    val name = resolved.optString("name", "")
      .ifEmpty { resolved.optString("function", "") }
      .ifEmpty { obj.optString("tool", "") }
    if (name.isEmpty() || !tools.containsKey(name)) return null

    // arguments can be a JSONObject or a JSON string
    val argsRaw = resolved.opt("arguments") ?: resolved.opt("parameters") ?: resolved.opt("args")
    val args = when (argsRaw) {
      is JSONObject -> argsRaw
      is String -> try { JSONObject(argsRaw) } catch (_: Exception) { JSONObject().apply { put("query", argsRaw) } }
      else -> JSONObject()
    }
    ToolCall("call_${System.currentTimeMillis()}", name, args)
  } catch (_: Exception) { null }

  private fun extractFirstJsonObject(text: String): String? {
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    for (i in text.indices) {
      val c = text[i]
      if (escaped) { escaped = false; continue }
      if (c == '\\' && inString) { escaped = true; continue }
      if (c == '"') { inString = !inString; continue }
      if (inString) continue
      when (c) {
        '{' -> { if (depth == 0) start = i; depth++ }
        '}' -> {
          depth--
          if (depth == 0 && start >= 0) return text.substring(start, i + 1)
        }
      }
    }
    return null
  }

  /** Try to extract a search query from natural language when no JSON was found */
  private fun inferArgsFromText(text: String, toolName: String): JSONObject {
    val args = JSONObject()
    if (toolName == "web_search") {
      // Patterns like: search for "X", search "X", look up X, find X
      val patterns = listOf(
        Regex("""search(?:\s+for)?\s+"([^"]+)"""", RegexOption.IGNORE_CASE),
        Regex("""search(?:\s+for)?\s+'([^']+)'""", RegexOption.IGNORE_CASE),
        Regex("""(?:look(?:ing)?\s+up|find|search(?:\s+for)?)\s+([^.!?\n]{5,80})""", RegexOption.IGNORE_CASE)
      )
      for (p in patterns) {
        val m = p.find(text) ?: continue
        val q = m.groupValues[1].trim()
        if (q.isNotEmpty()) { args.put("query", q); break }
      }
    }
    return args
  }

  fun executeTool(call: ToolCall): ToolResult {
    val result = try {
      tools[call.name]?.executor?.invoke(call.arguments) ?: "Tool '${call.name}' not found"
    } catch (e: Exception) { "Error executing tool: ${e.message}" }
    return ToolResult(call.id, call.name, result)
  }

  // ── Tool implementations ──────────────────────────────────────────────────

  private fun getCurrentTime() =
    "Current time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}"

  private fun getCurrentDate() =
    "Current date: ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())}"

  private fun webSearch(query: String, numResults: Int): String {
    if (query.isBlank()) return "Error: empty search query"
    return try {
      val encoded = URLEncoder.encode(query, "UTF-8")
      val lite = fetchDdgLite(encoded, numResults)
      if (lite.isNotBlank()) lite else fetchDdgHtml(encoded, numResults)
    } catch (e: Exception) {
      "Web search failed: ${e.message}"
    }
  }

  private fun openConn(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 20_000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
      setRequestProperty("Accept", "text/html,*/*;q=0.8")
      setRequestProperty("Accept-Language", "en-US,en;q=0.5")
      setRequestProperty("Connection", "close")
    }

  private fun fetchDdgLite(encoded: String, n: Int): String {
    val conn = openConn("https://lite.duckduckgo.com/lite/?q=$encoded")
    if (conn.responseCode != 200) { conn.disconnect(); return "" }
    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    conn.disconnect()
    return parseDdgLite(html, n)
  }

  private fun parseDdgLite(html: String, n: Int): String {
    val links = Regex("""<a[^>]+class="result-link"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
    val snips = Regex("""<td[^>]+class="result-snippet"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
    if (links.isEmpty()) return ""
    return buildString {
      var count = 0
      for (i in links.indices) {
        if (count >= n) break
        val title = stripTags(links[i].groupValues[2]).trim()
        if (title.isEmpty()) continue
        val url = decodeEntities(links[i].groupValues[1]).trim()
        val snip = snips.getOrNull(i)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
        if (isNotEmpty()) append("\n---\n")
        appendLine("Result ${count + 1}:"); appendLine("Title: $title"); appendLine("URL: $url")
        if (snip.isNotEmpty()) appendLine("Snippet: $snip")
        count++
      }
    }.trim()
  }

  private fun fetchDdgHtml(encoded: String, n: Int): String {
    val conn = openConn("https://html.duckduckgo.com/html/?q=$encoded")
    if (conn.responseCode != 200) { conn.disconnect(); return "No results found." }
    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    conn.disconnect()
    val links = Regex("""<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
    val snips = Regex("""<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
    if (links.isEmpty()) return "No results found."
    return buildString {
      var count = 0
      for (i in links.indices) {
        if (count >= n) break
        val title = stripTags(links[i].groupValues[2]).trim()
        if (title.isEmpty()) continue
        val rawUrl = decodeEntities(links[i].groupValues[1])
        val url = extractDdgUrl(rawUrl)
        val snip = snips.getOrNull(i)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
        if (isNotEmpty()) append("\n---\n")
        appendLine("Result ${count + 1}:"); appendLine("Title: $title"); appendLine("URL: $url")
        if (snip.isNotEmpty()) appendLine("Snippet: $snip")
        count++
      }
    }.trim().ifEmpty { "No results found." }
  }

  private fun extractDdgUrl(raw: String) = try {
    if ("uddg=" in raw) {
      java.net.URLDecoder.decode(raw.substringAfter("uddg=").substringBefore("&"), "UTF-8")
    } else raw
  } catch (_: Exception) { raw }

  private fun stripTags(html: String) = html.replace(Regex("<[^>]+>"), "").let { decodeEntities(it) }

  private fun decodeEntities(s: String) = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }

  // ── Calculator ────────────────────────────────────────────────────────────

  private fun evaluateExpression(expr: String): Double {
    val s = expr.replace(Regex("[^0-9+\\-*/().% ]"), "").trim()
    if (s.isEmpty()) throw IllegalArgumentException("Empty expression")
    val r = ExprParser(s).parseAddSub()
    if (r.isNaN() || r.isInfinite()) throw ArithmeticException("Invalid result")
    return r
  }

  private class ExprParser(val input: String) {
    var pos = 0
    fun parseAddSub(): Double {
      var l = parseMulDiv()
      while (pos < input.length) when (input[pos]) {
        '+' -> { pos++; l += parseMulDiv() }
        '-' -> { pos++; l -= parseMulDiv() }
        else -> break
      }
      return l
    }
    private fun parseMulDiv(): Double {
      var l = parseUnary()
      while (pos < input.length) when (input[pos]) {
        '*' -> { pos++; l *= parseUnary() }
        '/' -> { pos++; val d = parseUnary(); if (d == 0.0) throw ArithmeticException("Division by zero"); l /= d }
        '%' -> { pos++; val d = parseUnary(); if (d == 0.0) throw ArithmeticException("Division by zero"); l %= d }
        else -> break
      }
      return l
    }
    private fun parseUnary(): Double {
      if (pos < input.length && input[pos] == '-') { pos++; return -parsePrimary() }
      return parsePrimary()
    }
    private fun parsePrimary(): Double {
      while (pos < input.length && input[pos] == ' ') pos++
      if (pos >= input.length) throw IllegalArgumentException("Unexpected end")
      if (input[pos] == '(') {
        pos++; val r = parseAddSub()
        if (pos >= input.length || input[pos] != ')') throw IllegalArgumentException("Missing )")
        pos++; return r
      }
      val start = pos
      while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
      if (pos == start) throw IllegalArgumentException("Expected number at $pos")
      return input.substring(start, pos).toDouble()
    }
  }
}
