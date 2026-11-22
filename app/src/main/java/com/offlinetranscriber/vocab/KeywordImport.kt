package com.offlinetranscriber.vocab

/** One keyword parsed from a TXT/CSV/pasted import. */
data class ParsedKeyword(
    val phrase: String,
    val alternatives: List<String> = emptyList(),
    val category: String? = null,
    val pronunciation: String? = null,
    val notes: String? = null,
)

enum class ImportAction { ADD, MERGE_ALTERNATIVES, SKIP_DUPLICATE, SKIP_INVALID }

data class ImportItem(val keyword: ParsedKeyword, val action: ImportAction, val existingId: Long? = null, val reason: String = "")

data class ImportPreview(val items: List<ImportItem>) {
    val toAdd get() = items.count { it.action == ImportAction.ADD }
    val toMerge get() = items.count { it.action == ImportAction.MERGE_ALTERNATIVES }
    val duplicates get() = items.count { it.action == ImportAction.SKIP_DUPLICATE }
    val invalid get() = items.count { it.action == ImportAction.SKIP_INVALID }
}

/** Minimal view of an existing keyword needed for duplicate detection. */
data class ExistingKeyword(val id: Long, val phrase: String, val alternatives: List<String>)

object KeywordImport {
    const val MAX_PHRASE_LENGTH = 120
    private val bullet = Regex("""^\s*(?:[-*•·▪◦]+|\d{1,4}[.)])\s+""")
    private val altSplit = Regex("""\s*[;|]\s*""")

    /**
     * One keyword per line. Optional extras on the same line:
     *   `Keyword | alt1; alt2 | Category`   or   `Keyword = alt1, alt2`
     * Blank lines and lines starting with `#` are ignored; list bullets are stripped.
     */
    fun parseLines(text: String): List<ParsedKeyword> = text.lineSequence().mapNotNull { raw ->
        var line = raw.removePrefix("﻿")
        if (line.isBlank() || line.trimStart().startsWith("#")) return@mapNotNull null
        line = line.replace(bullet, "")
        when {
            line.contains('|') -> {
                val parts = line.split('|').map { it.trim() }
                ParsedKeyword(
                    phrase = VocabText.cleanPhrase(parts[0]),
                    alternatives = parts.getOrNull(1)?.let(::splitAlternatives).orEmpty(),
                    category = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                    notes = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
                )
            }
            line.contains('=') -> {
                val (k, v) = line.split('=', limit = 2)
                ParsedKeyword(VocabText.cleanPhrase(k), splitAlternatives(v.replace(',', ';')))
            }
            else -> ParsedKeyword(VocabText.cleanPhrase(line))
        }
    }.toList()

    fun splitAlternatives(s: String): List<String> =
        s.split(altSplit).map { VocabText.cleanPhrase(it) }.filter { it.isNotEmpty() }.distinct()

