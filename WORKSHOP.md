# Building this app in a workshop

This app is arranged so it can be built **one step at a time**, in front of a room, starting from
something that works after twenty minutes.

The guarantee that makes that possible:

> **Every step only adds. No step moves, renames or reorganises anything an earlier step wrote.**

Later steps add new files, and add fields and functions to existing ones. Nothing has to be undone.
Students who fall behind on step 4 still have a working app from step 3, and nobody has to watch you
delete code you told them to write an hour ago.

The step boundaries are not arbitrary: each one ends with something you can demonstrate on a phone,
and each one introduces exactly one new Android idea.

---

## Before the session

| | |
|---|---|
| **Phones** | One Android phone with NFC per group. The **emulator cannot do NFC**, so a laptop alone is not enough. |
| **Tags** | Two NTAG213/215/216 stickers per group. Cheap in packs of ten. |
| **Gradle JDK 21** | *Settings → Build, Execution, Deployment → Build Tools → Gradle*. AGP rejects JDK 25 and 26 and the failure message is just a version number. Fix this on every machine **before** the session — it is the single most likely way to lose fifteen minutes. |
| **A written tag, spare** | Program one tag yourself in advance. If a group's writing step fails you can lend them a working tag and keep them moving. |

Two things worth saying out loud early, because they look like bugs:

- **NFC needs the screen on and unlocked.** Tapping a sleeping phone does nothing.
- On Android 13+, a sideloaded app needs *Allow restricted settings* before some permissions can be
  granted — under *App info → ⋮*.

---

## The steps

### Step 1 — Tap a tag, silence the phone

The whole gesture, with no time tracking at all. Ends with: tap STUDY, notifications go quiet, a
toast says so. Tap again, they come back.

**Files written:** `StudyTag`, `StudyState`, `StudyModeController`, `StudyStateStore`,
`DndController`, `NfcTagWriter`, `TagIntentActivity`, `MainActivity`, the manifest, the layout and
`strings.xml`.

**Tests written:** `StudyModeControllerTest`, `StudyTagTest`.

**New ideas:** the `NDEF_DISCOVERED` intent filter with an app-specific MIME type, which is what
launches an app from a tag; an activity with no window; writing an NDEF record; Do Not Disturb as a
special-access permission.

**The teaching moment:** `StudyModeController` has no Android imports, so its tests run in a second
with no phone. Run them, break a rule on purpose, watch them fail. Everything after this leans on
that split.

### Step 2 — Write the hours down

Ends with: every tap reports how long the stretch was, and the app shows today, yesterday and the
last seven days.

**Files added:** `StudySegment`, `StudyTime`, `SessionLog`, `HistorySummary`.

**Files extended:** `StudyState` gains `segmentStartedAtMillis`; `StudyModeController` starts
returning the stretch a tap just ended; `TagIntentActivity` appends it; `MainActivity` shows the
summary.

**Tests added:** `StudySegmentTest`, `StudyTimeTest`.

**New ideas:** append-only storage in `filesDir`; deriving every figure from one record instead of
keeping running totals.

> **Do not shortcut this step with running totals in `SharedPreferences`.** It is tempting — three
> numbers instead of a file — and it is what the first version of this app did. It silently destroys
> yesterday's hours the moment a new day starts, and fixing it later means rewriting exactly the
> structure this workshop promises not to rewrite. Start with the log.

### Step 3 — Make it live

Ends with: the running time counts up on screen while you watch.

**Files added:** none. **Files extended:** `MainActivity` only.

**New ideas:** a `Handler` posting itself on a delay, started in `onResume` and removed in `onPause`
— paired exactly like the NFC reader mode above it.

**The teaching moment:** a small step, and a good one for asking *why* the totals below are left
alone while the top two lines tick. The answer is that nothing can change them while the screen is
open, which is worth working out together.

### Step 4 — Close a session nobody closed

Ends with: forgetting the second tap no longer leaves the phone silent all night. A cap in hours and
minutes, editable, three hours by default.

**Files added:** `AutoCloseScheduler`, `AutoCloseReceiver`.

**Files extended:** `StudyState` gains `sessionStartedAtMillis`; `StudyModeController` gains
`autoClose` and `autoCloseDeadline`; `StudyStateStore` stores the cap; `MainActivity` edits it; the
manifest gains the receiver and `RECEIVE_BOOT_COMPLETED`.

**Tests added:** `AutoCloseTest`.

**New ideas:** `AlarmManager`, `PendingIntent`, a `BroadcastReceiver`, and why alarms have to be
re-armed after a reboot.

**The teaching moment:** the cap is measured from the session start, not the current segment — which
is *why* this step adds a second timestamp instead of reusing the first. And the recorded stretch is
cut off at the deadline, not at whenever the alarm arrived, because an inexact alarm can be minutes
late. Both are two-line decisions with hours-long consequences, and both are unit-tested in seconds
rather than waited out.

### Step 5 — Make it ring

Ends with: an actual alarm sounds when the cap expires, so you know counting stopped.

**Files added:** `AutoCloseAlarm`, `res/drawable/ic_notification.xml`.

**Files extended:** `AutoCloseReceiver` rings instead of toasting; `MainActivity` asks for the
notification permission; the manifest gains `POST_NOTIFICATIONS` and `VIBRATE`.

**New ideas:** notification channels, why a channel's sound is fixed once it exists, `USAGE_ALARM`
versus a notification sound, and the Android 13 runtime permission.

### Step 6 — Taps on a locked phone (five minutes)

One attribute — `showWhenLocked` on the tap handler — plus the system setting that decides whether a
locked phone reads tags at all, and why a phone with the screen **off** never will.

---

## Running short on time

Cut from the back. Steps 1–3 are a complete, useful app and a coherent lesson on their own; 4 and 5
are the same app taken seriously. Step 6 is a footnote you can simply mention.

Do not cut the tests from step 1. They are the reason the rest of the workshop is fast.

## Running long

- Add a Quick Settings tile, so study mode can be toggled from the lock screen with no tag at all.
  It reuses `StudyModeController` untouched, which makes the point about that split better than any
  explanation.
- Have them add a third category. It touches one enum and no logic — a good demonstration of what
  the `when` exhaustiveness in `StudyModeController` buys.

## Where the finished code differs from a first attempt

Worth mentioning, because students will hit all three:

1. **Silencing must not gate a SWITCH tap.** A category switch does not change whether the phone is
   silenced, so it must not fail when the Do Not Disturb permission is missing.
2. **A printed column has to add up.** Round each figure first and sum the rounded values; rounding
   the raw sum instead produces a total that visibly disagrees with the numbers above it.
3. **The same tag can be read twice from one physical tap.** Without a two-second guard, one tap
   starts and instantly ends a session.
