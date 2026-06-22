package com.gguf.zerocopy.domain.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Extracts text from PDF files and images.
 * - Native/text PDFs: rendered page-by-page then OCR'd with ML Kit Text Recognition.
 * - Image files (JPEG, PNG, WebP, etc.): OCR'd directly.
 * - Falls back gracefully when ML Kit is unavailable.
 */
class PdfTextExtractor(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // ── Public API ───────────────────────────────────────────────────────────

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

    // ── PDF extraction ───────────────────────────────────────────────────────

    private fun extractFromPdfUri(uri: Uri): String? {
        val tempFile = copyToTemp(uri, "pdf_${System.currentTimeMillis()}.pdf") ?: return null
        return try {
            extractFromPdfFile(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun extractFromPdfFile(file: File): String? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer   = PdfRenderer(descriptor)
            val sb = StringBuilder()
            for (i in 0 until renderer.pageCount) {
                val bitmap = renderPageBitmap(renderer, i)
                val text   = ocrBitmap(bitmap)
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
        val page   = renderer.openPage(index)
        // 2× resolution for better OCR accuracy
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    // ── Image extraction ─────────────────────────────────────────────────────

    private fun extractFromImageUri(uri: Uri): String? {
        val bitmap = decodeBitmap(uri) ?: return null
        return try {
            ocrBitmap(bitmap).trim().takeIf { it.isNotEmpty() }
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

    // ── ML Kit OCR ───────────────────────────────────────────────────────────

    /**
     * Synchronous wrapper around ML Kit's async text recognition.
     * Blocks the calling thread (always called from IO dispatcher in this app).
     */
    private fun ocrBitmap(bitmap: Bitmap): String {
        val latch  = CountDownLatch(1)
        var result = ""
        val image  = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                result = visionText.text
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }
        // Wait up to 15 s per page; PDF pages are usually done in < 2 s
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
            val fd  = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val rnd = PdfRenderer(fd)
            try { block(rnd) } finally { runCatching { rnd.close() }; runCatching { fd.close() } }
        } catch (_: Exception) { null } finally { tmp.delete() }
    }
}
