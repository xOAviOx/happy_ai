# Happy — progress and next steps

Working notes for resuming. Everything here was verified on the real phone, not
assumed. Last session ended after Phase 4.

**Test device:** vivo V2502 (T4 5G), Android 16, SDK 36, `10BFB817HN00992`.

---

## Where things stand

| Phase | State |
|---|---|
| 0 — Skeleton, service, checklist, log | Done, all five acceptance gates passed on device |
| 1 — Wake word (openWakeWord) | Done, scores 0.55–0.999 on "hey Jarvis", 0.000 in a quiet room |
| 2 — STT, mic handover | Done, on-device recogniser, zero mic leaks |
| 3 — TTS, sanitiser, barge-in | Done, 18 sanitiser tests passing |
| 4 — Router and offline handlers | Done, all commands verified by voice |
| 5 — Calls and messaging | **Written, compiles, 40 tests pass — NOT verified on device** |
| 6 — Accessibility | **Written, compiles, 44 tests pass — NOT verified on device** |
| 7 — Knowledge | **Written, compiles, 60 tests pass — NOT verified on device** |
| 8–9 | Not started |

**Build is green:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — 60 tests pass.

There is no git repository. Consider `git init` before the next change;
`.gitignore` is already written and `local.properties` is listed in it.

---

## Working on it

    ./gradlew :app:installDebug        # build and push

Happy's own log is the source of truth, **not logcat** — vivo suppresses
third-party logcat output entirely. Pull the on-device database:

    ADB=/c/Users/avish/AppData/Local/Android/Sdk/platform-tools/adb.exe
    for f in happy.db happy.db-wal happy.db-shm; do
      "$ADB" exec-out run-as com.happy.assistant cat "databases/$f" > "$f"
    done

Then read `log_entries` (timestamp, level, tag, message, durationMs) with sqlite.

Useful check — is the mic actually live?

    "$ADB" shell dumpsys activity services com.happy.assistant | grep -oE "types=0x[0-9a-f]+"
    # 0x40000000 = specialUse only, Happy is deaf
    # 0x40000080 = specialUse + microphone, working

**After every reinstall the mic comes back muted** until the app is opened from
the launcher. That is expected — see below. `adb shell am start` only helps if
the activity was not already resumed.

---

## Platform findings that cost real time

Each of these looked like a bug in Happy and was not.

**The microphone foreground service type cannot be self-granted.** Android
decides the while-in-use capability from the process state at the moment the
service is *started*, not continuously. A service started from BOOT_COMPLETED or
MY_PACKAGE_REPLACED can never promote itself to type `microphone`, however long
it retries — and the mic then returns *pure digital silence* rather than an
error. Only a fresh start command issued while an activity is foreground
re-evaluates it. `SetupActivity` watches `HappyService.micBlockedFlow` and kicks
the service, capped at three attempts. The silence watchdog exists so this shows
up as a log line rather than a wake word that mysteriously never fires.

**Android 15 refuses a `microphone` FGS started from BOOT_COMPLETED**, which is
why the service always enters the foreground as `specialUse` and promotes later.
Both types are declared in the manifest so the promotion is legal.

**vivo suppresses third-party logcat.** The Room-backed log is not a nicety.

**Vendor settings screens are hostile.** On this Funtouch build,
`com.vivo.permissionmanager` and `com.iqoo.secure` do not exist, and
`com.iqoo.powersaving/.activity.ExcessivePowerManagerActivity` resolves, reports
`exported=true`, and *still* throws SecurityException when launched. Only
`com.iqoo.powersaving/.PowerManagerSettingsActivity` opens. Never trust a single
component name; try candidates until one opens.

**AlarmClock intents DO need a permission**, contrary to the build spec:
`com.android.alarm.permission.SET_ALARM`. Without it every alarm and timer fails
with a SecurityException.

**Volume needs `MODIFY_AUDIO_SETTINGS`**, and `adjustStreamVolume` can be a
silent no-op. Also `STREAM_MUSIC` sits pinned at max whenever nothing is playing
— even hardware volume keys will not move it. Happy targets the stream the keys
target: media if `isMusicActive`, otherwise the ringer.

