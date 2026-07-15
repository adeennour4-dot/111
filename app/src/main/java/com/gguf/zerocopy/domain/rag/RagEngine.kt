package com.gguf.zerocopy.domain.rag

import android.content.Context
import android.net.Uri
import com.gguf.zerocopy.domain.ocr.PdfTextExtractor

/**
 * Lightweight on-device RAG engine using BM25-lite scoring.
 *
 * Large-document fixes (100+ pages):
 * - ingestPdf/ingestText never hold the full document text after chunking
 * - bm25Score caches per-chunk term sets to avoid O(n²) retokenization
 * - avgDocLen is computed once per retrieve() call, not per-chunk
 * - MAX_TOTAL_CHUNKS caps memory use (~400 chars × 2000 chunks = 800 KB max)
 */
class RagEngine(context: Context, maxFiles: Int = 5) {

    private val ocr = PdfTextExtractor(context)
    private val _maxFiles = maxFiles.coerceIn(1, 50)

    companion object {
        private const val CHUNK_SIZE        = 400
        private const val CHUNK_OVERLAP     = 80
        // Hard cap: prevents unbounded RAM from very large documents.
        // 2000 chunks × ~400 chars = ~800 KB of text in RAM.
        private const val MAX_TOTAL_CHUNKS  = 2_000
        /** Maximum file size in bytes (60 MB). */
        private const val MAX_FILE_SIZE = 60L * 1024 * 1024
    }

    data class Chunk(
        val text: String,
        val source: String,
        val pageHint: String
    )

    private val chunks      = mutableListOf<Chunk>()
    private val sourceNames = mutableListOf<String>()
    // Pre-tokenized term sets for BM25 — built once on ingest, avoids retokenizing on every retrieve()
    private val chunkTerms  = mutableListOf<List<String>>()
    private val docCharCounts = mutableListOf<Int>()  // actual unique char count per document (not chunk sum)
    private val lock        = Any()

    val hasDocuments: Boolean       get() = synchronized(lock) { chunks.isNotEmpty() }
    val documentNames: List<String> get() = synchronized(lock) { sourceNames.toList() }

    /** Get stats about the current RAG index. */
    fun getStats(): RagStats = synchronized(lock) {
        RagStats(
            documentCount = sourceNames.size,
            chunkCount = chunks.size,
            totalChars = docCharCounts.sum(),
            sources = sourceNames.toList()
        )
    }

    data class RagStats(
        val documentCount: Int,
        val chunkCount: Int,
        val totalChars: Int,
        val sources: List<String>
    )

    fun clear() {
        synchronized(lock) {
            chunks.clear()
            chunkTerms.clear()
            sourceNames.clear()
        }
    }

    val fileCount: Int get() = synchronized(lock) { sourceNames.size }

    fun ingest(uri: Uri, context: Context): IngestResult {
        // Enforce max file limit
        synchronized(lock) {
            if (sourceNames.size >= _maxFiles)
                return IngestResult.Failed("Max $_maxFiles documents reached")
        }
        // Enforce file size limit
        val size = getFileSize(uri, context)
        if (size != null && size > MAX_FILE_SIZE) {
            val mb = size / (1024 * 1024)
            return IngestResult.Failed("File too large (${mb}MB). Maximum is ${MAX_FILE_SIZE / (1024 * 1024)}MB")
        }
        val mime = context.contentResolver.getType(uri) ?: ""
        val name = getFileName(uri, context)
        return when {
            mime == "application/pdf"  -> ingestPdf(uri, name)
            mime.startsWith("image/")  -> ingestImage(uri, name)
            mime.startsWith("text/")   -> ingestText(uri, name, context)
            else                       -> IngestResult.Unsupported
        }
    }

    private fun getFileSize(uri: Uri, context: Context): Long? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun ingestPdf(uri: Uri, name: String): IngestResult {
        // extractText() returns a potentially large String; we chunk it immediately
        // and let the local variable go out of scope so GC can reclaim it.
        val text = ocr.extractText(uri) ?: return IngestResult.Failed("PDF text extraction failed")
        val chunkCount = addChunksAndDiscard(text, name)
        return IngestResult.Success(chunkCount, name)
    }

    private fun ingestImage(uri: Uri, name: String): IngestResult {
        val text = ocr.extractText(uri)
        return if (!text.isNullOrBlank()) {
            val chunkCount = addChunksAndDiscard(text, name)
            IngestResult.Success(chunkCount, name)
        } else {
            IngestResult.ImageNoText(name)
        }
    }

    private fun ingestText(uri: Uri, name: String, context: Context): IngestResult {
        return try {
            // Read in a buffered manner; for very large text files we chunk on-the-fly
            val reader = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?: return IngestResult.Failed("Could not open text file")

            val chunks = mutableListOf<Chunk>()
            val buf = StringBuilder(CHUNK_SIZE * 2)
            var charsRead = 0L
            reader.use { r ->
                var line = r.readLine()
                while (line != null) {
                    buf.append(line).append('\n')
                    charsRead += line.length + 1 // +1 for the \n we appended
                    // Flush into chunks when buffer is full to avoid holding entire file
                    if (buf.length >= CHUNK_SIZE * 3) {
                        chunks.addAll(chunkText(buf.toString(), name, ""))
                        buf.clear()
                    }
                    line = r.readLine()
                }
                if (buf.isNotBlank()) chunks.addAll(chunkText(buf.toString(), name, ""))
            }

            if (chunks.isEmpty()) return IngestResult.Failed("File produced no text chunks")
            addPreparedChunks(chunks, name, originalCharCount = charsRead.toInt())
            IngestResult.Success(chunks.size, name)
        } catch (e: Exception) {
            IngestResult.Failed(e.message ?: "Text read error")
        }
    }

