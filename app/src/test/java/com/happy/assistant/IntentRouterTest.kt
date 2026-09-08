package com.happy.assistant

import com.happy.assistant.router.Command
import com.happy.assistant.router.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router is pure string work, so it is tested on the JVM with no device.
 * Each case is a phrasing this phone is actually likely to hear.
 */
class IntentRouterTest {

    private val router = IntentRouter()

    private inline fun <reified T : Command> assertMatches(vararg phrases: String) {
        for (phrase in phrases) {
            val command = router.match(phrase)
            assertTrue("$phrase produced $command", command is T)
        }
    }

    @Test
    fun `torch on and off`() {
        assertEquals(Command.Torch(true), router.match("turn on the torch"))
        assertEquals(Command.Torch(true), router.match("flashlight on"))
        assertEquals(Command.Torch(true), router.match("batti jala do"))
        assertEquals(Command.Torch(false), router.match("turn off the flashlight"))
        assertEquals(Command.Torch(false), router.match("torch off"))
        assertEquals(Command.Torch(false), router.match("light band karo"))
    }

    @Test
    fun `torch beats open app`() {
        // "light" must not be treated as an app name.
        assertEquals(Command.Torch(true), router.match("turn on the light"))
    }

    @Test
    fun `volume steps and levels`() {
        assertEquals(Command.VolumeStep(true), router.match("volume up"))
        assertEquals(Command.VolumeStep(false), router.match("volume down"))
        assertEquals(Command.VolumeStep(true), router.match("increase the volume"))
        assertEquals(Command.VolumeStep(true), router.match("awaaz badhao"))
        assertEquals(Command.VolumeSet(40), router.match("set volume to 40"))
        assertEquals(Command.VolumeSet(60), router.match("volume 60 percent"))
    }

    @Test
    fun `ringer modes`() {
        assertEquals(Command.RingerMode(Command.Ringer.SILENT), router.match("silent mode"))
        assertEquals(Command.RingerMode(Command.Ringer.VIBRATE), router.match("vibrate"))
        assertEquals(Command.RingerMode(Command.Ringer.NORMAL), router.match("normal mode"))
    }

    @Test
    fun `status questions`() {
        assertEquals(Command.BatteryStatus, router.match("battery"))
        assertEquals(Command.BatteryStatus, router.match("how much battery"))
        assertEquals(Command.BatteryStatus, router.match("what is the battery percentage"))
        assertEquals(Command.StorageStatus, router.match("how much storage is left"))
    }

    @Test
    fun `time and date`() {
        assertEquals(Command.TimeNow, router.match("what time is it"))
        assertEquals(Command.TimeNow, router.match("what's the time"))
        assertEquals(Command.DateToday, router.match("what's the date"))
        assertEquals(Command.DateToday, router.match("what day is it today"))
    }

    @Test
    fun `alarms resolve to a 24 hour clock`() {
        assertEquals(Command.SetAlarm(7, 0), router.match("set an alarm for 7 am"))
        assertEquals(Command.SetAlarm(19, 30), router.match("set an alarm for 7 30 pm"))
        assertEquals(Command.SetAlarm(6, 30), router.match("wake me up at 6 30"))
        assertEquals(Command.SetAlarm(5, 0), router.match("set alarm for five"))
        // "a.m." survives normalisation as two separate tokens.
        assertEquals(Command.SetAlarm(0, 42), router.match("set an alarm for 12:42 a.m."))
        // Trailing filler must not void the time.
        assertEquals(Command.SetAlarm(7, 0), router.match("set an alarm for 7 am right now"))
        // Half of the day said in words rather than as am or pm.
        assertEquals(Command.SetAlarm(19, 0), router.match("set an alarm for 7 in the evening"))
        assertEquals(Command.SetAlarm(6, 30), router.match("wake me up at 6 30 in the morning"))
    }

