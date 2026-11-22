package com.offlinetranscriber.audio

import kotlin.math.sqrt

/** Half-open sample range [start, end). */
data class AudioChunk(val index: Int, val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/**
 * Splits long audio into chunks of roughly [targetSeconds]. Each boundary is moved
 * to the quietest 100 ms frame within the [searchSeconds] before the nominal cut,
 * so chunks are split in pauses instead of in the middle of words.
 */
object ChunkPlanner {
    fun plan(source: PcmSource, targetSeconds: Int, searchSeconds: Int = 4): List<AudioChunk> {
        val rate = source.sampleRate
        val total = source.sampleCount
        val target = targetSeconds.coerceAtLeast(10).toLong() * rate
        if (total <= target + rate * 5) return listOf(AudioChunk(0, 0, total))
        val search = (searchSeconds.toLong() * rate).coerceAtMost(target / 2)
        val frame = rate / 10
        val chunks = ArrayList<AudioChunk>()
        var start = 0L
        var idx = 0
        while (start < total) {
            val nominal = start + target
            if (nominal + rate * 5 >= total) {  // avoid a tiny last chunk
                chunks.add(AudioChunk(idx, start, total))
                break
            }
            val windowStart = nominal - search
            val window = source.read(windowStart, search.toInt())
            var bestFrame = -1
            var bestEnergy = Double.MAX_VALUE
            var f = 0
            while ((f + 1) * frame <= window.size) {
                var e = 0.0
                for (i in f * frame until (f + 1) * frame) e += window[i] * window[i]
                // prefer later frames slightly on ties (keeps chunks near target length)
                if (e <= bestEnergy) { bestEnergy = e; bestFrame = f }
                f++
            }
            val cut = if (bestFrame < 0) nominal else windowStart + bestFrame * frame + frame / 2
            chunks.add(AudioChunk(idx++, start, cut))
            start = cut
        }
        return chunks
    }

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var s = 0.0
        for (v in samples) s += v * v
        return sqrt(s / samples.size).toFloat()
    }

    /** Encodes boundaries for persistence (resume after interruption). */
    fun encode(chunks: List<AudioChunk>): String = chunks.joinToString(",") { "${it.start}-${it.end}" }

    fun decode(text: String): List<AudioChunk> = text.split(',').filter { it.isNotBlank() }.mapIndexed { i, p ->
        val (a, b) = p.split('-').map { it.toLong() }
        AudioChunk(i, a, b)
    }
}
