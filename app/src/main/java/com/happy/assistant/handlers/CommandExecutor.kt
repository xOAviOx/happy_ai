package com.happy.assistant.handlers

import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.Command
import com.happy.assistant.router.ContactResolver
import com.happy.assistant.router.Fuzzy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches a matched [Command] and returns the sentence to speak back.
 *
 * Returns null for [Command.Unmatched] only, which is the signal to fall through
 * to the knowledge router in Phase 7. Everything else answers, including failures
 * - saying why something did not work is more useful than silence.
 */
@Singleton
class CommandExecutor @Inject constructor(
    private val device: DeviceHandler,
    private val apps: AppHandler,
    private val alarms: AlarmHandler,
    private val clock: ClockHandler,
    private val contacts: ContactResolver,
    private val log: HappyLog,
) {

    fun execute(command: Command): Reply? {
        log.d(TAG, "executing $command")
        return try {
            when (command) {
                is Command.Torch -> Reply(device.torch(command))
                is Command.VolumeStep -> Reply(device.volumeStep(command))
                is Command.VolumeSet -> Reply(device.volumeSet(command))
                is Command.RingerMode -> Reply(device.ringer(command))
                Command.BatteryStatus -> Reply(device.battery())
                Command.StorageStatus -> Reply(device.storage())
                Command.TimeNow -> Reply(clock.timeNow())
                Command.DateToday -> Reply(clock.dateToday())
                is Command.SetAlarm -> Reply(alarms.setAlarm(command))
                is Command.SetTimer -> Reply(alarms.setTimer(command))
                is Command.OpenApp -> Reply(apps.open(command.query))
                is Command.ContactNumber -> contactNumber(command.name)
                is Command.Unmatched -> null
            }
        } catch (t: Throwable) {
            // A handler must never take the pipeline down. Say so and carry on.
            log.e(TAG, "handler threw for $command", t)
            Reply("Something went wrong doing that.")
        }
    }

    private fun contactNumber(name: String): Reply {
        if (!contacts.hasPermission()) return Reply("I need permission to read your contacts.")
        val matches = contacts.resolve(name)
        return when {
            matches.isEmpty() -> Reply("I could not find $name in your contacts.")
            matches.size == 1 -> Reply(numberFor(matches[0]))
            else -> {
                // Spec section 6: ask rather than guess which person was meant, and
                // keep the options so the answer has somewhere to land.
                val options = matches.take(MAX_CHOICES)
                Reply(
                    "Do you mean " + describe(options).joinToString(" or ") + "?",
                    Pending.ChooseContact(options),
                )
            }
        }
    }

    /** Resolves an answer to a question Happy asked a moment ago. */
    fun resolve(pending: Pending, answer: String): Reply = when (pending) {
        is Pending.ChooseContact -> chooseContact(pending, answer)
    }

    private fun chooseContact(pending: Pending.ChooseContact, answer: String): Reply {
        val options = pending.options
        val lower = answer.lowercase()

        // "the first one" is a perfectly natural answer to a spoken list.
        val ordinal = ORDINALS.indexOfFirst { lower.contains(it) }
        if (ordinal in options.indices) return Reply(numberFor(options[ordinal]))

        // Where two people share a name, the answer has to be the digits.
        val digits = lower.filter(Char::isDigit)
        if (digits.length >= TAIL) {
            options.firstOrNull {
                it.number.filter(Char::isDigit).endsWith(digits.takeLast(TAIL))
            }?.let { return Reply(numberFor(it)) }
        }

        val best = options.mapNotNull { option ->
            Fuzzy.score(answer, option.name)?.let { it to option }
        }.minByOrNull { it.first }

        return if (best == null) {
            Reply("I still could not tell which one you meant.")
        } else {
            Reply(numberFor(best.second))
        }
    }

    private fun numberFor(contact: ContactResolver.Contact) =
        "${contact.name}'s number is ${contacts.spoken(contact.number)}."

    /**
     * Names, made distinguishable. Asking "do you mean Nishant or Nishant" is a
     * question nobody can answer, so identical names get their last digits.
     */
    private fun describe(options: List<ContactResolver.Contact>): List<String> {
        val repeated = options.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        return options.map { contact ->
            if (contact.name in repeated) {
                val tail = contact.number.filter(Char::isDigit).takeLast(TAIL)
                "${contact.name} ending " + tail.toCharArray().joinToString(" ")
            } else {
                contact.name
            }
        }
    }

    companion object {
        private const val TAG = "Exec"
        private const val MAX_CHOICES = 3
        private const val TAIL = 4
        private val ORDINALS = listOf("first", "second", "third")
    }
}
