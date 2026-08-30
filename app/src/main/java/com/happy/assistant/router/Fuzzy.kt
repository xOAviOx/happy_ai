package com.happy.assistant.router

import kotlin.math.min

/**
 * Name matching that tolerates what speech recognition does to proper nouns.
 *
 * Spec section 6: "call rohith" has to find Rohit. Edit distance of two, or a
 * confident prefix, is the rule - loose enough for a misheard vowel, tight enough
 * that Rohit and Mohit stay different people.
 */
object Fuzzy {

    /** Lowercase, letters and digits only, so spacing and punctuation stop mattering. */
    fun key(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    /**
     * How well [candidate] answers [query], as a score where lower is better, or
     * null when it is not a plausible match at all.
     */
    fun score(query: String, candidate: String): Int? {
        val q = key(query)
        val c = key(candidate)
        if (q.isEmpty() || c.isEmpty()) return null

        return when {
            c == q -> 0
            c.startsWith(q) && q.length >= MIN_PREFIX -> 1
            q.startsWith(c) && c.length >= MIN_PREFIX -> 2
            c.contains(q) && q.length >= MIN_CONTAINS -> 3
            else -> {
                // Longer names earn more slack. Recognition mangles more of a long
                // word than a short one, and "chutrant" for Chitransh is three
                // edits, while two edits on a four letter name is a different
                // person entirely.
                val budget = when {
                    c.length <= 4 -> 1
                    c.length <= 7 -> 2
                    else -> 3
                }
                val d = distance(q, c)
                if (d <= budget) 3 + d else null
            }
        }
    }

    private const val MIN_PREFIX = 3
    private const val MIN_CONTAINS = 4
}
