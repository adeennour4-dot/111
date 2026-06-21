package com.gguf.zerocopy.domain.inference

import java.io.File

class SimpleTokenizer(
    private val encoder: Map<String, Int>,
    private val decoder: Map<Int, String>
) {
    val vocabSize: Int get() = encoder.size

    fun encode(text: String): List<Long> {
        val ids = mutableListOf<Long>()
        val lower = text.lowercase()
        val words = lower.split(Regex("(?<=\\W)|(?=\\W)")).filter { it.isNotEmpty() }
        for (word in words) {
            val tokenId = encoder[word] ?: encoder.entries.find { (k, _) ->
                word.startsWith(k) || k.startsWith(word)
            }?.value ?: encoder["<unk>"] ?: encoder["[UNK]"] ?: 0
            ids.add(tokenId.toLong())
        }
        return ids
    }

    fun decode(id: Int): String = decoder[id] ?: ""

    fun findEosId(): Int = encoder["<eos>"]
        ?: encoder["</s>"]
        ?: encoder["<|endoftext|>"]
        ?: encoder["<EOS>"]
        ?: 0

    companion object {
        fun fromDir(dir: File): SimpleTokenizer? {
            val tokenizerJson = File(dir, "tokenizer.json")
            if (tokenizerJson.exists()) return fromHuggingFaceJson(tokenizerJson)
            val vocabJson = File(dir, "vocab.json")
            if (vocabJson.exists()) return fromVocabJson(vocabJson)
            val spModel = dir.listFiles()?.find { it.extension.lowercase() == "model" && it.name.contains("sentencepiece", ignoreCase = true) }
                ?: File(dir, "tokenizer.model").takeIf { it.exists() }
            if (spModel != null && spModel.exists()) return fromSentencePieceModel(spModel)
            return null
        }

        private fun fromHuggingFaceJson(file: File): SimpleTokenizer? {
            return try {
                val text = file.readText()
                val modelIdx = text.indexOf("\"model\"")
                if (modelIdx < 0) return null
                val vocabIdx = text.indexOf("\"vocab\"", modelIdx)
                if (vocabIdx < 0) return null
                val start = text.indexOf('{', vocabIdx)
                if (start < 0) return null

                val enc = mutableMapOf<String, Int>()
                val dec = mutableMapOf<Int, String>()
                var pos = start
                while (true) {
                    val keyStart = text.indexOf('"', pos)
                    if (keyStart < 0) break
                    val keyEnd = text.indexOf('"', keyStart + 1)
                    if (keyEnd < 0) break
                    val token = text.substring(keyStart + 1, keyEnd)
                    val colon = text.indexOf(':', keyEnd)
                    if (colon < 0) break
                    val valStart = text.indexOfAny(charArrayOf('0','1','2','3','4','5','6','7','8','9','-'), colon)
                    if (valStart < 0) break
                    val valEnd = text.indexOfAny(charArrayOf(',', '}', '\n', ' '), valStart)
                    val id = text.substring(valStart, if (valEnd < 0) text.length else valEnd).trim().toIntOrNull() ?: break
                    enc[token] = id
                    dec[id] = token
                    pos = if (valEnd < 0) text.length else valEnd
                    if (pos >= text.length || text[pos] == '}') break
                }
                if (enc.isEmpty()) null else SimpleTokenizer(enc, dec)
            } catch (_: Exception) { null }
        }

        private fun fromVocabJson(file: File): SimpleTokenizer? {
            return try {
                val text = file.readText()
                val enc = mutableMapOf<String, Int>()
                val dec = mutableMapOf<Int, String>()
                var pos = text.indexOf('{')
                if (pos < 0) return null
                pos++
                while (true) {
                    val keyStart = text.indexOf('"', pos)
                    if (keyStart < 0) break
                    val keyEnd = text.indexOf('"', keyStart + 1)
                    if (keyEnd < 0) break
                    val token = text.substring(keyStart + 1, keyEnd)
                    val colon = text.indexOf(':', keyEnd)
                    if (colon < 0) break
                    val valStart = text.indexOfAny(charArrayOf('0','1','2','3','4','5','6','7','8','9','-'), colon)
                    if (valStart < 0) break
                    val valEnd = text.indexOfAny(charArrayOf(',', '}', '\n', ' '), valStart)
                    val id = text.substring(valStart, if (valEnd < 0) text.length else valEnd).trim().toIntOrNull() ?: break
                    enc[token] = id
                    dec[id] = token
                    pos = if (valEnd < 0) text.length else valEnd
                    if (pos >= text.length || text[pos] == '}') break
                }
                if (enc.isEmpty()) null else SimpleTokenizer(enc, dec)
            } catch (_: Exception) { null }
        }

        fun fromSentencePieceModel(file: File): SimpleTokenizer? {
            return try {
                val pieces = parseSentencePieceModel(file.readBytes())
                if (pieces.isEmpty()) return null
                val enc = pieces.withIndex().associate { (i, p) -> p to i }
                val dec = pieces.withIndex().associate { (i, p) -> i to p }
                SimpleTokenizer(enc, dec)
            } catch (_: Exception) { null }
        }

        private fun parseSentencePieceModel(data: ByteArray): List<String> {
            val pieces = mutableListOf<String>()
            var pos = 0

            while (pos < data.size) {
                val tag = readVarintRaw(data, pos)
                pos += varintLen(tag)
                val fieldNum = tag shr 3
                val wireType = tag and 0x07

                when (wireType) {
                    0 -> {
                        pos += varintLen(readVarintRaw(data, pos))
                    }
                    1 -> pos += 8
                    2 -> {
                        val len = readVarintRaw(data, pos)
                        pos += varintLen(len)
                        val contentLen = len.toInt()
                        if (fieldNum == 1) {
                            val subEnd = pos + contentLen
                            while (pos < subEnd) {
                                val subTag = readVarintRaw(data, pos)
                                pos += varintLen(subTag)
                                val subField = subTag shr 3
                                val subWire = subTag and 0x07
                                when (subWire) {
                                    0 -> {
                                        pos += varintLen(readVarintRaw(data, pos))
                                    }
                                    2 -> {
                                        val strLen = readVarintRaw(data, pos)
                                        pos += varintLen(strLen)
                                        if (subField == 1) {
                                            val str = data.decodeToString(pos, pos + strLen.toInt())
                                            pieces.add(str)
                                        }
                                        pos += strLen.toInt()
                                    }
                                    5 -> pos += 4
                                    else -> return pieces
                                }
                            }
                        } else {
                            pos += contentLen
                        }
                    }
                    5 -> pos += 4
                    else -> return pieces
                }
            }
            return pieces
        }

        private fun readVarintRaw(data: ByteArray, pos: Int): Long {
            var result = 0L
            var shift = 0
            var i = pos
            while (i < data.size) {
                val byte = data[i].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                shift += 7
                i++
                if (byte and 0x80 == 0) return result
            }
            return result
        }

        private fun varintLen(value: Long): Int {
            if (value == 0L) return 1
            var v = value
            var count = 0
            while (v != 0L) {
                count++
                v = v shr 7
            }
            return count.coerceAtLeast(1)
        }

        private fun ByteArray.decodeToString(offset: Int, end: Int): String {
            val len = end - offset
            if (len <= 0) return ""
            val chars = CharArray(len)
            var charIdx = 0
            var i = offset
            while (i < end) {
                val b = this[i].toInt() and 0xFF
                when {
                    b < 0x80 -> {
                        chars[charIdx++] = b.toChar()
                        i++
                    }
                    b < 0xE0 && i + 1 < end -> {
                        val b2 = this[i + 1].toInt() and 0x3F
                        chars[charIdx++] = ((b and 0x1F) shl 6 or b2).toChar()
                        i += 2
                    }
                    b < 0xF0 && i + 2 < end -> {
                        val b2 = this[i + 1].toInt() and 0x3F
                        val b3 = this[i + 2].toInt() and 0x3F
                        chars[charIdx++] = ((b and 0x0F) shl 12 or (b2 shl 6) or b3).toChar()
                        i += 3
                    }
                    else -> {
                        i++
                    }
                }
            }
            return chars.concatToString(0, charIdx)
        }
    }
}
