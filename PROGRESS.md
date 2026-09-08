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
| 5 — Calls and messaging | **Not started.** Draft router rules below |
| 6–9 | Not started |

**Build is green:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — 35 tests pass.

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

## Next: Phase 5 — calls and messaging

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

### Draft router rules

Written and compiling last session, then rolled back to keep the build green
while the handlers do not exist. Insert before the `// ---- contacts ----`
section of `IntentRouter`. Ordering matters: fixed call phrases must come before
the broad `call X`, or "call back" is parsed as a person named "back".

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
