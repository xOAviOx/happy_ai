# Happy

A wake-word voice assistant for one Android phone. Sideloaded, personal use, zero
recurring cost. Built strictly phase by phase - see the build spec.

**Current state: Phase 0 (skeleton) complete. Happy does not listen yet.**

## What exists

| Piece | File |
|---|---|
| Foreground service, notification, heartbeat, state machine | `app/src/main/java/com/happy/assistant/service/HappyService.kt` |
| Restart after reboot or app update | `service/BootReceiver.kt` |
| Pipeline states (spec section 4) | `service/HappyState.kt` |
| On-device log, written by everything, never silent | `core/HappyLog.kt` |
| Room store for that log | `data/LogEntry.kt`, `data/HappyDatabase.kt` |
| Settings (DataStore) | `data/Prefs.kt` |
| Permission checklist and OEM deep links | `ui/Checklist.kt`, `ui/OemAutostart.kt`, `ui/SetupActivity.kt` |
| Log viewer | `ui/LogActivity.kt` |

## Build and install

    ./gradlew :app:assembleDebug
    ./gradlew :app:installDebug          # with a phone connected and USB debugging on

Or install the APK by hand from `app/build/outputs/apk/debug/app-debug.apk`.

Toolchain is pinned in `gradle/libs.versions.toml` to versions already in the
local Gradle cache, so the first build does not pull a new toolchain: AGP 8.7.3,
Kotlin 2.0.21, Hilt 2.52, Room 2.6.1, Compose BOM 2024.12.01. compileSdk and
targetSdk are 35 because AGP 8.7.3 does not support 36; bump both together.

## Secrets

`local.properties` holds `GEMINI_API_KEY` and `SPOTIFY_CLIENT_ID`, is gitignored,
and is surfaced to code through `BuildConfig`. Both are empty until Phases 7 and
8. Nothing else may hold a key.

## Phase 0 acceptance tests

Run these on the real phone before starting Phase 1.

1. **Grant and start.** Open Happy, grant Notifications, allow the battery
   optimisation exemption, follow the autostart hint for this phone, tap
   *Start Happy*. The notification appears and the card reads *Running / Idle*.
2. **Survives an hour with the screen off.** Lock the phone, leave it an hour,
   open *View log*. What proves survival is an unbroken *uptime* count - the last
   `heartbeat, up 1h 5m` with no `service created` line in between. The service
   died if and only if uptime resets.

   Do not read the five-minute spacing as the test. The heartbeat is a plain
   coroutine `delay`, which does not run while the CPU is suspended, so on an idle
   phone the intervals drift: measured 5.0 to 15.6 minutes over an hour on a vivo
   V2502. That is deep sleep working correctly, not Happy being killed. From
   Phase 1 the drift should disappear on its own, because an active `AudioRecord`
   holds the CPU awake - and if it does not, that is a genuine finding about the
   wake-word loop being suspended.
3. **Survives a reboot.** Reboot. Within a minute of unlocking, the notification
   is back and the log has a `Boot: received android.intent.action.BOOT_COMPLETED,
   serviceEnabled=true` line.
4. **Survives clear-all.** Swipe Happy out of recents. The notification stays and
   the log records `task removed from recents`.
5. **Stop means stop.** Tap *Stop* in the notification, then reboot. Happy must
   not come back: an explicit stop writes `serviceEnabled=false`.

## Deliberate decisions worth remembering

- The service enters the foreground as `specialUse`, never `microphone`. Android
  15 refuses to start a `microphone` foreground service from a BOOT_COMPLETED
  receiver, which would break restart-on-boot. Both types are declared in the
  manifest so Phase 1 can promote the type when the mic actually opens.
- The heartbeat exists purely to make survival auditable from the phone itself,
  with no cable and no logcat.
- Later-phase permissions are already in the checklist. Granting them early is
  harmless and a half-granted checklist is the most common reason an assistant
  like this looks broken.
- Notification access and Accessibility show as *Phase 5* and *Phase 6*: those
  services do not exist yet, so Happy would not appear in those Settings lists.

## Next

Phase 1: audio capture and the three-stage openWakeWord pipeline. On detection,
earcon and a logged score, nothing more.
