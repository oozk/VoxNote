package com.offlinetranscriber.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class EnergyEndpointerTest {
    private val rnd = Random(3)
    private fun noise(sec: Double, amp: Float) = FloatArray((sec * 16000).toInt()) { (rnd.nextFloat() - 0.5f) * 2 * amp }
    private fun speech(sec: Double, amp: Float) = FloatArray((sec * 16000).toInt()) { i ->
        // syllable-like bursts at ~4 Hz on a 200 Hz carrier
        val env = (0.6 + 0.4 * sin(2 * PI * 4 * i / 16000)).toFloat()
        (sin(2 * PI * 200 * i / 16000) * amp * env).toFloat() + (rnd.nextFloat() - 0.5f) * amp * 0.1f
    }

    /** Feeds in 100 ms blocks and records (event, time in s). */
    private fun run(e: EnergyEndpointer, audio: FloatArray): List<Pair<EnergyEndpointer.Event, Double>> {
        val out = ArrayList<Pair<EnergyEndpointer.Event, Double>>()
        var t = 0
        while (t < audio.size) {
            val n = minOf(1600, audio.size - t)
            e.push(audio.copyOfRange(t, t + n))?.let { out.add(it to (t + n) / 16000.0) }
            t += n
        }
        return out
    }

    @Test fun detectsStartAndEndOfSpeech() {
        val audio = noise(1.0, 0.001f) + speech(2.0, 0.3f) + noise(3.0, 0.001f)
        val ev = run(EnergyEndpointer(endSilenceMs = 1500, noSpeechTimeoutMs = 0), audio)
        assertEquals(EnergyEndpointer.Event.SPEECH_START, ev[0].first)
        assertTrue("start at ${ev[0].second}", ev[0].second in 1.0..1.3)
        assertEquals(EnergyEndpointer.Event.SPEECH_END, ev[1].first)
        assertTrue("end at ${ev[1].second}", ev[1].second in 4.4..4.8)
    }

    @Test fun adaptsToLoudBackgroundNoise() {
        // fan noise at -30 dBFS, speech well above it
        val audio = noise(2.0, 0.05f) + speech(2.0, 0.6f) + noise(3.0, 0.05f)
        val ev = run(EnergyEndpointer(endSilenceMs = 1500, noSpeechTimeoutMs = 0), audio)
        assertEquals(listOf(EnergyEndpointer.Event.SPEECH_START, EnergyEndpointer.Event.SPEECH_END), ev.map { it.first })
    }

    @Test fun noSpeechTimeoutAndDigitalSilence() {
        val ev = run(EnergyEndpointer(endSilenceMs = 1500, noSpeechTimeoutMs = 3000), FloatArray(16000 * 5))
        assertEquals(EnergyEndpointer.Event.NO_SPEECH, ev.single().first)
    }

    @Test fun speechRangeTrimsLongSilence() {
        val audio = noise(10.0, 0.001f) + speech(3.0, 0.3f) + noise(10.0, 0.001f)
        val r = EnergyEndpointer.speechRange(audio)
        assertTrue("start ${r.first / 16000.0}", r.first / 16000.0 in 8.5..10.0)
        assertTrue("end ${r.last / 16000.0}", r.last / 16000.0 in 13.0..14.5)
        // short recordings are left alone
        val short = noise(1.0, 0.001f) + speech(1.0, 0.3f) + noise(1.0, 0.001f)
        assertEquals(short.indices, EnergyEndpointer.speechRange(short))
        assertTrue(EnergyEndpointer.speechRange(FloatArray(16000)).isEmpty())
    }
}
