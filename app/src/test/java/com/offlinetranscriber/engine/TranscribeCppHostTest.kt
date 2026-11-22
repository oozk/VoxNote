package com.offlinetranscriber.engine

import com.offlinetranscriber.audio.WavPcmSource
import com.offlinetranscriber.engine.tc.TranscribeNative
import com.offlinetranscriber.transcription.SpeakerAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The second engine (transcribe.cpp JNI, built for the host) against real GGUF models.
 *   -Ptc.lib.path=.../libtranscribe_jni.so -Ptc.model.path=parakeet-*.gguf -Ptc.diarize.path=sortformer-*.gguf
 */
class TranscribeCppHostTest {
    companion object {
        lateinit var jfk: FloatArray

        @BeforeClass @JvmStatic fun setUp() {
            val lib = System.getProperty("tc.lib.path")
            assumeTrue("tc.lib.path not set", lib != null)
            TranscribeNative.loadFrom(lib!!)
            println(TranscribeCppEngine.version())
            WavPcmSource(File(TranscribeCppHostTest::class.java.classLoader!!.getResource("jfk.wav")!!.toURI())).use {
                jfk = it.read(0, it.sampleCount.toInt())
            }
        }
    }

    @Test fun inspectorClassifiesGgufModels() {
        System.getProperty("tc.model.path")?.let {
            val i = ModelInspector.inspect(File(it))
            assertTrue(i.message, i.compatible)
            assertEquals(ENGINE_TRANSCRIBE, i.engine)
            assertEquals(ROLE_ASR, i.role)
            println("ASR: ${i.summary} / ${i.displayName} / ${i.languages}")
        }
        System.getProperty("tc.diarize.path")?.let {
            val i = ModelInspector.inspect(File(it))
            assertTrue(i.message, i.compatible)
            assertEquals(ROLE_DIARIZE, i.role)
        }
    }

    @Test fun parakeetTranscribesJfk() {
        val path = System.getProperty("tc.model.path")
        assumeTrue(path != null)
        val e = TranscribeCppEngine.load(path!!, 4)
        try {
            println("caps=${e.capabilities} type=${e.modelType} langs=${e.languages}")
            assertTrue(e.countTokens("Thermo Fisher") > 0)
            val r = e.transcribe(jfk, EngineOptions(language = "en", initialPrompt = "Glossary: HPLC.", boostTerms = listOf("HPLC")))
            val text = r.segments.joinToString(" ") { it.text }
            println("Parakeet: $text  segments=${r.segments.map { it.startMs to it.endMs }}")
            assertTrue(text, text.lowercase().contains("what your country can do for you"))
            assertTrue(r.segments.last().endMs in 8_000..11_500)
        } finally {
            e.release()
        }
    }

    @Test fun sortformerFindsTwoSpeakersInConversation() {
        val path = System.getProperty("tc.diarize.path")
        assumeTrue(path != null)
        // a voice and a pitch-shifted version of it (crude 2nd speaker): JFK, then JFK at 0.8x pitch/speed
        val slow = FloatArray((jfk.size * 1.25).toInt()) { i -> jfk[(i * 0.8).toInt().coerceAtMost(jfk.size - 1)] }
        val convo = jfk + FloatArray(8000) + slow
        val d = SpeakerDiarizer.load(path!!, 4)
        try {
            val turns = d.run(convo)
            println("Sortformer turns: $turns")
            assertTrue("expected speaker turns", turns.isNotEmpty())
            val spans = listOf(SpeakerAssignment.Span(1000, 10000), SpeakerAssignment.Span(13000, 24000))
            val assigned = SpeakerAssignment.assign(spans, turns)
            println("assigned=$assigned")
            assertTrue(assigned.all { it != null })
        } finally {
            d.release()
        }
    }
}