**Package visibility:** without the `<queries>` element the launcher list comes
back empty, which is indistinguishable from "no app matched".

---

## Bugs worth remembering

Found by tests, before the phone ever saw them:

- Stripping a disallowed character glued tokens: `24/7` became `247`, read aloud
  as "two hundred and forty seven". Disallowed characters become a space.
- Currency was spoken backwards — `₹40` as "rupees 40". Symbols precede the
  amount in text and follow it in speech.
- `half an hour` parsed as **thirty hours**.
- `Numbers.parse` had an if/else with identical branches.

Found on the phone:

- `rohit ka number` matched the English pattern first, yielding a contact named
  "rohit ka". Hinglish rules now come first — a general ordering rule.
- `Can you tell me Papa's number` captured the name as "can you tell me papa".
  Courtesy prefixes are now stripped centrally in `normalise`.
- `Chutrant` (for Chitransh) is three edits, over the fixed limit of two. The
  edit budget now scales with name length; short names stay strict.
- Two concurrent restart requests each cancelled-and-started the pipeline,
  leaving **two loops sharing one mic and the detector's unsynchronised
  buffers** — surfaced as `MIC LEAK: 2 sessions open at once` and a
  `BufferOverflowException`. All pipeline starts now go through a `Mutex`.
  The Phase 2 mic-leak guard is the only reason this was legible.

---

## Tuning values measured on this phone

- Wake threshold **0.50**. Quiet room peaks 0.000–0.001; true positives
  0.55–0.999, most above 0.98.
- Barge-in RMS **3000**. With echo cancellation on, Happy's own speech peaks at
  **2271** in the mic, so it cannot cut itself off.
- AEC and noise suppression are both available and enabled.
- TTS voice `en_IN` is installed and selected.
- Heartbeat drifts 5–15 min when idle because `delay` does not run in deep
  sleep. Uptime continuity is the survival test, not heartbeat spacing.

---

## Phase 5 — built, awaiting device testing

Everything below compiles and the routing is unit tested, but **none of it has
run on the phone**. Unit tests cover string handling only; every Android API call
here is unverified.

New files: `handlers/CallHandler.kt`, `handlers/MessageHandler.kt`,
`handlers/NotificationHandler.kt`, `service/HappyNotificationListener.kt`.

What it does:

- **Call by name**, call back from the log, answer and reject via
  `TelecomManager`, speakerphone via `setCommunicationDevice`, "who called me"
  from the call log with reverse contact lookup.
- **Send a text**, with a spoken confirmation first — Happy reads the message
  back and only sends on a clear yes. Anything not clearly yes is treated as no.
- **Read notifications aloud** and **reply inline via RemoteInput**, which works
  in any app supporting inline reply, with no accessibility scripting.
- Contact ambiguity is now one path for all three actions: `Pending.ChooseContact`
  carries a `ContactAction`, so "do you mean Rohit or Mohit" behaves identically
  whether reading a number, calling, or texting. Nothing irreversible happens
  while more than one person still matches.

### Before testing, on the phone

1. Open Happy and grant **Notification access** — the checklist row is live now
   that the listener service exists.
2. The listener needs the toggle flipped *after* this install; it will not be
   bound otherwise.

### What to test, riskiest first

- **Sending a text.** The only irreversible action here. Confirm the read-back
  names the right person before saying yes.
- **Answer and reject** need a live incoming call, so they cannot be tested any
  other way.
- **Inline reply** needs a message from an app that offers inline reply
  (WhatsApp, Messages). `canReplyTo` filters to notifications that actually
  carry a RemoteInput action.
- Call by name, call back, who called, speaker on and off.

### Known limits

- The follow-up loop allows **two rounds**, which is exactly what an ambiguous
  text needs (pick the person, then confirm the message). A flow needing three
  would be cut off.
- `MessageHandler.isYes` treats anything unrecognised as no. Deliberate.

## Phase 6 — built, awaiting device testing

