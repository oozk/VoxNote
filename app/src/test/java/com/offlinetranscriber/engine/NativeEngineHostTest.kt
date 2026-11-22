package com.offlinetranscriber.engine

import com.offlinetranscriber.audio.ArrayPcmSource
import com.offlinetranscriber.audio.ChunkPlanner
import com.offlinetranscriber.audio.WavPcmSource
import com.offlinetranscriber.transcription.ChunkListener
import com.offlinetranscriber.transcription.ChunkResult
import com.offlinetranscriber.transcription.ChunkedTranscriber
import com.offlinetranscriber.transcription.VocabularyAssist
import com.offlinetranscriber.vocab.MatchKind
import com.offlinetranscriber.vocab.VocabTerm
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Runs the REAL native engine (the same transcriber_jni.cpp + whisper.cpp that ship in
 * the APK, compiled for the host) against a real Whisper model. No network is used.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*NativeEngineHostTest*' \
 *       -Pnative.lib.path=/abs/libtranscriber_jni.so -Ptest.model.path=/abs/ggml-tiny.bin
 *
 * Skipped automatically when the properties are not supplied (see docs/TESTING.md).
 */
class NativeEngineHostTest {
    companion object {
        private var engine: WhisperEngine? = null
        private lateinit var jfk: FloatArray

        @BeforeClass @JvmStatic
        fun setUp() {
            val lib = System.getProperty("native.lib.path")
            val model = System.getProperty("test.model.path")
            assumeTrue("native.lib.path / test.model.path not set: skipping native test", lib != null && model != null)
            WhisperNative.loadFrom(lib!!)
            println(WhisperEngine.systemInfo())
            engine = WhisperEngine.load(model!!)
            val wav = File(javaClass.classLoader!!.getResource("jfk.wav")!!.toURI())
            WavPcmSource(wav).use { jfk = it.read(0, it.sampleCount.toInt()) }
        }

        @AfterClass @JvmStatic
        fun tearDown() { engine?.release() }
    }

    private val e get() = engine!!

    @Test
    fun inspectorAcceptsRealModel() {
        val info = ModelInspector.inspect(File(System.getProperty("test.model.path")!!))
        assertTrue(info.message, info.compatible)
        assertEquals("tiny", info.sizeClass)
        assertEquals(true, info.multilingual)
        assertEquals(e.isMultilingual, info.multilingual)
    }

    @Test
    fun transcribesJfkWithAutoLanguageDetection() {
        val r = e.transcribe(jfk, EngineOptions(threads = 4, language = "auto"))
        val text = r.segments.joinToString(" ") { it.text }.lowercase()
        println("JFK: $text  [lang=${r.detectedLanguage}]")
        assertTrue(text, text.contains("ask not what your country can do for you"))
        assertEquals("en", r.detectedLanguage)
        assertTrue(r.segments.first().startMs >= 0)
        assertTrue(r.segments.last().endMs in 9_000..11_500)
    }

    @Test
    fun sileroVadSkipsSilenceAndKeepsTimestamps() {
        val vad = System.getProperty("vad.model.path")
        assumeTrue("vad.model.path not set", vad != null)
        // 8 s of silence, then JFK: with VAD the text starts after 8 s and nothing is invented in the silence
        val audio = FloatArray(16_000 * 8) + jfk
        val r = e.transcribe(audio, EngineOptions(language = "en", vad = true, vadModelPath = vad))
        val text = r.segments.joinToString(" ") { it.text }
        println("VAD: ${r.segments.map { it.startMs to it.endMs }} $text")
        assertTrue(text, text.lowercase().contains("what your country can do for you"))
        assertTrue("first segment should start near 8 s, was ${r.segments.first().startMs}", r.segments.first().startMs >= 7_000)
    }

    @Test
    fun reportsSegmentConfidence() {
        val r = e.transcribe(jfk, EngineOptions(language = "en"))
        val conf = r.segments.mapNotNull { it.confidence }
        println("confidence=$conf")
        assertTrue(conf.isNotEmpty() && conf.all { it in 0.3f..1f })
        assertTrue(e.capabilities.decoderBoost && e.capabilities.vad)
    }

    @Test
    fun countsTokens() {
        assertTrue(e.countTokens("Thermo Fisher, HPLC, GC-MS.") in 5..20)
    }

