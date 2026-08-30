package com.happy.assistant.router

/**
 * Clock times and durations out of already-normalised speech.
 *
 * Recognition hands us "7 30 pm" rather than "7:30pm", because normalisation has
 * stripped the punctuation by the time we get here. Deliberately conservative:
 * anything it cannot read confidently returns null, and the router falls through
 * to the next pattern rather than setting an alarm for the wrong time.
 */
object TimeParser {

    /** Words that carry no time information and must not abort parsing. */
    private val IGNORED = setOf(
        "oclock", "baje", "sharp", "past", "and", "right", "now", "please",
        "tomorrow", "today", "the", "in", "at", "ka", "ke", "liye",
    )

    /** Words that mean a half of the day just as clearly as am and pm do. */
    private val MORNING = setOf("morning", "subah")
    private val EVENING = setOf("evening", "night", "afternoon", "shaam", "raat", "dopahar")

    private val HOUR_UNITS = setOf("hour", "hours", "ghanta", "ghante")
    private val MINUTE_UNITS = setOf("minute", "minutes", "min", "mins", "minut")
    private val SECOND_UNITS = setOf("second", "seconds", "sec", "secs")

    /** Returns hour and minute on a 24 hour clock, or null. */
    fun clockTime(raw: String): Pair<Int, Int>? {
        val tokens = raw.trim().split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        var meridiem: String? = null
        val numbers = mutableListOf<Int>()
        for (token in tokens) {
            when {
                token == "am" || token == "pm" -> meridiem = token
                // "a.m." survives normalisation as two tokens, "a" then "m".
                token == "a" || token == "p" -> meridiem = token
                token == "m" -> meridiem = if (meridiem == "p") "pm" else "am"
                token in MORNING -> meridiem = "am"
                token in EVENING -> meridiem = "pm"
                token in IGNORED -> Unit
                else -> {
                    val value = Numbers.parse(token)
                    // Trailing filler like "right now" must not void the whole time.
                    if (value == null) break else numbers += value
                }
            }
        }
        if (numbers.isEmpty()) return null

        var hour = numbers[0]
        val minute = numbers.getOrNull(1) ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null

        when (meridiem) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        return hour to minute
    }

    /** Returns a duration in seconds, or null. */
    fun duration(raw: String): Int? {
        val tokens = raw.trim().split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        var total = 0
        var pending: Int? = null
        var half = false
        var sawUnit = false

        for (token in tokens) {
            when (token) {
                // "half" is a fraction of whatever unit follows, not the number 30.
                // Without this, "half an hour" becomes thirty hours.
                "half", "aadha" -> half = true

                "a", "an", "and", "ka", "ke", "for" -> Unit

                in HOUR_UNITS -> {
                    total += if (half) 1800 else (pending ?: 1) * 3600
                    pending = null; half = false; sawUnit = true
                }

                in MINUTE_UNITS -> {
                    total += if (half) 30 else (pending ?: 1) * 60
                    pending = null; half = false; sawUnit = true
                }

                in SECOND_UNITS -> {
                    total += if (half) 1 else (pending ?: 1)
                    pending = null; half = false; sawUnit = true
                }

                else -> pending = Numbers.parse(token) ?: return null
            }
        }

        if (!sawUnit) return null
        return total.takeIf { it in 1..86_400 }
    }
}
