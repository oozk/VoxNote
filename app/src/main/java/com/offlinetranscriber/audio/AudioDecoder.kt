package com.offlinetranscriber.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class UnsupportedAudioException(message: String) : Exception(message)

/**
 * Converts any audio file to the 16 kHz mono 16-bit WAV Whisper needs, entirely on
 * the device. Uses Android's built-in decoders (MediaExtractor + MediaCodec), which
 * cover WAV, MP3, M4A/AAC, FLAC, OGG Vorbis, Opus and the audio track of MP4/WebM/3GP.
 * WAV files are handled by our own parser (faster, supports 16/24/32-bit and float).
 */
object AudioDecoder {

    data class Result(val wav: File, val durationMs: Long, val decoded: Boolean)

    /**
     * @return [Result] whose [Result.wav] equals [input] when the input already is a
     *         16 kHz mono 16-bit WAV (no copy made).
     */
    fun toWhisperWav(input: File, output: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): Result {
        val info = Wav.readInfo(input)
        if (info != null && info.isWhisperReady) return Result(input, info.durationMs, decoded = false)
        if (info != null && (info.audioFormat == 1 || info.audioFormat == 3)) {
            return Result(output, decodeWav(input, info, output, isCancelled, onProgress), decoded = true)
        }
        return Result(output, decodeWithMediaCodec(input, output, isCancelled, onProgress), decoded = true)
    }

    private fun decodeWav(input: File, info: WavInfo, output: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): Long {
        val bytesPerSample = info.bitsPerSample / 8
        if (bytesPerSample !in 1..4) throw UnsupportedAudioException("Unsupported WAV sample size: ${info.bitsPerSample} bit")
        val frameBytes = bytesPerSample * info.channels
        val resampler = StreamingResampler(info.sampleRate)
        WavWriter(output, WHISPER_SAMPLE_RATE).use { writer ->
            RandomAccessFile(input, "r").use { raf ->
                raf.seek(info.dataOffset)
                val buf = ByteArray(frameBytes * 16384)
                var remaining = info.dataBytes
                while (remaining > 0) {
                    if (isCancelled()) throw CancellationException()
                    val toRead = minOf(buf.size.toLong(), remaining).toInt() / frameBytes * frameBytes
                    if (toRead <= 0) break
                    val n = raf.read(buf, 0, toRead)
                    if (n <= 0) break
                    remaining -= n
                    val frames = n / frameBytes
                    val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                    val mono = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until info.channels) sum += readSample(bb, (f * info.channels + c) * bytesPerSample, bytesPerSample, info.audioFormat == 3)
                        mono[f] = sum / info.channels
                    }
                    writer.writeFloats(resampler.process(mono))
                    onProgress(1f - remaining.toFloat() / info.dataBytes)
                }
            }
            writer.writeFloats(resampler.flush())
            return writer.samplesWritten * 1000 / WHISPER_SAMPLE_RATE
        }
    }

    private fun readSample(bb: ByteBuffer, pos: Int, bytes: Int, isFloat: Boolean): Float = when {
        isFloat && bytes == 4 -> bb.getFloat(pos)
        bytes == 1 -> ((bb.get(pos).toInt() and 0xff) - 128) / 128f
        bytes == 2 -> bb.getShort(pos) / 32768f
        bytes == 3 -> {
            val v = (bb.get(pos).toInt() and 0xff) or ((bb.get(pos + 1).toInt() and 0xff) shl 8) or (bb.get(pos + 2).toInt() shl 16)
            v / 8388608f
        }
        else -> bb.getInt(pos) / 2147483648f
    }

    private fun decodeWithMediaCodec(input: File, output: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioException("This file could not be opened as audio (${e.message ?: "unknown format"}).")
        }
        var codec: MediaCodec? = null
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("The file contains no audio track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw UnsupportedAudioException("No decoder on this device for $mime audio.")
            }
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var resampler = StreamingResampler(sampleRate)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            WavWriter(output, WHISPER_SAMPLE_RATE).use { writer ->
                while (!outputDone) {
                    if (isCancelled()) throw CancellationException()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            writer.writeFloats(resampler.flush())
                            resampler = StreamingResampler(sampleRate)
                        }
                        outIdx >= 0 -> {
                            if (bufferInfo.size > 0) {
                                val ob = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                                ob.position(bufferInfo.offset)
                                ob.limit(bufferInfo.offset + bufferInfo.size)
                                val mono = if (pcmFloat) {
                                    val fb = ob.asFloatBuffer()
                                    val arr = FloatArray(fb.remaining()); fb.get(arr)
                                    Downmix.floatToMono(arr, arr.size, channels)
                                } else {
                                    val sb = ob.asShortBuffer()
                                    val arr = ShortArray(sb.remaining()); sb.get(arr)
                                    Downmix.pcm16ToMono(arr, arr.size, channels)
                                }
                                writer.writeFloats(resampler.process(mono))
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                writer.writeFloats(resampler.flush())
                onProgress(1f)
                if (writer.samplesWritten == 0L) throw UnsupportedAudioException("The decoder produced no audio.")
                return writer.samplesWritten * 1000 / WHISPER_SAMPLE_RATE
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Duration without decoding (for display). */
    fun probeDurationMs(file: File): Long {
        Wav.readInfo(file)?.let { return it.durationMs }
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true && it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1000) ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            ex.release()
        }
    }
}
