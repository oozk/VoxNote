package com.offlinetranscriber.vocab

/**
 * Optional local clean-up of recognised text: filler words and stutters.
 * Language-aware: a token is only removed as a filler where it is not a real word
 * (Portuguese "um" = "a", German "um" = "around", Spanish "ha" = "has").
 */
object TextCleanup {
    /** Not words in any supported language: always safe to remove. */
    private val UNIVERSAL = setOf("uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "erm")

    private fun gated(lang: String?): Set<String> = when (lang?.substringBefore('-')?.substringBefore('_')?.lowercase()) {
        "en" -> setOf("um", "ah", "eh", "er")
        "de" -> setOf("äh", "ähm", "öhm")
        "fr" -> setOf("euh", "heu")
        "nl" -> setOf("eh", "ehm", "uhm")
        "es" -> setOf("eh", "em", "este")
        "it" -> setOf("ehm", "eh", "uhm")
        "tr" -> setOf("ıı", "ııı", "şey")
        else -> emptySet()
    }

    private val TOKEN = Regex("""[\p{L}\p{N}'’]+|[^\p{L}\p{N}'’]+""")

    /** Removes fillers (and the comma that usually follows them) and collapses 3+ repeated words. */
    fun clean(text: String, language: String?): String {
        if (text.isBlank()) return text
        val fillers = UNIVERSAL + gated(language)
        val parts = TOKEN.findAll(text).map { it.value }.toMutableList()
        val out = ArrayList<String>(parts.size)
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            val isWord = p.first().isLetterOrDigit() || p.first() == '\''
            if (isWord && p.lowercase() in fillers) {
                // "we, uh, ran" -> "we ran": drop the filler with the commas around it
                val prev = out.lastOrNull()
                if (prev != null && !prev.first().isLetterOrDigit() && prev.trim() == ",") out[out.size - 1] = " "
                val next = parts.getOrNull(i + 1)
                if (next != null && next.trim().let { it == "," || it == "..." || it == "…" || it.isEmpty() }) i++
                i++
                continue
            }
            out.add(p)
            i++
        }
        var s = out.joinToString("")
        s = collapseStutters(s)
        s = s.replace(Regex("""\s+([,.!?;:])"""), "$1")
            .replace(Regex("""^[\s,]+"""), "")
            .replace(Regex("""([.!?]\s+|^)([,;]\s*)"""), "$1")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        // capitalise a sentence start that lost its first word ("um, so we" -> "So we")
        if (s.isNotEmpty() && text.trimStart().firstOrNull()?.isUpperCase() == true && s[0].isLowerCase()) {
            s = s[0].uppercaseChar() + s.substring(1)
        }
        return s
    }

    /** "I I I I think" -> "I think"; "the the" (2x) is kept, as it can be intentional. */
    fun collapseStutters(text: String): String =
        Regex("""\b([\p{L}\p{N}'’]+)(?:[\s,]+\1\b){2,}""", RegexOption.IGNORE_CASE).replace(text) { it.groupValues[1] }
}

/** A user-defined exact replacement rule. */
data class ReplacementRule(val find: String, val replace: String, val wholeWord: Boolean = true, val matchCase: Boolean = false)

/** Applies exact "find -> replace" rules. `\n` in the replacement inserts a line break. */
class WordReplacer(rules: List<ReplacementRule>) {
    private val compiled: List<Pair<Regex, String>> = rules.filter { it.find.isNotBlank() }
        .sortedByDescending { it.find.length }   // longer phrases first ("new paragraph" before "new")
        .map { r ->
            val core = Regex.escape(r.find.trim())
            val p = if (r.wholeWord) "(?<![\\p{L}\\p{N}])$core(?![\\p{L}\\p{N}])" else core
            val opts = if (r.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(p, opts) to r.replace.replace("\\n", "\n")
        }

    val isEmpty get() = compiled.isEmpty()

    fun apply(text: String): String {
        var s = text
        for ((re, rep) in compiled) s = re.replace(s, Regex.escapeReplacement(rep))
        if (s.contains('\n')) s = s.replace(Regex("""[ \t]*\n[ \t]*"""), "\n")
        return s
    }
}

/**
 * Short natural sentences in the recognised language, used as the start of the Whisper
 * prompt. A well-punctuated sample nudges the decoder toward punctuated, capitalised
 * output in that language (a technique used by several dictation apps).
 */
object LanguageStarters {
    private val starters = mapOf(
        "en" to "Hello, how are you? Nice to meet you.",
        "de" to "Hallo, wie geht es Ihnen? Schön, Sie kennenzulernen.",
        "fr" to "Bonjour, comment allez-vous ? Ravi de vous rencontrer.",
        "es" to "Hola, ¿cómo estás? Encantado de conocerte.",
        "it" to "Ciao, come stai? Piacere di conoscerti.",
        "pt" to "Olá, como você está? Prazer em conhecê-lo.",
        "nl" to "Hallo, hoe gaat het? Aangenaam kennis te maken.",
        "tr" to "Merhaba, nasılsınız? Tanıştığımıza memnun oldum.",
        "pl" to "Cześć, jak się masz? Miło cię poznać.",
        "ru" to "Здравствуйте, как ваши дела? Приятно познакомиться.",
        "uk" to "Добрий день, як справи? Приємно познайомитися.",
        "sv" to "Hej, hur mår du? Trevligt att träffas.",
        "da" to "Hej, hvordan har du det? Rart at møde dig.",
        "no" to "Hei, hvordan har du det? Hyggelig å møte deg.",
        "fi" to "Hei, mitä kuuluu? Hauska tavata.",
        "cs" to "Dobrý den, jak se máte? Rád vás poznávám.",
        "el" to "Γεια σας, τι κάνετε; Χάρηκα για τη γνωριμία.",
        "ar" to "مرحباً، كيف حالك؟ سعيد بلقائك.",
        "hi" to "नमस्ते, आप कैसे हैं? आपसे मिलकर अच्छा लगा।",
        "ja" to "こんにちは、お元気ですか？お会いできて嬉しいです。",
        "zh" to "你好，最近好吗？见到你很高兴。",
        "ko" to "안녕하세요, 잘 지내시나요? 만나서 반갑습니다.",
    )

    fun forLanguage(code: String?): String? = code?.let { starters[it.substringBefore('-').lowercase()] }
}

/** Order of local post-processing applied to engine text. The engine's output is kept separately. */
class TextPipeline(
    private val replacer: WordReplacer?,
    private val removeFillers: Boolean,
) {
    fun process(text: String, language: String?): String {
        var s = text
        if (replacer != null && !replacer.isEmpty) s = replacer.apply(s)
        if (removeFillers) s = TextCleanup.clean(s, language)
        return s
    }
}
