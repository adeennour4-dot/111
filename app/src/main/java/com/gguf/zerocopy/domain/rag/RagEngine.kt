package com.gguf.zerocopy.domain.rag

import android.content.Context
import android.net.Uri
import com.gguf.zerocopy.domain.ocr.PdfTextExtractor

/**
 * Lightweight on-device RAG (Retrieval-Augmented Generation) engine.
 *
 * No embedding model required — uses BM25-lite scoring to rank overlapping
 * text chunks against the user query, then injects only the top-k relevant
 * chunks into the prompt.
 */
class RagEngine(context: Context) {

    private val ocr = PdfTextExtractor(context)

    companion object {
        private const val CHUNK_SIZE        = 400
        private const val CHUNK_OVERLAP     = 80
        private const val MAX_CHUNKS_INJECT = 5
        private const val MAX_INJECT_CHARS  = 3000
        private const val MIN_SCORE         = 0.05f
    }

    data class Chunk(
        val text: String,
        val source: String,
        val pageHint: String
    )

    private val chunks      = mutableListOf<Chunk>()
    private val sourceNames = mutableListOf<String>()
    private val lock        = Any()

    // avgDocLen is now computed on demand each time — never stale after
    // documents are added post-first-retrieval (previously `by lazy` froze it).
    private val avgDocLen: Float
        get() = synchronized(lock) {
            if (chunks.isEmpty()) 250f
            else chunks.sumOf { tokenize(it.text).size.toDouble() }.toFloat() / chunks.size
        }

    val hasDocuments: Boolean       get() = synchronized(lock) { chunks.isNotEmpty() }
    val documentNames: List<String> get() = synchronized(lock) { sourceNames.toList() }

    fun clear() { synchronized(lock) { chunks.clear(); sourceNames.clear() } }

    fun ingest(uri: Uri, context: Context): IngestResult {
        val mime = context.contentResolver.getType(uri) ?: ""
        val name = getFileName(uri, context)
        return when {
            mime == "application/pdf" -> ingestPdf(uri, name)
            mime.startsWith("image/") -> ingestImage(uri, name)
            mime.startsWith("text/")  -> ingestText(uri, name, context)
            else                      -> IngestResult.Unsupported
        }
    }

    private fun ingestPdf(uri: Uri, name: String): IngestResult {
        val text = ocr.extractText(uri) ?: return IngestResult.Failed("PDF text extraction failed")
        addChunks(text, name)
        return IngestResult.Success(text, name)
    }

    private fun ingestImage(uri: Uri, name: String): IngestResult {
        val text = ocr.extractText(uri)
        return if (!text.isNullOrBlank()) {
            addChunks(text, name)
            IngestResult.Success(text, name)
        } else {
            IngestResult.ImageNoText(name)
        }
    }

