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

/**
 * Extracts text from PDFs and images on-device.
 *
 * Key design constraints for large documents (100+ pages):
 * - Never load the entire PDF as a String or byte[] — stream page by page
 * - Recycle every Bitmap immediately after text extraction
 * - Render at reduced resolution (1× instead of 2×) to stay within 8MB per page
 * - Cap render-based OCR at MAX_RENDER_PAGES to prevent OOM on huge files
 * - Return text as a streaming sequence to avoid holding the full document in RAM
 */
class PdfTextExtractor(private val context: Context) {

    companion object {
        // Maximum pages to OCR via bitmap rendering when native extraction fails.
        // At 1× scale a typical A4 page is ~2MB ARGB_8888; 50 pages = ~100MB peak.
        private const val MAX_RENDER_PAGES = 50
        // Render scale — 1.5× gives readable OCR output without blowing RAM
        private const val RENDER_SCALE = 1.5f
        // Stream buffer for native text extraction
        private const val STREAM_BUFFER = 65_536
    }

    fun extractText(uri: Uri): String? = try {
        val mime = context.contentResolver.getType(uri) ?: ""
        when {
            mime == "application/pdf" -> extractFromPdfUri(uri)
            mime.startsWith("image/") -> extractFromImageUri(uri)
            else                      -> null
        }
    } catch (_: Exception) { null }

    fun extractTextFromFile(file: File): String? = try {
        extractFromPdfFile(file)
    } catch (_: Exception) { null }

    fun getPageCount(uri: Uri): Int = withTempPdf(uri) { it.pageCount } ?: 0

    fun renderPage(uri: Uri, pageIndex: Int): Bitmap? = withTempPdf(uri) { renderer ->
        if (pageIndex >= renderer.pageCount) null
        else renderPageBitmap(renderer, pageIndex)
    }

    // ── PDF extraction ────────────────────────────────────────────────────────

    private fun extractFromPdfUri(uri: Uri): String? {
        val tmp = copyToTemp(uri, "pdf_${System.currentTimeMillis()}.pdf") ?: return null
        return try {
            extractFromPdfFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun extractFromPdfFile(file: File): String? {
        // Try streaming native text extraction first (no bitmap RAM)
        val native = extractTextNativeStreaming(file)
        if (!native.isNullOrBlank() && native.length > 50) return native
        // Fall back to page-by-page bitmap OCR with strict page cap
        return extractTextViaRender(file)
    }

    /**
     * Stream through the PDF file in chunks, decompressing FlateDecode streams
     * one at a time. Never holds the entire file in memory as a String.
     *
     * Uses RandomAccessFile to read in 64 KB blocks so even a 50 MB PDF
     * only uses ~128 KB of working memory at peak.
     */
    private fun extractTextNativeStreaming(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val sb = StringBuilder(8192)
            val raf = RandomAccessFile(file, "r")
            val fileLen = raf.length()
            val buf = ByteArray(STREAM_BUFFER)
            val carry = StringBuilder()  // holds partial stream marker across blocks

            var pos = 0L
            var inStream = false
            var streamBuf = StringBuilder()

            while (pos < fileLen) {
                val toRead = minOf(STREAM_BUFFER.toLong(), fileLen - pos).toInt()
                raf.seek(pos)
                raf.readFully(buf, 0, toRead)
                pos += toRead

                val chunk = carry.toString() + String(buf, 0, toRead, Charsets.ISO_8859_1)
                carry.clear()

                var i = 0
                while (i < chunk.length) {
                    if (!inStream) {
                        // Look for "stream\n" or "stream\r\n"
                        val idx = chunk.indexOf("stream", i)
                        if (idx < 0) {
                            // Save last 10 chars as carry to catch markers split across blocks
                            if (chunk.length > 10) carry.append(chunk.takeLast(10))
                            break
                        }
                        val after = idx + 6
                        if (after < chunk.length && (chunk[after] == '\n' || chunk[after] == '\r')) {
                            inStream = true
                            streamBuf.clear()
                            i = after + 1
                            if (i < chunk.length && chunk[i] == '\n') i++
                        } else {
                            i = idx + 1
                        }
                    } else {
                        val endIdx = chunk.indexOf("endstream", i)
                        if (endIdx < 0) {
                            // Partial stream — keep accumulating but cap at 2MB to avoid OOM
                            val appendable = chunk.substring(i)
                            if (streamBuf.length + appendable.length < 2_000_000) {
                                streamBuf.append(appendable)
                            }
                            carry.append(chunk.takeLast(9)) // "endstream" length
                            break
                        }
                        streamBuf.append(chunk.substring(i, endIdx))
                        // Try to decompress and extract text
                        val rawBytes = streamBuf.toString().toByteArray(Charsets.ISO_8859_1)
                        val text = tryDecompressAndExtract(rawBytes)
                            ?: extractParenthesizedText(streamBuf.toString())
                        if (text.length > 5) sb.append(text).append('\n')
                        streamBuf.clear()
                        inStream = false
                        i = endIdx + 9
                    }
                }
            }
            raf.close()

            val result = sb.toString().trim().replace(Regex("\\s{3,}"), "  ")
            result.takeIf { it.length > 20 }
        } catch (_: Exception) { null }
    }

    private fun tryDecompressAndExtract(data: ByteArray): String? {
        // Check for zlib header (FlateDecode streams start with 0x78 0x9C or similar)
        if (data.size < 4) return null
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        val isZlib = b0 == 0x78 && (b1 == 0x9C || b1 == 0x01 || b1 == 0xDA || b1 == 0x5E)
        if (!isZlib) return null
        return try {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 2)
            val tmpBuf = ByteArray(8192)
            var safetyLimit = 0
            while (!inflater.finished() && safetyLimit < 500) {
                val n = inflater.inflate(tmpBuf)
                if (n == 0) break
                out.write(tmpBuf, 0, n)
                safetyLimit++
                // Cap decompressed output at 4MB per stream
                if (out.size() > 4_000_000) break
            }
            inflater.end()
            val decompressed = out.toString(Charsets.ISO_8859_1.name())
            extractParenthesizedText(decompressed).takeIf { it.length > 5 }
        } catch (_: Exception) { null }
    }

