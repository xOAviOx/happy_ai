package com.happy.assistant.handlers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Time and date from the system clock.
 *
 * Spec section 7: anything with a deterministic source must use that source. A
 * language model must never be asked what time it is.
 */
@Singleton
class ClockHandler @Inject constructor() {

    fun timeNow(now: Date = Date()): String {
        val hour = SimpleDateFormat("h", Locale.UK).format(now)
        val minute = SimpleDateFormat("mm", Locale.UK).format(now)
        val suffix = SimpleDateFormat("a", Locale.UK).format(now).lowercase()
        val spokenSuffix = if (suffix.startsWith("a")) "a m" else "p m"
        return when {
            minute == "00" -> "It is $hour o clock $spokenSuffix."
            // "3 5 p m" reads as three five. Past the hour, single digits get an oh.
            minute.startsWith("0") -> "It is $hour oh ${minute[1]} $spokenSuffix."
            else -> "It is $hour $minute $spokenSuffix."
        }
    }

    fun dateToday(now: Date = Date()): String =
        "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.UK).format(now) + "."
}
