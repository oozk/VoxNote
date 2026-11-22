package com.offlinetranscriber.transcription

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinetranscriber.OfflineTranscriberApp
import com.offlinetranscriber.audio.WavPcmSource
import com.offlinetranscriber.audio.WavWriter
import com.offlinetranscriber.data.JobStatus
import com.offlinetranscriber.data.SuggestionStatus
import com.offlinetranscriber.engine.WhisperNative
import com.offlinetranscriber.export.ExportFormat
import com.offlinetranscriber.export.Exporters
import com.offlinetranscriber.vocab.BuiltInCategories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The app's real pipeline end to end, on the JVM via Robolectric, with the real native
 * engine and a real model: import model -> verify -> import audio -> transcribe with
 * vocabulary -> suggestions -> export, plus cancel-and-resume recovery.
 * Requires -Pnative.lib.path and -Ptest.model.path (skipped otherwise). No network is used.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class EndToEndPipelineTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val c get() = (ctx as OfflineTranscriberApp).container
    private lateinit var jfk: File

    @Before fun setUp() {
        val lib = System.getProperty("native.lib.path")
        val model = System.getProperty("test.model.path")
        assumeTrue(lib != null && model != null)
        WhisperNative.loadFrom(lib!!)
        // Robolectric reports no memory by default; emulate a 6 GB phone
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 6L shl 30; availMem = 3L shl 30 })
        jfk = File(javaClass.classLoader!!.getResource("jfk.wav")!!.toURI())
    }

    private suspend fun importModel(): Long {
        val id = c.models.importCopy(Uri.fromFile(File(System.getProperty("test.model.path")!!)), "tiny", { false }) { _, _ -> }
        val m = c.models.get(id)!!
        assertTrue(m.compatible)
        assertEquals("tiny", m.sizeClass)
        assertTrue(c.models.verify(m).isSuccess)
        assertEquals(1, c.models.get(id)!!.verified)
        assertEquals(id, c.settings.current().defaultModelId)
        return id
    }

    @Test fun fullPipelineWithVocabularyAndExport() = runBlocking(Dispatchers.Default) {
        importModel()
        c.vocabulary.save(null, "Fellow-Americans", listOf("fellow Americans"), BuiltInCategories.CUSTOM, null, null, false)
        val id = c.transcriptions.importAudio(Uri.fromFile(jfk))
        assertEquals(JobStatus.NOT_STARTED, c.transcriptions.get(id)!!.status)
        c.transcriptions.queue(id, null, "auto", emptyList())
        c.runner.runQueue()

        val job = c.transcriptions.get(id)!!
        assertEquals(job.error, JobStatus.COMPLETED, job.status)
        assertEquals("en", job.languageDetected)
        assertNotNull(job.vocabularyNote)
        val segs = c.transcriptions.segmentsOf(id)
        val text = segs.joinToString(" ") { it.text }
        println("E2E transcript: $text")
        assertTrue(text, text.lowercase().contains("what your country can do for you"))
        assertTrue(segs.all { it.originalText != null })   // original preserved
        val sugg = c.db.suggestions().withStatus(id, SuggestionStatus.PENDING) + c.db.suggestions().withStatus(id, SuggestionStatus.AUTO_APPLIED)
        assertTrue("expected vocabulary suggestions", sugg.isNotEmpty())
        c.transcriptions.acceptAll(id)
        assertTrue(c.transcriptions.segmentsOf(id).joinToString(" ") { it.text }.contains("Fellow-Americans"))
        // hit counts updated for adaptive ranking
        assertTrue(c.vocabulary.findDuplicate("Fellow-Americans")!!.hitCount >= 1)
        // exports
        val doc = c.transcriptions.exportDocument(id)
        assertTrue(Exporters.srt(doc).startsWith("1\n00:00:00,"))
        assertTrue(Exporters.exportToBytes(doc, ExportFormat.DOCX).isNotEmpty())
    }

    @Test fun cancelThenResumeContinuesWithoutDuplicates() = runBlocking(Dispatchers.Default) {
        importModel()
        c.settings.update { it.copy(chunkSeconds = 30) }
        // ~84 s recording: JFK x7 with 1 s pauses
        val long = File(ctx.filesDir, "long.wav")
        val clip = WavPcmSource(jfk).use { it.read(0, it.sampleCount.toInt()) }
        WavWriter(long, 16000).use { w -> repeat(7) { w.writeFloats(clip); w.writeFloats(FloatArray(16000)) } }
        val id = c.transcriptions.importAudio(Uri.fromFile(long))
        c.transcriptions.queue(id, null, "en", emptyList())

        val run = async { c.runner.runQueue() }
        withTimeout(120_000) { while ((c.transcriptions.get(id)?.nextChunk ?: 0) < 1) delay(50) }
        c.runner.cancelCurrent()
        run.await()
        val cancelled = c.transcriptions.get(id)!!
        assertEquals(JobStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.nextChunk in 1 until 3)
        val partial = c.transcriptions.segmentsOf(id).size
        assertTrue(partial > 0)

        c.transcriptions.queue(id, null, null, null)          // same settings -> resume
        assertEquals(cancelled.nextChunk, c.transcriptions.get(id)!!.nextChunk)
        c.runner.runQueue()
        val done = c.transcriptions.get(id)!!
        assertEquals(done.error, JobStatus.COMPLETED, done.status)
        val segs = c.transcriptions.segmentsOf(id)
        val text = segs.joinToString(" ") { it.text }.lowercase()
        // every repetition starts with "And so my fellow Americans": exactly 7 means nothing was
        // lost or transcribed twice across the cancel/resume boundary
        val n = Regex("fellow americans").findAll(text).count()
        println("Resume test: chunks=${done.chunkPlan?.split(',')?.size} segments=${segs.size} (partial before resume: $partial) occurrences=$n")
        assertEquals("occurrences in: $text", 7, n)
        segs.zipWithNext().forEach { (a, b) -> assertTrue("ordered timestamps", b.startMs >= a.startMs - 50) }
        assertEquals(segs.indices.toList(), segs.map { it.position })
    }
}
