package com.offlinetranscriber.transcription

import com.offlinetranscriber.audio.EnergyEndpointer
import com.offlinetranscriber.engine.EngineOptions
import com.offlinetranscriber.engine.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

data class Captions(
    /** Finished caption lines, oldest first. */
    val lines: List<String> = emptyList(),
    /** Replaceable hypothesis for the speech in progress. */
    val partial: String = "",
    /** Parts dropped because the device could not keep up (shown as "…"). */
    val skipped: Int = 0,
)

/**
 * Real-time captions from a 16 kHz stream with a batch model. Speech is cut into short
 * segments at pauses (or at [maxSegmentMs]).
 *  - Partials re-transcribe the current segment about every [tickMs], only when the worker is idle
 *    (latest wins), so a slow phone never builds a queue and drifts behind.
 *  - Finals are queued (FIFO) and never lost; if they pile up, waiting finals are merged into one
 *    run, which is nearly as cheap for fixed-window models such as Whisper.
 */
class LiveCaptioner(
    private val engine: () -> SpeechEngine?,
    private val options: EngineOptions,
    private val tickMs: Int = 1200,
    private val maxSegmentMs: Int = 8000,
    private val maxLines: Int = 200,
) {
    private val _captions = MutableStateFlow(Captions())
    val captions: StateFlow<Captions> = _captions.asStateFlow()

    private val lock = Object()
    private var segment = FloatArray(16_000 * 10)
    private var segLen = 0
    private var sinceTick = 0
    private var endpointer = EnergyEndpointer(endSilenceMs = 700, noSpeechTimeoutMs = 0)
    private val finals = ArrayDeque<FloatArray>()
    private var partialRequest: FloatArray? = null
    private val running = AtomicBoolean(true)
    private val worker = Thread(::work, "live-captions").apply { isDaemon = true; start() }

    /** Feed 16 kHz mono samples (any block size). Thread-safe, never blocks on inference. */
    fun feed(samples: FloatArray) {
        synchronized(lock) {
            if (!running.get()) return
            ensure(segLen + samples.size)
            samples.copyInto(segment, segLen)
            segLen += samples.size
            sinceTick += samples.size
            val ev = endpointer.push(samples)
            when {
                ev == EnergyEndpointer.Event.SPEECH_END || segLen >= maxSegmentMs * 16 -> finalizeLocked()
                endpointer.speechStarted && sinceTick >= tickMs * 16 -> { partialRequest = segment.copyOf(segLen); sinceTick = 0; lock.notifyAll() }
                !endpointer.speechStarted && segLen > 16_000 * 2 -> {   // keep only 1 s of leading silence
                    val keep = 16_000
                    segment.copyInto(segment, 0, segLen - keep, segLen); segLen = keep
                }
            }
        }
    }

    /** Ends the stream: the last segment is finalized and the worker exits once idle. */
    fun finish() {
        synchronized(lock) { if (segLen > 16_000 / 2 && endpointer.speechStarted) finalizeLocked(); running.set(false); lock.notifyAll() }
    }

    fun close() {
        running.set(false)
        synchronized(lock) { finals.clear(); lock.notifyAll() }
        engine()?.cancel()
    }

    private fun finalizeLocked() {
        if (endpointer.speechStarted) finals.addLast(segment.copyOf(segLen))
        segLen = 0
        sinceTick = 0
        partialRequest = null
        endpointer = EnergyEndpointer(endSilenceMs = 700, noSpeechTimeoutMs = 0)
        lock.notifyAll()
    }

    private fun ensure(n: Int) { if (n > segment.size) segment = segment.copyOf(maxOf(n, segment.size * 2)) }

    private fun work() {
        while (true) {
            var final: FloatArray? = null
            var partial: FloatArray? = null
            synchronized(lock) {
                while (finals.isEmpty() && partialRequest == null && running.get()) lock.wait(500)
                if (finals.isEmpty() && partialRequest == null && !running.get()) return
                if (finals.isNotEmpty()) {
                    // merge queued finals so a slow device catches up instead of dropping text
                    final = if (finals.size == 1) finals.removeFirst() else {
                        val all = finals.toList(); finals.clear()
                        val gap = FloatArray(16_000 / 5)
                        all.flatMapIndexed { i, a -> if (i == 0) listOf(a) else listOf(gap, a) }
                            .let { parts -> FloatArray(parts.sumOf { it.size }).also { out -> var o = 0; parts.forEach { p -> p.copyInto(out, o); o += p.size } } }
                    }
                } else {
                    partial = partialRequest
                    partialRequest = null
                }
            }
            val audio = final ?: partial ?: continue
            val text = runCatching { transcribe(audio) }.getOrNull()
            val cur = _captions.value
            _captions.value = if (final != null) {
                if (text.isNullOrBlank()) cur.copy(partial = "")
                else cur.copy(lines = (cur.lines + text).takeLast(maxLines), partial = "")
            } else {
                cur.copy(partial = text ?: cur.partial)
            }
        }
    }

    private fun transcribe(audio: FloatArray): String {
        val e = engine() ?: return ""
        if (audio.size < 16_000 / 3) return ""
        val r = e.transcribe(audio, options.copy(timestamps = false))
        return ChunkedTranscriber.cleanSegments(r.segments, null).joinToString(" ") { it.text.trim() }.trim()
    }

    /** The full caption text so far. */
    fun text(): String = _captions.value.lines.joinToString(" ")
}
