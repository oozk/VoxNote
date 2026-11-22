package com.offlinetranscriber.transcription

import com.offlinetranscriber.engine.SpeakerDiarizer

/** Attaches speaker numbers from diarization turns to transcript segments by time overlap. */
object SpeakerAssignment {
    data class Span(val startMs: Long, val endMs: Long)

    fun assign(segments: List<Span>, turns: List<SpeakerDiarizer.Turn>, maxGapMs: Long = 1000): List<Int?> = segments.map { s ->
        var best: Int? = null
        var bestOverlap = 0L
        for (t in turns) {
            val o = minOf(s.endMs, t.endMs) - maxOf(s.startMs, t.startMs)
            if (o > bestOverlap) { bestOverlap = o; best = t.speaker }
        }
        best ?: turns.minByOrNull { t -> gap(s, t) }?.takeIf { gap(s, it) <= maxGapMs }?.speaker
    }

    private fun gap(s: Span, t: SpeakerDiarizer.Turn): Long = when {
        t.endMs < s.startMs -> s.startMs - t.endMs
        t.startMs > s.endMs -> t.startMs - s.endMs
        else -> 0
    }

    /** Renumbers speakers 1, 2, 3… in order of first appearance. */
    fun renumber(speakers: List<Int?>): List<Int?> {
        val map = LinkedHashMap<Int, Int>()
        return speakers.map { sp -> sp?.let { map.getOrPut(it) { map.size + 1 } } }
    }
}