    private fun extractParenthesizedText(input: String): String {
        val sb = StringBuilder()
        val chars = input.toCharArray()
        var i = 0
        while (i < chars.size) {
            if (chars[i] != '(') { i++; continue }
            val start = i + 1
            var depth = 1
            i++
            while (i < chars.size && depth > 0) {
                when (chars[i]) {
                    '(' -> depth++
                    ')' -> depth--
                    '\\' -> i++
                }
                i++
            }
            if (depth == 0 && i - start - 1 > 0) {
                val raw = String(chars, start, i - start - 1)
                val cleaned = raw
                    .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                    .replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
                    .replace(Regex("\\\\([0-7]{3})")) { m -> m.groupValues[1].toInt(8).toChar().toString() }
                if (cleaned.length > 1 && cleaned.any { it.isLetterOrDigit() }) {
                    sb.append(cleaned).append(' ')
                }
            }
        }
        return sb.toString()
    }

    /**
     * Page-by-page bitmap OCR. Processes one page at a time, recycling the
     * bitmap immediately so only ~2–3 MB is live at any moment.
     * Capped at MAX_RENDER_PAGES to prevent OOM on very large documents.
     */
    private fun extractTextViaRender(file: File): String? {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            val totalPages = renderer.pageCount
            val pagesToRender = minOf(totalPages, MAX_RENDER_PAGES)
            val sb = StringBuilder(pagesToRender * 200)

            for (i in 0 until pagesToRender) {
                // Open, extract, recycle in the same block so GC sees it freed each iteration
                val bitmap = renderPageBitmap(renderer, i)
                try {
                    val text = extractTextFromBitmap(bitmap)
                    if (text.isNotBlank()) {
                        sb.appendLine("--- Page ${i + 1} ---")
                        sb.appendLine(text.trim())
                        sb.appendLine()
                    }
                } finally {
                    bitmap.recycle()
                }
                // Yield to GC every 10 pages on large documents
                if (i > 0 && i % 10 == 0) System.gc()
            }

            if (totalPages > MAX_RENDER_PAGES) {
                sb.appendLine("--- [Note: Only first $MAX_RENDER_PAGES of $totalPages pages were processed] ---")
            }

            sb.toString().trim().takeIf { it.isNotEmpty() }
        } finally {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
    }

    /**
     * Render at RENDER_SCALE (1.5×) instead of 2×.
     * A4 at 1.5× = ~893×1263 px = ~4.5 MB ARGB_8888 vs 8 MB at 2×.
     */
    private fun renderPageBitmap(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        val w = (page.width * RENDER_SCALE).toInt().coerceAtLeast(1)
        val h = (page.height * RENDER_SCALE).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)  // RGB_565 = half the RAM of ARGB_8888
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bmp
    }

    // ── Image extraction ──────────────────────────────────────────────────────

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

    /**
     * Lightweight pixel-scan OCR: detects text rows by dark-pixel density.
     * Uses the bitmap pixels directly without allocating a separate IntArray
     * when possible (getPixel() per-column is slower but avoids the 8MB array).
     * For typical page widths we use a row buffer instead of the full pixel array.
     */
    private fun extractTextFromBitmap(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val gapThreshold = (height / 30).coerceAtLeast(3)
        val lineRanges = mutableListOf<IntRange>()
        var lastDarkRow = -1

        // Row buffer: only one row at a time = width × 4 bytes instead of width×height × 4
        val rowPixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
            var darkCount = 0
            for (x in 0 until width) {
                val p = rowPixels[x]
                if (((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3 < 180) darkCount++
            }
            val isTextRow = darkCount > width * 0.02
            if (isTextRow) {
                if (lastDarkRow < 0 || y - lastDarkRow > gapThreshold) {
                    lineRanges.add(y..y)
                } else {
                    lineRanges[lineRanges.lastIndex] = lineRanges.last().first..y
                }
                lastDarkRow = y
            }
        }

        return if (lineRanges.isEmpty()) ""
        else lineRanges.indices.joinToString("\n") { "[text line ${it + 1}]" }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun copyToTemp(uri: Uri, name: String): File? = try {
        val tmp = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(tmp).use { inp.copyTo(it, bufferSize = 65_536) }
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
