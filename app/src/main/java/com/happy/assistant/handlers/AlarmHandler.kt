package com.happy.assistant.handlers

import android.content.Intent
import android.provider.AlarmClock
import com.happy.assistant.router.Command
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alarms and timers through the platform intents, which need no permission at
 * all and work with whatever clock app the phone actually uses.
 *
 * SKIP_UI is set so the clock app does the work without surfacing, which is the
 * difference between a voice assistant and a shortcut.
 */
@Singleton
class AlarmHandler @Inject constructor(private val apps: AppHandler) {

    fun setAlarm(command: Command.SetAlarm): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return apps.start(intent, "set the alarm", "Alarm set for ${spokenTime(command)}.")
    }

    fun setTimer(command: Command.SetTimer): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return apps.start(intent, "set the timer", "Timer set for ${spokenDuration(command.seconds)}.")
    }

    private fun spokenTime(command: Command.SetAlarm): String {
        val hour12 = when {
            command.hour == 0 -> 12
            command.hour > 12 -> command.hour - 12
            else -> command.hour
        }
        val suffix = if (command.hour < 12) "a m" else "p m"
        return if (command.minute == 0) {
            "$hour12 $suffix"
        } else {
            "$hour12 ${command.minute.toString().padStart(2, '0')} $suffix"
        }
    }

    private fun spokenDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        val parts = buildList {
            if (hours > 0) add("$hours ${plural(hours, "hour")}")
            if (minutes > 0) add("$minutes ${plural(minutes, "minute")}")
            if (rest > 0) add("$rest ${plural(rest, "second")}")
        }
        return if (parts.isEmpty()) "no time" else parts.joinToString(" and ")
    }

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
}