    /**
     * Chunk the text, add to the store, then let [text] be GC'd.
     * Never stores the full document text — only the chunks.
     */
    private fun addChunksAndDiscard(text: String, source: String): Int {
        val pageRegex = Regex("--- Page (\\d+) ---")
        val matches   = pageRegex.findAll(text).toList()
        val result    = mutableListOf<Chunk>()
        val actualLen = text.length

        if (matches.isEmpty()) {
            result.addAll(chunkText(text, source, ""))
        } else {
            val boundaries = matches.map { it.range.first to it.groupValues[1] } +
                             listOf(text.length to "")
            var prev = 0; var prevPage = "1"
            for ((start, page) in boundaries) {
                val section = text.substring(prev, start).trim()
                if (section.isNotEmpty()) result.addAll(chunkText(section, source, "Page $prevPage"))
                prev = start
                if (page.isNotEmpty()) prevPage = page
            }
        }

        addPreparedChunks(result, source, originalCharCount = actualLen)
        return result.size
    }

    private fun addPreparedChunks(newChunks: List<Chunk>, source: String, originalCharCount: Int = 0) {
        synchronized(lock) {
            val available = MAX_TOTAL_CHUNKS - chunks.size
            if (available <= 0) return
            val toAdd = newChunks.take(available)
            chunks.addAll(toAdd)
            // Pre-tokenize at ingest time so retrieve() is O(1) per chunk
            chunkTerms.addAll(toAdd.map { tokenize(it.text) })
            if (!sourceNames.contains(source)) {
                sourceNames.add(source)
                docCharCounts.add(originalCharCount)
            }
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

    /**
     * Retrieve relevant chunks for [query].
     * @param maxChunks Max chunks to inject into context (from SettingsManager.ragMaxChunks)
     * @param maxChars Max total chars of context to inject
     * @param minScore Minimum BM25 score to consider relevant
     */
    fun retrieve(
        query: String,
        maxChunks: Int = 5,
        maxChars: Int = 3000,
        minScore: Float = 0.05f
    ): String {
        val (snapshot, termSets) = synchronized(lock) {
            chunks.toList() to chunkTerms.toList()
        }
        if (snapshot.isEmpty()) return ""

        val queryTerms = tokenize(query)

        // avgDocLen: computed once for this retrieve() call
        val avgDocLen: Float = if (termSets.isEmpty()) 250f
            else termSets.sumOf { it.size.toDouble() }.toFloat() / termSets.size

        val selected: List<Pair<Chunk, Float>> = if (queryTerms.isEmpty()) {
            snapshot.take(2).map { it to 1f }
        } else {
            val scored = snapshot.mapIndexed { i, chunk ->
                // Use pre-tokenized term sets — no re-tokenization per retrieve()
                val terms = termSets.getOrElse(i) { tokenize(chunk.text) }
                chunk to bm25Score(queryTerms, terms, termSets, avgDocLen)
            }
            val relevant = scored
                .filter  { (_, s) -> s >= minScore }
                .sortedByDescending { (_, s) -> s }
                .take(maxChunks.coerceAtMost(chunks.size))
            if (relevant.isEmpty()) snapshot.take(2).map { it to 1f } else relevant
        }
        return buildContextBlock(selected, maxChars = maxChars)
    }

    /**
     * BM25 using pre-tokenized [docTerms] and [allTermSets].
     * allTermSets.count { it.contains(term) } is O(chunks) — unavoidable for IDF —
     * but no longer calls tokenize() inside the loop.
     */
    private fun bm25Score(
        queryTerms: List<String>,
        docTerms: List<String>,
        allTermSets: List<List<String>>,
        avg: Float
    ): Float {
        val docLen = docTerms.size.toFloat().coerceAtLeast(1f)
        val k1 = 1.5f; val b = 0.75f
        return queryTerms.sumOf { term ->
            val tf = docTerms.count { it == term }.toFloat()
            if (tf == 0f) return@sumOf 0.0
            val df  = allTermSets.count { it.contains(term) }.toFloat().coerceAtLeast(1f)
            val idf = Math.log((allTermSets.size - df + 0.5) / (df + 0.5) + 1.0)
            val tfNorm = (tf * (k1 + 1f)) / (tf + k1 * (1f - b + b * docLen / avg))
            idf * tfNorm
        }.toFloat()
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\w\u0600-\u06FF\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in STOP_WORDS }

    private fun buildContextBlock(scored: List<Pair<Chunk, Float>>, maxChars: Int = 3000): String {
        val sb = StringBuilder()
        sb.appendLine("[Relevant document excerpts]")
        var totalChars = 0
        scored.forEachIndexed { idx, (chunk, _) ->
            val label = buildString {
                append(chunk.source)
                if (chunk.pageHint.isNotEmpty()) append(" · ${chunk.pageHint}")
            }
            val entry = "[$label]\n${chunk.text}"
            if (totalChars + entry.length > maxChars) return@forEachIndexed
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
        /** [chunkCount] is how many chunks were added (not the full text) */
        data class Success(val chunkCount: Int, val sourceName: String) : IngestResult()
        data class ImageNoText(val sourceName: String) : IngestResult()
        data class Failed(val reason: String) : IngestResult()
        data object Unsupported : IngestResult()
    }
}
