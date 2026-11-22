package com.offlinetranscriber.vocab

/**
 * Builds the Whisper "initial prompt" from the user's vocabulary.
 *
 * What this really does: Whisper's text decoder is conditioned on preceding text.
 * Showing it a list of correctly spelled terms makes it more likely to *spell*
 * those terms that way when it hears them. It is decoder context, not acoustic
 * biasing — it cannot make the model recognise a word it does not hear.
 *
 * The prompt is limited (whisper.cpp uses at most ~224 prompt tokens), so terms
 * are ranked: starred terms first, then terms already heard earlier in this
 * recording, then terms frequently found in past transcripts.
 */
object PromptBuilder {
    const val MAX_PROMPT_TOKENS = 200        // leave room inside whisper's 224-token limit
    const val CONTEXT_TOKENS = 48            // tail of the previous chunk, for continuity

    data class Plan(val prompt: String?, val glossary: String?, val includedTermIds: List<Long>, val boostTerms: List<String>)

    fun rank(terms: List<VocabTerm>, seenInThisRecording: Set<Long>): List<VocabTerm> =
        terms.sortedWith(
            compareByDescending<VocabTerm> { it.priority }
                .thenByDescending { it.id in seenInThisRecording }
                .thenByDescending { it.hitCount }
                .thenBy { it.phrase.length }
        )

    /**
     * @param countTokens tokenizer of the loaded model (exact budget); falls back to an estimate.
     * @param previousText text of the previous chunk (helps continuity across chunk boundaries)
     */
    fun build(
        terms: List<VocabTerm>,
        seenInThisRecording: Set<Long>,
        previousText: String?,
        useVocabulary: Boolean,
        countTokens: (String) -> Int = ::estimateTokens,
        maxBoostTerms: Int = 150,
        /** Optional well-punctuated sentence in the target language (see [LanguageStarters]). */
        starter: String? = null,
    ): Plan {
        val ranked = if (useVocabulary) rank(terms, seenInThisRecording) else emptyList()
        val context = previousText?.let { tailWords(it, 40) }?.takeIf { it.isNotBlank() }
        val contextTokens = context?.let { countTokens(" $it") } ?: 0
        val starterText = starter?.takeIf { context == null }   // only before any real context exists
        val starterTokens = starterText?.let { countTokens(" $it") } ?: 0
        val budget = MAX_PROMPT_TOKENS - contextTokens.coerceAtMost(CONTEXT_TOKENS + 16) - starterTokens

        val included = ArrayList<VocabTerm>()
        var used = if (ranked.isEmpty()) 0 else countTokens("Glossary:") + 1
        for (t in ranked) {
            val cost = countTokens(" ${t.phrase},")
            if (used + cost > budget) {
                if (cost > 12) continue else break   // skip a very long phrase, stop otherwise
            }
            included.add(t)
            used += cost
        }
        val glossary = if (included.isEmpty()) null else "Glossary: " + included.joinToString(", ") { it.phrase } + "."
        val prompt = listOfNotNull(starterText, glossary, context).joinToString(" ").ifBlank { null }
        val boost = ranked.take(maxBoostTerms).map { it.phrase }
        return Plan(prompt, glossary, included.map { it.id }, boost)
    }

    /** Rough token estimate (~4 characters per token for English, never less than 1 per word). */
    fun estimateTokens(text: String): Int {
        val words = text.split(Regex("""\s+""")).count { it.isNotEmpty() }
        return maxOf(words, (text.length + 3) / 4)
    }

    private fun tailWords(text: String, n: Int): String {
        val words = text.trim().split(Regex("""\s+"""))
        return words.takeLast(n).joinToString(" ")
    }
}
