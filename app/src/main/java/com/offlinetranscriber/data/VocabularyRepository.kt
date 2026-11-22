package com.offlinetranscriber.data

import androidx.room.withTransaction
import com.offlinetranscriber.vocab.BuiltInCategories
import com.offlinetranscriber.vocab.ExistingKeyword
import com.offlinetranscriber.vocab.ImportAction
import com.offlinetranscriber.vocab.ImportPreview
import com.offlinetranscriber.vocab.KeywordExport
import com.offlinetranscriber.vocab.KeywordImport
import com.offlinetranscriber.vocab.ParsedKeyword
import com.offlinetranscriber.vocab.VocabTerm
import com.offlinetranscriber.vocab.VocabText
import kotlinx.coroutines.flow.Flow

class DuplicateKeywordException(val existing: KeywordEntity) : Exception("\"${existing.phrase}\" is already in your vocabulary")

data class ImportOutcome(val added: Int, val merged: Int, val skipped: Int)

class VocabularyRepository(private val db: AppDatabase) {
    private val keywords = db.keywords()
    private val categories = db.categories()

    val all: Flow<List<KeywordEntity>> = keywords.observeAll()
    val count: Flow<Int> = keywords.observeCount()
    val categoryList: Flow<List<CategoryEntity>> = categories.observeAll()
    val categoryCounts: Flow<List<CategoryCount>> = keywords.observeCategoryCounts()

    fun search(q: String): Flow<List<KeywordEntity>> = if (q.isBlank()) all else keywords.search(q.trim())

    suspend fun ensureBuiltInCategories() {
        categories.insertAll(BuiltInCategories.ALL.map { CategoryEntity(it, builtIn = true) })
    }

    suspend fun addCategory(name: String): String {
        val clean = VocabText.cleanPhrase(name)
        require(clean.isNotEmpty()) { "Category name is empty" }
        categories.insert(CategoryEntity(clean, builtIn = false))
        return clean
    }

    /** Deletes a custom category; its keywords move to "Custom". */
    suspend fun deleteCategory(name: String) = db.withTransaction {
        keywords.moveCategory(name, BuiltInCategories.CUSTOM)
        categories.deleteCustom(name)
    }

    suspend fun get(id: Long) = keywords.get(id)

    /** Finds an existing keyword that would duplicate [phrase] (ignoring [exceptId]). */
    suspend fun findDuplicate(phrase: String, exceptId: Long? = null): KeywordEntity? =
        keywords.byKey(VocabText.duplicateKey(phrase))?.takeIf { it.id != exceptId }

    suspend fun save(
        id: Long?, phrase: String, alternatives: List<String>, category: String,
        pronunciation: String?, notes: String?, priority: Boolean,
    ): Long {
        val clean = VocabText.cleanPhrase(phrase)
        require(clean.isNotEmpty()) { "Please enter a keyword or phrase" }
        require(clean.length <= KeywordImport.MAX_PHRASE_LENGTH) { "Keyword is too long" }
        findDuplicate(clean, id)?.let { throw DuplicateKeywordException(it) }
        val alts = alternatives.map { VocabText.cleanPhrase(it) }.filter { it.isNotEmpty() && it != clean }.distinct()
        val cat = category.ifBlank { BuiltInCategories.CUSTOM }
        categories.insert(CategoryEntity(cat, builtIn = cat in BuiltInCategories.ALL))
        return if (id == null) {
            keywords.insert(KeywordEntity(phrase = clean, phraseKey = VocabText.duplicateKey(clean), alternatives = alts.joinToString("\n"),
                pronunciation = pronunciation?.trim()?.ifEmpty { null }, category = cat, notes = notes?.trim()?.ifEmpty { null }, priority = priority))
        } else {
            val old = keywords.get(id) ?: error("Keyword not found")
            keywords.update(old.copy(phrase = clean, phraseKey = VocabText.duplicateKey(clean), alternatives = alts.joinToString("\n"),
                pronunciation = pronunciation?.trim()?.ifEmpty { null }, category = cat, notes = notes?.trim()?.ifEmpty { null },
                priority = priority, updatedAt = System.currentTimeMillis()))
            id
        }
    }

