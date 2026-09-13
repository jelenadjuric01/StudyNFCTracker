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
| **An Android phone or Emulator** | Android 7.0 (API 24) or newer. Physical hardware is needed for programming physical NFC tags; the included simulation scripts and test suite allow full testing on an Android Emulator. |
| **Two writable NFC tags** | NTAG213/215/216 stickers are the usual choice and cost very little. Any NDEF-writable tag works. The app programs them for you. *(Optional if using Emulator simulation).* |
| **Do Not Disturb access** | A one-off grant in system settings. The app cannot silence notifications without it. |
| **Notification permission** | Optional. Only needed for the alarm when a session closes itself. |
| **To build it yourself** | JDK 21 and the Android SDK. See [Building](#building). |

---

## Setting it up

**1. Install the app** and open it.

**2. Grant Do Not Disturb access.** The first line on screen says whether it is granted; the button
below takes you to the right system screen, because Android has no in-app dialog for this
permission. Nothing works until it is on.

**3. Program the tags.** *(Physical device only)* Switch to the **Tags** tab. Choose `STUDY`, hold a
tag flat against the back of the phone, and wait for *"Written: study"*. Choose `SWITCH` and do the
same with the other tag. The app must be open for this — while it is, it takes over the NFC radio so
that tapping a tag writes to it instead of triggering study mode.

**4. Label the tags with a pen.** They are identical once written, and the only way to tell them
apart afterwards is to tap one and see what happens.

**5. Set the auto-close cap** on the **Setup** tab if three hours is not what you want. See
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
hours by default. When it expires an alarm sounds, the phone is unsilenced, the session is closed,
and the stretch is recorded **cut off at the cap**, not at whenever the alarm happened to arrive.
Such a stretch is flagged as auto-closed, and any total containing one says so, because it is a cap
rather than a measurement.

**The alarm matters as much as the closing.** Studying may well still be going on, and the STUDY tag
needs tapping again to keep counting — so it rings at alarm volume and vibrates, rather than relying
on you noticing that notifications quietly came back. Tapping it opens the app. It needs permission
to post notifications, which the setup screen asks for; refusing costs only the alarm, and sessions
still close.

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

Sixteen small files, each with one job. The files without Android dependencies hold pure domain logic,
making the rules fully testable on a local JVM without a device.

| File | Job |
|---|---|
| `StudyTag.kt` | The two tags and the text written on them. |
| `StudyState.kt` | What the app knows after the last tap. Immutable. |
| `StudyModeController.kt` | The rules: state + tag → new state, the stretch just ended, and when to give up on a session. |
| `StudySegment.kt` | A recorded stretch, totals over a day or a week, and the log line format. |
| `StudyTime.kt` | Durations in words, and which local day a moment belongs to. |
| `StudyStateLock.kt` | Application-wide monitor lock serializing background and foreground storage access. |
| `SessionLog.kt` | Appends finished stretches to the log file and reads them back. |
| `StudyStateStore.kt` | Current state and the cap, in `SharedPreferences`. |
| `DndController.kt` | Silences and unsilences the phone. |
| `NfcTagWriter.kt` | Writes a payload onto a physical tag. |
| `AutoCloseScheduler.kt` | Sets and cancels the alarm for a forgotten closing tap. |
| `AutoCloseReceiver.kt` | Closes the forgotten session; re-arms the alarm after a reboot. |
| `AutoCloseAlarm.kt` | Rings, so you know a session was closed for you. |
| `HistorySummary.kt` | Builds the today / yesterday / last-7-days text. |
| `TagIntentActivity.kt` | No UI. Handles a tap, reports it, finishes. |
| `MainActivity.kt` | Tab navigation, settings, history display, and NFC tag programming. |

Silencing uses `INTERRUPTION_FILTER_PRIORITY` rather than blocking everything, so whatever you have
marked as important — starred contacts, alarms — still gets through. A study session cannot swallow
an emergency call.

---

## Testing

The project uses a three-tier testing strategy covering pure logic, Android OS integration, and virtual device emulation.

### 1. Local Unit Tests (Host JVM)

`StudyModeController`, `StudySegment`, `StudyTime`, `StudyState`, `StudyTag`, and `StudyStateLock` are covered by 61 fast JVM tests. They run locally in ~1 second with no emulator, no Robolectric, and no physical tags required.

```bash
./gradlew testDebugUnitTest
# or run all unit tests:
./gradlew test
```

Tested areas include:
- Every `StudyState` × `StudyTag` transition.
- Rapid duplicate-tap debounce protection (2-second window).
- Time formatting, midnight boundary rollover, and weekly aggregation.
- Auto-close cap deadline math and pre-cap / post-cap detection.
- Multithreaded lock safety and state synchronization (`StudyStateLockTest`).

---

### 2. Instrumented Android Tests (Emulator or Real Device)

Located in `app/src/androidTest/`, these tests run directly on the Android ART runtime (API 24+) to verify real system intents, AndroidX lifecycle scenarios, `SharedPreferences` persistence, and broadcast handling.

With an emulator running or a phone connected via USB:

```bash
# Run all instrumented tests:
./gradlew connectedDebugAndroidTest

# Run specific test suites:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jelena.studytracker.NfcTapSimulationTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jelena.studytracker.AutoCloseSimulationTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jelena.studytracker.MainActivityUiTest
```

| Test Suite | What it verifies |
|---|---|
| `NfcTapSimulationTest.kt` | Simulates `STUDY` and `SWITCH` NFC taps using real `NdefMessage` parcels, debounce guards, category switches, unknown payloads, and DND permission refusal. |
| `AutoCloseSimulationTest.kt` | Verifies `ACTION_AUTO_CLOSE` broadcast handling, session cap truncation, and alarm re-arming on system reboot (`BOOT_COMPLETED`) or app update (`MY_PACKAGE_REPLACED`). |
| `MainActivityUiTest.kt` | Exercises tab navigation (Session / Tags / Setup), auto-close cap persistence, live timer ticks, and asynchronous history summary rendering. |

---

### 3. Testing on an Android Virtual Device (Emulator)

Standard Android emulators do not have physical NFC radio hardware. However, the app is fully testable on an emulator using **intent simulation scripts** that interact directly with the running app.

#### Step 1: Start the Emulator & Launch the App
1. Open Android Studio → **Tools > Device Manager** → Start your virtual device (e.g. *Pixel 8 API 35*).
2. Install and open the app:
   ```bash
   ./gradlew installDebug
   adb shell am start -n com.jelena.studytracker/.MainActivity
   ```

#### Step 2: Interactive Terminal Simulation Scripts
Keep `MainActivity` open on the emulator screen, then run these scripts from your terminal to trigger live actions in real time:

- **Start studying (School)**:
  ```bash
  ./tap_study.sh
  ```
  *(Watch the emulator: a Toast appears, status updates to "Studying School", and the timer starts ticking live).*

- **Switch category to Personal**:
  ```bash
  ./tap_switch.sh
  ```
  *(Watch the category update to Personal while the timer continues counting).*

- **Stop studying & record session**:
  ```bash
  ./tap_study.sh
  ```
  *(Watch status return to "Not studying" and the History section update with your recorded time).*

- **Simulate auto-close timeout**:
  ```bash
  ./trigger_autoclose.sh
  ```

- **Reset storage to clean state**:
  ```bash
  ./reset_storage.sh
  ```

#### Step 3: Visual Demonstration Test
If you want to sit back and watch an automated end-to-end demo on the emulator screen:
1. Open `app/src/androidTest/java/com/jelena/studytracker/MainActivityUiTest.kt`.
2. Run **`demonstrateLiveWorkflowOnScreen`** (includes deliberate 2–4 second pauses between actions to showcase the live timer, tab switching, and cap settings).

---

### 4. Testing on a Physical Android Phone

To test with real physical NFC stickers:

1. **Enable Developer Options & USB Debugging**:
   - *Settings → About Phone* → Tap **Build Number** 7 times.
   - *Settings → System → Developer Options* → Turn on **USB Debugging** (and ensure **NFC** is turned on in settings).
2. **Connect phone & install**:
   ```bash
   ./gradlew installDebug
   ```
3. **Grant Permissions**:
   - Open the app and grant **Do Not Disturb Access** (or run `adb shell cmd notification allow_dnd com.jelena.studytracker`).
   - Grant Notification permission for auto-close alarms.
4. **Program your NFC stickers**:
   - Go to the **Tags** tab in the app.
   - Choose `STUDY` and hold a sticker to the back of the phone until written.
   - Choose `SWITCH` and hold the second sticker until written.
5. **Tap and study!**

---

## Building

```bash
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest # the JVM test suite
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
- Dependencies are AndroidX AppCompat, Core-KTX and Material, plus JUnit and AndroidX Test libraries.

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
| Simulation scripts say DND permission denied | Run `adb shell cmd notification allow_dnd com.jelena.studytracker` to grant DND access on your emulator or test device. |

---

## What about iPhone?

If you or your workshop participants use an iPhone, you cannot install this Android build directly. You have two main routes on iOS:

### 1. Native iOS App (Swift / SwiftUI + CoreNFC)

You can build a native iOS counterpart using **Swift**, **SwiftUI**, and Apple's **CoreNFC** framework (using background tag reading available on iPhone XS and newer):
- **The Platform Wall (Do Not Disturb Access)**: On Android, `NotificationManager.setInterruptionFilter` allows third-party apps with `ACCESS_NOTIFICATION_POLICY` permission to programmatically silence and unsilence the phone. **iOS provides no public API for third-party apps to toggle system Do Not Disturb or Focus modes in the background.** iOS Focus Filter APIs only let apps filter their *own* internal notifications when the user activates a Focus mode; they do not let an app turn system-wide DND on or off on a tag tap.
- **The Result**: A native iOS app can read the NFC tag, manage state, and log hours to local files or CoreData, but it cannot automatically silence the phone without user intervention.

### 2. The Practical iOS Alternative: Apple Shortcuts

Because the built-in **Apple Shortcuts** app has privileged first-party access to system settings, it can control Focus modes directly. Building the study tracker with Shortcuts automations gives you the exact same physical, zero-touch NFC workflow on an iPhone without writing any code.

#### Step-by-Step: Building Study Tracker with iOS Shortcuts

**Step 1: Create the STUDY Tag Automation**
1. Open the **Shortcuts** app on iPhone and tap the **Automations** tab.
2. Tap **+** (New Automation) and select **NFC** as the trigger.
3. Tap **Scan**, tap your physical NFC sticker to the top edge of your iPhone, and name it `STUDY`.
4. Choose **Run Immediately** (disable *Ask Before Running* and turn off *Notify When Run* so taps trigger instantly with zero friction).

**Step 2: Add the Study Toggle Logic**
Inside the automation action editor:
1. **Check Current State**: Add **Get File from Shortcuts** (e.g. `study_state.json` stored in iCloud Drive / Shortcuts) to check if a session is currently running.
2. **Add an `If` Condition**:
   - **If session is active (ending a session)**:
     - Action: **Set Focus** → Turn *Do Not Disturb* **Off**.
     - Action: **Date** (Current Date) minus `start_time` from `study_state.json` → Calculate elapsed time.
     - Action: **Append to File** → Append `category,start,end,duration` to `sessions.csv` in iCloud Drive / Shortcuts folder.
     - Action: **Save File** → Update `study_state.json` with `{"active": false}`.
     - Action: **Show Notification** → *"Study mode off: [Duration] of [Category] recorded"*.
   - **Otherwise (starting a new session)**:
     - Action: **Set Focus** → Turn *Do Not Disturb* **On** until turned off.
     - Action: **Save File** → Write `{"active": true, "category": "school", "start_time": "[Current Date]"}` to `study_state.json`.
     - Action: **Show Notification** → *"Study mode on — school work"*.

**Step 3: Create the SWITCH Tag Automation**
1. Create a second NFC Automation triggered by scanning your `SWITCH` tag (set to **Run Immediately**).
2. **Logic**:
   - Read `study_state.json`.
   - If `active` is `true`: calculate the elapsed time for the current category, append the completed stretch to `sessions.csv`, toggle `category` between `"school"` and `"personal"`, update `start_time` to current time in `study_state.json`, and show a notification (*"Switched to personal project"*).
   - If `active` is `false`: show a notification (*"Not studying — tap STUDY first"*).

**Step 4: Reviewing History**
- Open `sessions.csv` in Apple Numbers, Files, or create a simple display shortcut that reads the CSV and calculates daily and 7-day totals.

---

### What about Kotlin Multiplatform (KMP)?

**Kotlin Multiplatform (KMP)** and Compose Multiplatform are rapidly emerging across the mobile ecosystem, closing the architectural gap between platform-specific apps by sharing pure Kotlin business logic (`StudyModeController`, `StudySegment`, `StudyTime`), state storage, and serialization across Android and iOS.

However, KMP compiles shared code against platform-native APIs via `expect` / `actual` bindings. Because iOS itself lacks an API for third-party apps to toggle system Do Not Disturb programmatically, **KMP cannot bridge this limitation for our study tracker**. The business logic and math can be shared 100%, but the physical hardware payoff—silencing notifications seamlessly on a desk tap—remains blocked by Apple's OS sandboxing rules, making Apple Shortcuts or platform-specific workarounds necessary on iOS.

---

## What this is not

This is a deliberately small, self-contained app. It has **no sync, no cloud, no charts, and no
history beyond what the phone holds**. It tracks two categories, not arbitrary projects, and it
serves one person on one phone.

---

## License

[MIT](LICENSE) — do what you like with it, including at your own workshop.
