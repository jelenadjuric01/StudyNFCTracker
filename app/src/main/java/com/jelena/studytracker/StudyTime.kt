package com.jelena.studytracker

import java.util.TimeZone

private const val SECOND_MILLIS = 1_000L
private const val MINUTE_MILLIS = 60 * SECOND_MILLIS
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS

/**
 * Turning a timestamp into "which day was that, here".
 *
 * `java.time` would be the obvious tool, but it needs API 26 or core library desugaring, and this
 * app supports API 24. The arithmetic is one division, so the dependency is not worth it.
 */
object LocalDays {

    private const val DAY_MILLIS = 24 * HOUR_MILLIS

    /**
     * Which local day [millis] falls in, counted from the epoch.
     *
     * @param zoneOffsetMillis the local offset from UTC at that moment, daylight saving included.
     *   Injected so tests can pin a zone instead of inheriting the machine's.
     */
    fun of(millis: Long, zoneOffsetMillis: Int): Long =
        Math.floorDiv(millis + zoneOffsetMillis, DAY_MILLIS)

    /** As above, using the phone's current time zone. */
    fun of(millis: Long): Long = of(millis, TimeZone.getDefault().getOffset(millis))

    /** Today, here, now. */
    fun today(): Long = of(System.currentTimeMillis())
}

/**
 * A duration snapped to the nearest whole second — the precision everything is shown at.
 *
 * The single place that decides what a printed figure really means, which is what lets
 * [CategoryTotals.totalMillis] guarantee that a column adds up.
 */
fun snapForDisplay(millis: Long): Long =
    ((millis.coerceAtLeast(0) + SECOND_MILLIS / 2) / SECOND_MILLIS) * SECOND_MILLIS

/**
 * Formats a duration the way a person says one: `"40 s"`, `"5 min 12 s"`, `"2 h 14 min 3 s"`.
 *
 * Units that are zero are left out entirely, so an exact hour reads `"1 h"` rather than
 * `"1 h 0 min 0 s"`, and an hour and five seconds reads `"1 h 5 s"`. A duration of nothing at all
 * reads `"0 s"`, because something has to be printed.
 *
 * Not localised: the unit strings are English. Fine for a single-user app, and something to move
 * into `strings.xml` if that ever changes.
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

/**
 * Splits a duration into whole hours and the minutes left over — what the auto-close cap is edited
 * as on the setup screen.
 */
fun hoursAndMinutesOf(millis: Long): Pair<Long, Long> {
    val safe = millis.coerceAtLeast(0)
    return safe / HOUR_MILLIS to (safe % HOUR_MILLIS) / MINUTE_MILLIS
}

/** The reverse of [hoursAndMinutesOf]. Negative inputs are treated as zero. */
fun millisOfHoursAndMinutes(hours: Long, minutes: Long): Long =
    hours.coerceAtLeast(0) * HOUR_MILLIS + minutes.coerceAtLeast(0) * MINUTE_MILLIS
