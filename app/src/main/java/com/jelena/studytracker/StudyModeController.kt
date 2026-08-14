package com.jelena.studytracker

/** Why nothing changed, so the caller can say something useful about it. */
enum class IgnoredReason {

    /** The same tag was tapped twice inside [StudyModeController.DEBOUNCE_MILLIS]. */
    DUPLICATE_TAP,

    /**
     * There was no running session for this to affect.
     *
     * Two different situations produce it: the SWITCH tag tapped while study mode is off, and the
     * auto-close alarm arriving after the session was already ended by a real tap. Both mean "there
     * is nothing here to do", and neither is an error.
     */
    MODE_OFF,
}

/**
 * The outcome of a tap: the state to store either way, plus whether anything moved.
 *
 * A sealed hierarchy rather than a nullable state, because "nothing changed" is a normal
 * outcome that the UI reports differently — not an error and not an absence.
 *
 * @property state the state to persist. For [Ignored] this is the unchanged previous state,
 *   so the caller can save unconditionally without special-casing.
 */
sealed interface TapResult {

    val state: StudyState

    /**
     * The tap moved the state. Callers should apply Do Not Disturb to match, and add
     * [completed] to the day's totals if it is present.
     *
     * @property completed the stretch of time this tap ended, or `null` if it ended nothing —
     *   which is the case for the tap that starts a session, and for any tap whose segment
     *   turned out to be worthless (a zero length, or a clock that moved backwards).
     */
    data class Changed(
        override val state: StudyState,
        val completed: StudySegment? = null,
    ) : TapResult

    /** The tap was deliberately dropped. Do Not Disturb must be left alone. */
    data class Ignored(override val state: StudyState, val reason: IgnoredReason) : TapResult
}

/**
 * The whole rule set of the app:
 *
 * ```
 * tap STUDY   -> mode ON,  tracking SCHOOL
 * tap SWITCH  -> PERSONAL
 * tap SWITCH  -> SCHOOL
 * tap STUDY   -> mode OFF
 * ```
 *
 * Deliberately free of Android imports: no context, no clock, no storage. The caller
 * supplies the previous state and the current time, which is what lets every combination
 * be unit-tested on the JVM.
 */
object StudyModeController {

    /**
     * How long after an accepted tap the same tag is ignored.
     *
     * A tag held near the phone for a moment too long can be read twice, and two STUDY
     * reads would start and immediately end a session — the phone would unmute itself and
     * look broken. Two seconds is comfortably longer than a double read and far shorter
     * than any deliberate re-tap.
     */
    const val DEBOUNCE_MILLIS = 2_000L

    /**
     * Applies [tag] to [previous].
     *
     * @param previous the state after the last accepted tap.
     * @param tag which tag was just read.
     * @param tapAtMillis when it was read, from `System.currentTimeMillis()`. Passed in
     *   rather than read here so tests can control it.
     * @return [TapResult.Changed] with the new state, or [TapResult.Ignored] carrying
     *   [previous] untouched and the reason why.
     */
    fun onTap(previous: StudyState, tag: StudyTag, tapAtMillis: Long): TapResult {
        if (isBounce(previous, tag, tapAtMillis)) {
            return TapResult.Ignored(previous, IgnoredReason.DUPLICATE_TAP)
        }

        // SWITCH is meaningless with no session to switch, and starting one on a stray tap
        // would silence the phone without the user asking. Do nothing at all — not even
        // record the tap, since there is no state change to debounce against.
        if (tag == StudyTag.SWITCH && !previous.active) {
            return TapResult.Ignored(previous, IgnoredReason.MODE_OFF)
        }

        val next = when (tag) {
            // Toggle. A session always begins on school work, and the category resets on
            // the way out so the next session does too.
            StudyTag.STUDY -> previous.copy(
                active = !previous.active,
                category = Category.SCHOOL,
                // Starting: the first segment begins now. Ending: nothing is running, and a
                // leftover start time would later be read as a segment that never happened.
                segmentStartedAtMillis = if (previous.active) 0L else tapAtMillis,
                sessionStartedAtMillis = if (previous.active) 0L else tapAtMillis,
            )

            // The old category stops being tracked and the new one starts, at the same instant.
            // sessionStartedAtMillis deliberately untouched: switching category does not restart
            // the session, so it must not push the auto-close deadline back either.
            StudyTag.SWITCH -> previous.copy(
                category = previous.category.toggled(),
                segmentStartedAtMillis = tapAtMillis,
            )
        }

        return TapResult.Changed(
            state = next.copy(lastTag = tag, lastTapAtMillis = tapAtMillis),
            completed = closedSegment(previous, tapAtMillis),
        )
    }

