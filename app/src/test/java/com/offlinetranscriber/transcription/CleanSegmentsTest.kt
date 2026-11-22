package com.offlinetranscriber.transcription

import com.offlinetranscriber.engine.EngineSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanSegmentsTest {
    private fun seg(t: String, s: Long = 0) = EngineSegment(s, s + 1000, t)

    @Test fun dropsEmptyAndGlossaryEchoes() {
        val glossary = "Glossary: Thermo Fisher, HPLC, Mass Spectrometry, Chromatography."
        val out = ChunkedTranscriber.cleanSegments(listOf(
            seg(" "), seg("Glossary: Thermo Fisher"), seg("Thermo Fisher, HPLC, Mass Spectrometry"), seg("We used HPLC today."),
        ), glossary)
        assertEquals(listOf("We used HPLC today."), out.map { it.text })
    }

    @Test fun keepsRepeatsButCutsRunawayLoops() {
        val out = ChunkedTranscriber.cleanSegments(listOf(seg("Thank you."), seg("Thank you."), seg("Thank you."), seg("Thank you."), seg("Bye.")), null)
        assertEquals(listOf("Thank you.", "Thank you.", "Bye."), out.map { it.text })
    }
}
