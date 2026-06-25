package com.gguf.zerocopy.domain.inference

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class ToolDefinition(
  val name: String,
  val description: String,
  val parameters: JSONObject
)

data class ToolCall(
  val id: String,
  val name: String,
  val arguments: JSONObject
)

data class ToolResult(
  val callId: String,
  val name: String,
  val result: String
)

class ToolManager {
  private data class ToolEntry(
    val definition: ToolDefinition,
    val executor: (JSONObject) -> String
  )

  private val tools = mutableMapOf<String, ToolEntry>()

  init {
    registerBuiltinTools()
  }

  private fun registerBuiltinTools() {
    register(
      name = "get_current_time",
      description = "Get the current date and time",
      parameters = JSONObject()
    ) { getCurrentTime() }

    register(
      name = "get_current_date",
      description = "Get the current date",
      parameters = JSONObject()
    ) { getCurrentDate() }

    register(
      name = "web_search",
      description = "Search the web for current information. Returns title, snippet, and URL for each result.",
      parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
          put("query", JSONObject().apply {
            put("type", "string")
            put("description", "The search query")
          })
          put("num_results", JSONObject().apply {
            put("type", "integer")
            put("description", "Number of results to return (1-10)")
          })
        })
        put("required", JSONArray(listOf("query")))
      }
    ) { args ->
      val query = args.optString("query", "")
      val num = args.optInt("num_results", 5).coerceIn(1, 10)
      webSearch(query, num)
    }

    register(
      name = "calculate",
      description = "Perform a mathematical calculation (supports +, -, *, /, %, parentheses)",
      parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
          put("expression", JSONObject().apply {
            put("type", "string")
            put("description", "The mathematical expression to evaluate (e.g. 2 + 2)")
          })
        })
        put("required", JSONArray(listOf("expression")))
      }
    ) { args ->
      val expr = args.optString("expression", "")
      try {
        val result = evaluateExpression(expr)
        "Result: $result"
      } catch (e: Exception) {
        "Error evaluating expression: ${e.message}"
      }
    }
  }

  fun register(
    name: String,
    description: String,
    parameters: JSONObject,
    executor: (JSONObject) -> String
  ) {
    val def = ToolDefinition(name = name, description = description, parameters = parameters)
    tools[name] = ToolEntry(definition = def, executor = executor)
  }

  fun register(def: ToolDefinition, executor: (JSONObject) -> String) {
    tools[def.name] = ToolEntry(definition = def, executor = executor)
  }

  fun unregister(name: String): Boolean = tools.remove(name) != null

  fun clearAll() {
    tools.clear()
    registerBuiltinTools()
  }

  fun hasTool(name: String): Boolean = tools.containsKey(name)

  fun getToolNames(): List<String> = tools.keys.toList()

  fun getToolCount(): Int = tools.size

  fun getToolDefinition(name: String): ToolDefinition? = tools[name]?.definition

  fun getToolDefinitionsJson(): String {
    val arr = JSONArray()
    for ((name, _) in tools) {
      val def = tools[name]?.definition ?: continue
      arr.put(JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
          put("name", def.name)
          put("description", def.description)
          put("parameters", def.parameters)
        })
      })
    }
    return arr.toString()
  }

  fun parseToolCall(text: String): ToolCall? {
    val jsonStr = extractJsonBlock(text) ?: return null
    return try {
      val obj = JSONObject(jsonStr)
      val name = obj.optString("name", "")
        .ifEmpty { obj.optString("function", "") }
      if (name.isEmpty() || !tools.containsKey(name)) return null
      val argsObj = obj.optJSONObject("arguments") ?: JSONObject()
      ToolCall(
        id = "call_${System.currentTimeMillis()}",
        name = name,
        arguments = argsObj
      )
    } catch (_: Exception) {
      null
    }
  }

  fun executeTool(call: ToolCall): ToolResult {
    val entry = tools[call.name]
    val result = try {
      entry?.executor?.invoke(call.arguments) ?: "Tool '${call.name}' not found"
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
    return ToolResult(callId = call.id, name = call.name, result = result)
  }

  private fun extractJsonBlock(text: String): String? {
    // Try fenced code block first (```json ... ``` or ``` ... ```)
    val codeBlockRegex = Regex("""```(?:json)?\s*(\{.*?})\s*```""", RegexOption.DOT_MATCHES_ALL)
    val cbMatch = codeBlockRegex.find(text)
    if (cbMatch != null) {
      val json = cbMatch.groupValues[1].trim()
      if (json.startsWith("{") && json.endsWith("}")) return json
    }

    // Try bare JSON object containing "name" key
    val start = text.indexOf("{")
    if (start < 0) return null
    var depth = 0
    var end = -1
    for (i in start until text.length) {
      when (text[i]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) { end = i; break }
        }
      }
    }
    if (end < 0) return null
    val candidate = text.substring(start, end + 1)
    // Only accept if it has a "name" field pointing to a known tool
    return if (candidate.contains("\"name\"")) candidate else null
  }

  private fun getCurrentTime(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return "Current time: ${sdf.format(Date())}"
  }

  private fun getCurrentDate(): String {
    val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    return "Current date: ${sdf.format(Date())}"
  }

  /**
   * Web search via DuckDuckGo Lite (plain HTML, no JS, no redirect loop).
   * Falls back to DuckDuckGo HTML endpoint if Lite returns nothing.
   */
  private fun webSearch(query: String, numResults: Int): String {
    if (query.isBlank()) return "Error: empty search query."
    return try {
      val encoded = URLEncoder.encode(query, "UTF-8")
      // DuckDuckGo Lite — returns simple table-based HTML, no JS
      val result = fetchDdgLite(encoded, numResults)
      if (result.isNotBlank()) result
      else fetchDdgHtml(encoded, numResults)
    } catch (e: Exception) {
      "Web search failed: ${e.message}"
    }
  }

  private fun openConnection(urlStr: String): HttpURLConnection {
    val conn = URL(urlStr).openConnection() as HttpURLConnection
    conn.apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 20_000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
      setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
      setRequestProperty("Accept-Language", "en-US,en;q=0.5")
      setRequestProperty("Connection", "close")
    }
    return conn
  }

  /** DuckDuckGo Lite endpoint — simpler HTML, more reliable on Android */
  private fun fetchDdgLite(encoded: String, numResults: Int): String {
    val conn = openConnection("https://lite.duckduckgo.com/lite/?q=$encoded")
    val responseCode = conn.responseCode
    if (responseCode != 200) {
      conn.disconnect()
      return ""
    }
    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    conn.disconnect()
    return parseDdgLite(html, numResults)
  }

  /** Parse the DuckDuckGo Lite table result page */
  private fun parseDdgLite(html: String, numResults: Int): String {
    // Each result: <a class="result-link" href="URL">Title</a> then <td class="result-snippet">Snippet</td>
    val linkRegex = Regex("""<a[^>]+class="result-link"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    val snippetRegex = Regex("""<td[^>]+class="result-snippet"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

    val links = linkRegex.findAll(html).toList()
    val snippets = snippetRegex.findAll(html).toList()

    if (links.isEmpty()) return ""

    return buildString {
      var count = 0
      for (i in links.indices) {
        if (count >= numResults) break
        val url = decodeHtmlEntities(links[i].groupValues[1]).trim()
        val title = stripTags(links[i].groupValues[2]).trim()
        val snippet = snippets.getOrNull(i)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
        if (title.isEmpty()) continue
        if (isNotEmpty()) append("\n---\n")
        appendLine("Result ${count + 1}:")
        appendLine("Title: $title")
        appendLine("URL: $url")
        if (snippet.isNotEmpty()) appendLine("Snippet: $snippet")
        count++
      }
    }.trim()
  }

  /** Fallback: DuckDuckGo HTML endpoint */
  private fun fetchDdgHtml(encoded: String, numResults: Int): String {
    val conn = openConnection("https://html.duckduckgo.com/html/?q=$encoded")
    val responseCode = conn.responseCode
    if (responseCode != 200) {
      conn.disconnect()
      return "No results found (HTTP $responseCode)."
    }
    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    conn.disconnect()
    return parseDdgHtml(html, numResults)
  }

  /** Parse DuckDuckGo HTML full result page */
  private fun parseDdgHtml(html: String, numResults: Int): String {
    // result links are inside <a class="result__a" ...>
    val linkRegex = Regex("""<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    val snippetRegex = Regex("""<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    val links = linkRegex.findAll(html).toList()
    val snippets = snippetRegex.findAll(html).toList()

    if (links.isEmpty()) return "No results found for the query."

    return buildString {
      var count = 0
      for (i in links.indices) {
        if (count >= numResults) break
        // DDG HTML wraps URLs in a redirect — extract uddg= param when present
        val rawUrl = decodeHtmlEntities(links[i].groupValues[1])
        val url = extractDdgUrl(rawUrl)
        val title = stripTags(links[i].groupValues[2]).trim()
        val snippet = snippets.getOrNull(i)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
        if (title.isEmpty()) continue
        if (isNotEmpty()) append("\n---\n")
        appendLine("Result ${count + 1}:")
        appendLine("Title: $title")
        appendLine("URL: $url")
        if (snippet.isNotEmpty()) appendLine("Snippet: $snippet")
        count++
      }
    }.trim().ifEmpty { "No results found for the query." }
  }

  /** Extract the actual URL from a DDG redirect like /l/?uddg=https%3A%2F%2F... */
  private fun extractDdgUrl(raw: String): String {
    return try {
      if (raw.startsWith("/l/?") || raw.contains("uddg=")) {
        val idx = raw.indexOf("uddg=")
        if (idx >= 0) {
          java.net.URLDecoder.decode(raw.substring(idx + 5).substringBefore("&"), "UTF-8")
        } else raw
      } else raw
    } catch (_: Exception) { raw }
  }

  private fun stripTags(html: String): String =
    html.replace(Regex("<[^>]+>"), "").let { decodeHtmlEntities(it) }

  private fun decodeHtmlEntities(s: String): String =
    s.replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&nbsp;", " ")
      .replace(Regex("&#(\\d+);")) { mr ->
        mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: mr.value
      }

  private fun evaluateExpression(expr: String): Double {
    val sanitized = expr.replace("[^0-9+\\-*/().% ]".toRegex(), "").trim()
    if (sanitized.isEmpty()) throw IllegalArgumentException("Empty expression")
    return parseExpression(sanitized).also {
      if (it.isNaN() || it.isInfinite()) throw ArithmeticException("Invalid result")
    }
  }

  private fun parseExpression(expr: String): Double {
    val parser = ExprParser(expr)
    val result = parser.parseAddSub()
    if (parser.pos < expr.length) throw IllegalArgumentException("Unexpected character at position ${parser.pos}")
    return result
  }

  private class ExprParser(val input: String) {
    var pos = 0

    fun parseAddSub(): Double {
      var left = parseMulDiv()
      while (pos < input.length) {
        when (input[pos]) {
          '+' -> { pos++; left += parseMulDiv() }
          '-' -> { pos++; left -= parseMulDiv() }
          else -> break
        }
      }
      return left
    }

    private fun parseMulDiv(): Double {
      var left = parseUnary()
      while (pos < input.length) {
        when (input[pos]) {
          '*' -> { pos++; left *= parseUnary() }
          '/' -> { pos++; val d = parseUnary(); if (d == 0.0) throw ArithmeticException("Division by zero"); left /= d }
          '%' -> { pos++; val d = parseUnary(); if (d == 0.0) throw ArithmeticException("Division by zero"); left %= d }
          else -> break
        }
      }
      return left
    }

    private fun parseUnary(): Double {
      if (pos < input.length && input[pos] == '-') {
        pos++
        return -parsePrimary()
      }
      return parsePrimary()
    }

    private fun parsePrimary(): Double {
      if (pos >= input.length) throw IllegalArgumentException("Unexpected end")
      // skip spaces
      while (pos < input.length && input[pos] == ' ') pos++
      if (input[pos] == '(') {
        pos++
        val result = parseAddSub()
        if (pos >= input.length || input[pos] != ')') throw IllegalArgumentException("Missing closing parenthesis")
        pos++
        return result
      }
      val start = pos
      while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
      if (pos == start) throw IllegalArgumentException("Expected number at position $pos")
      return input.substring(start, pos).toDouble()
    }
  }
}
