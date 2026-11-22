package com.offlinetranscriber.transcription

import com.offlinetranscriber.audio.AudioChunk
import com.offlinetranscriber.audio.PcmSource
import com.offlinetranscriber.engine.EngineOptions
import com.offlinetranscriber.engine.EngineSegment
import com.offlinetranscriber.engine.SpeechEngine
import com.offlinetranscriber.engine.TranscriptionCancelledException
import com.offlinetranscriber.vocab.CorrectionEngine
import com.offlinetranscriber.vocab.CorrectionSuggestion
import com.offlinetranscriber.vocab.PromptBuilder
import com.offlinetranscriber.vocab.Similarity
import com.offlinetranscriber.vocab.VocabTerm
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one processed chunk, with timestamps relative to the start of the recording. */
data class ChunkResult(
    val chunk: AudioChunk,
    val segments: List<EngineSegment>,
    val detectedLanguage: String?,
    /** Suggestions keyed by index into [segments]. */
    val suggestions: List<CorrectionSuggestion>,
    val recognizedTermIds: Set<Long>,
    val promptUsed: String?,
)

data class VocabularyAssist(
    val terms: List<VocabTerm> = emptyList(),
    /** Put vocabulary into the decoder prompt. */
    val usePrompt: Boolean = true,
    /** Decoder logit boost strength (0 = off). */
    val boostStrength: Float = 0f,
    /** Run local text post-processing and produce suggestions. */
    val suggestCorrections: Boolean = true,
)

interface ChunkListener {
    fun onChunkStarted(index: Int, total: Int, prompt: String?) {}
    fun onLiveSegment(segment: EngineSegment) {}
    /** Called after each chunk; implementations persist the result so work can resume after a crash. */
    fun onChunkCompleted(result: ChunkResult)
    /** [fraction] 0..1 of the whole recording. */
    fun onProgress(fraction: Float) {}
}

/**
 * Transcribes long audio chunk by chunk: memory use is bounded by one chunk,
 * progress is persisted per chunk, and context (language, previous text, terms
 * already heard) is carried across chunk boundaries.
 */
class ChunkedTranscriber(private val engine: SpeechEngine) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        engine.cancel()
    }

    fun run(
        source: PcmSource,
        chunks: List<AudioChunk>,
        fromChunk: Int,
        options: EngineOptions,
        vocab: VocabularyAssist,
        listener: ChunkListener,
        previousText: String? = null,
        seenTermIds: Set<Long> = emptySet(),
        knownLanguage: String? = null,
    ) {
        cancelled.set(false)
        val total = source.sampleCount.coerceAtLeast(1)
        val corrector = if (vocab.suggestCorrections && vocab.terms.isNotEmpty()) CorrectionEngine(vocab.terms) else null
        var prevText = previousText
        val seen = seenTermIds.toMutableSet()
        var language = when {
            !engine.isMultilingual -> "en"
            options.language != "auto" -> options.language
            knownLanguage != null -> knownLanguage
            else -> "auto"
        }

        for (chunk in chunks.drop(fromChunk)) {
            if (cancelled.get()) throw TranscriptionCancelledException()
            val plan = PromptBuilder.build(
                terms = vocab.terms,
                seenInThisRecording = seen,
                previousText = prevText,
                useVocabulary = vocab.usePrompt,
                countTokens = { safeCount(it) },
            )
            listener.onChunkStarted(chunk.index, chunks.size, plan.prompt)

            val samples = source.read(chunk.start, chunk.length.toInt())
            val offsetMs = chunk.start * 1000 / source.sampleRate
            val chunkOptions = options.copy(
                language = language,
                initialPrompt = plan.prompt,
                boostTerms = if (vocab.boostStrength > 0f) plan.boostTerms else emptyList(),
                boostStrength = vocab.boostStrength,
            )

            val poller = ProgressPoller(engine) { p ->
                listener.onProgress(((chunk.start + chunk.length * p / 100f) / total).coerceIn(0f, 1f))
            }
            poller.start()
            val result = try {
                engine.transcribe(samples, chunkOptions) { seg ->
                    listener.onLiveSegment(seg.copy(startMs = seg.startMs + offsetMs, endMs = seg.endMs + offsetMs))
                }
            } finally {
                poller.stopPolling()
            }
            if (cancelled.get()) throw TranscriptionCancelledException()

            val chunkEndMs = chunk.end * 1000 / source.sampleRate
            val segments = cleanSegments(result.segments, plan.glossary).map {
                it.copy(
                    startMs = (it.startMs + offsetMs).coerceAtMost(chunkEndMs),
                    endMs = (it.endMs + offsetMs).coerceAtMost(chunkEndMs),
                )
            }
            if (language == "auto") language = result.detectedLanguage ?: "auto"

            val suggestions = ArrayList<CorrectionSuggestion>()
            if (corrector != null) {
                segments.forEachIndexed { i, s ->
                    val (sugg, recognized) = corrector.analyze(i, s.text)
                    suggestions.addAll(sugg)
                    seen.addAll(recognized)
                    sugg.filter { it.confidence >= 0.9 }.forEach { seen.add(it.termId) }
                }
            }
            prevText = segments.joinToString(" ") { it.text.trim() }.takeIf { it.isNotBlank() } ?: prevText

            listener.onChunkCompleted(
                ChunkResult(chunk, segments, result.detectedLanguage ?: language.takeIf { it != "auto" },
                    suggestions, seen.toSet(), plan.prompt)
            )
            listener.onProgress((chunk.end.toFloat() / total).coerceIn(0f, 1f))
        }
    }

    private fun safeCount(text: String): Int =
        runCatching { engine.countTokens(text) }.getOrNull()?.takeIf { it >= 0 } ?: PromptBuilder.estimateTokens(text)

    companion object {
        /**
         * Removes empty segments, prompt echoes (Whisper sometimes repeats the glossary
         * during silence) and runaway repetition loops.
         */
        fun cleanSegments(segments: List<EngineSegment>, glossary: String?): List<EngineSegment> {
            // only the glossary is checked: the previous-chunk context may legitimately repeat
            val promptCompact = glossary?.let { Similarity.compact(it) }.orEmpty()
            val out = ArrayList<EngineSegment>()
            var repeat = 0
            for (s in segments) {
                val text = s.text.trim()
                if (text.isEmpty()) continue
                val c = Similarity.compact(text)
                if (c.isEmpty()) continue
                if (text.startsWith("Glossary", ignoreCase = true)) continue
                if (promptCompact.isNotEmpty() && c.length >= 12 && promptCompact.contains(c)) continue
                val prev = out.lastOrNull()
                if (prev != null && Similarity.compact(prev.text) == c) {
                    repeat++
                    if (repeat >= 2) continue
                } else repeat = 0
                out.add(s)
            }
            return out
        }
    }
}

/** Polls native progress on a daemon thread (native code never calls back into the JVM for this). */
private class ProgressPoller(private val engine: SpeechEngine, private val onProgress: (Int) -> Unit) : Thread("progress-poller") {
    @Volatile private var running = true

    init { isDaemon = true }

    override fun run() {
        var last = -1
        while (running) {
            val p = engine.progress()
            if (p != last) { last = p; runCatching { onProgress(p) } }
            try { sleep(300) } catch (_: InterruptedException) { return }
        }
    }

    fun stopPolling() {
        running = false
        interrupt()
    }
}