Also unverified on hardware. New files: `service/HappyAccessibilityService.kt`,
`handlers/ScreenHandler.kt`, `handlers/WhatsAppHandler.kt`, plus
`res/xml/accessibility_service_config.xml`.

- **Global actions**: back, home, recents, lock, screenshot, notification shade.
- **Read the screen**: walks `rootInActiveWindow` and reads it back, truncated to
  twelve lines. Phase 7 replaces this with a Gemini summary, at which point the
  full text goes to the model rather than the speaker.
- **WhatsApp send** to someone not already in a chat: opens a `wa.me` deep link
  with the message pre-filled, then presses send through accessibility.

The accessibility service is deliberately passive - it subscribes to no events
and does nothing in the background. It only acts when asked.

**WhatsApp send is the fragile one, by nature.** WhatsApp has no send API, so the
second half depends on view ids that change between releases. It fails in the
least annoying direction: if the send button is not found within about five
seconds, the chat is left open with the message typed and Happy says to tap send.
Nothing is lost and nothing is sent by accident. View ids tried are
`com.whatsapp:id/send` and the business build, then any clickable node described
as "send".

### Before testing

Grant **Accessibility** in the checklist - the row is live now that the service
exists. It resets on every reinstall, so it will need granting again after each
install.

### What to test

- Back, home, recents, lock, screenshot.
- "What's on my screen" on a text-heavy app.
- WhatsApp send to a contact, and check what happens when the send button is not
  found - it should leave the chat open rather than claiming success.

## Phase 7 — built, awaiting device testing

New files under `knowledge/`: `KnowledgeRouter`, `MathEvaluator`,
`WikipediaClient`, `WeatherClient`, `GeminiClient`, `SentenceChunker`.

Dispatch order, deterministic first, exactly as section 7 requires:

1. **Maths and unit conversion** on device. Arithmetic with real precedence,
   percentages, length, mass, volume, and temperature. Temperature is handled
   apart from the rest because it is an offset scale, not a ratio.
2. **Weather** from Open-Meteo, no key. Uses the last known location, or a named
   city via Open-Meteo geocoding, which needs no permission at all.
3. **Wikipedia** for named entities, trimmed to two sentences. A disambiguation
   page or a 404 falls through rather than apologising.
4. **Gemini** only for what is left. Time, date, battery and storage never reach
   here at all - the intent router matches those as commands first.

Streaming speaks sentence by sentence through `SentenceChunker`, which holds back
decimals, initials like "J. R. R." and abbreviations so the speech does not
stutter mid-sentence. `Speaker.enqueue` queues without blocking, so collecting
the next chunk is not stalled by the sentence currently being read.

Rate limiting backs off 1, 2, 4, 8 seconds then says it has hit the daily limit,
without quoting a number - Google revises free-tier quotas without notice.
Offline says "I need a connection for that."

**Follow-up mode** is live: five seconds after any answer, "explain more" or
"tell me more" re-asks with thinking enabled and a larger token budget. Anything
else said in that window is treated as a fresh command rather than ignored. The
window is offered only after questions, never after commands.

### What to test

- Maths and conversions out loud, and check the answers.
- "What's the weather" and "weather in Delhi tomorrow".
- "Who is Ada Lovelace" should come from Wikipedia, fast.
- "Why is the sky blue" should stream from Gemini, and should start speaking
  before the whole answer has arrived - that is the thing to listen for.
- "Explain more" within five seconds of an answer.
- Airplane mode: device commands must still work, questions must fail gracefully.

## Phase 8 next — Spotify

Design decisions already made:

1. **Reuse the follow-up machinery for calls.** `Pending.ChooseContact` should
   carry the action to perform once a person is picked, not just "read the
   number" — so ambiguity works identically for call, SMS and lookup. Suggested:
   `ChooseContact(options, then: ContactAction)` where `ContactAction` is
   `ReadNumber | Call | Sms(message)`.
2. **Confirm before sending an SMS.** Read the message back and require a yes.
   Recognition errors are common and a sent text is not recoverable. The
   follow-up loop already supports this.
