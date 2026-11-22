package com.offlinetranscriber.vocab

/** Why a correction is proposed. */
enum class MatchKind(val label: String) {
    ALTERNATIVE_SPELLING("Matches a spelling you listed"),
    CASE_OR_SPACING("Same word, different capitals/spacing"),
    SIMILAR_SPELLING("Similar spelling"),
    SOUNDS_ALIKE("Sounds similar"),
}

data class CorrectionSuggestion(
    val segmentIndex: Int,
    /** Character range [start, end) in the segment text. */
    val start: Int,
    val end: Int,
    val original: String,
    val replacement: String,
    val termId: Long,
    val confidence: Double,
    val kind: MatchKind,
)

data class CorrectionOptions(
    /** Suggestions below this confidence are discarded. */
    val minConfidence: Double = 0.6,
    /** Fuzzy matching is only attempted for words at least this long (letters/digits). */
    val minFuzzyLength: Int = 5,
)

/**
 * LOCAL TEXT POST-PROCESSING. Compares recognised text with the user's vocabulary
 * and proposes corrections. It works on text only: it never changes what the
 * acoustic model heard, and it never overwrites the original transcription —
 * callers store suggestions separately and apply them only when accepted
 * (or when the user enabled auto-apply for high-confidence matches).
 */
class CorrectionEngine(terms: List<VocabTerm>, private val options: CorrectionOptions = CorrectionOptions()) {

    private class Entry(
        val termId: Long,
        val canonical: String,
        val variant: String,
        val compact: String,
        val phonetic: String,
        val words: Int,
        val isAlternative: Boolean,
    )

    private val byCompact = HashMap<String, MutableList<Entry>>()
    private val byPhonetic = HashMap<String, MutableList<Entry>>()
    private val byBucket = HashMap<String, MutableList<Entry>>()   // first char + length
    private val canonicalSurface = HashSet<String>()
    private val canonicalCompact = HashMap<String, Long>()
    private val maxWords: Int

    init {
        var mw = 1
        for (t in terms) {
            val phrase = VocabText.cleanPhrase(t.phrase)
            if (phrase.isEmpty()) continue
            canonicalSurface.add(phrase)
            canonicalCompact[Similarity.compact(phrase)] = t.id
            val variants = listOf(phrase to false) + t.alternatives.map { VocabText.cleanPhrase(it) to true }
            for ((v, alt) in variants) {
                val c = Similarity.compact(v)
                if (c.isEmpty()) continue
                val e = Entry(t.id, phrase, v, c, Phonetic.key(v), wordCount(v), alt)
                mw = maxOf(mw, e.words)
                byCompact.getOrPut(c) { ArrayList() }.add(e)
                if (e.phonetic.length >= 3) byPhonetic.getOrPut(e.phonetic) { ArrayList() }.add(e)
                if (!alt) byBucket.getOrPut(bucket(c)) { ArrayList() }.add(e)
            }
        }
        maxWords = mw.coerceAtMost(6)
    }

    val isEmpty: Boolean get() = byCompact.isEmpty()

    private data class Token(val start: Int, val end: Int)

    /** Analyse one segment; returns non-overlapping suggestions and the ids of terms found correctly spelled. */
    fun analyze(segmentIndex: Int, text: String): Pair<List<CorrectionSuggestion>, Set<Long>> {
        if (isEmpty || text.isBlank()) return emptyList<CorrectionSuggestion>() to emptySet()
        val tokens = tokenize(text)
        val found = ArrayList<CorrectionSuggestion>()
        val recognized = HashSet<Long>()
        val exactSpans = ArrayList<IntRange>()

        for (n in maxWords + 1 downTo 1) {
            for (i in 0..tokens.size - n) {
                val first = tokens[i]
                val last = tokens[i + n - 1]
                if (!joinable(text, tokens, i, n)) continue
                val start = first.start
                val end = last.end
                val surface = text.substring(start, end)
                val compact = Similarity.compact(surface)
                if (compact.isEmpty()) continue

                if (surface in canonicalSurface) {          // already correct
                    canonicalCompact[compact]?.let { recognized.add(it) }
                    exactSpans.add(start until end)
                    continue
                }
                val exact = byCompact[compact]
                if (exact != null) {
                    // a spelling the user explicitly listed wins over a mere case/spacing difference
                    val e = exact.firstOrNull { it.isAlternative && it.variant.equals(surface, ignoreCase = true) }
                        ?: exact.firstOrNull { !it.isAlternative } ?: exact.first()
                    recognized.add(e.termId)
                    if (surface == e.canonical) continue
                    val conf = when {
                        e.isAlternative -> 0.98
                        n == 1 && CommonWords.isCommon(surface) -> 0.45   // "apple" vs "Apple": ambiguous
                        else -> 0.9
                    }
                    val kind = if (e.isAlternative) MatchKind.ALTERNATIVE_SPELLING else MatchKind.CASE_OR_SPACING
                    found.add(CorrectionSuggestion(segmentIndex, start, end, surface, e.canonical, e.termId, conf, kind))
                    continue
                }
                fuzzy(segmentIndex, surface, compact, n, start, end)?.let { found.add(it) }
            }
        }
        val accepted = resolveOverlaps(found.filter { it.confidence >= options.minConfidence }, exactSpans)
        return accepted to recognized
    }

