package com.offlinetranscriber.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming mono resampler: windowed-sinc low-pass with a precomputed kernel table
 * (no trig in the inner loop), suitable for hours of audio on a phone.
 * Converts any input rate to [outRate] (16 kHz for Whisper).
 */
class StreamingResampler(private val inRate: Int, private val outRate: Int = WHISPER_SAMPLE_RATE) {
    private val ratio = inRate.toDouble() / outRate
    val isPassthrough get() = inRate == outRate

    private val halfTaps = 8
    // when downsampling, scale the kernel so the cut-off sits below the new Nyquist
    private val scale = if (inRate > outRate) outRate.toDouble() / inRate * 0.92 else 0.92
    private val phases = 256
    // kernel sampled at distance d = i / phases, for d in [0, halfTaps/scale]
    private val reach = halfTaps / scale
    private val table: FloatArray = FloatArray((reach * phases).toInt() + 2) { i ->
        val d = i.toDouble() / phases
        val x = d * scale
        val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
        val t = (d / reach).coerceAtMost(1.0)
        (sinc * (0.5 + 0.5 * cos(PI * t))).toFloat()
    }

    private var buf = FloatArray(0)  // retained input
    private var bufStart = 0L        // absolute index of buf[0]
    private var bufLen = 0
    private var totalIn = 0L
    private var nextOut = 0L

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (isPassthrough) return input.copyOf(count)
        append(input, count)
        return produce(flushing = false)
    }

    /** Emits the remaining samples at the end of the stream. */
    fun flush(): FloatArray = if (isPassthrough) FloatArray(0) else produce(flushing = true)

    private fun append(input: FloatArray, count: Int) {
        if (bufLen + count > buf.size) {
            buf = buf.copyOf(maxOf(bufLen + count, buf.size * 2, 4096))
        }
        System.arraycopy(input, 0, buf, bufLen, count)
        bufLen += count
        totalIn += count
    }

    private fun produce(flushing: Boolean): FloatArray {
        val r = reach.toInt() + 1
        val limit = if (flushing) {
            (totalIn / ratio).toLong()
        } else {
            // output n is ready when its right-most tap is available
            floor((totalIn - 1 - r) / ratio).toLong() + 1
        }
        val n = (limit - nextOut).coerceAtLeast(0).toInt()
        val out = FloatArray(n)
        for (k in 0 until n) {
            val center = (nextOut + k) * ratio
            val lo = maxOf(floor(center - reach).toLong() + 1, bufStart)
            val hi = minOf(floor(center + reach).toLong(), totalIn - 1)
            var acc = 0f
            var norm = 0f
            var idx = lo
            while (idx <= hi) {
                val d = kotlin.math.abs(center - idx) * phases
                val ti = d.toInt()
                if (ti < table.size - 1) {
                    val frac = (d - ti).toFloat()
                    val w = table[ti] + (table[ti + 1] - table[ti]) * frac
                    acc += buf[(idx - bufStart).toInt()] * w
                    norm += w
                }
                idx++
            }
            out[k] = if (norm != 0f) acc / norm else 0f
        }
        nextOut += n
        // discard input no longer needed
        val keepFrom = (floor(nextOut * ratio - reach).toLong()).coerceAtLeast(bufStart)
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return out
    }
}

/** Interleaved PCM -> mono float in [-1, 1]. */
object Downmix {
    fun pcm16ToMono(interleaved: ShortArray, count: Int, channels: Int): FloatArray {
        if (channels <= 1) return FloatArray(count) { interleaved[it] / 32768f }
        val frames = count / channels
        return FloatArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            sum / (32768f * channels)
        }
    }

    fun floatToMono(interleaved: FloatArray, count: Int, channels: Int): FloatArray {
        if (channels <= 1) return interleaved.copyOf(count)
        val frames = count / channels
        return FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            sum / channels
        }
    }
}
