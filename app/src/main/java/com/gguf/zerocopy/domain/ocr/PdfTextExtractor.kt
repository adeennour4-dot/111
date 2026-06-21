package com.gguf.zerocopy.domain.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

class PdfTextExtractor(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun extractText(pdfUri: android.net.Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(pdfUri) ?: return null
            val tempFile = File(context.cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            val text = extractTextFromFile(tempFile)
            tempFile.delete()
            text
        } catch (e: Exception) {
            android.util.Log.e("PdfTextExtractor", "extractText failed", e)
            null
        }
    }

    fun extractTextFromFile(file: File): String? {
        return try {
            val doc = PDDocument.load(file)
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val text = stripper.getText(doc)
            doc.close()
            text.trim().ifEmpty { null }
        } catch (e: Exception) {
            android.util.Log.e("PdfTextExtractor", "PDFBox extract failed", e)
            fallbackOcrText(file)
        }
    }

    private fun fallbackOcrText(file: File): String? {
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            val textBuilder = StringBuilder()

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(
                    page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                textBuilder.appendLine("--- Page ${i + 1} ---")
                val image = InputImage.fromBitmap(bitmap, 0)
                try {
                    val result = Tasks.await(recognizer.process(image))
                    textBuilder.appendLine(result.text)
                } catch (_: Exception) {
                    textBuilder.appendLine("[OCR failed on page ${i + 1}]")
                }

                bitmap.recycle()
                page.close()
            }

            val result = textBuilder.toString().trim()
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            null
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { descriptor?.close() } catch (_: Exception) {}
        }
    }

    fun getPageCount(pdfUri: android.net.Uri): Int {
        return try {
            val inputStream = context.contentResolver.openInputStream(pdfUri) ?: return 0
            val tempFile = File(context.cacheDir, "temp_pdf_count.pdf")
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            val doc = PDDocument.load(tempFile)
            val count = doc.numberOfPages
            doc.close()
            tempFile.delete()
            count
        } catch (e: Exception) {
            0
        }
    }

    fun renderPage(pdfUri: android.net.Uri, pageIndex: Int): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(pdfUri) ?: return null
            val tempFile = File(context.cacheDir, "temp_pdf_render.pdf")
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            val descriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(descriptor)

            if (pageIndex >= renderer.pageCount) {
                renderer.close()
                descriptor.close()
                tempFile.delete()
                return null
            }

            val page = renderer.openPage(pageIndex)
            val bitmap = Bitmap.createBitmap(
                page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()
            renderer.close()
            descriptor.close()
            tempFile.delete()

            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