    private fun fuzzy(seg: Int, surface: String, compact: String, n: Int, start: Int, end: Int): CorrectionSuggestion? {
        if (compact.length < options.minFuzzyLength) return null
        if (compact.all { it.isDigit() }) return null
        val ph = Phonetic.key(surface)
        val candidates = LinkedHashSet<Entry>()
        byPhonetic[ph]?.let { candidates.addAll(it) }
        for (len in compact.length - 2..compact.length + 2) {
            byBucket[compact.first() + ":" + len]?.let { candidates.addAll(it) }
        }
        var best: CorrectionSuggestion? = null
        for (e in candidates) {
            if (e.compact.length < options.minFuzzyLength) continue
            if (kotlin.math.abs(e.words - n) > 1) continue
            val lenRatio = compact.length.toDouble() / e.compact.length
            if (lenRatio < 0.7 || lenRatio > 1.4) continue
            if (canonicalCompact.containsKey(compact)) continue     // it is another keyword: leave it
            val r = Similarity.ratio(compact, e.compact)
            val samePhonetic = ph.length >= 3 && ph == e.phonetic
            var conf = when {
                r >= 0.85 -> 0.62 + (r - 0.85) * 2.0
                samePhonetic && r >= 0.6 -> 0.6 + (r - 0.6) * 0.5
                else -> continue
            }
            if (samePhonetic) conf += 0.08
            if (compact.first() != e.compact.first()) conf -= 0.1
            if (n == 1 && CommonWords.isCommon(surface)) conf -= 0.3
            conf = conf.coerceIn(0.0, 0.94)   // fuzzy matches are never "certain"
            val kind = if (samePhonetic && r < 0.85) MatchKind.SOUNDS_ALIKE else MatchKind.SIMILAR_SPELLING
            if (best == null || conf > best.confidence) {
                best = CorrectionSuggestion(seg, start, end, surface, e.canonical, e.termId, conf, kind)
            }
        }
        return best
    }

    private fun resolveOverlaps(list: List<CorrectionSuggestion>, protected: List<IntRange>): List<CorrectionSuggestion> {
        val chosen = ArrayList<CorrectionSuggestion>()
        val sorted = list.sortedWith(compareByDescending<CorrectionSuggestion> { it.confidence }.thenByDescending { it.end - it.start })
        for (s in sorted) {
            val r = s.start until s.end
            if (protected.any { overlaps(it, r) }) continue
            if (chosen.any { overlaps(it.start until it.end, r) }) continue
            chosen.add(s)
        }
        return chosen.sortedBy { it.start }
    }

    private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last

    private fun joinable(text: String, tokens: List<Token>, i: Int, n: Int): Boolean {
        for (k in i until i + n - 1) {
            val gap = text.substring(tokens[k].end, tokens[k + 1].start)
            if (gap.any { it != ' ' && it != '-' && it != ' ' }) return false   // never across punctuation
        }
        return true
    }

    private fun tokenize(text: String): List<Token> = WORD.findAll(text).map { m ->
        var end = m.range.last + 1
        val v = m.value
        if (v.length > 3 && (v.endsWith("'s") || v.endsWith("’s"))) end -= 2   // keep possessive outside the match
        Token(m.range.first, end)
    }.toList()

    private fun bucket(compact: String) = compact.first() + ":" + compact.length

    companion object {
        private val WORD = Regex("""[\p{L}\p{N}]+(?:['’.&+\-][\p{L}\p{N}]+)*""")
        fun wordCount(s: String) = WORD.findAll(s).count().coerceAtLeast(1)

        /** Applies accepted suggestions to [text] (right-to-left so offsets stay valid). */
        fun apply(text: String, suggestions: List<CorrectionSuggestion>): String {
            val sb = StringBuilder(text)
            for (s in suggestions.sortedByDescending { it.start }) {
                if (s.end <= sb.length && sb.substring(s.start, s.end) == s.original) {
                    sb.replace(s.start, s.end, s.replacement)
                }
            }
            return sb.toString()
        }

        /**
         * Locates [original] in a possibly edited [text], preferring the occurrence
         * nearest to [hint]. Returns the start index or -1 when the text no longer contains it.
         */
        fun locate(text: String, original: String, hint: Int): Int {
            if (hint in 0..text.length - original.length && text.regionMatches(hint, original, 0, original.length)) return hint
            var best = -1
            var idx = text.indexOf(original)
            while (idx >= 0) {
                if (best < 0 || kotlin.math.abs(idx - hint) < kotlin.math.abs(best - hint)) best = idx
                idx = text.indexOf(original, idx + 1)
            }
            return best
        }
    }
}
