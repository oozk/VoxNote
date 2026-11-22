package com.offlinetranscriber.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityTest {
    @Test fun damerauLevenshtein() {
        assertEquals(0, Similarity.damerauLevenshtein("abc", "abc"))
        assertEquals(1, Similarity.damerauLevenshtein("abc", "acb"))   // transposition
        assertEquals(3, Similarity.damerauLevenshtein("kitten", "sitting"))
        assertEquals(4, Similarity.damerauLevenshtein("", "abcd"))
    }

    @Test fun compactRemovesCaseAccentsAndPunctuation() {
        assertEquals("thermofisher", Similarity.compact("Thermo-Fisher"))
        assertEquals("cafe", Similarity.compact("Café"))
        assertEquals("gcms", Similarity.compact("GC–MS"))
    }

    @Test fun phoneticKeysGroupSimilarSounds() {
        assertEquals(Phonetic.key("Shimadzu"), Phonetic.key("Shimadsu"))
        assertEquals(Phonetic.key("Fisher"), Phonetic.key("Fischer"))
        assertEquals(Phonetic.key("Agilent"), Phonetic.key("Ajilent"))
        assertEquals(Phonetic.key("photometer"), Phonetic.key("fotometer"))
        assertNotEquals(Phonetic.key("Waters"), Phonetic.key("Agilent"))
        assertTrue(Phonetic.key("").isEmpty())
    }
}
