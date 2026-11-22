package com.offlinetranscriber.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinetranscriber.vocab.BuiltInCategories
import com.offlinetranscriber.vocab.KeywordImport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Vocabulary manager against a real (in-memory) Room/SQLite database. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class VocabularyRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: VocabularyRepository

    @Before fun setUp() = runBlocking {
        db = AppDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        repo = VocabularyRepository(db)
        repo.ensureBuiltInCategories()
    }

    @After fun tearDown() = db.close()

    @Test fun addEditDeleteKeyword() = runBlocking {
        val id = repo.save(null, "  Thermo   Fisher ", listOf("Thermofisher", "ThermoFisher Scientific", "Thermo fisher", "Thermo Fisher"),
            BuiltInCategories.COMPANY, null, "Scientific equipment manufacturer.", false)
        val k = repo.get(id)!!
        assertEquals("Thermo Fisher", k.phrase)
        assertEquals(listOf("Thermofisher", "ThermoFisher Scientific", "Thermo fisher"), k.alternativeList)  // phrase itself removed
        repo.save(id, "Thermo Fisher Scientific", k.alternativeList, BuiltInCategories.COMPANY, "THER-mo", null, true)
        val edited = repo.get(id)!!
        assertEquals("Thermo Fisher Scientific", edited.phrase)
        assertTrue(edited.priority)
        repo.delete(listOf(id))
        assertEquals(0, repo.count.first())
    }

    @Test fun duplicateIsRejected() = runBlocking {
        repo.save(null, "GC-MS", emptyList(), BuiltInCategories.ACRONYM, null, null, false)
        try {
            repo.save(null, "gc ms", emptyList(), BuiltInCategories.ACRONYM, null, null, false)
            fail("duplicate accepted")
        } catch (e: DuplicateKeywordException) {
            assertEquals("GC-MS", e.existing.phrase)
        }
        assertEquals("GC-MS", repo.findDuplicate("Gc-Ms")!!.phrase)
    }

    @Test fun bulkImportThousandsWithDuplicatesAndMerge() = runBlocking {
        repo.save(null, "Shimadzu", emptyList(), BuiltInCategories.COMPANY, null, null, false)
        val text = buildString {
            (1..2000).forEach { append("Compound $it\n") }
            append("shimadzu | Shimadsu\n")       // existing -> merge new spelling
            append("Compound 7\n")                // duplicate within input
            append("HPLC\n")
        }
        val preview = repo.preview(KeywordImport.parseLines(text))
        assertEquals(2001, preview.toAdd)
        assertEquals(1, preview.toMerge)
        assertEquals(1, preview.duplicates)
        val started = System.currentTimeMillis()
        val outcome = repo.commit(preview, null)
        assertTrue("import took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 10_000)
        assertEquals(2001, outcome.added)
        assertEquals(1, outcome.merged)
        assertEquals(2002, repo.count.first())
        assertEquals(listOf("Shimadsu"), repo.findDuplicate("Shimadzu")!!.alternativeList)
        assertEquals(BuiltInCategories.ACRONYM, repo.findDuplicate("HPLC")!!.category)   // guessed automatically
        // importing the same list again adds nothing
        assertEquals(0, repo.preview(KeywordImport.parseLines(text)).toAdd)
    }

    @Test fun searchCategoriesAndTermsFilter() = runBlocking {
        repo.save(null, "Agilent Technologies", listOf("Agilant"), BuiltInCategories.COMPANY, null, null, false)
        repo.save(null, "Spectrophotometer", emptyList(), BuiltInCategories.SCIENTIFIC, null, "UV-Vis", false)
        val custom = repo.addCategory("Lab equipment")
        repo.save(null, "Centrifuge", emptyList(), custom, null, null, false)
        assertEquals(listOf("Agilent Technologies"), repo.search("agilant").first().map { it.phrase })   // matches alternatives
        assertEquals(listOf("Spectrophotometer"), repo.search("uv-vis").first().map { it.phrase })      // matches notes
        assertEquals(1, repo.termsFor(listOf(BuiltInCategories.SCIENTIFIC)).size)
        assertEquals(3, repo.termsFor(emptyList()).size)
        repo.deleteCategory(custom)
        assertEquals(BuiltInCategories.CUSTOM, repo.findDuplicate("Centrifuge")!!.category)
        assertTrue(repo.categoryList.first().none { it.name == custom })
        val rows = repo.exportRows()
        assertEquals(listOf("Agilent Technologies", "Centrifuge", "Spectrophotometer"), rows.map { it.phrase })
    }

    @Test fun hitCountsRankTerms() = runBlocking {
        val a = repo.save(null, "Alpha", emptyList(), BuiltInCategories.CUSTOM, null, null, false)
        repo.recordHits(listOf(a, a))
        repo.recordHits(listOf(a))
        assertEquals(2, repo.get(a)!!.hitCount)  // each call increments once per id
    }
}
