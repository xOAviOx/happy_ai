package com.happy.assistant.router

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transcript to [Command], by an ordered list of patterns. First match wins, and
 * no language model is involved anywhere (spec section 6).
 *
 * Order matters and is deliberate: narrow patterns come before broad ones, so
 * "open the torch" is a torch command and not an attempt to launch an app called
 * "the torch". Every intent carries several phrasings including Hinglish, because
 * that is how this phone is actually spoken to.
 *
 * Pure string work on purpose - it is unit tested without a device.
 */
@Singleton
class IntentRouter @Inject constructor() {

    /**
     * Leading courtesy, stripped before matching.
     *
     * "Can you tell me Papa's number" captured the name as "can you tell me papa",
     * because the contact rule takes everything before the word number. Removing
     * the preamble once, here, fixes it for every rule at the same time.
     */
    private val POLITENESS = Regex(
        """^(?:hey |ok |okay |please |can you |could you |would you |will you |""" +
            """do you know |i want to know |i need |tell me |give me |show me |""" +
            """mujhe |bata do |batao )+"""
    )

    private class Rule(val pattern: Regex, val build: (MatchResult) -> Command?)

    private fun rule(pattern: String, build: (MatchResult) -> Command?) =
        Rule(Regex(pattern), build)

    private val rules: List<Rule> = listOf(

        // ---- torch, before "open X" so "turn on the light" is not an app ----
        rule("""^(?:turn |switch )?(?:on |chalu |jala ?(?:do|o) )?(?:the )?(?:torch|flashlight|light|batti)(?: ?(?:on|jala ?(?:do|o)|chalu(?: karo)?))?$""") {
            Command.Torch(true)
        },
        rule("""^(?:turn |switch )?(?:off |band )?(?:the )?(?:torch|flashlight|light|batti)(?: ?(?:off|band(?: karo| kar do)?|bujha ?do))$""") {
            Command.Torch(false)
        },
        rule("""^(?:turn |switch )(on|off) (?:the )?(?:torch|flashlight|light|batti)$""") {
            Command.Torch(it.groupValues[1] == "on")
        },
        rule("""^(?:toggle )(?:the )?(?:torch|flashlight|light)$""") { Command.Torch(null) },

        // ---- volume ----
        rule("""^(?:set )?volume (?:to |ko )?(\d{1,3})(?: percent)?$""") {
            it.groupValues[1].toIntOrNull()?.let { v -> Command.VolumeSet(v) }
        },
        rule("""^(?:turn |kar )?(?:the )?(?:volume|sound|awaaz|आवाज) ?(up|down|badhao|kam karo|ghatao|zyada karo)$""") {
            val up = it.groupValues[1] in setOf("up", "badhao", "zyada karo")
            Command.VolumeStep(up)
        },
        rule("""^(?:increase|raise|badhao) (?:the )?(?:volume|sound|awaaz)$""") { Command.VolumeStep(true) },
        rule("""^(?:decrease|lower|reduce|kam karo) (?:the )?(?:volume|sound|awaaz)$""") { Command.VolumeStep(false) },
        rule("""^(?:louder|volume up)$""") { Command.VolumeStep(true) },
        rule("""^(?:quieter|volume down)$""") { Command.VolumeStep(false) },

        // ---- ringer ----
        rule("""^(?:put (?:the )?phone (?:on|in) |go |switch to )?silent(?: mode| kar do| karo)?$""") {
            Command.RingerMode(Command.Ringer.SILENT)
        },
        rule("""^(?:put (?:the )?phone (?:on|in) |go |switch to )?vibrate(?: mode| kar do| karo| only)?$""") {
            Command.RingerMode(Command.Ringer.VIBRATE)
        },
        rule("""^(?:put (?:the )?phone (?:on|in) |go |switch to )?(?:normal|loud|ringer)(?: mode| kar do| karo)?$""") {
            Command.RingerMode(Command.Ringer.NORMAL)
        },

        // ---- status ----
        rule("""^(?:whats |what is |how much |kitni )?(?:the )?battery(?: (?:level|status|percentage|percent|hai|kitni hai|bachi hai))?$""") {
            Command.BatteryStatus
        },
        rule("""^(?:how much )?(?:storage|space|memory)(?: (?:is )?(?:left|free|status|available))?$""") {
            Command.StorageStatus
        },

        // ---- clock ----
        rule("""^(?:whats |what is |tell me )?(?:the )?time(?: (?:now|is it|kya hua|kya hai))?$""") { Command.TimeNow },
        rule("""^what time is it(?: now)?$""") { Command.TimeNow },
        rule("""^(?:whats |what is |tell me )?(?:the )?(?:date|day)(?: (?:today|is it|is today|kya hai))?$""") { Command.DateToday },
        rule("""^what (?:is the )?date(?: is it)?(?: today)?$""") { Command.DateToday },
        rule("""^(?:aaj )?(?:kya )?(?:din|tareekh|date) (?:kya )?hai$""") { Command.DateToday },
        rule("""^what day is it(?: today)?$""") { Command.DateToday },

        // ---- alarms and timers, before "open X" ----
        rule("""^(?:set|lagao|laga do)?\s*(?:an? )?alarm (?:for|at|ke liye) (.+)$""") { alarmFrom(it.groupValues[1]) },
        rule("""^wake me (?:up )?at (.+)$""") { alarmFrom(it.groupValues[1]) },
        rule("""^(.+?) (?:baje )?(?:ka |ke )?alarm (?:laga ?do|lagao|set karo)$""") { alarmFrom(it.groupValues[1]) },
        rule("""^(?:set|start)?\s*(?:a )?timer (?:for|of) (.+)$""") { timerFrom(it.groupValues[1]) },
        rule("""^(.+?) (?:ka |ke )?timer (?:laga ?do|lagao|set karo|start karo)$""") { timerFrom(it.groupValues[1]) },

        // ---- screen control, before "open X" so none of these read as app names ----
        rule("""^(?:go |press )?back$""") { Command.GlobalAction(Command.Screen.BACK) },
        rule("""^(?:peeche jao|wapas jao)$""") { Command.GlobalAction(Command.Screen.BACK) },
        rule("""^(?:go |press )?home(?: screen)?$""") { Command.GlobalAction(Command.Screen.HOME) },
        rule("""^(?:show )?recent(?: apps| screens)?$""") { Command.GlobalAction(Command.Screen.RECENTS) },
        rule("""^(?:show )?recents$""") { Command.GlobalAction(Command.Screen.RECENTS) },
        rule("""^lock(?: the)?(?: screen| phone| it)?$""") { Command.GlobalAction(Command.Screen.LOCK) },
        rule("""^(?:phone |screen )?lock kar ?do$""") { Command.GlobalAction(Command.Screen.LOCK) },
        rule("""^(?:take (?:a )?)?screenshot(?: lo| le lo)?$""") {
            Command.GlobalAction(Command.Screen.SCREENSHOT)
        },
        rule("""^(?:open |show |pull down )(?:the )?notification(?:s)?(?: shade| panel)?$""") {
            Command.GlobalAction(Command.Screen.NOTIFICATIONS)
        },

        rule("""^(?:whats|what is) on (?:my |the )?screen$""") { Command.ReadScreen },
        rule("""^read (?:the |my )?screen$""") { Command.ReadScreen },
        rule("""^(?:screen (?:padho|padh do)|screen mein kya hai)$""") { Command.ReadScreen },

        // WhatsApp before the generic text rules, or "whatsapp X saying Y" is an SMS.
        rule("""^whats ?app (.+?) (?:saying|that says|ki) (.+)$""") {
            whatsApp(it.groupValues[1], it.groupValues[2])
        },
        rule("""^(.+?) ko whats ?app (?:karo |kar do |bhejo )?(?:ki )?(.+)$""") {
            whatsApp(it.groupValues[1], it.groupValues[2])
        },

        // ---- calls, fixed phrases first so they cannot be read as names ----
        rule("""^(?:call|dial|phone) (?:back|the last number)$""") { Command.CallBack },
        rule("""^(?:redial|call back)$""") { Command.CallBack },

        rule("""^(?:answer|pick up|accept)(?: the)?(?: call| phone)?$""") { Command.AnswerCall },
        rule("""^(?:phone |call )?(?:uthao|utha lo)$""") { Command.AnswerCall },
        rule("""^(?:reject|decline|hang up|hangup|cut|end|disconnect)(?: the)?(?: call| phone)?$""") {
            Command.RejectCall
        },
        rule("""^(?:call )?(?:kaat do|kat do|cut karo|kaato)$""") { Command.RejectCall },

        rule("""^(?:turn |switch |put )?(?:the )?speaker ?(?:phone)? (on|off)$""") {
            Command.Speakerphone(it.groupValues[1] == "on")
        },
        rule("""^(?:turn |switch )(on|off) (?:the )?speaker ?(?:phone)?$""") {
            Command.Speakerphone(it.groupValues[1] == "on")
        },

        rule("""^who called(?: me)?(?: today)?$""") { Command.WhoCalled },
        rule("""^kiska (?:call|phone) (?:aaya|aya)(?: tha)?$""") { Command.WhoCalled },

        // Reply before the send rules: "reply to X saying Y" is a different act
        // from "text X saying Y" and must not be swallowed by it.
        rule("""^(?:reply|respond)(?: to)? (.+?) (?:saying|with|that says|ki) (.+)$""") {
            val who = it.groupValues[1].trim()
            val body = it.groupValues[2].trim()
            if (who.isEmpty() || body.isEmpty()) null else Command.ReplyToNotification(who, body)
        },

        // ---- messages, Hinglish first for the same reason as contacts ----
        rule("""^(.+?) ko (?:message|text|sms) (?:karo |bhejo |kar do )?(?:ki )?(.+)$""") {
            sms(it.groupValues[1], it.groupValues[2])
        },
        rule("""^(?:send (?:a |an )?(?:text|message|sms) to|text|message|sms) (.+?) (?:saying|that says|about|ki) (.+)$""") {
            sms(it.groupValues[1], it.groupValues[2])
        },

        rule("""^(?:read|check)? ?(?:out )?(?:my |the )?(?:messages|notifications|texts)$""") {
            Command.ReadNotifications
        },
        rule("""^what did i miss$""") { Command.ReadNotifications },

        // "call X" is broad, so it sits after every fixed call phrase above.
        rule("""^(.+?) ko (?:call|phone) (?:karo|kar do|lagao|milao)$""") {
            Command.CallContact(it.groupValues[1].trim())
        },
        rule("""^(?:call|dial|phone|ring) (?:up )?(.+)$""") {
            it.groupValues[1].trim().takeIf { n -> n.isNotEmpty() }
                ?.let { n -> Command.CallContact(n) }
        },

        // ---- contacts ----
        // Hinglish first: the English pattern is broad enough to swallow
        // "rohit ka number" and read the name as "rohit ka".
        rule("""^(.+?) ka (?:phone )?number(?: kya hai| batao)?$""") {
            Command.ContactNumber(it.groupValues[1].trim())
        },
        rule("""^(?:whats |what is )?(.+?)s? (?:phone )?number$""") {
            it.groupValues[1].trim().takeIf { n -> n.isNotEmpty() }?.let { n -> Command.ContactNumber(n) }
        },
        // ---- apps, last because "open X" is the broadest thing here ----
        rule("""^(?:open|launch|start|run|kholo) (?:the )?(?:app )?(.+?)(?: app)?$""") {
            it.groupValues[1].trim().takeIf { q -> q.isNotEmpty() }?.let { q -> Command.OpenApp(q) }
        },
        rule("""^(.+?) (?:ko )?(?:kholo|khol do|open karo|chalu karo)$""") {
            Command.OpenApp(it.groupValues[1].trim())
        },
    )

