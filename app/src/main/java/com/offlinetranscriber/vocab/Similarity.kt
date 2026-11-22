package com.offlinetranscriber.vocab

import java.text.Normalizer

object Similarity {
    /** Optimal-string-alignment Damerau–Levenshtein distance. */
    fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
            }
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** 1.0 = identical, 0.0 = completely different. */
    fun ratio(a: String, b: String): Double {
        val max = maxOf(a.length, b.length)
        return if (max == 0) 1.0 else 1.0 - damerauLevenshtein(a, b).toDouble() / max
    }

    /** Lowercase, strip accents, keep letters/digits only ("Thermo-Fisher" -> "thermofisher"). */
    fun compact(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(n.length)
        for (c in n) if (c.isLetterOrDigit()) sb.append(c)
        return sb.toString()
    }
}

/**
 * Simplified English phonetic key (Metaphone-inspired). Two spellings that sound
 * alike ("Shimadzu"/"Shimadsu", "Agilent"/"Ajilent", "fisher"/"fischer") share a key.
 * It is a heuristic for ranking suggestions, not a pronunciation model.
 */
object Phonetic {
    fun key(input: String): String {
        val s = Similarity.compact(input).filter { it in 'a'..'z' }
        if (s.isEmpty()) return ""
        var w = s
        // initial exceptions
        w = when {
            w.startsWith("kn") || w.startsWith("gn") || w.startsWith("pn") || w.startsWith("wr") -> w.substring(1)
            w.startsWith("x") -> "s" + w.substring(1)
            w.startsWith("wh") -> "w" + w.substring(2)
            else -> w
        }
        w = w.replace("sch", "sh").replace("tch", "ch").replace("ph", "f").replace("ck", "k")
            .replace("gh", "").replace("dg", "j").replace("tion", "shn").replace("sion", "shn")
            .replace("qu", "kw").replace("q", "k").replace("x", "ks").replace("z", "s")
            .replace("ts", "s").replace("ds", "s")
        val out = StringBuilder()
        for (i in w.indices) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            val code: Char? = when (c) {
                'a', 'e', 'i', 'o', 'u', 'y' -> if (i == 0) 'a' else null
                'c' -> if (next == 'h') 'x' else if (next == 'e' || next == 'i' || next == 'y') 's' else 'k'
                'g' -> if (next == 'e' || next == 'i' || next == 'y') 'j' else 'k'
                'h' -> if (i > 0 && w[i - 1] in "csptg") null else if (next != null && next in "aeiou") 'h' else null
                's' -> if (next == 'h') 'x' else 's'
                'w' -> if (next != null && next in "aeiou") 'w' else null
                'b' -> if (i == w.lastIndex && i > 0 && w[i - 1] == 'm') null else 'b'
                'v' -> 'f'
                'd' -> 't'
                else -> c
            }
            if (code != null && (out.isEmpty() || out.last() != code)) out.append(code)
        }
        return out.toString()
    }
}