    /**
     * CSV import (RFC 4180 quoting, comma / semicolon / tab delimited).
     * Recognised header names (case-insensitive): keyword|term|phrase|word,
     * alternatives|alternative spellings|aliases|variants, pronunciation|hint,
     * category|type, notes|note|description. Without a header the column order is
     * keyword, alternatives, category, notes.
     */
    fun parseCsv(text: String): List<ParsedKeyword> {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
            .takeIf { d -> firstLine.count { it == d } > 0 } ?: ','
        val rows = parseCsvRows(clean, delimiter).filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { h -> names.any { h == it || h.startsWith(it) } }
        var kw = col("keyword", "term", "phrase", "word", "name")
        val hasHeader = kw >= 0
        var alt = col("alternative", "alias", "variant", "spelling", "synonym")
        var pron = col("pronunciation", "hint", "sounds like")
        var cat = col("category", "type", "group")
        var notes = col("note", "description", "comment")
        val body = if (hasHeader) rows.drop(1) else rows
        if (!hasHeader) { kw = 0; alt = 1; cat = 2; notes = 3; pron = -1 }

        return body.mapNotNull { r ->
            val phrase = r.getOrNull(kw)?.let { VocabText.cleanPhrase(it, stripQuotes = false) }.orEmpty()
            if (phrase.isEmpty()) return@mapNotNull null
            ParsedKeyword(
                phrase = phrase,
                alternatives = r.getOrNull(alt)?.let(::splitAlternatives).orEmpty(),
                category = r.getOrNull(cat)?.trim()?.takeIf { it.isNotEmpty() },
                pronunciation = r.getOrNull(pron)?.trim()?.takeIf { it.isNotEmpty() },
                notes = r.getOrNull(notes)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** Auto-detects CSV vs. plain list from file name / content. */
    fun parseAuto(text: String, fileName: String?): List<ParsedKeyword> {
        val name = fileName?.lowercase().orEmpty()
        val looksCsv = name.endsWith(".csv") || name.endsWith(".tsv") ||
            text.lineSequence().firstOrNull { it.isNotBlank() }?.lowercase()?.let {
                (it.startsWith("keyword") || it.startsWith("term") || it.startsWith("phrase")) &&
                    (it.contains(',') || it.contains(';') || it.contains('\t'))
            } == true
        return if (looksCsv) parseCsv(text) else parseLines(text)
    }

    internal fun parseCsvRows(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                delimiter -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> Unit
                '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    /**
     * Classifies parsed keywords against existing ones and against each other.
     * A duplicate that brings new alternative spellings is merged instead of skipped.
     */
    fun preview(parsed: List<ParsedKeyword>, existing: List<ExistingKeyword>): ImportPreview {
        val existingByKey = HashMap<String, ExistingKeyword>()
        for (e in existing) existingByKey.putIfAbsent(VocabText.duplicateKey(e.phrase), e)
        val seenInInput = HashMap<String, Int>()
        val items = ArrayList<ImportItem>(parsed.size)
        for (p in parsed) {
            val phrase = p.phrase
            if (phrase.isBlank() || phrase.length > MAX_PHRASE_LENGTH || phrase.none { it.isLetterOrDigit() }) {
                items.add(ImportItem(p, ImportAction.SKIP_INVALID, reason = "Empty or too long"))
                continue
            }
            val key = VocabText.duplicateKey(phrase)
            val cleanAlts = p.alternatives.filter { VocabText.duplicateKey(it) != key || it != phrase }
                .filter { it != phrase }
            val kw = p.copy(alternatives = cleanAlts)
            val ex = existingByKey[key]
            val prevIdx = seenInInput[key]
            when {
                ex != null -> {
                    val known = (ex.alternatives + ex.phrase).toSet()
                    val newAlts = kw.alternatives.filter { it !in known }
                    if (newAlts.isNotEmpty()) {
                        items.add(ImportItem(kw.copy(alternatives = newAlts), ImportAction.MERGE_ALTERNATIVES, ex.id, "Adds spellings to \"${ex.phrase}\""))
                    } else {
                        items.add(ImportItem(kw, ImportAction.SKIP_DUPLICATE, ex.id, "Already in your vocabulary as \"${ex.phrase}\""))
                    }
                }
                prevIdx != null -> {
                    val first = items[prevIdx]
                    val mergedAlts = (first.keyword.alternatives + kw.alternatives).distinct()
                    items[prevIdx] = first.copy(keyword = first.keyword.copy(alternatives = mergedAlts))
                    items.add(ImportItem(kw, ImportAction.SKIP_DUPLICATE, reason = "Listed twice in this import"))
                }
                else -> {
                    seenInInput[key] = items.size
                    items.add(ImportItem(kw, ImportAction.ADD))
                }
            }
        }
        return ImportPreview(items)
    }
}

object KeywordExport {
    data class Row(val phrase: String, val alternatives: List<String>, val category: String, val pronunciation: String?, val notes: String?)

    fun toCsv(rows: List<Row>): String = buildString {
        append("keyword,alternatives,category,pronunciation,notes\r\n")
        for (r in rows) {
            append(listOf(r.phrase, r.alternatives.joinToString("; "), r.category, r.pronunciation.orEmpty(), r.notes.orEmpty())
                .joinToString(",") { csvField(it) })
            append("\r\n")
        }
    }

    /** One keyword per line; alternatives and category after `|` (re-importable). */
    fun toTxt(rows: List<Row>): String = buildString {
        append("# Offline Transcriber vocabulary — format: keyword | alternative; alternative | category\n")
        for (r in rows) {
            append(r.phrase)
            if (r.alternatives.isNotEmpty() || r.category != BuiltInCategories.CUSTOM) {
                append(" | ").append(r.alternatives.joinToString("; "))
                append(" | ").append(r.category)
            }
            append('\n')
        }
    }

    private fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