    fun match(transcript: String): Command {
        val text = normalise(transcript)
        if (text.isEmpty()) return Command.Unmatched(transcript)
        for (r in rules) {
            val m = r.pattern.find(text) ?: continue
            val command = r.build(m) ?: continue
            return command
        }
        return Command.Unmatched(transcript)
    }

    /**
     * Lowercase, punctuation stripped, whitespace collapsed. Apostrophes are
     * removed rather than replaced, so "what's" becomes "whats" and the patterns
     * only ever have to spell it one way.
     */
    fun normalise(transcript: String): String {
        val stripped = transcript
            .lowercase()
            .replace("'", "")
            .replace("\u2019", "")
            .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        // Applied repeatedly: "can you please tell me" is three layers deep.
        return POLITENESS.replace(stripped, "").trim()
    }

    private fun whatsApp(name: String, message: String): Command? {
        val who = name.trim()
        val body = message.trim()
        return if (who.isEmpty() || body.isEmpty()) null else Command.WhatsApp(who, body)
    }

    private fun sms(name: String, message: String): Command? {
        val who = name.trim()
        val body = message.trim()
        return if (who.isEmpty() || body.isEmpty()) null else Command.SendSms(who, body)
    }

    private fun alarmFrom(raw: String): Command? {
        val time = TimeParser.clockTime(raw) ?: return null
        return Command.SetAlarm(time.first, time.second)
    }

    private fun timerFrom(raw: String): Command? {
        val seconds = TimeParser.duration(raw) ?: return null
        return Command.SetTimer(seconds)
    }
}