    suspend fun setPriority(k: KeywordEntity, priority: Boolean) = keywords.update(k.copy(priority = priority, updatedAt = System.currentTimeMillis()))

    suspend fun delete(ids: List<Long>) = keywords.deleteIds(ids)

    suspend fun preview(parsed: List<ParsedKeyword>): ImportPreview {
        val existing = keywords.all().map { ExistingKeyword(it.id, it.phrase, it.alternativeList) }
        return KeywordImport.preview(parsed, existing)
    }

    /** Commits an import preview in one transaction (thousands of rows take well under a second). */
    suspend fun commit(preview: ImportPreview, defaultCategory: String?): ImportOutcome = db.withTransaction {
        var added = 0
        var merged = 0
        val newCats = HashSet<String>()
        val toInsert = ArrayList<KeywordEntity>()
        for (item in preview.items) {
            val k = item.keyword
            when (item.action) {
                ImportAction.ADD -> {
                    val cat = BuiltInCategories.normalize(k.category) ?: defaultCategory ?: BuiltInCategories.guess(k.phrase)
                    newCats.add(cat)
                    toInsert.add(KeywordEntity(phrase = k.phrase, phraseKey = VocabText.duplicateKey(k.phrase),
                        alternatives = k.alternatives.joinToString("\n"), pronunciation = k.pronunciation, category = cat, notes = k.notes))
                }
                ImportAction.MERGE_ALTERNATIVES -> {
                    val ex = item.existingId?.let { keywords.get(it) } ?: continue
                    val alts = (ex.alternativeList + k.alternatives).distinct()
                    keywords.update(ex.copy(alternatives = alts.joinToString("\n"), updatedAt = System.currentTimeMillis()))
                    merged++
                }
                else -> Unit
            }
        }
        categories.insertAll(newCats.map { CategoryEntity(it, builtIn = it in BuiltInCategories.ALL) })
        toInsert.chunked(500).forEach { batch -> added += keywords.insertAll(batch).count { it > 0 } }
        ImportOutcome(added, merged, preview.items.size - added - merged)
    }

    suspend fun exportRows(): List<KeywordExport.Row> = keywords.all().sortedBy { it.phrase.lowercase() }
        .map { KeywordExport.Row(it.phrase, it.alternativeList, it.category, it.pronunciation, it.notes) }

    /** Vocabulary for a transcription run (all, or only the chosen categories). */
    suspend fun termsFor(categoriesFilter: List<String>): List<VocabTerm> {
        val list = if (categoriesFilter.isEmpty()) keywords.all() else keywords.inCategories(categoriesFilter)
        return list.map { VocabTerm(it.id, it.phrase, it.alternativeList, it.category, it.pronunciation, it.priority, it.hitCount) }
    }

    // ---------- word replacements ----------
    val replacements: Flow<List<WordReplacementEntity>> = db.replacements().observeAll()

    suspend fun saveReplacement(id: Long?, find: String, replace: String, wholeWord: Boolean, matchCase: Boolean) {
        val f = find.trim()
        require(f.isNotEmpty()) { "Enter the text to find" }
        if (id == null) db.replacements().insert(WordReplacementEntity(find = f, replace = replace, wholeWord = wholeWord, matchCase = matchCase))
        else db.replacements().update(WordReplacementEntity(id = id, find = f, replace = replace, wholeWord = wholeWord, matchCase = matchCase))
    }

    suspend fun setReplacementEnabled(r: WordReplacementEntity, enabled: Boolean) = db.replacements().update(r.copy(enabled = enabled))
    suspend fun deleteReplacement(id: Long) = db.replacements().delete(id)

    suspend fun replacer(): com.offlinetranscriber.vocab.WordReplacer? {
        val rules = db.replacements().enabled().map { com.offlinetranscriber.vocab.ReplacementRule(it.find, it.replace, it.wholeWord, it.matchCase) }
        return if (rules.isEmpty()) null else com.offlinetranscriber.vocab.WordReplacer(rules)
    }

    suspend fun recordHits(ids: Collection<Long>) {
        if (ids.isNotEmpty()) ids.chunked(500).forEach { keywords.incrementHits(it) }
    }
}
