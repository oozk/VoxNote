package com.offlinetranscriber.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionEngineTest {
    private val terms = listOf(
        VocabTerm(1, "Thermo Fisher", listOf("Thermofisher", "ThermoFisher Scientific", "Thermo fisher"), BuiltInCategories.COMPANY),
        VocabTerm(2, "HPLC", category = BuiltInCategories.ACRONYM),
        VocabTerm(3, "GC-MS", category = BuiltInCategories.ACRONYM),
        VocabTerm(4, "Shimadzu", category = BuiltInCategories.COMPANY),
        VocabTerm(5, "Agilent Technologies", category = BuiltInCategories.COMPANY),
        VocabTerm(6, "Waters Corporation", category = BuiltInCategories.COMPANY),
        VocabTerm(7, "Apple", category = BuiltInCategories.COMPANY),
        VocabTerm(8, "Spectrophotometer"),
    )
    private val engine = CorrectionEngine(terms)

    private fun suggest(text: String) = engine.analyze(0, text).first

    @Test fun listedAlternativeSpellingIsHighConfidence() {
        val s = suggest("We ordered it from thermofisher last week.").single()
        assertEquals("thermofisher", s.original)
        assertEquals("Thermo Fisher", s.replacement)
        assertEquals(MatchKind.ALTERNATIVE_SPELLING, s.kind)
        assertTrue(s.confidence >= 0.95)
    }

    @Test fun acronymCaseIsFixed() {
        val s = suggest("the hplc column was replaced").single()
        assertEquals("HPLC", s.replacement)
        assertEquals(MatchKind.CASE_OR_SPACING, s.kind)
    }

    @Test fun spacingAndHyphenVariants() {
        val s = suggest("we ran GC MS on the samples").single()
        assertEquals("GC MS", s.original)
        assertEquals("GC-MS", s.replacement)
    }

    @Test fun fuzzyMisspellingIsSuggestedButNotCertain() {
        val s = suggest("The Shimadsu instrument failed.").single()
        assertEquals("Shimadsu", s.original)
        assertEquals("Shimadzu", s.replacement)
        assertTrue(s.confidence in 0.6..0.94)
    }

    @Test fun soundsAlikeMultiWord() {
        val s = suggest("the spectro photo meter reading").single()
        assertEquals("Spectrophotometer", s.replacement)
    }

    @Test fun correctTextProducesNoSuggestionAndIsRecognized() {
        val (sugg, recognized) = engine.analyze(0, "Thermo Fisher and Agilent Technologies use HPLC.")
        assertTrue(sugg.isEmpty())
        assertEquals(setOf(1L, 5L, 2L), recognized)
    }

    @Test fun ordinaryWordsAreNotBlindlyReplaced() {
        // "apple" the fruit must not silently become the company "Apple"; "waters" alone is not "Waters Corporation"
        assertTrue(suggest("I ate an apple and drank some waters.").isEmpty())
        // short words are never fuzzy-matched
        assertTrue(suggest("the HPL was fine").isEmpty())
        // unrelated similar-length words are left alone
        assertTrue(suggest("The spectrum looked normal.").isEmpty())
    }

    @Test fun neverMatchesAcrossPunctuation() {
        assertTrue(suggest("It was thermo. Fisher said hello.").none { it.replacement == "Thermo Fisher" })
    }

    @Test fun possessiveKeptOutsideMatch() {
        val s = suggest("Shimadsu's new model").single()
        assertEquals("Shimadsu", s.original)
        assertEquals("Shimadzu's new model", CorrectionEngine.apply("Shimadsu's new model", listOf(s)))
    }

    @Test fun applyAndLocatePreserveOriginalOffsets() {
        val text = "thermofisher and the hplc"
        val sugg = suggest(text)
        assertEquals(2, sugg.size)
        assertEquals("Thermo Fisher and the HPLC", CorrectionEngine.apply(text, sugg))
        // after the user edits the text, the suggestion is located again
        val edited = "Note: thermofisher and the hplc"
        val s = sugg.first { it.original == "hplc" }
        assertEquals(edited.indexOf("hplc"), CorrectionEngine.locate(edited, "hplc", s.start))
        assertEquals(-1, CorrectionEngine.locate("nothing here", "hplc", 3))
    }

    @Test fun scalesToThousandsOfTerms() {
        val many = (1..5000).map { VocabTerm(100L + it, "Compound${it}ase", category = BuiltInCategories.SCIENTIFIC) } + terms
        val big = CorrectionEngine(many)
        val text = (1..200).joinToString(" ") { "the sample was measured with the shimadsu device at minute $it." }
        val start = System.nanoTime()
        val (s, _) = big.analyze(0, text)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(200, s.count { it.replacement == "Shimadzu" })
        assertTrue("took $ms ms", ms < 3000)
    }
}
