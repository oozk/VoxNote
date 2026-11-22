package com.offlinetranscriber.audio

import kotlin.math.log10

/**
 * Energy-based speech endpointing on 20 ms frames of 16 kHz audio, against an adaptive
 * noise floor (a low percentile of the last 2 s). Because the floor follows the quiet parts
 * of the signal it works with quiet and loud microphones and steady background noise
 * (fan, car, café) without absolute thresholds. Time is counted in samples, so results do
 * not depend on thread timing.
 */
class EnergyEndpointer(
    /** Silence after speech that ends the utterance. */
    private val endSilenceMs: Int = 1500,
    /** Give up when no speech starts within this time (0 = never). */
    private val noSpeechTimeoutMs: Int = 8000,
) {
    enum class Event { SPEECH_START, SPEECH_END, NO_SPEECH }

    private val floorWindow = FloatArray(FLOOR_FRAMES)
    private var floorCount = 0
    private var floorPos = 0
    private val pending = FloatArray(FRAME)
    private var pendingLen = 0
    private var frames = 0L
    private var voicedRun = 0
    private var lastSpeechFrame = -1L
    private var smoothed: Float? = null

    var speechStarted = false
        private set
    var finished = false
        private set
    /** Most recent frame level in dBFS (for level meters). */
    var levelDb = -90f
        private set

    /** Feeds samples; returns an event when the state changes. */
    fun push(samples: FloatArray, count: Int = samples.size): Event? {
        var event: Event? = null
        var i = 0
        while (i < count) {
            val n = minOf(FRAME - pendingLen, count - i)
            System.arraycopy(samples, i, pending, pendingLen, n)
            pendingLen += n
            i += n
            if (pendingLen == FRAME) {
                pendingLen = 0
                frame(pending)?.let { if (event == null) event = it }
            }
        }
        return event
    }

    private fun frame(f: FloatArray): Event? {
        frames++
        if (finished) return null
        var e = 0.0
        for (v in f) e += v * v
        val db = (10 * log10(e / f.size + 1e-10)).toFloat()
        levelDb = db
        if (db < DIGITAL_SILENCE_DB) { voicedRun = 0; return timeouts() }  // stream warm-up zeros
        val s = smoothed?.let { 0.5f * it + 0.5f * db } ?: db
        smoothed = s
        floorWindow[floorPos] = s
        floorPos = (floorPos + 1) % FLOOR_FRAMES
        if (floorCount < FLOOR_FRAMES) floorCount++
        val floor = percentile(floorWindow, floorCount, FLOOR_PERCENTILE)
        val voiced = s > floor + SPEECH_DB && s > MIN_SPEECH_DB
        voicedRun = if (voiced) voicedRun + 1 else 0
        if (voicedRun >= MIN_VOICED_RUN) {
            lastSpeechFrame = frames
            if (!speechStarted) { speechStarted = true; return Event.SPEECH_START }
        }
        return timeouts()
    }

    private fun timeouts(): Event? {
        if (speechStarted && (frames - lastSpeechFrame) * 20 >= endSilenceMs) { finished = true; return Event.SPEECH_END }
        if (!speechStarted && noSpeechTimeoutMs > 0 && frames * 20 >= noSpeechTimeoutMs) { finished = true; return Event.NO_SPEECH }
        return null
    }

    companion object {
        const val FRAME = 320                // 20 ms at 16 kHz
        private const val FLOOR_FRAMES = 100 // 2 s
        private const val FLOOR_PERCENTILE = 0.1f
        private const val SPEECH_DB = 10f
        private const val MIN_SPEECH_DB = -55f
        private const val MIN_VOICED_RUN = 3
        private const val DIGITAL_SILENCE_DB = -90f

        private fun percentile(a: FloatArray, n: Int, p: Float): Float {
            val sorted = a.copyOf(n).also { it.sort() }
            return sorted[((n - 1) * p).toInt()]
        }

        /**
         * Range worth transcribing in a finished recording: long quiet stretches before the first
         * and after the last speech are cut (Whisper tends to invent text in long silence),
         * keeping a 1 s margin. Uses a floor over the whole recording. Returns the full range if
         * no speech is found, and an empty range for digital silence.
         */
        fun speechRange(samples: FloatArray): IntRange {
            if (samples.isEmpty()) return IntRange.EMPTY
            val nFrames = samples.size / FRAME
            if (nFrames == 0) return samples.indices
            val dbs = FloatArray(nFrames) { fi ->
                var e = 0.0
                for (k in fi * FRAME until (fi + 1) * FRAME) e += samples[k] * samples[k]
                (10 * log10(e / FRAME + 1e-10)).toFloat()
            }
            val valid = dbs.filter { it >= DIGITAL_SILENCE_DB }
            if (valid.isEmpty()) return IntRange.EMPTY
            val floor = valid.sorted()[((valid.size - 1) * FLOOR_PERCENTILE).toInt()]
            var run = 0
            var first = -1
            var last = -1
            for (fi in 0 until nFrames) {
                run = if (dbs[fi] > floor + SPEECH_DB && dbs[fi] > MIN_SPEECH_DB) run + 1 else 0
                if (run >= MIN_VOICED_RUN) { if (first < 0) first = fi - MIN_VOICED_RUN + 1; last = fi }
            }
            if (first < 0) return samples.indices
            val margin = 16_000
            val start = (first * FRAME - margin).coerceAtLeast(0)
            val end = ((last + 1) * FRAME + margin).coerceAtMost(samples.size) - 1
            // only trim when it saves at least 2 s; short gaps are left to the model
            val s = if (start < 2 * 16_000) 0 else start
            val e = if (samples.size - 1 - end < 2 * 16_000) samples.size - 1 else end
            return s..e
        }
    }
}