    @Test
    fun promptAndDecoderBoostRunAndKeepAccuracy() {
        val opts = EngineOptions(
            language = "en",
            initialPrompt = "Glossary: Thermo Fisher, HPLC, fellow Americans.",
            boostTerms = listOf("fellow Americans", "country"),
            boostStrength = 2f,
        )
        val text = e.transcribe(jfk, opts).segments.joinToString(" ") { it.text }.lowercase()
        println("JFK (prompt+boost): $text")
        assertTrue(text, text.contains("what your country can do"))
    }

    @Test
    fun longAudioIsChunkedWithContinuousTimestamps() {
        // ~72 s recording: JFK six times separated by 1 s of silence
        val silence = FloatArray(16_000)
        val parts = (1..6).flatMap { listOf(jfk, silence) }
        val long = FloatArray(parts.sumOf { it.size }).also { arr ->
            var o = 0
            for (p in parts) { p.copyInto(arr, o); o += p.size }
        }
        val src = ArrayPcmSource(long)
        val chunks = ChunkPlanner.plan(src, targetSeconds = 25)
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size >= 2)
        // chunks must tile the audio exactly
        assertEquals(0L, chunks.first().start)
        assertEquals(src.sampleCount, chunks.last().end)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }

        val results = ArrayList<ChunkResult>()
        var lastProgress = 0f
        ChunkedTranscriber(e).run(
            src, chunks, 0, EngineOptions(language = "auto"),
            VocabularyAssist(terms = listOf(VocabTerm(1, "Fellow-Americans", listOf("fellow Americans")))),
            object : ChunkListener {
                override fun onChunkCompleted(result: ChunkResult) { results.add(result) }
                override fun onProgress(fraction: Float) { lastProgress = fraction }
            },
        )
        assertEquals(chunks.size, results.size)
        val segs = results.flatMap { it.segments }
        segs.zipWithNext().forEach { (a, b) -> assertTrue("timestamps must not go backwards", b.startMs >= a.startMs - 50) }
        assertTrue("last segment ends late in the recording", segs.last().endMs > 60_000)
        val text = segs.joinToString(" ") { it.text }.lowercase()
        val occurrences = Regex("country can do for you").findAll(text).count()
        println("Long audio: ${chunks.size} chunks, ${segs.size} segments, $occurrences repetitions found")
        assertTrue("expected the phrase ~6 times, got $occurrences: $text", occurrences >= 5)
        assertEquals(1f, lastProgress)
        // the glossary prompt steers the decoder's spelling (decoder context, not acoustics)
        assertTrue("prompt should influence spelling: $text", text.contains("fellow-americans"))
        // remaining case differences are offered as suggestions, never silently applied
        val sugg = results.flatMap { it.suggestions }
        assertTrue("expected suggestions towards the keyword", sugg.any { it.replacement == "Fellow-Americans" })
    }

    @Test
    fun postProcessingSuggestsListedAlternativeWithoutPrompt() {
        val results = ArrayList<ChunkResult>()
        ChunkedTranscriber(e).run(
            ArrayPcmSource(jfk), listOf(com.offlinetranscriber.audio.AudioChunk(0, 0, jfk.size.toLong())), 0,
            EngineOptions(language = "en"),
            VocabularyAssist(terms = listOf(VocabTerm(7, "Fellow-Americans", listOf("fellow Americans"))), usePrompt = false),
            object : ChunkListener { override fun onChunkCompleted(result: ChunkResult) { results.add(result) } },
        )
        val r = results.single()
        assertEquals(null, r.promptUsed)
        val s = r.suggestions.single()
        assertEquals(MatchKind.ALTERNATIVE_SPELLING, s.kind)
        assertEquals("fellow Americans", s.original.let { o -> if (o.equals("fellow Americans", true)) "fellow Americans" else o })
        assertEquals(7L, s.termId)
        // original text is untouched by analysis
        assertTrue(r.segments.joinToString { it.text }.contains(s.original))
    }

    @Test
    fun cancellationStopsInference() {
        val long = FloatArray(16_000 * 60) { i -> jfk[i % jfk.size] }
        var thrown: Throwable? = null
        val t = Thread {
            try { e.transcribe(long, EngineOptions(language = "en")) } catch (ex: Throwable) { thrown = ex }
        }
        t.start()
        Thread.sleep(400)
        e.cancel()
        t.join(30_000)
        assertTrue("worker should have stopped", !t.isAlive)
        assertTrue("expected cancellation, got $thrown", thrown is TranscriptionCancelledException)
        // engine remains usable afterwards
        val ok = e.transcribe(jfk, EngineOptions(language = "en")).segments.joinToString(" ") { it.text }
        assertTrue(ok.lowercase().contains("country"))
    }
}
