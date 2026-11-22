package com.offlinetranscriber.transcription

import com.offlinetranscriber.audio.AudioChunk
import com.offlinetranscriber.audio.ArrayPcmSource
import com.offlinetranscriber.engine.EngineCapabilities
import com.offlinetranscriber.engine.EngineOptions
import com.offlinetranscriber.engine.EngineResult
import com.offlinetranscriber.engine.EngineSegment
import com.offlinetranscriber.engine.SpeakerDiarizer
import com.offlinetranscriber.engine.SpeechEngine
import com.offlinetranscriber.vocab.VocabTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Engine stand-in: reports how long the audio was and records the options it got. */
class FakeEngine(override val capabilities: EngineCapabilities) : SpeechEngine {
    override val modelPath = "fake"
    override val modelType = "fake"
    override val isMultilingual = true
    val seen = ArrayList<EngineOptions>()
    override fun transcribe(samples: FloatArray, options: EngineOptions, onSegment: ((EngineSegment) -> Unit)?): EngineResult {
        seen.add(options)
        return EngineResult(listOf(EngineSegment(0, samples.size / 16L, "seconds ${samples.size / 16000}")), "en")
    }
    override fun cancel() {}
    override fun progress() = 100
    override fun countTokens(text: String) = text.length / 4 + 1
    override fun release() {}

    companion object {
        fun caps(prompt: Boolean, boost: Boolean, vocab: Boolean) = EngineCapabilities("fake", prompt, boost, vocab, false, false, true)
    }
}

class SpeakerAndCaptionTest {
    @Test fun assignsSpeakersByOverlapAndRenumbers() {
        val turns = listOf(SpeakerDiarizer.Turn(0, 5000, 3), SpeakerDiarizer.Turn(5000, 9000, 1), SpeakerDiarizer.Turn(9500, 12000, 3))
        val segs = listOf(SpeakerAssignment.Span(0, 4000), SpeakerAssignment.Span(4500, 8000), SpeakerAssignment.Span(9000, 11000),
            SpeakerAssignment.Span(12500, 13000), SpeakerAssignment.Span(20000, 21000))
        val a = SpeakerAssignment.assign(segs, turns)
        assertEquals(listOf(3, 1, 3, 3, null), a)
        assertEquals(listOf(1, 2, 1, 1, null), SpeakerAssignment.renumber(a))
    }

    @Test fun vocabularyFollowsEngineCapabilities() {
        val terms = listOf(VocabTerm(1, "HPLC"))
        val src = ArrayPcmSource(FloatArray(16000 * 3))
        val chunks = listOf(AudioChunk(0, 0, src.sampleCount))
        val noop = object : ChunkListener { override fun onChunkCompleted(result: ChunkResult) {} }
        // Parakeet-like: no prompt, but a native vocabulary list
        val parakeet = FakeEngine(FakeEngine.caps(prompt = false, boost = false, vocab = true))
        ChunkedTranscriber(parakeet).run(src, chunks, 0, EngineOptions(vad = true, vadModelPath = "x"), VocabularyAssist(terms, boostStrength = 1.5f), noop)
        with(parakeet.seen.single()) {
            assertNull(initialPrompt)
            assertEquals(listOf("HPLC"), boostTerms)
            assertEquals(0f, boostStrength)
            assertEquals(false, vad)   // engine has no VAD
        }
        // whisper-like
        val whisper = FakeEngine(FakeEngine.caps(prompt = true, boost = true, vocab = false))
        ChunkedTranscriber(whisper).run(src, chunks, 0, EngineOptions(language = "en"), VocabularyAssist(terms, boostStrength = 1.5f, languageStarter = true), noop)
        with(whisper.seen.single()) {
            assertTrue(initialPrompt!!, initialPrompt!!.startsWith("Hello, how are you?"))
            assertTrue(initialPrompt!!.contains("Glossary: HPLC."))
            assertEquals(1.5f, boostStrength)
        }
    }

    @Test fun liveCaptionerCommitsUtterancesAndFlushesOnFinish() {
        val engine = FakeEngine(FakeEngine.caps(true, true, false))
        val cap = LiveCaptioner({ engine }, EngineOptions(), tickMs = 400)
        fun speech(sec: Double) = FloatArray((sec * 16000).toInt()) { i -> (sin(2 * PI * 200 * i / 16000) * 0.3 * (0.6 + 0.4 * sin(2 * PI * 4 * i / 16000))).toFloat() }
        fun quiet(sec: Double) = FloatArray((sec * 16000).toInt()) { (Math.random().toFloat() - 0.5f) * 0.002f }
        val stream = quiet(1.0) + speech(2.0) + quiet(1.5) + speech(1.0)
        var t = 0
        while (t < stream.size) { val n = minOf(1600, stream.size - t); cap.feed(stream.copyOfRange(t, t + n)); t += n; Thread.sleep(2) }
        cap.finish()
        val deadline = System.currentTimeMillis() + 5000
        while (cap.captions.value.lines.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val lines = cap.captions.value.lines
        assertEquals("captions: $lines", 2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("seconds 2") || lines[0].startsWith("seconds 3"))
        assertTrue(engine.seen.all { !it.timestamps })
    }
}
