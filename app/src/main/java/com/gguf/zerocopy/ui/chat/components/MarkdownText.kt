package com.gguf.zerocopy.ui.chat.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.currentPalette

private const val URL_TAG = "url"
private const val CODE_TAG = "code"

@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  fontSize: TextUnit = 14.sp,
  style: TextStyle = LocalTextStyle.current
) {
  val colors = currentPalette()
  val uriHandler = LocalUriHandler.current
  val clipboardManager = LocalClipboardManager.current
  val annotated = remember(markdown) {
    parseMarkdown(markdown, colors.Text, colors.Accent, colors.Accent2)
  }
  ClickableText(
    text = annotated,
    modifier = modifier,
    maxLines = maxLines,
    style = style.copy(fontSize = fontSize, lineHeight = fontSize * 1.5f),
    onClick = { offset ->
      annotated.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.let {
        uriHandler.openUri(it.item)
      }
      annotated.getStringAnnotations(CODE_TAG, offset, offset).firstOrNull()?.let {
        clipboardManager.setText(AnnotatedString(it.item))
      }
    }
  )
}

private fun parseMarkdown(
  text: String,
  textColor: Color,
  linkColor: Color,
  codeColor: Color
): AnnotatedString = buildAnnotatedString {
  var i = 0
  while (i < text.length) {
    when {
      text.startsWith("\n\n", i) || text.startsWith("\r\n\r\n", i) -> {
        append("\n\n")
        i += if (text.startsWith("\r\n\r\n", i)) 4 else 2
      }
      text[i] == '\n' || text[i] == '\r' -> {
        append("\n")
        i++
        if (i < text.length && text[i] == '\n') i++
      }
      text[i] == ' ' && i + 1 < text.length && text[i + 1] == ' ' -> {
        append(' ')
        while (i < text.length && text[i] == ' ') i++
      }
      text.startsWith("```", i) -> {
        val end = text.indexOf("```", i + 3)
        if (end >= 0) {
          val code = text.substring(i + 3, end).trimStart('\n', '\r').trimEnd('\n', '\r')
          withAnnotation(CODE_TAG, code) {
            withStyle(
              SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = codeColor,
                background = Color(0x22FFFFFF)
              )
            ) {
              append("\n")
              append(code)
              append("\n")
            }
          }
          i = end + 3
        } else {
          append(text[i])
          i++
        }
      }
      text[i] == '`' -> {
        val end = text.indexOf('`', i + 1)
        if (end >= 0) {
          val code = text.substring(i + 1, end)
          withStyle(
            SpanStyle(
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              color = codeColor,
              background = Color(0x22FFFFFF)
            )
          ) {
            append(code)
          }
          i = end + 1
        } else {
          append(text[i])
          i++
        }
      }
      text.startsWith("![", i) -> {
        val closeBracket = text.indexOf("](", i + 2)
        val closeParen = if (closeBracket >= 0) text.indexOf(')', closeBracket + 2) else -1
        if (closeBracket >= 0 && closeParen >= 0) {
          val alt = text.substring(i + 2, closeBracket)
          append(alt)
          i = closeParen + 1
        } else {
          append(text[i])
          i++
        }
      }
      text[i] == '[' -> {
        val closeBracket = text.indexOf("](", i + 1)
        val closeParen = if (closeBracket >= 0) text.indexOf(')', closeBracket + 2) else -1
        if (closeBracket >= 0 && closeParen >= 0) {
          val linkText = text.substring(i + 1, closeBracket)
          val url = text.substring(closeBracket + 2, closeParen)
          withAnnotation(URL_TAG, url) {
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
              append(linkText)
            }
          }
          i = closeParen + 1
        } else {
          append(text[i])
          i++
        }
      }
      text.startsWith("**", i) && i + 2 < text.length -> {
        val end = text.indexOf("**", i + 2)
        if (end >= 0) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(i + 2, end))
          }
          i = end + 2
        } else {
          append(text[i])
          i++
        }
      }
      text[i] == '*' && i + 1 < text.length && text[i + 1] != '*' -> {
        val end = text.indexOf('*', i + 1)
        if (end >= 0) {
          withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append(text.substring(i + 1, end))
          }
          i = end + 1
        } else {
          append(text[i])
          i++
        }
      }
      text.startsWith("__", i) && i + 2 < text.length -> {
        val end = text.indexOf("__", i + 2)
        if (end >= 0) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(i + 2, end))
          }
          i = end + 2
        } else {
          append(text[i])
          i++
        }
      }
      text.startsWith("~~", i) && i + 2 < text.length -> {
        val end = text.indexOf("~~", i + 2)
        if (end >= 0) {
          withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            append(text.substring(i + 2, end))
          }
          i = end + 2
        } else {
          append(text[i])
          i++
        }
      }
      text[i] == '#' && (i == 0 || text[i - 1] == '\n') -> {
        var level = 0
        while (i + level < text.length && text[i + level] == '#') level++
        if (level <= 6 && i + level < text.length && text[i + level] == ' ') {
          val lineEnd = text.indexOf('\n', i)
          val headerText = text.substring(
            i + level + 1,
            if (lineEnd >= 0) lineEnd else text.length
          ).trim()
          val size = when (level) {
            1 -> 20.sp
            2 -> 17.sp
            3 -> 15.sp
            else -> 14.sp
          }
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
            append(headerText)
          }
          append("\n")
          i = if (lineEnd >= 0) lineEnd + 1 else text.length
        } else {
          append(text[i])
          i++
        }
      }
      text[i] == '>' && (i == 0 || text[i - 1] == '\n') -> {
        val lineEnd = text.indexOf('\n', i)
        val quoteText = text.substring(i + 1, if (lineEnd >= 0) lineEnd else text.length).trim()
        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = textColor.copy(alpha = 0.7f))) {
          append("  \u201c$quoteText\u201d")
        }
        append("\n")
        i = if (lineEnd >= 0) lineEnd + 1 else text.length
      }
      (text[i] == '-' || text[i] == '*') &&
        (i == 0 || text[i - 1] == '\n') &&
        i + 1 < text.length &&
        text[i + 1] == ' ' -> {
        append("  \u2022  ")
        i += 2
      }
      text[i].isDigit() && (i == 0 || text[i - 1] == '\n') -> {
        var j = i
        while (j < text.length && text[j].isDigit()) j++
        if (j < text.length && text[j] == '.' && j + 1 < text.length && text[j + 1] == ' ') {
          append("  ")
          append(text.substring(i, j + 1))
          append(" ")
          i = j + 2
        } else {
          append(text[i])
          i++
        }
      }
      (text.startsWith("---", i) || text.startsWith("***", i)) &&
        (i == 0 || text[i - 1] == '\n') -> {
        val lineEnd = text.indexOf('\n', i)
        val line = text.substring(i, if (lineEnd >= 0) lineEnd else text.length).trim()
        if (line.matches(Regex("^[-*]{3,}$"))) {
          append("\u2500".repeat(20))
          append("\n")
          i = if (lineEnd >= 0) lineEnd + 1 else text.length
        } else {
          append(text[i])
          i++
        }
      }
      else -> {
        append(text[i])
        i++
      }
    }
  }
}
