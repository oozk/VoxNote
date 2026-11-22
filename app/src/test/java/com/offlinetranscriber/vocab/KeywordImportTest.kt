package com.offlinetranscriber.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordImportTest {
    private val pasted = """
        Thermo Fisher
        Mass Spectrometry
        Chromatography
        HPLC
        GC-MS
        Agilent Technologies
        Shimadzu
        Waters Corporation
        Spectrophotometer
    """.trimIndent()

    @Test fun bulkPasteFromSpecificationAddsAllNine() {
        val parsed = KeywordImport.parseLines(pasted)
        assertEquals(9, parsed.size)
        assertEquals("Thermo Fisher", parsed[0].phrase)
        assertEquals("GC-MS", parsed[4].phrase)
        val preview = KeywordImport.preview(parsed, emptyList())
        assertEquals(9, preview.toAdd)
        assertEquals(0, preview.duplicates)
    }

    @Test fun ignoresBlankLinesCommentsAndBullets() {
        val parsed = KeywordImport.parseLines("# my list\n\n- HPLC\n* GC-MS\n• Shimadzu\n1. Agilent\n   \n2) Waters\n")
        assertEquals(listOf("HPLC", "GC-MS", "Shimadzu", "Agilent", "Waters"), parsed.map { it.phrase })
    }

    @Test fun lineSyntaxWithAlternativesAndCategory() {
        val p = KeywordImport.parseLines("Thermo Fisher | Thermofisher; ThermoFisher Scientific; Thermo fisher | Company name\nHPLC = high performance liquid chromatography, H.P.L.C.")
        assertEquals("Thermo Fisher", p[0].phrase)
        assertEquals(listOf("Thermofisher", "ThermoFisher Scientific", "Thermo fisher"), p[0].alternatives)
        assertEquals("Company name", p[0].category)
        assertEquals(BuiltInCategories.COMPANY, BuiltInCategories.normalize(p[0].category))
        assertEquals(listOf("high performance liquid chromatography", "H.P.L.C."), p[1].alternatives)
    }

    @Test fun detectsDuplicatesCaseAndSpacingInsensitive() {
        val existing = listOf(ExistingKeyword(1, "Thermo Fisher", listOf("Thermofisher")))
        val parsed = KeywordImport.parseLines("thermo fisher\nThermo-Fisher | Thermo Fischer\nHPLC\nhplc\nH P L C")
        val pv = KeywordImport.preview(parsed, existing)
        assertEquals(ImportAction.SKIP_DUPLICATE, pv.items[0].action)            // same as existing
        assertEquals(ImportAction.MERGE_ALTERNATIVES, pv.items[1].action)        // brings a new spelling
        assertEquals(listOf("Thermo Fischer"), pv.items[1].keyword.alternatives)
        assertEquals(ImportAction.ADD, pv.items[2].action)
        assertEquals(ImportAction.SKIP_DUPLICATE, pv.items[3].action)            // duplicate within the import
        assertEquals(ImportAction.SKIP_DUPLICATE, pv.items[4].action)
        assertEquals(1, pv.toAdd)
    }

    @Test fun csvWithHeaderQuotesAndSemicolonAlternatives() {
        val csv = "Keyword,Alternative spellings,Category,Notes\r\n" +
            "\"Thermo Fisher\",\"Thermofisher; Thermo fisher\",Company name,\"Scientific equipment, manufacturer\"\r\n" +
            "HPLC,,Acronyms,\r\n" +
            "\"He said \"\"hi\"\"\",,,\r\n"
        val p = KeywordImport.parseCsv(csv)
        assertEquals(3, p.size)
        assertEquals(listOf("Thermofisher", "Thermo fisher"), p[0].alternatives)
        assertEquals("Scientific equipment, manufacturer", p[0].notes)
        assertEquals("Acronyms", p[1].category)
        assertEquals("He said \"hi\"", p[2].phrase)
    }

    @Test fun csvSemicolonDelimitedWithoutHeader() {
        val p = KeywordImport.parseCsv("Shimadzu;Shimadsu|Shimazu;Company names\nWaters Corporation;;Company names\n")
        assertEquals(2, p.size)
        assertEquals(listOf("Shimadsu", "Shimazu"), p[0].alternatives)
        assertEquals("Company names", p[1].category)
    }

    @Test fun autoDetectsCsvAndHandlesBom() {
        val p = KeywordImport.parseAuto("﻿keyword,category\nAgilent,Company names\n", "export.csv")
        assertEquals("Agilent", p.single().phrase)
        val txt = KeywordImport.parseAuto("﻿Agilent\nShimadzu", "list.txt")
        assertEquals(listOf("Agilent", "Shimadzu"), txt.map { it.phrase })
    }

    @Test fun thousandsOfKeywordsPreviewQuickly() {
        val text = (1..5000).joinToString("\n") { "Term number $it" } + "\nTerm number 17"
        val start = System.nanoTime()
        val pv = KeywordImport.preview(KeywordImport.parseLines(text), (1..2000L).map { ExistingKeyword(it, "Existing $it", emptyList()) })
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(5000, pv.toAdd)
        assertEquals(1, pv.duplicates)
        assertTrue("took $ms ms", ms < 2000)
    }

    @Test fun exportRoundTripsThroughImport() {
        val rows = listOf(
            KeywordExport.Row("Thermo Fisher", listOf("Thermofisher", "Thermo fisher"), BuiltInCategories.COMPANY, null, "Lab, equipment"),
            KeywordExport.Row("HPLC", emptyList(), BuiltInCategories.ACRONYM, "aitch-pee-el-see", null),
        )
        val fromCsv = KeywordImport.parseCsv(KeywordExport.toCsv(rows))
        assertEquals(rows.map { it.phrase }, fromCsv.map { it.phrase })
        assertEquals(rows[0].alternatives, fromCsv[0].alternatives)
        assertEquals("Lab, equipment", fromCsv[0].notes)
        assertEquals("aitch-pee-el-see", fromCsv[1].pronunciation)
        val fromTxt = KeywordImport.parseLines(KeywordExport.toTxt(rows))
        assertEquals(rows.map { it.phrase }, fromTxt.map { it.phrase })
        assertEquals(rows[0].alternatives, fromTxt[0].alternatives)
        assertEquals(BuiltInCategories.ACRONYM, fromTxt[1].category)
    }

    @Test fun guessesCategories() {
        assertEquals(BuiltInCategories.ACRONYM, BuiltInCategories.guess("HPLC"))
        assertEquals(BuiltInCategories.COMPANY, BuiltInCategories.guess("Agilent Technologies"))
        assertEquals(BuiltInCategories.CUSTOM, BuiltInCategories.guess("Spectrophotometer"))
    }
}
