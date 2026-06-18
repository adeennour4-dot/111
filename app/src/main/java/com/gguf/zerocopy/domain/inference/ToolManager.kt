package com.gguf.zerocopy.domain.inference

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

  fun unregister(name: String): Boolean {
    return tools.remove(name) != null
  }

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
    val codeBlockRegex = Regex("```(?:json)?\\s*\\{.*?\\}\\s*```", RegexOption.DOT_MATCHES_ALL)
    val match = codeBlockRegex.find(text)
    if (match != null) {
      val block = match.value
      val json = block
        .replace(Regex("```(?:json)?\\s*"), "")
        .replace("```", "")
        .trim()
      return if (json.startsWith("{") && json.endsWith("}")) json else null
    }

    val inlineRegex = Regex("\\{\\s*\"name\"\\s*:\\s*\"[^\"]+\"\\s*,", RegexOption.DOT_MATCHES_ALL)
    val inlineMatch = inlineRegex.find(text)
    if (inlineMatch != null) {
      val start = inlineMatch.range.first
      var depth = 0
      var end = start
      for (i in start until text.length) {
        when (text[i]) {
          '{' -> depth++
          '}' -> { depth--; if (depth == 0) { end = i; break } }
        }
      }
      return if (end > start) text.substring(start, end + 1) else null
    }

    return null
  }

  private fun getCurrentTime(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return "Current time: ${sdf.format(Date())}"
  }

  private fun getCurrentDate(): String {
    val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
    return "Current date: ${sdf.format(Date())}"
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
