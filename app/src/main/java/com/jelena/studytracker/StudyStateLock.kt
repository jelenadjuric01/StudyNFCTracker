package com.jelena.studytracker

/**
 * The monitor every read-modify-write of the study state is held under.
 *
 * Changing the state is never one call: it is load, decide, silence, save, append, re-arm the
 * alarm. Two of those sequences running at once can interleave so that the second one decides from
 * a snapshot the first has already replaced — the same stretch appended to the log twice, or a
 * saved state that does not match what Do Not Disturb is actually doing.
 *
 * Until the auto-close work moved off the main thread this was impossible for free: [MainActivity],
 * [TagIntentActivity] and [AutoCloseReceiver] all ran on the one looper, which does one thing at a
 * time. [AutoCloseReceiver] now runs on a worker thread, so the exclusion has to be asked for. It
 * is an `object` because a lock is only worth anything if every writer shares the same one, and
 * this app is a single process — see the absence of `android:process` in the manifest.
 *
 * Hold it across the whole sequence, not around the individual reads and writes:
 *
 * ```
 * synchronized(StudyStateLock) {
 *     val previous = store.load()
 *     // decide, silence, save, append, re-arm
 * }
 * ```
 *
 * The sections are a few milliseconds of storage work, so the main thread blocking on one is not
 * worth avoiding — and much cheaper than the totals being wrong.
 */
object StudyStateLock