    private fun ingestText(uri: Uri, name: String, context: Context): IngestResult {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return IngestResult.Failed("Could not open text file")
            if (text.isBlank()) return IngestResult.Failed("File is empty")
            addChunks(text, name)
            IngestResult.Success(text, name)
        } catch (e: Exception) {
            IngestResult.Failed(e.message ?: "Text read error")
        }
    }

    private fun addChunks(text: String, source: String) {
        val pageRegex = Regex("--- Page (\\d+) ---")
        val matches   = pageRegex.findAll(text).toList()

        val result = mutableListOf<Chunk>()
        if (matches.isEmpty()) {
            result.addAll(chunkText(text, source, ""))
        } else {
            val boundaries = matches.map { it.range.first to it.groupValues[1] } +
                             listOf(text.length to "")
            var prev = 0
            var prevPage = "1"
            for ((start, page) in boundaries) {
                val section = text.substring(prev, start).trim()
                if (section.isNotEmpty()) result.addAll(chunkText(section, source, "Page $prevPage"))
                prev = start
                if (page.isNotEmpty()) prevPage = page
            }
        }
        synchronized(lock) {
            chunks.addAll(result)
            if (!sourceNames.contains(source)) sourceNames.add(source)
        }
    }

    private fun chunkText(text: String, source: String, pageHint: String): List<Chunk> {
        if (text.length <= CHUNK_SIZE) {
            return if (text.isBlank()) emptyList()
            else listOf(Chunk(text.trim(), source, pageHint))
        }
        val result    = mutableListOf<Chunk>()
        val sentences = splitSentences(text)
        val buf       = StringBuilder()
        for (sentence in sentences) {
            if (buf.length + sentence.length > CHUNK_SIZE && buf.isNotEmpty()) {
                result.add(Chunk(buf.toString().trim(), source, pageHint))
                val keep = buf.toString().takeLast(CHUNK_OVERLAP)
                buf.clear().append(keep)
            }
            buf.append(sentence).append(' ')
        }
        if (buf.isNotBlank()) result.add(Chunk(buf.toString().trim(), source, pageHint))
        return result
    }

    private fun splitSentences(text: String): List<String> {
        val out  = mutableListOf<String>()
        val reg  = Regex("(?<=[.!?])\\s+|\\n{2,}")
        var last = 0
        reg.findAll(text).forEach { m ->
            val s = text.substring(last, m.range.last + 1)
            if (s.isNotBlank()) out.add(s)
            last = m.range.last + 1
        }
        if (last < text.length) {
            val tail = text.substring(last)
            if (tail.isNotBlank()) out.add(tail)
        }
        return out
    }

    fun retrieve(query: String): String {
        val snapshot: List<Chunk> = synchronized(lock) { chunks.toList() }
        if (snapshot.isEmpty()) return ""

        val queryTerms = tokenize(query)
        val avg = avgDocLen  // compute once per retrieve, not per chunk

        val selected: List<Pair<Chunk, Float>> = if (queryTerms.isEmpty()) {
            snapshot.take(2).map { it to 1f }
        } else {
            val scored = snapshot.map { it to bm25Score(queryTerms, it.text, snapshot, avg) }
            val relevant = scored
                .filter  { (_, s) -> s >= MIN_SCORE }
                .sortedByDescending { (_, s) -> s }
                .take(MAX_CHUNKS_INJECT)
            if (relevant.isEmpty()) snapshot.take(2).map { it to 1f } else relevant
        }
        return buildContextBlock(selected)
    }

    private fun bm25Score(
        queryTerms: List<String>,
        text: String,
        allChunks: List<Chunk>,
        avg: Float
    ): Float {
        val docTerms = tokenize(text)
        val docLen   = docTerms.size.toFloat().coerceAtLeast(1f)
        val k1 = 1.5f; val b = 0.75f
        return queryTerms.sumOf { term ->
            val tf = docTerms.count { it == term }.toFloat()
            if (tf == 0f) return@sumOf 0.0
            val df  = allChunks.count { tokenize(it.text).contains(term) }.toFloat().coerceAtLeast(1f)
            val idf = Math.log((allChunks.size.toFloat() - df + 0.5) / (df + 0.5) + 1.0)
            val tfNorm = (tf * (k1 + 1f)) / (tf + k1 * (1f - b + b * docLen / avg))
            idf * tfNorm
        }.toFloat()
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\w\u0600-\u06FF\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in STOP_WORDS }

    private fun buildContextBlock(scored: List<Pair<Chunk, Float>>): String {
        val sb = StringBuilder()
        sb.appendLine("[Relevant document excerpts]")
        var totalChars = 0
        scored.forEachIndexed { idx, (chunk, _) ->
            val label = buildString {
                append(chunk.source)
                if (chunk.pageHint.isNotEmpty()) append(" · ${chunk.pageHint}")
            }
            val entry = "[$label]\n${chunk.text}"
            if (totalChars + entry.length > MAX_INJECT_CHARS) return@forEachIndexed
            sb.appendLine(entry)
            if (idx < scored.size - 1) sb.appendLine("---")
            totalChars += entry.length
        }
        return sb.toString().trim()
    }

    private fun getFileName(uri: Uri, context: Context): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "document"
    } catch (_: Exception) { uri.lastPathSegment ?: "document" }

    private val STOP_WORDS = setOf(
        "the","a","an","and","or","but","in","on","at","to","for","of","with",
        "is","are","was","were","be","been","being","have","has","had","do",
        "does","did","will","would","could","should","may","might","shall",
        "this","that","these","those","i","you","he","she","it","we","they",
        "not","no","nor","so","yet","both","either","neither","each","few",
        "more","most","other","some","such","than","too","very","can","just",
        "what","how","when","where","who","which","why","all","any","its"
    )

    sealed class IngestResult {
        data class Success(val text: String, val sourceName: String) : IngestResult()
        data class ImageNoText(val sourceName: String) : IngestResult()
        data class Failed(val reason: String) : IngestResult()
        data object Unsupported : IngestResult()
    }
}
