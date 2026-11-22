package com.offlinetranscriber.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanupTest {
    @Test fun removesEnglishFillersAndFixesCapitals() {
        assertEquals("So we ran the HPLC.", TextCleanup.clean("Um, so we, uh, ran the HPLC.", "en"))
        assertEquals("I think it works.", TextCleanup.clean("I think, hmm, it works.", "en"))
    }

    @Test fun languageGatedFillersAreKeptWhereTheyAreWords() {
        // Portuguese "um" = "a": must stay; German "um" = "around": must stay
        assertEquals("Comprei um carro.", TextCleanup.clean("Comprei um carro.", "pt"))
        assertEquals("Wir treffen uns um acht.", TextCleanup.clean("Wir treffen uns um acht.", "de"))
        assertEquals("Wir treffen uns um acht.", TextCleanup.clean("Wir treffen uns, äh, um acht.", "de"))
        // universal fillers go everywhere
        assertEquals("Comprei um carro.", TextCleanup.clean("Comprei uhm um carro.", "pt"))
    }

    @Test fun collapsesStuttersButKeepsDoubles() {
        assertEquals("I think so", TextCleanup.collapseStutters("I I I I think so"))
        assertEquals("that that is fine", TextCleanup.collapseStutters("that that is fine"))
        assertEquals("We go", TextCleanup.clean("We we we we go", "en"))
        assertEquals("We go", TextCleanup.clean("We, we, we go", "en"))
    }

    @Test fun replacementsWholeWordCaseAndLongestFirst() {
        val r = WordReplacer(listOf(
            ReplacementRule("acme corp", "ACME Corp."),
            ReplacementRule("new paragraph", "\\n\\n"),
            ReplacementRule("new", "NEW"),
            ReplacementRule("cat", "dog"),
            ReplacementRule("Mr", "Mister", matchCase = true),
        ))
        assertEquals("Order from ACME Corp. today.\n\nThe NEW one.", r.apply("Order from acme corp today. new paragraph The new one."))
        assertEquals("catalogue dog", r.apply("catalogue cat"))         // whole words only
        assertEquals("Mister Smith and mr Jones", r.apply("Mr Smith and mr Jones"))
        assertEquals("price $5 \\x", WordReplacer(listOf(ReplacementRule("cost", "$5 \\x"))).apply("price cost"))
    }

    @Test fun pipelineAppliesReplacementsThenCleanup() {
        val p = TextPipeline(WordReplacer(listOf(ReplacementRule("acme", "ACME"))), removeFillers = true)
        assertEquals("ACME is great.", p.process("Uh, acme is great.", "en"))
        assertEquals("Uh, ACME is great.", TextPipeline(WordReplacer(listOf(ReplacementRule("acme", "ACME"))), false).process("Uh, acme is great.", "en"))
    }

    @Test fun languageStarterOnlyBeforeContext() {
        assertTrue(LanguageStarters.forLanguage("de-DE")!!.startsWith("Hallo"))
        assertNull(LanguageStarters.forLanguage("xx"))
        val terms = listOf(VocabTerm(1, "HPLC"))
        val first = PromptBuilder.build(terms, emptySet(), null, true, starter = "Hello, how are you?")
        assertEquals("Hello, how are you? Glossary: HPLC.", first.prompt)
        val later = PromptBuilder.build(terms, emptySet(), "previous words", true, starter = "Hello, how are you?")
        assertEquals("Glossary: HPLC. previous words", later.prompt)
    }
}