    /**
     * Ends a session that nobody ended — the closing tap was forgotten.
     *
     * The resulting segment is cut off at [closeAtMillis] rather than the current time, so the
     * recorded hours are the cap the user chose and not however long the phone sat there. It is
     * flagged [StudySegment.autoClosed] because it is a cap, not a measurement.
     *
     * @param closeAtMillis the deadline the session should be treated as having ended at, from
     *   [autoCloseDeadline].
     * @return [TapResult.Changed] with the mode off, or [TapResult.Ignored] if there was nothing
     *   running — which happens routinely, since a late alarm can arrive after a real closing tap.
     */
    fun autoClose(previous: StudyState, closeAtMillis: Long): TapResult {
        if (!previous.active) return TapResult.Ignored(previous, IgnoredReason.MODE_OFF)

        val completed = closedSegment(previous, closeAtMillis)?.copy(autoClosed = true)

        return TapResult.Changed(
            state = previous.copy(
                active = false,
                category = Category.SCHOOL,
                segmentStartedAtMillis = 0L,
                sessionStartedAtMillis = 0L,
            ),
            completed = completed,
        )
    }

    /**
     * When the running session should be given up on, or `null` if there is nothing to give up on.
     *
     * Measured from the start of the session, so changing the cap mid-session moves the deadline
     * rather than extending it from now — setting six hours two hours in means four hours left,
     * which is what "close it six hours after I started" means.
     *
     * @param capMillis how long a session may run unattended. Zero or less disables the cap
     *   entirely, which is a deliberate escape hatch for anyone who does not want one.
     */
    fun autoCloseDeadline(state: StudyState, capMillis: Long): Long? {
        if (!state.active || capMillis <= 0L || state.sessionStartedAtMillis <= 0L) return null
        return state.sessionStartedAtMillis + capMillis
    }

    /**
     * Whether moving from [previous] to [next] means the phone has to be silenced or unsilenced.
     *
     * Only the STUDY tag can change this. SWITCH changes which category is being tracked, and a
     * session is equally silenced either way — so a SWITCH tap must not depend on Do Not
     * Disturb being available, or losing the permission mid-session would block category
     * changes for no reason.
     */
    fun silencingChanged(previous: StudyState, next: StudyState): Boolean =
        previous.active != next.active

    /**
     * The segment this tap brought to an end, or `null` if it ended nothing worth recording.
     *
     * Any accepted tap while the mode is on closes the current segment — the STUDY tag by
     * ending the session, the SWITCH tag by handing over to the other category.
     *
     * Returns `null` for a zero-length or negative-length segment. A start time of `0` means
     * the state was restored from an older build that did not track one, and a negative length
     * means the clock moved backwards; in both cases there is no real duration to claim.
     */
    private fun closedSegment(previous: StudyState, tapAtMillis: Long): StudySegment? {
        if (!previous.active || previous.segmentStartedAtMillis <= 0L) return null
        val segment = StudySegment(
            category = previous.category,
            startedAtMillis = previous.segmentStartedAtMillis,
            endedAtMillis = tapAtMillis,
        )
        return segment.takeIf { it.durationMillis > 0 }
    }

    /**
     * Whether this tap looks like the radio reading one physical tap twice.
     *
     * Only the *same* tag counts: STUDY then SWITCH in quick succession is a deliberate
     * "start on personal work", not a bounce.
     *
     * A gap that is negative — the clock moved backwards between taps — is treated as
     * genuine. Otherwise a single clock correction could lock the tags out for hours.
     */
    private fun isBounce(previous: StudyState, tag: StudyTag, tapAtMillis: Long): Boolean {
        if (previous.lastTag != tag) return false
        val sinceLastTap = tapAtMillis - previous.lastTapAtMillis
        return sinceLastTap in 0 until DEBOUNCE_MILLIS
    }
}
