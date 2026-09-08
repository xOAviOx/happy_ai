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
    private val calls: CallHandler,
    private val messages: MessageHandler,
    private val notifications: NotificationHandler,
    private val screen: ScreenHandler,
    private val whatsApp: WhatsAppHandler,
    private val log: HappyLog,
) {

    suspend fun execute(command: Command): Reply? {
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
                is Command.ContactNumber -> withContact(command.name, ContactAction.ReadNumber)
                is Command.CallContact -> withContact(command.name, ContactAction.Call)
                Command.CallBack -> calls.callBack()
                Command.AnswerCall -> Reply(calls.answer())
                Command.RejectCall -> Reply(calls.reject())
                is Command.Speakerphone -> Reply(calls.speakerphone(command.on))
                Command.WhoCalled -> Reply(calls.whoCalled())
                is Command.SendSms -> withContact(command.name, ContactAction.Sms(command.message))
                Command.ReadNotifications -> Reply(notifications.readRecent())
                is Command.ReplyToNotification ->
                    Reply(notifications.reply(command.name, command.message))
                is Command.GlobalAction -> Reply(screen.globalAction(command))
                Command.ReadScreen -> Reply(screen.readScreen())
                is Command.WhatsApp -> withContact(command.name, ContactAction.WhatsApp(command.message))
                is Command.Unmatched -> null
            }
        } catch (t: Throwable) {
            // A handler must never take the pipeline down. Say so and carry on.
            log.e(TAG, "handler threw for $command", t)
            Reply("Something went wrong doing that.")
        }
    }

    /**
     * Resolves a spoken name, then does the thing.
     *
     * One path for reading a number, calling and texting, so ambiguity is handled
     * identically for all three. Nothing irreversible happens while more than one
     * person still matches.
     */
    private suspend fun withContact(name: String, action: ContactAction): Reply {
        if (!contacts.hasPermission()) return Reply("I need permission to read your contacts.")
        val matches = contacts.resolve(name)
        return when {
            matches.isEmpty() -> Reply("I could not find $name in your contacts.")
            matches.size == 1 -> perform(matches[0], action)
            else -> {
                val options = matches.take(MAX_CHOICES)
                Reply(
                    "Do you mean " + describe(options).joinToString(" or ") + "?",
                    Pending.ChooseContact(options, action),
                )
            }
        }
    }

    private suspend fun perform(contact: ContactResolver.Contact, action: ContactAction): Reply =
        when (action) {
            ContactAction.ReadNumber -> Reply(numberFor(contact))
            ContactAction.Call -> Reply(calls.dial(contact))
            // Composes and asks. Sending happens only after a spoken yes.
            is ContactAction.Sms -> messages.compose(contact, action.message)
            // WhatsApp goes straight out rather than asking: the deep link shows
            // the message on screen before it sends, so the user already sees it,
            // and the fallback leaves it unsent for them to check.
            is ContactAction.WhatsApp -> Reply(whatsApp.send(contact, action.message))
        }

    /** Resolves an answer to a question Happy asked a moment ago. */
    suspend fun resolve(pending: Pending, answer: String): Reply = when (pending) {
        is Pending.ChooseContact -> chooseContact(pending, answer)
        is Pending.ConfirmSms ->
            if (messages.isYes(answer)) {
                Reply(messages.send(pending.to, pending.message))
            } else {
                // Anything that is not clearly yes is treated as no. Sending a
                // text on an ambiguous answer is not a recoverable mistake.
                Reply("Not sent.")
            }
    }

    private suspend fun chooseContact(pending: Pending.ChooseContact, answer: String): Reply {
        val options = pending.options
        val lower = answer.lowercase()

        // "the first one" is a perfectly natural answer to a spoken list.
        val ordinal = ORDINALS.indexOfFirst { lower.contains(it) }
        if (ordinal in options.indices) return perform(options[ordinal], pending.then)

        // Where two people share a name, the answer has to be the digits.
        val digits = lower.filter(Char::isDigit)
        if (digits.length >= TAIL) {
            options.firstOrNull {
                it.number.filter(Char::isDigit).endsWith(digits.takeLast(TAIL))
            }?.let { return perform(it, pending.then) }
        }

        val best = options.mapNotNull { option ->
            Fuzzy.score(answer, option.name)?.let { it to option }
        }.minByOrNull { it.first }

        return if (best == null) {
            Reply("I still could not tell which one you meant.")
        } else {
            perform(best.second, pending.then)
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
