package com.offlinetranscriber.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val WHISPER_SAMPLE_RATE = 16_000

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val audioFormat: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val isWhisperReady: Boolean
        get() = audioFormat == 1 && sampleRate == WHISPER_SAMPLE_RATE && channels == 1 && bitsPerSample == 16
    val frameCount: Long get() = dataBytes / (channels * bitsPerSample / 8).coerceAtLeast(1)
    val durationMs: Long get() = if (sampleRate == 0) 0 else frameCount * 1000 / sampleRate
}

object Wav {
    /** Parses RIFF/WAVE headers (tolerates extra chunks and streaming-size placeholders). */
    fun readInfo(file: File): WavInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") {
                return null
            }
            var fmt: IntArray? = null
            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                when (id) {
                    "fmt " -> {
                        val b = ByteArray(16)
                        raf.readFully(b)
                        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                        var format = bb.getShort(0).toInt() and 0xffff
                        val channels = bb.getShort(2).toInt()
                        val rate = bb.getInt(4)
                        val bits = bb.getShort(14).toInt()
                        if (format == 0xFFFE && size >= 40) { // WAVE_FORMAT_EXTENSIBLE: sub-format GUID
                            val ext = ByteArray(24)
                            raf.readFully(ext)
                            format = ByteBuffer.wrap(ext, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                            raf.seek(raf.filePointer + (size - 40))
                        } else {
                            raf.seek(raf.filePointer + (size - 16))
                        }
                        fmt = intArrayOf(format, channels, rate, bits)
                    }
                    "data" -> {
                        val f = fmt ?: return null
                        val available = raf.length() - raf.filePointer
                        val dataSize = if (size == 0L || size > available) available else size
                        return WavInfo(f[2], f[1], f[3], f[0], raf.filePointer, dataSize)
                    }
                    else -> raf.seek(raf.filePointer + size + (size and 1))
                }
            }
            null
        }
    }.getOrNull()

    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataBytes: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36 + dataBytes).coerceAtMost(0xffffffffL).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * bitsPerSample / 8).toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes.coerceAtMost(0xffffffffL).toInt())
        return bb.array()
    }
}

/**
 * Streams 16-bit mono PCM into a WAV file; header sizes are patched on [close].
 * Used by both the recorder and the decoder (so recordings survive a crash: a WAV
 * whose header was never patched is still readable by [Wav.readInfo]).
 */
class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int = 1) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(Wav.header(sampleRate, channels, 16, 0))
    }
    private var dataBytes = 0L
    private var scratch = ByteArray(0)

    val samplesWritten: Long get() = dataBytes / 2 / channels

    fun write(samples: ShortArray, count: Int = samples.size) {
        val needed = count * 2
        if (scratch.size < needed) scratch = ByteArray(needed)
        var j = 0
        for (i in 0 until count) {
            val s = samples[i].toInt()
            scratch[j++] = (s and 0xff).toByte()
            scratch[j++] = ((s shr 8) and 0xff).toByte()
        }
        raf.write(scratch, 0, needed)
        dataBytes += needed
    }

    /** Writes floats in [-1, 1] as 16-bit PCM. */
    fun writeFloats(samples: FloatArray, count: Int = samples.size) {
        val shorts = ShortArray(count) { i -> floatToPcm16(samples[i]) }
        write(shorts, count)
    }

    /** Patches the header so the file is valid up to now (cheap; used periodically). */
    fun flushHeader() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(Wav.header(sampleRate, channels, 16, dataBytes))
        raf.seek(pos)
    }

    override fun close() {
        flushHeader()
        raf.close()
    }
}

fun floatToPcm16(f: Float): Short {
    val v = (f * 32767f).toInt()
    return v.coerceIn(-32768, 32767).toShort()
}
