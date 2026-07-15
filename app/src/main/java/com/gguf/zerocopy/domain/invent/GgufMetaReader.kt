package com.gguf.zerocopy.domain.invent

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Reads context_length from GGUF file header without loading the model
object GgufMetaReader {

    private const val GGUF_MAGIC = 0x46554747L // "GGUF"

    /**
     * Read context_length from GGUF metadata.
     * @return The context length, or null if not found / file unreadable / not a valid GGUF.
     *         Callers should use a sensible default (e.g., 2048) when null is returned.
     */
    fun readContextLength(path: String): Int? {
        return try {
            RandomAccessFile(File(path), "r").use { raf ->
                val buf4 = ByteArray(4)
                val buf8 = ByteArray(8)

                // Check magic
                raf.read(buf4)
                val magic = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (magic != GGUF_MAGIC) {
                    android.util.Log.w("GgufMetaReader", "File $path: invalid GGUF magic")
                    return null
                }

                // Version
                raf.read(buf4)
                val version = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int

                // Tensor count (skip)
                raf.read(buf8)

                // KV count
                raf.read(buf8)
                val kvCount = ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN).long
                if (kvCount <= 0 || kvCount > 50000) {
                    android.util.Log.w("GgufMetaReader", "File $path: suspicious KV count $kvCount")
                    return null
                }

                // Scan KV pairs for context_length
                for (i in 0 until kvCount) {
                    val keyLen = readU64(raf)
                    if (keyLen <= 0 || keyLen > 512) break
                    val keyBytes = ByteArray(keyLen.toInt())
                    raf.read(keyBytes)
                    val key = String(keyBytes)

                    val valueType = readU32(raf)
                    val value = readValue(raf, valueType, version)

                    if (key == "llm.context_length" && value != null) {
                        val ctx = (value as? Long)?.toInt()
                        if (ctx != null && ctx > 0) return ctx
                        android.util.Log.w("GgufMetaReader", "File $path: context_length=$ctx (ignored)")
                    }
                }
                android.util.Log.d("GgufMetaReader", "File $path: no context_length found in ${kvCount}kv pairs")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("GgufMetaReader", "File $path: failed to read", e)
            null
        }
    }

    private fun readValue(raf: RandomAccessFile, type: Int, version: Int): Any? {
        return when (type) {
            0 -> { val b = ByteArray(1); raf.read(b); b[0].toInt() and 0xFF } // UINT8 — mask to unsigned
            1 -> { val b = ByteArray(1); raf.read(b); b[0].toInt() } // INT8
            2 -> { val b = ByteArray(2); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF } // UINT16 — mask to unsigned
            3 -> { val b = ByteArray(2); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() } // INT16
            4 -> { val b = ByteArray(4); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL } // UINT32
            5 -> { val b = ByteArray(4); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() } // INT32
            6 -> { val b = ByteArray(4); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float } // FLOAT32
            7 -> { val b = ByteArray(1); raf.read(b); b[0] != 0.toByte() } // BOOL
            8 -> { // STRING
                val len = readU64(raf)
                if (len <= 0 || len > 4096) return null
                val bytes = ByteArray(len.toInt())
                raf.read(bytes)
                String(bytes)
            }
            9 -> { // ARRAY — skip
                val arrType = readU32(raf)
                val arrLen = readU64(raf)
                repeat(arrLen.toInt().coerceIn(0, 1024)) { readValue(raf, arrType, version) }
                null
            }
            10, 11 -> { val b = ByteArray(8); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long } // UINT64/INT64
            12 -> { val b = ByteArray(8); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).double } // FLOAT64
            else -> null
        }
    }

    private fun readU64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.read(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readU32(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.read(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
