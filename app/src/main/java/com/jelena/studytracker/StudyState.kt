package com.jelena.studytracker

/** What the time is being spent on while study mode is on. */
enum class Category {
    SCHOOL,
    PERSONAL,
    ;

    /** The other one. There are exactly two, so switching is not a lookup. */
    fun toggled(): Category = if (this == SCHOOL) PERSONAL else SCHOOL
}

/**
 * Everything the app knows after the last tap. Immutable — a tap produces a new instance
 * rather than editing this one, which is what makes [StudyModeController] trivially
 * testable.
 *
 * The default instance is the honest cold-start state: mode off, nothing tapped yet.
 *
 * @property active whether study mode is on. When on, notifications are silenced.
 * @property category what is being tracked. Only meaningful while [active]; it is reset to
 *   [Category.SCHOOL] when a session ends so the next session starts predictably.
 * @property lastTag the tag from the last accepted tap, or `null` before the first one.
 *   Exists only to power the anti-bounce rule.
 * @property lastTapAtMillis when that tap happened, in `System.currentTimeMillis()` terms.
 *   Also anti-bounce bookkeeping.
 * @property segmentStartedAtMillis when the stretch of time now being tracked began. Not the
 *   start of the session: the SWITCH tag closes one segment and opens another, so this moves
 *   mid-session. `0` whenever [active] is false.
 * @property sessionStartedAtMillis when the session began — the STUDY tap that turned the mode
 *   on. Unlike [segmentStartedAtMillis] this survives a category switch, because the auto-close
 *   cap is measured from the start of studying, not from the last switch. `0` whenever [active]
 *   is false.
 */
data class StudyState(
    val active: Boolean = false,
    val category: Category = Category.SCHOOL,
    val lastTag: StudyTag? = null,
    val lastTapAtMillis: Long = 0L,
    val segmentStartedAtMillis: Long = 0L,
    val sessionStartedAtMillis: Long = 0L,
)