3. Answer and reject via `TelecomManager` (`acceptRingingCall`, `endCall`) —
   both need `ANSWER_PHONE_CALLS`, already declared and granted.
4. Speakerphone via `setCommunicationDevice`; minSdk is 31, so the deprecated
   `setSpeakerphoneOn` is not needed.
5. `HappyNotificationListener` for reading notifications aloud and replying via
   `RemoteInput` — preferred over accessibility taps. Declaring the service
   flips the checklist row for notification access from "Phase 5" to live, so
   set `implemented = true` on that `CheckItem`.
6. Calls and the alarm intents both need the overlay grant, already given.

Commands to add to `Command.kt`: `CallContact`, `CallBack`, `AnswerCall`,
`RejectCall`, `Speakerphone`, `SendSms`, `ReadNotifications`, `WhoCalled`.
The `when` in `CommandExecutor` is exhaustive, so it will name what is missing.

### Router rules as built

Now in `IntentRouter`, before the contacts section. Ordering matters and is
tested: fixed call phrases come before the broad `call X`, or "call back" parses
as a person named "back"; and "reply to X saying Y" comes before "text X saying
Y" so the two acts stay distinct.

```kotlin
// ---- calls, in-call controls first so they cannot be read as names ----
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
```

With this helper beside `alarmFrom`:

```kotlin
private fun sms(name: String, message: String): Command? {
    val who = name.trim()
    val body = message.trim()
    return if (who.isEmpty() || body.isEmpty()) null else Command.SendSms(who, body)
}
```

---

## Phase 7 groundwork, measured against the live API

The Gemini key is in `local.properties` and verified working: it authenticates as
an API key on `?key=`, is rejected as a bearer token, and `gemini-2.5-flash` is
available with a 1M input limit.

**Streaming only works with thinking disabled.** Three runs each, same prompt,
asking for six sentences:

| Config | Time to first byte | Chunks |
|---|---|---|
| default, thinking on | 4.33 / 3.14 / 3.58 s | **1** |
| `thinkingConfig.thinkingBudget = 0` | 2.80 / 6.59 / 1.23 s | **4** |

With thinking on the whole answer arrives as a single chunk, so sentence-by-
sentence TTS - the spec's biggest perceived-latency win - would be dead code.
**Set `thinkingBudget: 0` for normal answers.** Keep thinking available for an
explicit "explain more", where the wait is expected.

Note `streamGenerateContent` is absent from the model's advertised
`supportedGenerationMethods`, yet works. Do not trust that field.

**The latency target is not currently reachable.** The spec budgets 600–1500 ms
to first token and 2.5 s to the first spoken word; measured range was 1.2–6.6 s
with wide variance, on a desktop connection rather than the phone's. Design for
it: Wikipedia-first routing avoids Gemini entirely for entity lookups, and
something should fill the silence - a short earcon, or a spoken "let me think" -
rather than leaving several seconds of nothing.

## Open items, not blocking

- **Wake-word false-positive soak never run.** The spec wants a full day of
  ordinary speech before Phase 1 is signed off. Every detection is logged with a
  score, so the data can be gathered whenever.
- **Battery drain unmeasured** with the mic always on. Spec budgets 3–8% per 24h.
- **Spotify client secret deliberately not stored.** PKCE needs no secret, and
  one compiled into a sideloaded APK is extractable. The secret was shared in
  chat once and should be rotated; the client ID is public by design and is in
  `local.properties`.
- **Vosk fallback deliberately skipped** — the on-device recogniser works
  (`onDevice=true`). Revisit only if it ever fails.
- **`WRITE_SETTINGS` ungranted** — only needed for brightness.
- **The proper fix for the muted-mic-after-reboot problem** is registering Happy
  as the system digital assistant via `RoleManager.ROLE_ASSISTANT`, which grants
  always-on microphone rights. Phase 9 material, but it removes the "open the app
  once after every reboot" caveat entirely.
- Once, the recogniser transcribed the wake phrase itself as the command
  ("Hey Jarvis"). Seen a single time; add a short gap after detection if it
  recurs.
