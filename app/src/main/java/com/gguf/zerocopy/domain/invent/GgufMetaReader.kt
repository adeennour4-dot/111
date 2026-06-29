package com.gguf.zerocopy.domain.invent

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Reads context_length from GGUF file header without loading the model
object GgufMetaReader {

    private const val GGUF_MAGIC = 0x46554747L // "GGUF"

    fun readContextLength(path: String): Int {
        return try {
            RandomAccessFile(File(path), "r").use { raf ->
                val buf4 = ByteArray(4)
                val buf8 = ByteArray(8)

                // Check magic
                raf.read(buf4)
                val magic = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (magic != GGUF_MAGIC) return DEFAULT_CONTEXT

                // Version
                raf.read(buf4)
                val version = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).int

                // Tensor count
                raf.read(buf8)

                // KV count
                raf.read(buf8)
                val kvCount = ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN).long

                // Scan KV pairs for context_length
                for (i in 0 until kvCount) {
                    val keyLen = readU64(raf)
                    if (keyLen > 512) break
                    val keyBytes = ByteArray(keyLen.toInt())
                    raf.read(keyBytes)
                    val key = String(keyBytes)

                    val valueType = readU32(raf)
                    val value = readValue(raf, valueType, version)

                    if (key == "llm.context_length" && value != null) {
                        return (value as? Long)?.toInt() ?: DEFAULT_CONTEXT
                    }
                }
                DEFAULT_CONTEXT
            }
        } catch (e: Exception) {
            DEFAULT_CONTEXT
        }
    }

    private fun readValue(raf: RandomAccessFile, type: Int, version: Int): Any? {
        return when (type) {
            0 -> { val b = ByteArray(1); raf.read(b); b[0].toInt() } // UINT8
            1 -> { val b = ByteArray(1); raf.read(b); b[0].toInt() } // INT8
            2 -> { val b = ByteArray(2); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() } // UINT16
            3 -> { val b = ByteArray(2); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() } // INT16
            4 -> { val b = ByteArray(4); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL } // UINT32
            5 -> { val b = ByteArray(4); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() } // INT32
            6 -> { val b = ByteArray(4); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float } // FLOAT32
            7 -> { val b = ByteArray(1); raf.read(b); b[0] != 0.toByte() } // BOOL
            8 -> { // STRING
                val len = readU64(raf)
                val bytes = ByteArray(len.toInt().coerceAtMost(4096))
                raf.read(bytes)
                String(bytes)
            }
            9 -> { // ARRAY
                val arrType = readU32(raf)
                val arrLen = readU64(raf)
                repeat(arrLen.toInt().coerceAtMost(1024)) { readValue(raf, arrType, version) }
                null
            }
            10 -> { val b = ByteArray(8); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long } // UINT64
            11 -> { val b = ByteArray(8); raf.read(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long } // INT64
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

    const val DEFAULT_CONTEXT = 4096
}
