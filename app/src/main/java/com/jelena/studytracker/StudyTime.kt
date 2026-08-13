package com.jelena.studytracker

import java.util.TimeZone

/**
 * A finished stretch of time spent on one category.
 *
 * Produced whenever a tap closes something: the SWITCH tag ends a school segment and starts
 * a personal one, and the STUDY tag ends whatever was running. A single session that was
 * switched twice therefore produces three segments, which is what keeps the two categories'
 * totals honest.
 *
 * @property startedAtMillis when the segment began, from `System.currentTimeMillis()`.
 * @property endedAtMillis when the tap that closed it happened.
 */
data class StudySegment(
    val category: Category,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
) {
    /**
     * How long the segment lasted.
     *
     * Floors at zero: if the clock moved backwards mid-session the segment is worth nothing,
     * but it must never subtract from a day's total.
     */
    val durationMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0)
}

/**
 * Today's running totals, one per category.
 *
 * Deliberately only today. Full history is what the Google Sheet is for in phase 2 — keeping
 * a single day here means three numbers in [android.content.SharedPreferences], no log to
 * grow forever and no pruning to get wrong.
 *
 * @property day which local day these totals belong to, as a count of days since the epoch.
 *   A segment landing on a different day replaces them rather than adding to them.
 */
data class DailyTotals(
    val day: Long = 0L,
    val schoolMillis: Long = 0L,
    val personalMillis: Long = 0L,
) {
    /** Every millisecond recorded today. Use this to ask *whether* anything was studied. */
    val rawTotalMillis: Long get() = schoolMillis + personalMillis

    /**
     * The total to *show*: the sum of the per-category figures as they will actually be
     * printed, via [snapForDisplay].
     *
     * Not the same as [rawTotalMillis] snapped, and deliberately so: summing what is on screen
     * is what guarantees the column adds up, whatever precision the figures are printed at.
     */
    val shownTotalMillis: Long get() = Category.entries.sumOf { snapForDisplay(millisFor(it)) }

    fun millisFor(category: Category): Long = when (category) {
        Category.SCHOOL -> schoolMillis
        Category.PERSONAL -> personalMillis
    }

    /**
     * Adds [segment] to the totals for [day].
     *
     * If [day] is not the day these totals are for, the old numbers are dropped: yesterday's
     * hours are not history worth keeping here, and carrying them over would silently inflate
     * today's.
     *
     * @param day the local day to attribute the segment to — normally
     *   `LocalDays.of(segment.endedAtMillis)`. Passed in rather than derived so the rule stays
     *   pure and testable.
     */
    fun plus(segment: StudySegment, day: Long): DailyTotals {
        val base = if (day == this.day) this else DailyTotals(day = day)
        return when (segment.category) {
            Category.SCHOOL -> base.copy(schoolMillis = base.schoolMillis + segment.durationMillis)
            Category.PERSONAL -> base.copy(personalMillis = base.personalMillis + segment.durationMillis)
        }
    }
}

/**
 * Turning a timestamp into "which day was that, here".
 *
 * `java.time` would be the obvious tool, but it needs API 26 or core library desugaring, and
 * this app supports API 24. The arithmetic is one division, so the dependency is not worth it.
 */
object LocalDays {

    private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

    /**
     * Which local day [millis] falls in, counted from the epoch.
     *
     * @param zoneOffsetMillis the local offset from UTC at that moment, daylight saving
     *   included. Injected so tests can pin a zone instead of inheriting the machine's.
     */
    fun of(millis: Long, zoneOffsetMillis: Int): Long =
        Math.floorDiv(millis + zoneOffsetMillis, MILLIS_PER_DAY)

    /** As above, using the phone's current time zone. */
    fun of(millis: Long): Long = of(millis, TimeZone.getDefault().getOffset(millis))
}

private const val SECOND_MILLIS = 1_000L
private const val MINUTE_MILLIS = 60 * SECOND_MILLIS

private const val HOUR_MILLIS = 60 * MINUTE_MILLIS

/**
 * A duration snapped to the nearest whole second — the precision everything is shown at.
 *
 * The single place that decides what a printed figure really means. Totals are summed from
 * *snapped* parts rather than from raw milliseconds, which is what keeps a column adding up —
 * see [DailyTotals.shownTotalMillis].
 */
fun snapForDisplay(millis: Long): Long =
    ((millis.coerceAtLeast(0) + SECOND_MILLIS / 2) / SECOND_MILLIS) * SECOND_MILLIS

/**
 * Formats a duration the way a person says one: `"40 s"`, `"5 min 12 s"`, `"2 h 14 min 3 s"`.
 *
 * Units that are zero are left out entirely, so an exact hour reads `"1 h"` rather than
 * `"1 h 0 min 0 s"`, and an hour and five seconds reads `"1 h 5 s"`. A duration of nothing at
 * all reads `"0 s"`, because something has to be printed.
 *
 * Not localised: the unit strings are English. Fine for a single-user app, and something to
 * move into `strings.xml` if that ever changes.
 */
fun formatDuration(millis: Long): String {
    val snapped = snapForDisplay(millis)

    val hours = snapped / HOUR_MILLIS
    val minutes = (snapped % HOUR_MILLIS) / MINUTE_MILLIS
    val seconds = (snapped % MINUTE_MILLIS) / SECOND_MILLIS

    val parts = buildList {
        if (hours > 0) add("$hours h")
        if (minutes > 0) add("$minutes min")
        if (seconds > 0) add("$seconds s")
    }

    return if (parts.isEmpty()) "0 s" else parts.joinToString(" ")
}
