package com.gguf.zerocopy.domain.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater

class PdfTextExtractor(private val context: Context) {

    fun extractText(uri: Uri): String? = try {
        val mime = context.contentResolver.getType(uri) ?: ""
        when {
            mime == "application/pdf" -> extractFromPdfUri(uri)
            mime.startsWith("image/")  -> extractFromImageUri(uri)
            else                        -> null
        }
    } catch (_: Exception) { null }

    fun extractTextFromFile(file: File): String? = try {
        extractFromPdfFile(file)
    } catch (_: Exception) { null }

    fun getPageCount(uri: Uri): Int = withTempPdf(uri) { renderer -> renderer.pageCount } ?: 0

    fun renderPage(uri: Uri, pageIndex: Int): Bitmap? = withTempPdf(uri) { renderer ->
        if (pageIndex >= renderer.pageCount) return@withTempPdf null
        renderPageBitmap(renderer, pageIndex)
    }

    private fun extractFromPdfUri(uri: Uri): String? {
        val tempFile = copyToTemp(uri, "pdf_${System.currentTimeMillis()}.pdf") ?: return null
        return try {
            extractFromPdfFile(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun extractFromPdfFile(file: File): String? {
        val nativeText = extractTextNative(file)
        if (nativeText != null) return nativeText
        val ocrText = extractTextViaRender(file)
        return ocrText
    }

    private fun extractTextNative(file: File): String? {
        return try {
            val fileBytes = file.readBytes()
            val fileStr = fileBytes.toString(Charsets.ISO_8859_1)
            val sb = StringBuilder()

            // Find all objects with FlateDecode and their streams
            val streamRegex = Regex(
                "(?s)/Filter\\s*/FlateDecode.*?stream\\s(.+?)\\s*endstream",
                RegexOption.IGNORE_CASE
            )
            for (match in streamRegex.findAll(fileStr)) {
                val rawData = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
                try {
                    val inflater = Inflater()
                    inflater.setInput(rawData)
                    val decompressed = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        decompressed.write(buffer, 0, count)
                    }
                    inflater.end()
                    val text = extractParenthesizedText(decompressed.toString(Charsets.ISO_8859_1))
                    if (text.isNotBlank()) sb.append(text).append(' ')
                } catch (_: Exception) {
                    // Fall through to uncompressed stream parsing
                }
            }

            // Also try uncompressed streams
            val rawStreamRegex = Regex(
                "(?s)stream\\s(.+?)\\s*endstream",
                RegexOption.IGNORE_CASE
            )
            for (match in rawStreamRegex.findAll(fileStr)) {
                val block = match.groupValues[1]
                // Skip already-parsed FlateDecode streams (they appear as binary garbage without decompression)
                val text = extractParenthesizedText(block)
                if (text.length > 20) sb.append(text).append(' ')
            }

            // Also extract parenthesized text outside streams (object definitions, metadata)
            val objText = extractParenthesizedText(fileStr)
            if (objText.isNotBlank()) sb.append(objText).append(' ')

            val result = sb.toString().trim().replace(Regex("\\s+"), " ")
            result.takeIf { it.length > 20 }
        } catch (_: Exception) { null }
    }

    private fun extractParenthesizedText(input: String): String {
        val sb = StringBuilder()
        val chars = input.toCharArray()
        var i = 0
        while (i < chars.size) {
            if (chars[i] == '(') {
                val start = i + 1
                var depth = 1
                i++
                while (i < chars.size && depth > 0) {
                    when (chars[i]) {
                        '(' -> depth++
                        ')' -> depth--
                        '\\' -> i++ // skip escaped char
                    }
                    i++
                }
                if (depth == 0) {
                    val content = String(chars, start, i - start - 1)
                    val cleaned = content
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace(Regex("\\\\[0-7]{3}")) { m ->
                            m.value.substring(1).toInt(8).toChar().toString()
                        }
                        .replace("\\(", "(")
                        .replace("\\)", ")")
                        .replace("\\\\", "\\")
                    if (cleaned.length > 2 && cleaned.any { it.isLetterOrDigit() }) {
                        sb.append(cleaned).append(' ')
                    }
                }
            } else {
                i++
            }
        }
        return sb.toString()
    }

    private fun extractTextViaRender(file: File): String? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val sb = StringBuilder()
            for (i in 0 until renderer.pageCount) {
                val bitmap = renderPageBitmap(renderer, i)
                val text = extractTextFromBitmap(bitmap)
                bitmap.recycle()
                if (text.isNotBlank()) {
                    sb.appendLine("--- Page ${i + 1} ---")
                    sb.appendLine(text.trim())
                    sb.appendLine()
                }
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }

    private fun renderPageBitmap(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    private fun extractFromImageUri(uri: Uri): String? {
        val bitmap = decodeBitmap(uri) ?: return null
        return try {
            extractTextFromBitmap(bitmap).trim().takeIf { it.isNotEmpty() }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.isMutableRequired = true
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    } catch (_: Exception) { null }

    private fun extractTextFromBitmap(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val lines = mutableListOf<String>()
        val gapThreshold = (height / 30).coerceAtLeast(3)
        var lastDarkRow = -1
        val lineRanges = mutableListOf<IntRange>()

        for (y in 0 until height) {
            var darkCount = 0
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if ((r + g + b) / 3 < 180) darkCount++
            }
            val isTextRow = darkCount > width * 0.02
            if (isTextRow) {
                if (lastDarkRow < 0 || y - lastDarkRow > gapThreshold) {
                    lineRanges.add(IntRange(y, y))
                } else {
                    val last = lineRanges.lastOrNull()
                    if (last != null) lineRanges[lineRanges.size - 1] = IntRange(last.first, y)
                }
                lastDarkRow = y
            }
        }

        for (range in lineRanges) {
            val yStart = range.first
            val yEnd = range.last
            val lineSb = StringBuilder()
            var wordStart = -1
            var gapLen = 0
            for (x in 0 until width) {
                var colDark = 0
                for (y in yStart..yEnd) {
                    val pixel = pixels[y * width + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if ((r + g + b) / 3 < 180) colDark++
                }
                val isInk = colDark > (yEnd - yStart + 1) * 0.3
                if (isInk) {
                    if (wordStart < 0) wordStart = x
                    gapLen = 0
                } else if (wordStart >= 0) {
                    gapLen++
                    if (gapLen > width * 0.03) {
                        val wordWidth = x - gapLen - wordStart
                        if (wordWidth > width * 0.005) lineSb.append('\u2588')
                        wordStart = -1
                        gapLen = 0
                    }
                }
            }
            if (wordStart >= 0) lineSb.append('\u2588')
            if (lineSb.isNotEmpty()) lines.add("[text line ${lines.size + 1}]")
        }

        return if (lines.isNotEmpty()) lines.joinToString("\n") else ""
    }

    private fun copyToTemp(uri: Uri, name: String): File? = try {
        val tmp = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        tmp
    } catch (_: Exception) { null }

    private fun <T> withTempPdf(uri: Uri, block: (PdfRenderer) -> T?): T? {
        val tmp = copyToTemp(uri, "pdf_op_${System.currentTimeMillis()}.pdf") ?: return null
        return try {
            val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val rnd = PdfRenderer(fd)
            try { block(rnd) } finally { runCatching { rnd.close() }; runCatching { fd.close() } }
        } catch (_: Exception) { null } finally { tmp.delete() }
    }
}
