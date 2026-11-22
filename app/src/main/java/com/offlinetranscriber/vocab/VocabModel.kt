package com.offlinetranscriber.vocab

/** Domain view of a vocabulary entry, independent of storage. */
data class VocabTerm(
    val id: Long,
    val phrase: String,
    val alternatives: List<String> = emptyList(),
    val category: String = BuiltInCategories.CUSTOM,
    val pronunciation: String? = null,
    val priority: Boolean = false,
    /** How often this term was recognised in past transcripts (adaptive ranking). */
    val hitCount: Int = 0,
)

object BuiltInCategories {
    const val PERSON = "Personal names"
    const val COMPANY = "Company names"
    const val PRODUCT = "Product names"
    const val TECHNICAL = "Technical terminology"
    const val MEDICAL = "Medical terminology"
    const val SCIENTIFIC = "Scientific terminology"
    const val LOCATION = "Locations"
    const val ACRONYM = "Acronyms"
    const val CUSTOM = "Custom"

    val ALL = listOf(PERSON, COMPANY, PRODUCT, TECHNICAL, MEDICAL, SCIENTIFIC, LOCATION, ACRONYM, CUSTOM)

    /** Map loose user input ("company", "Acronym", "med") to a built-in category when obvious. */
    fun normalize(input: String?): String? {
        val s = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val l = s.lowercase()
        return when {
            l.startsWith("person") || l == "name" || l == "names" || l == "people" -> PERSON
            l.startsWith("compan") || l == "organisation" || l == "organization" || l == "brand" -> COMPANY
            l.startsWith("product") -> PRODUCT
            l.startsWith("tech") -> TECHNICAL
            l.startsWith("med") -> MEDICAL
            l.startsWith("scien") -> SCIENTIFIC
            l.startsWith("location") || l == "place" || l == "places" -> LOCATION
            l.startsWith("acronym") || l.startsWith("abbrev") -> ACRONYM
            l == "custom" -> CUSTOM
            else -> s
        }
    }

    /** Best-guess category for a bare keyword (used by bulk paste; user can change it). */
    fun guess(phrase: String): String {
        val p = phrase.trim()
        val letters = p.filter { it.isLetter() }
        return when {
            letters.length in 2..8 && letters.all { it.isUpperCase() } -> ACRONYM
            Regex("""\b(Inc|Ltd|LLC|GmbH|Corp|Corporation|Technologies|Scientific|Company|AG|plc|S\.A\.)\b""").containsMatchIn(p) -> COMPANY
            else -> CUSTOM
        }
    }
}

object VocabText {
    /** Key used for duplicate detection: case/space/punctuation-insensitive. */
    fun duplicateKey(s: String): String =
        s.lowercase().replace(Regex("""[\s\-_.’'",;:()]+"""), "").trim()

    /** Normalises whitespace; [stripQuotes] removes wrapping quotes from pasted text (not used for CSV fields). */
    fun cleanPhrase(s: String, stripQuotes: Boolean = true): String {
        val t = s.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()
        return if (stripQuotes) t.trim('"', '\'', '“', '”', '‘', '’').trim() else t
    }
}
