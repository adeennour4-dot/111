package com.gguf.zerocopy.domain.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

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
            val raf = RandomAccessFile(file, "r")
            val sb = StringBuilder()
            val buf = ByteArray(8192)
            var inStream = false
            var depth = 0
            var read: Int
            while (raf.read(buf).also { read = it } != -1) {
                var pos = 0
                while (pos < read) {
                    val b = buf[pos].toInt() and 0xFF
                    val c = b.toChar()
                    when {
                        c == 's' && pos + 5 < read && buf[pos + 1].toChar() == 't' && buf[pos + 2].toChar() == 'r' && buf[pos + 3].toChar() == 'e' && buf[pos + 4].toChar() == 'a' && buf[pos + 5].toChar() == 'm' -> {
                            inStream = true
                            depth = 1
                            pos += 6
                        }
                        c == 'E' && pos + 2 < read && buf[pos + 1].toChar() == 'n' && buf[pos + 2].toChar() == 'd' && pos + 3 < read && buf[pos + 3].toChar() == 's' && pos + 4 < read && buf[pos + 4].toChar() == 't' && pos + 5 < read && buf[pos + 5].toChar() == 'r' && pos + 6 < read && buf[pos + 6].toChar() == 'e' && pos + 7 < read && buf[pos + 7].toChar() == 'a' && pos + 8 < read && buf[pos + 8].toChar() == 'm' -> {
                            inStream = false
                            pos += 9
                        }
                        inStream && c == '(' -> depth++
                        inStream && c == ')' -> {
                            depth--
                            if (depth == 0) inStream = false
                        }
                        else -> if (!inStream && c == '(') {
                            val start = pos
                            var end = pos
                            var parenDepth = 1
                            end++
                            while (end < read && parenDepth > 0) {
                                val ec = buf[end].toInt() and 0xFF
                                when (ec.toChar()) {
                                    '(' -> parenDepth++
                                    ')' -> parenDepth--
                                    '\\' -> end++
                                }
                                end++
                            }
                            if (parenDepth == 0) {
                                val content = String(buf, start + 1, end - start - 2, Charsets.ISO_8859_1)
                                val cleaned = content
                                    .replace("\\n", "\n")
                                    .replace("\\r", "\r")
                                    .replace("\\t", "\t")
                                    .replace(Regex("\\\\[0-7]{3}")) { m -> m.value.substring(1).toInt(8).toChar().toString() }
                                    .replace("\\(", "(")
                                    .replace("\\)", ")")
                                    .replace("\\\\", "\\")
                                if (cleaned.length > 3 && cleaned.any { it.isLetter() }) {
                                    sb.append(cleaned).append(' ')
                                }
                            }
                        }
                    }
                    pos++
                }
            }
            raf.close()
            val result = sb.toString().trim().replace(Regex("\\s+"), " ")
            result.takeIf { it.length > 20 }
        } catch (_: Exception) { null }
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

        val rowText = StringBuilder()
        val lines = mutableListOf<String>()
        val gapThreshold = (height / 30).coerceAtLeast(3)
        var lastNonEmptyRow = -1

        for (y in 0 until height) {
            var rowStart = -1
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val isDark = (r + g + b) / 3 < 180
                if (isDark) {
                    if (rowStart == -1) rowStart = x
                }
            }
            if (rowStart >= 0) {
                lastNonEmptyRow = y
            }
            if (lastNonEmptyRow >= 0 && y - lastNonEmptyRow > gapThreshold) {
                if (rowText.isNotEmpty()) {
                    val line = rowText.toString().trim()
                    if (line.isNotEmpty()) lines.add(line)
                    rowText.clear()
                }
                lastNonEmptyRow = -1
            }
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
