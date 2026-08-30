package com.happy.assistant.router

/**
 * Spoken numbers to integers.
 *
 * On-device recognition usually returns digits for times and durations, but not
 * always, and never for Hinglish counting. Small and deliberately limited: this
 * only has to cover clock times and timer lengths.
 */
object Numbers {

    private val WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5,
        "chhe" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "pandrah" to 15, "bees" to 20, "tees" to 30,
        "half" to 30, "aadha" to 30,
    )

    /** Parses "7", "seven", or "twenty five". Returns null when it is not a number. */
    fun parse(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return it }

        val parts = trimmed.split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        var total = 0
        var matched = false
        for (part in parts) {
            val value = WORDS[part] ?: part.toIntOrNull() ?: return if (matched) total else null
            // "twenty five" is 25: the tens word and the unit word simply add.
            total += value
            matched = true
        }
        return if (matched) total else null
    }
}
