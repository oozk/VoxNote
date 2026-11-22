package com.offlinetranscriber.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test fun buildsGlossaryWithinTokenBudget() {
        val terms = (1..3000).map { VocabTerm(it.toLong(), "Keyword$it") }
        val plan = PromptBuilder.build(terms, emptySet(), null, useVocabulary = true)
        assertTrue(plan.prompt!!.startsWith("Glossary: "))
        assertTrue(PromptBuilder.estimateTokens(plan.prompt!!) <= PromptBuilder.MAX_PROMPT_TOKENS)
        assertTrue(plan.includedTermIds.size in 10..200)
        assertEquals(150, plan.boostTerms.size)
    }

    @Test fun priorityThenSeenThenFrequentTermsComeFirst() {
        val terms = listOf(
            VocabTerm(1, "Plain"), VocabTerm(2, "Frequent", hitCount = 9), VocabTerm(3, "Seen"), VocabTerm(4, "Starred", priority = true),
        )
        val ranked = PromptBuilder.rank(terms, seenInThisRecording = setOf(3L)).map { it.phrase }
        assertEquals(listOf("Starred", "Seen", "Frequent", "Plain"), ranked)
    }

    @Test fun includesPreviousChunkContext() {
        val plan = PromptBuilder.build(listOf(VocabTerm(1, "HPLC")), emptySet(), "and then we calibrated the column", true)
        assertEquals("Glossary: HPLC. and then we calibrated the column", plan.prompt)
        assertEquals("Glossary: HPLC.", plan.glossary)
    }

    @Test fun disabledVocabularyGivesNoGlossary() {
        val plan = PromptBuilder.build(listOf(VocabTerm(1, "HPLC")), emptySet(), null, useVocabulary = false)
        assertNull(plan.prompt)
        assertTrue(plan.boostTerms.isEmpty())
    }

    @Test fun usesExactTokenizerWhenGiven() {
        val plan = PromptBuilder.build((1..100).map { VocabTerm(it.toLong(), "T$it") }, emptySet(), null, true, countTokens = { 20 })
        // "Glossary:" costs 21 (20 + 1), each term 20 -> floor((200 - 21) / 20) = 8 terms
        assertEquals(8, plan.includedTermIds.size)
    }
}
