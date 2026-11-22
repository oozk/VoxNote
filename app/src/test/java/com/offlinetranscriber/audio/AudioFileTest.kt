package com.offlinetranscriber.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun wavWriterProducesReadableWhisperReadyFile() {
        val f = tmp.newFile("a.wav")
        val samples = FloatArray(16000) { (sin(2 * PI * 440 * it / 16000) * 0.5).toFloat() }
        WavWriter(f, 16000).use { it.writeFloats(samples) }
        val info = Wav.readInfo(f)!!
        assertTrue(info.isWhisperReady)
        assertEquals(1000L, info.durationMs)
        WavPcmSource(f).use { src ->
            assertEquals(16000L, src.sampleCount)
            val back = src.read(100, 50)
            for (i in back.indices) assertEquals(samples[100 + i], back[i], 1e-4f)
        }
    }

    @Test fun headerPatchedPeriodicallySoCrashLeavesValidFile() {
        val f = tmp.newFile("crash.wav")
        val w = WavWriter(f, 16000)
        w.write(ShortArray(1600) { 1000 })
        w.flushHeader()
        w.write(ShortArray(1600) { 1000 })   // "crash": never closed
        val info = Wav.readInfo(f)!!
        // data size from header is 3200 bytes but file holds 6400 -> readInfo trusts the declared size
        assertEquals(1600L, info.frameCount)
    }

    @Test fun readsStreamingHeaderWithZeroSize() {
        val f = tmp.newFile("stream.wav")
        f.outputStream().use { o -> o.write(Wav.header(16000, 1, 16, 0)); o.write(ByteArray(3200)) }
        assertEquals(1600L, Wav.readInfo(f)!!.frameCount)
    }

    @Test fun rejectsNonWav() {
        val f = tmp.newFile("x.mp3").apply { writeBytes(ByteArray(100) { 0x49 }) }
        assertNull(Wav.readInfo(f))
    }

    @Test fun resampler48kTo16kPreservesToneAndLength() {
        val inRate = 48000
        val seconds = 2
        val input = FloatArray(inRate * seconds) { (sin(2 * PI * 300 * it / inRate) * 0.5).toFloat() }
        val r = StreamingResampler(inRate)
        val out = ArrayList<Float>()
        input.toList().chunked(4096).forEach { c -> r.process(c.toFloatArray()).forEach { out.add(it) } }
        r.flush().forEach { out.add(it) }
        assertTrue("length ${out.size}", abs(out.size - 16000 * seconds) <= 2)
        // compare with ideal 300 Hz tone at 16 kHz (skip edges)
        var err = 0.0
        for (i in 200 until 31800) err += abs(out[i] - (sin(2 * PI * 300 * i / 16000) * 0.5))
        assertTrue("mean error ${err / 31600}", err / 31600 < 0.02)
    }

    @Test fun resamplerRemovesFrequenciesAboveNewNyquist() {
        val inRate = 44100
        val input = FloatArray(inRate) { (sin(2 * PI * 15000 * it / inRate) * 0.5).toFloat() }  // 15 kHz: above 8 kHz
        val r = StreamingResampler(inRate)
        val out = r.process(input) + r.flush()
        val rms = ChunkPlanner.rms(out.copyOfRange(500, out.size - 500))
        assertTrue("aliasing rms $rms", rms < 0.05f)
    }

    @Test fun passthroughWhenAlready16k() {
        val r = StreamingResampler(16000)
        val x = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertArrayEquals(x, r.process(x), 0f)
    }

    @Test fun downmixStereo() {
        val mono = Downmix.pcm16ToMono(shortArrayOf(16384, -16384, 8192, 8192), 4, 2)
        assertArrayEquals(floatArrayOf(0f, 0.25f), mono, 1e-6f)
    }

    @Test fun chunkPlannerCutsInSilence() {
        val rate = 16000
        // 100 s of "speech" (noise) with a silent gap at 57.5-58.5 s
        val rnd = java.util.Random(1)
        val audio = FloatArray(rate * 100) { i -> if (i in (57.5 * rate).toInt() until (58.5 * rate).toInt()) 0f else (rnd.nextFloat() - 0.5f) * 0.5f }
        val chunks = ChunkPlanner.plan(ArrayPcmSource(audio), targetSeconds = 60, searchSeconds = 4)
        assertEquals(2, chunks.size)
        val cutSec = chunks[0].end.toDouble() / rate
        assertTrue("cut at $cutSec s", cutSec in 57.5..58.5)
        assertEquals(chunks[0].end, chunks[1].start)
        assertEquals(audio.size.toLong(), chunks[1].end)
    }

    @Test fun shortAudioIsOneChunkAndPlanRoundTrips() {
        val single = ChunkPlanner.plan(ArrayPcmSource(FloatArray(16000 * 30)), 120)
        assertEquals(1, single.size)
        val plan = ChunkPlanner.plan(ArrayPcmSource(FloatArray(16000 * 600)), 120)
        assertEquals(5, plan.size)
        assertEquals(plan, ChunkPlanner.decode(ChunkPlanner.encode(plan)))
    }

    @Test fun noTinyTrailingChunk() {
        val plan = ChunkPlanner.plan(ArrayPcmSource(FloatArray(16000 * 123)), 120)
        assertEquals(1, plan.size)
    }

    @Suppress("unused") private fun File.size() = length()
}
