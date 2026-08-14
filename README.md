# Study NFC Tracker

An Android app that turns two NFC stickers into a study timer.

Tap the **STUDY** tag and the phone silences its notifications and starts counting. Tap the
**SWITCH** tag to move between *school work* and *personal project* without stopping the clock.
Tap STUDY again to finish: notifications come back and the time is written down.

The point is that starting and stopping costs no attention — no app to open, no timer to set. You
tap a sticker on your desk and put the phone down.

```
tap STUDY   → mode ON,  notifications silenced, tracking SCHOOL
tap SWITCH  → now tracking PERSONAL
tap SWITCH  → back to SCHOOL
tap STUDY   → mode OFF, notifications back, time recorded
```

Everything stays on the phone. No account, no network, no server.

---

## What you need

| | |
|---|---|
| **An Android phone with NFC** | Android 7.0 (API 24) or newer. The emulator cannot do NFC, so testing needs a real device. |
| **Two writable NFC tags** | NTAG213/215/216 stickers are the usual choice and cost very little. Any NDEF-writable tag works. The app programs them for you. |
| **Do Not Disturb access** | A one-off grant in system settings. The app cannot silence anything without it. |
| **To build it yourself** | JDK 21 and the Android SDK. See [Building](#building). |

---

## Setting it up

**1. Install the app** and open it.

**2. Grant Do Not Disturb access.** The first line on screen says whether it is granted; the button
below takes you to the right system screen, because Android has no in-app dialog for this
permission. Nothing works until it is on.

**3. Program the tags.** Choose `STUDY`, hold a tag flat against the back of the phone, and wait for
*"Written: study"*. Choose `SWITCH` and do the same with the other tag. The app must be open for
this — while it is, it takes over the NFC radio so that tapping a tag writes to it instead of
triggering study mode.

**4. Label the tags with a pen.** They are identical once written, and the only way to tell them
apart afterwards is to tap one and see what happens.

**5. Set the auto-close cap** if three hours is not what you want. See
[Forgetting the second tap](#forgetting-the-second-tap).

Then close the app. Day to day you only tap the tags — you open the app to check your hours.

---

## Using it

**Tapping needs the screen on.** Android does not read tags while the screen is off, so the gesture
is: wake the phone → tap → put it down. Whether it works while *locked* depends on one system
setting — see [Locked phones](#locked-phones).

Every tap that ends a stretch of time says how long it was:

```
Study mode off
47 min 12 s of personal project recorded
```

Tapping SWITCH reports the same way, because switching category ends one stretch and starts
another. Stretches under a minute are reported in seconds, so you can try the whole thing out
without waiting around.

Two taps of the same tag within two seconds count as one. The radio can read a single physical tap
twice, and without that rule one tap would start and instantly end a session.

Tapping SWITCH while study mode is off does nothing, and says so.

### Your hours

Open the app:

```
Study mode is ON — school work.
Running for 12 min 30 s — not counted below until you tap.
Closes automatically in 2 h 47 min if you forget.

Today
  school work       2 h 14 min 8 s
  personal project     35 min 41 s
  total             2 h 49 min 49 s

Yesterday
  school work            1 h 5 min
  total                  1 h 5 min

Last 7 days
  school work      9 h 12 min 30 s
  personal project  2 h 4 min 10 s
  total            11 h 16 min 40 s
```

- **Nothing is ever overwritten.** Every finished stretch is appended to a file, and every figure
  here is computed from it, so a day rolling over loses nothing.
- **The stretch running now is listed apart from the totals** because it has not been recorded yet.
  It joins them when a tap ends it.
- **Those first lines tick every second**; the totals do not, because they cannot change while the
  app is open — a tap cannot reach the tap handler while this screen holds the NFC radio. The one
  exception is the auto-close firing while you watch, which the app notices and redraws for.
- **Durations leave out units that are zero** — `2 h 14 min 3 s`, but `1 h` for an exact hour and
  `40 s` for a short one. Never `0 hours`.
- **A total is the sum of the figures above it**, each snapped to the second it is printed at, so
  the column always adds up on screen.
- **A session crossing midnight counts entirely on the day it ended.** Splitting it would be more
  accurate and considerably more code.

---

## Forgetting the second tap

Forgetting to tap STUDY at the end is the normal failure mode of any toggle. Left alone, the phone
would stay silenced all night and the session would eventually claim every hour since.

So sessions close themselves. On the setup screen you set a cap in **hours and minutes** — three
hours by default. When it expires the phone is unsilenced, the session is closed, and the stretch is
recorded **cut off at the cap**, not at whenever the alarm happened to arrive. Such a stretch is
flagged as auto-closed, and any total containing one says so, because it is a cap rather than a
measurement.

- **The cap runs from the start of the session**, not from the last category switch — otherwise
  switching every hour would keep a session alive forever.
- **Changing it moves the deadline of the session already running.** Raising three hours to six
  two hours in leaves four hours. Lowering it below the time already elapsed closes the session
  almost immediately.
- **Zero in both boxes turns it off**, if you would rather nothing ever intervened.

The alarm is deliberately inexact, so it needs no special permission and cannot be defeated by the
phone dozing; it may fire a few minutes late, which costs nothing because the recorded time comes
from the deadline rather than the alarm. It is re-armed after a reboot or a reinstall, both of which
drop pending alarms.

---

## Locked phones

**Screen off: not possible.** Android stops polling for tags when the screen is off, so no app is
ever told anything. The only NFC that survives a dark screen is card emulation for contactless
payment, which cannot read a tag. iPhone behaves the same way.

**Locked with the screen on: usually works.** The app is set up for it — the tap handler is declared
`showWhenLocked`, so a tap takes effect immediately and its message appears over the lock screen
instead of waiting for you to unlock.

Whether a locked phone reads tags at all is a system setting rather than the app's call:

> *Settings → Connected devices → Connection preferences → NFC → **Require device unlock for NFC***
> (some phones call it *Secure NFC*; the wording varies by manufacturer)

With it **on**, NFC does nothing until you unlock, and no app can override that. Turning it **off**
is a real trade-off, not just a convenience: the same setting is what stops a payment card being
read while your phone is locked in a pocket.

---

## Where your data lives

Two places, both private to the app:

| What | Where |
|---|---|
| Every recorded stretch — the whole history | `sessions.log` in app storage, one line per stretch: `category,start,end,autoClosed` |
| Current state and the auto-close cap | `SharedPreferences`, a handful of numbers |

Consequences worth knowing:

- **Reinstalling over the top keeps everything.** Uninstalling or *Clear data* deletes it.
- **There is no backup.** The app opts out of Android's cloud backup, so a lost or reset phone loses
  your history.
- **No other app can read it**, and nothing leaves the phone.

On a debug build you can read the log yourself:

```bash
adb shell run-as com.jelena.studytracker cat /data/data/com.jelena.studytracker/files/sessions.log
```

---

## How a tap reaches the app

Each tag holds a single NDEF record with the app-specific MIME type
`application/vnd.com.jelena.studytracker` and a payload of `study` or `switch`.

That MIME type is the trick: it lets an `NDEF_DISCOVERED` intent filter launch the app on a tap even
when the app is closed. A plain URL record could not do this — Android would open a browser instead.

```
tag tapped
  → Android launches TagIntentActivity (no visible window)
  → StudyModeController works out the new state
  → DndController silences or unsilences the phone
  → the state is saved, and any finished stretch is appended to the log
  → the auto-close alarm is set or cancelled
  → toast: "Study mode on — school work"
  → activity finishes
```

The network is never on this path, which is why a tap is instant.

### The files

| File | Job |
|---|---|
| `StudyTag.kt` | The two tags and the text written on them. |
| `StudyState.kt` | What the app knows after the last tap. Immutable. |
| `StudyModeController.kt` | The rules: state + tag → new state, the stretch just ended, and when to give up on a session. No Android imports. |
| `StudyTime.kt` | Durations, totals per day and week, the local-midnight boundary, the log line format. All pure. |
| `SessionLog.kt` | Appends finished stretches to the log file and reads them back. |
| `StudyStateStore.kt` | Current state and the cap, in `SharedPreferences`. |
| `DndController.kt` | Silences and unsilences the phone. |
| `AutoCloseScheduler.kt` | Sets and cancels the alarm for a forgotten closing tap. |
| `AutoCloseReceiver.kt` | Closes the forgotten session; re-arms the alarm after a reboot. |
| `TagIntentActivity.kt` | No UI. Handles a tap, reports it, finishes. |
| `MainActivity.kt` | Setup: permission, programming tags, the cap, your hours. |

`StudyModeController` and `StudyTime` deliberately have no Android dependencies. They hold every
real rule in the app, so the whole rule set is covered by plain JVM tests — 56 of them, over every
state × tag combination, the double-tap guard, the duration arithmetic, the day boundary, the log
format and the auto-close. No emulator, no Robolectric, no tags, and no waiting three hours to find
out whether the cap works.

```bash
./gradlew testDebugUnitTest
```

Silencing uses `INTERRUPTION_FILTER_PRIORITY` rather than blocking everything, so whatever you have
marked as important — starred contacts, alarms — still gets through. A study session cannot swallow
an emergency call.

---

## Building

```bash
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest # the test suite
```

Then install it over USB or wireless debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Set the Gradle JDK to 21** — *Settings → Build, Execution, Deployment → Build Tools → Gradle*.
  AGP rejects JDK 25 and 26, and Android Studio's bundled runtime is 25. The failure message is
  just the version number, with no explanation, so this is worth doing before anything else.
- Known-good versions: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, compileSdk 35, minSdk 24.
- `local.properties` holds the Android SDK path, is specific to your machine, and is not in the
  repository. Android Studio creates it when you open the project.
- Dependencies are AndroidX AppCompat, Core-KTX and Material, plus JUnit for tests. No Compose, no
  dependency injection, no coroutines.

---

## If something does not work

| Symptom | Cause |
|---|---|
| Tapping a tag does nothing | Screen off, or the phone is locked with *Require device unlock for NFC* on. Also check NFC is switched on at all. |
| *"Could not write"* when programming a tag | The tag is locked, is not NDEF-capable, or moved out of range mid-write. Hold it still against the back of the phone. |
| *"Could not change Do Not Disturb"* | The permission was revoked. Open the app and grant it again; the tap is deliberately discarded so nothing is recorded that did not happen. |
| *"Already registered that tap"* | The same tag was read twice within two seconds. One tap, one toggle — this is the guard working. |
| A recorded stretch says `0 s` | Under half a second elapsed. |
| Notifications stayed silenced overnight | The closing tap was forgotten and the cap is off. Set one on the setup screen. |

---

## What this is not

This is a deliberately small, self-contained app. It has **no sync, no cloud, no charts, and no
history beyond what the phone holds**. It tracks two categories, not arbitrary projects, and it
serves one person on one phone.

A companion design document (kept outside this repository) plans a second phase: a queue of taps, a
Google Apps Script endpoint and a Google Sheet as the permanent record, with charts for free and an
iPhone Shortcut alongside. None of that is here, and the app is complete without it.

---

## License

[MIT](LICENSE) — do what you like with it, including at your own workshop.
