package com.offlinetranscriber.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Random-access source of 16 kHz mono samples. */
interface PcmSource : AutoCloseable {
    val sampleCount: Long
    val sampleRate: Int get() = WHISPER_SAMPLE_RATE
    fun read(start: Long, count: Int): FloatArray
}

/** A 16 kHz mono 16-bit WAV file read lazily, so hours of audio never sit in RAM at once. */
class WavPcmSource(file: File) : PcmSource {
    private val info: WavInfo = Wav.readInfo(file) ?: throw IllegalArgumentException("Not a WAV file: ${file.name}")
    private val raf = RandomAccessFile(file, "r")

    init {
        require(info.isWhisperReady) { "WAV must be 16 kHz mono 16-bit PCM (got ${info.sampleRate} Hz, ${info.channels} ch, ${info.bitsPerSample} bit)" }
    }

    override val sampleCount: Long = info.dataBytes / 2

    @Synchronized
    override fun read(start: Long, count: Int): FloatArray {
        val s = start.coerceIn(0, sampleCount)
        val n = count.toLong().coerceAtMost(sampleCount - s).toInt()
        if (n <= 0) return FloatArray(0)
        val bytes = ByteArray(n * 2)
        raf.seek(info.dataOffset + s * 2)
        raf.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { sb.get(it) / 32768f }
    }

    override fun close() = raf.close()
}

class ArrayPcmSource(private val samples: FloatArray) : PcmSource {
    override val sampleCount: Long get() = samples.size.toLong()
    override fun read(start: Long, count: Int): FloatArray {
        val s = start.toInt().coerceIn(0, samples.size)
        return samples.copyOfRange(s, (s + count).coerceAtMost(samples.size))
    }
    override fun close() {}
}
