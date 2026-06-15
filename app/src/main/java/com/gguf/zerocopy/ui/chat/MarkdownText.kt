package com.gguf.zerocopy.ui.chat

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.sp
import com.gguf.zerocopy.ui.theme.ZcColors

@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  linkColor: Color = ZcColors.Accent,
  codeColor: Color = ZcColors.Accent2,
  textColor: Color = ZcColors.Text
) {
  val context = LocalContext.current
  val annotated = remember(markdown) { parseMarkdown(markdown, textColor, linkColor, codeColor) }
  ClickableText(
    text = annotated,
    modifier = modifier,
    style = style.copy(fontSize = 14.sp, lineHeight = 20.sp),
    onClick = { offset ->
      annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
        try {
          context.startActivity(
            android.content.Intent(
              android.content.Intent.ACTION_VIEW,
              android.net.Uri.parse(it.item)
            )
          )
        } catch (_: Exception) {}
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
      // Code block ```...```
      text.startsWith("```", i) -> {
        val end = text.indexOf("```", i + 3)
        if (end >= 0) {
          val code = text.substring(i + 3, end).trimStart('\n', '\r').trimEnd('\n', '\r')
          withStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = codeColor)
          ) {
            append("\n")
            append(code)
            append("\n")
          }
          i = end + 3
        } else {
          append(text[i])
          i++
        }
      }
      // Inline code `...`
      text[i] == '`' -> {
        val end = text.indexOf('`', i + 1)
        if (end >= 0) {
          val code = text.substring(i + 1, end)
          withStyle(
            SpanStyle(
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              color = codeColor,
              background = Color(0x33FFFFFF)
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
      // Image ![alt](url)
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
      // Link [text](url)
      text[i] == '[' -> {
        val closeBracket = text.indexOf("](", i + 1)
        val closeParen = if (closeBracket >= 0) text.indexOf(')', closeBracket + 2) else -1
        if (closeBracket >= 0 && closeParen >= 0) {
          val linkText = text.substring(i + 1, closeBracket)
          val url = text.substring(closeBracket + 2, closeParen)
          withAnnotation("url", url) {
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
      // Bold **text**
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
      // Italic *text*
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
      // Bold __text__
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
      // Strikethrough ~~text~~
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
      // Header # through ######
      text[i] == '#' && (i == 0 || text[i - 1] == '\n') -> {
        var level = 0
        while (i + level < text.length && text[i + level] == '#') level++
        if (level <= 6 && i + level < text.length && text[i + level] == ' ') {
          val lineEnd = text.indexOf('\n', i)
          val headerText = text.substring(
            i + level + 1,
            if (lineEnd >=
              0
            ) {
              lineEnd
            } else {
              text.length
            }
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
      // Bullet list - or *
      (text[i] == '-' || text[i] == '*') &&
        (i == 0 || text[i - 1] == '\n') &&
        i + 1 < text.length &&
        text[i + 1] == ' ' -> {
        append("  \u2022  ")
        i += 2
      }
      // Numbered list 1. 2. etc
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
      // Horizontal rule --- or ***
      (
        text.startsWith(
          "---",
          i
        ) ||
          text.startsWith("***", i)
        ) &&
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