    @Test
    fun `timers in seconds`() {
        assertEquals(Command.SetTimer(600), router.match("set a timer for 10 minutes"))
        assertEquals(Command.SetTimer(30), router.match("set a timer for 30 seconds"))
        assertEquals(Command.SetTimer(3600), router.match("set a timer for 1 hour"))
        // The one that used to parse as thirty hours.
        assertEquals(Command.SetTimer(1800), router.match("set a timer for half an hour"))
    }

    @Test
    fun `open app takes everything after the verb`() {
        assertEquals(Command.OpenApp("spotify"), router.match("open spotify"))
        assertEquals(Command.OpenApp("google maps"), router.match("launch google maps"))
        assertEquals(Command.OpenApp("whatsapp"), router.match("whatsapp kholo"))
    }

    @Test
    fun `contact number lookup`() {
        assertEquals(Command.ContactNumber("rohit"), router.match("what's rohit's number"))
        assertEquals(Command.ContactNumber("rohit"), router.match("rohit ka number"))
    }

    @Test
    fun `politeness does not become part of the name`() {
        // These are the phrasings that actually failed on the phone: the contact
        // rule takes everything before "number", so the preamble has to go first.
        assertEquals(Command.ContactNumber("papa"), router.match("Can you tell me Papa's number"))
        assertEquals(Command.ContactNumber("mom"), router.match("please tell me mom's number"))
        assertEquals(Command.OpenApp("spotify"), router.match("hey can you open spotify"))
        assertEquals(Command.TimeNow, router.match("can you tell me the time"))
    }

    @Test
    fun `call phrases that are not names`() {
        // "call back" must not become a person called "back".
        assertEquals(Command.CallBack, router.match("call back"))
        assertEquals(Command.CallBack, router.match("call the last number"))
        assertEquals(Command.AnswerCall, router.match("pick up"))
        assertEquals(Command.AnswerCall, router.match("answer the call"))
        assertEquals(Command.RejectCall, router.match("hang up"))
        assertEquals(Command.RejectCall, router.match("cut the call"))
        assertEquals(Command.Speakerphone(true), router.match("speaker on"))
        assertEquals(Command.Speakerphone(false), router.match("turn off the speakerphone"))
        assertEquals(Command.WhoCalled, router.match("who called me"))
    }

    @Test
    fun `calling a person`() {
        assertEquals(Command.CallContact("rohit"), router.match("call rohit"))
        assertEquals(Command.CallContact("mom"), router.match("please call mom"))
        assertEquals(Command.CallContact("papa"), router.match("papa ko call karo"))
    }

    @Test
    fun `texts carry the recipient and the body`() {
        assertEquals(
            Command.SendSms("rohit", "i am late"),
            router.match("text rohit saying i am late")
        )
        assertEquals(
            Command.SendSms("mom", "reaching by eight"),
            router.match("send a message to mom saying reaching by eight")
        )
        assertEquals(
            Command.SendSms("papa", "ghar aa raha hu"),
            router.match("papa ko message karo ki ghar aa raha hu")
        )
    }

    @Test
    fun `reply is not the same act as send`() {
        // Must not be swallowed by the "text X saying Y" rule.
        assertEquals(
            Command.ReplyToNotification("rohit", "on my way"),
            router.match("reply to rohit saying on my way")
        )
    }

    @Test
    fun `reading notifications`() {
        assertEquals(Command.ReadNotifications, router.match("read my messages"))
        assertEquals(Command.ReadNotifications, router.match("what did I miss"))
        assertEquals(Command.ReadNotifications, router.match("check notifications"))
    }

    @Test
    fun `anything else falls through unmatched`() {
        assertMatches<Command.Unmatched>(
            "why is the sky blue",
            "explain loop quantum gravity",
            "who is ada lovelace",
        )
        assertEquals(
            "why is the sky blue",
            (router.match("why is the sky blue") as Command.Unmatched).text
        )
    }

    @Test
    fun `normalisation strips punctuation and case`() {
        assertEquals("whats the time", router.normalise("What's the TIME?"))
    }
}
