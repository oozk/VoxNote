package com.offlinetranscriber.engine

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal streaming reader for GGUF metadata (key/value section only). Large arrays
 * (tokenizer vocabularies) are skipped, so only scalars, strings and short arrays are kept.
 */
object GgufReader {
    private const val MAX_KEYS = 100_000

    data class Header(val version: Int, val tensorCount: Long, val metadata: Map<String, Any>)

    fun read(input: InputStream): Header {
        val r = LeReader(DataInputStream(input.buffered(1 shl 16)))
        val magic = r.bytes(4)
        require(String(magic, Charsets.US_ASCII) == "GGUF") { "Not a GGUF file" }
        val version = r.u32().toInt()
        require(version in 2..3) { "Unsupported GGUF version $version" }
        val tensors = r.u64()
        val nkv = r.u64()
        require(nkv in 0..MAX_KEYS) { "Corrupt GGUF header" }
        val meta = LinkedHashMap<String, Any>()
        for (i in 0 until nkv) {
            val key = r.string(1 shl 16)
            val type = r.u32().toInt()
            val v = value(r, type, depth = 0)
            if (v != null) meta[key] = v
        }
        return Header(version, tensors, meta)
    }

    private fun value(r: LeReader, type: Int, depth: Int): Any? = when (type) {
        0 -> r.bytes(1)[0].toInt() and 0xff
        1 -> r.bytes(1)[0].toInt()
        2 -> r.u16()
        3 -> r.u16().toShort().toInt()
        4 -> r.u32()
        5 -> r.u32().toInt()
        6 -> java.lang.Float.intBitsToFloat(r.u32().toInt())
        7 -> r.bytes(1)[0].toInt() != 0
        8 -> r.string(1 shl 24)
        9 -> {
            val et = r.u32().toInt()
            val n = r.u64()
            require(n in 0..100_000_000) { "Corrupt GGUF array" }
            if (n <= 64 && depth == 0) (0 until n).map { value(r, et, depth + 1) } else { skipArray(r, et, n); null }
        }
        10, 11 -> r.u64()
        12 -> java.lang.Double.longBitsToDouble(r.u64())
        else -> throw IllegalArgumentException("Unknown GGUF value type $type")
    }

    private fun skipArray(r: LeReader, et: Int, n: Long) {
        val size = when (et) { 0, 1, 7 -> 1L; 2, 3 -> 2L; 4, 5, 6 -> 4L; 10, 11, 12 -> 8L; else -> -1L }
        if (size > 0) { r.skip(size * n); return }
        for (i in 0 until n) {
            if (et == 8) r.skip(r.u64()) else value(r, et, 1)
        }
    }

    private class LeReader(private val d: DataInputStream) {
        private val b8 = ByteArray(8)
        fun bytes(n: Int): ByteArray = ByteArray(n).also { d.readFully(it) }
        fun u16(): Int { d.readFully(b8, 0, 2); return (b8[0].toInt() and 0xff) or ((b8[1].toInt() and 0xff) shl 8) }
        fun u32(): Long { d.readFully(b8, 0, 4); return ByteBuffer.wrap(b8, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL }
        fun u64(): Long { d.readFully(b8, 0, 8); return ByteBuffer.wrap(b8, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long }
        fun string(max: Int): String {
            val n = u64()
            require(n in 0..max.toLong()) { "Corrupt GGUF string" }
            return String(bytes(n.toInt()), Charsets.UTF_8)
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = d.skip(left)
                if (s <= 0) { if (d.read() < 0) throw EOFException(); left-- } else left -= s
            }
        }
    }
}
