# Study NFC Tracker

Tap an NFC tag to start a focused study session: the phone silences its notifications and
starts tracking. A second tag switches between *school work* and *personal project*
mid-session. Tapping the first tag again ends the session and unsilences.

This repository is **phase 1** of [the design](../study-mode-tracker-design.md): tags, tap,
Do Not Disturb, toast, and today's hours kept on the phone. No network, no database, no
Google Sheet yet — the point of phase 1 is to find out whether the gesture is one you
actually use.

## The gesture

```
tap STUDY   → mode ON,  silenced, tracking SCHOOL
tap SWITCH  → now tracking PERSONAL
tap SWITCH  → back to SCHOOL
tap STUDY   → mode OFF, unsilenced
```

`SWITCH` tapped while the mode is off does nothing. The same tag tapped twice within two
seconds counts once — the radio can read one physical tap twice, and that would otherwise
start and immediately end a session.

## Setup

1. **Open the app and grant Do Not Disturb access.** There is no in-app dialog for this
   permission; the button walks you to the right system screen. Nothing works without it.
2. **Program two NTAG215 stickers.** Pick `STUDY`, hold a tag against the back of the phone,
   wait for "Written". Pick `SWITCH`, do the same with the other one.
3. **Label the tags with a pen.** They look identical, and there is no way to tell them
   apart afterwards without tapping them.

Then close the app. Day to day you tap the tags; you only open the app to check your hours.

## Seeing your hours

Every tap that ends a stretch of time reports it — tapping SWITCH after 45 minutes of school
work says so, and so does tapping STUDY to finish. Short stretches report in seconds, which is
what makes the app demonstrable without waiting around:

```
Study mode off
47 min 12 s of personal project recorded
```

Open the app for the day's running totals:

```
Study mode is ON — school work.
Running for 12 min 30 s — not counted below until you tap.

Today
  school work       2 h 14 min 8 s
  personal project     35 min 41 s
  total             2 h 49 min 49 s
```

Two things worth knowing about those numbers:

- **Only today.** Yesterday's totals are dropped the moment the first segment of a new day is
  recorded. Keeping real history is what the Google Sheet is for in phase 2 — here it would
  mean a log that grows forever and pruning rules to get wrong.
- **A session that crosses midnight counts entirely on the day it ended.** Splitting it would
  be more accurate and a lot more code; the Sheet gets this right later from raw timestamps.
- **Durations read in hours, minutes and seconds, with zero units left out** — `2 h 14 min 3 s`,
  but `1 h` for an exact hour and `40 s` for a short demo tap. Never `0 hours`.
- **The total is the sum of the figures above it**, each snapped to the second it is printed at,
  rather than the raw sum rounded separately. Otherwise the column can disagree with itself.

The stretch running right now is shown separately because it is not recorded anywhere yet — it
joins the totals when a tap closes it. That also means the numbers only refresh when you open
the app; nothing ticks in the background.

## How a tap reaches the app

Each tag holds one NDEF record with the MIME type
`application/vnd.com.jelena.studytracker` and a payload of `study` or `switch`. That
app-specific MIME type is what lets an `NDEF_DISCOVERED` intent filter launch the app on a
tap even when it is closed. A plain URL record could not do this — Android would open a
browser instead.

```
tag tapped
  → Android launches TagIntentActivity (no visible window)
  → StudyModeController computes the new state
  → DndController silences or unsilences the phone
  → StudyStateStore saves the state
  → toast: "Study mode on — school work"
  → activity finishes
```

## The files

| File | Job |
|---|---|
| `StudyTag.kt` | The two tags and the text written on them. |
| `StudyState.kt` | What the app knows after the last tap. Immutable. |
| `StudyModeController.kt` | The rules: current state + tag → new state, plus the stretch of time that tap just ended. No Android imports. |
| `StudyTime.kt` | Segments, the day's totals, the local-midnight boundary, duration wording. All pure. |
| `StudyStateStore.kt` | Saves the state and today's totals between taps (`SharedPreferences`). |
| `DndController.kt` | Silences and unsilences the phone. |
| `TagIntentActivity.kt` | No UI. Handles a tap, toasts, finishes. |
| `MainActivity.kt` | Setup: permission, programming tags, current state. |

`StudyModeController` and `StudyTime` have no Android dependencies on purpose. They hold the
only real rules in the app, so every state × tag combination — and every bit of the time
arithmetic — is covered by plain JVM unit tests: no emulator, no Robolectric, no tags needed:

```bash
./gradlew testDebugUnitTest
```

## Building

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

- **Set the Gradle JDK to 21** in *Settings → Build, Execution, Deployment → Build Tools →
  Gradle*. AGP rejects JDK 25 and 26, and Android Studio's bundled JBR is 25. The failure
  message is just the version number, with no explanation.
- **The emulator cannot do NFC.** All testing needs a real phone.
- NFC needs the screen on and unlocked, on every phone. The real gesture is: wake phone →
  tap tag → put phone down.

## License

[MIT](LICENSE) — do what you like with it, including at your own workshop.

## Not in phase 1

Room queue, `SyncWorker`, the Apps Script web app, the Google Sheet, the stats screen, and
the iPhone Shortcut. See the design document for those.
