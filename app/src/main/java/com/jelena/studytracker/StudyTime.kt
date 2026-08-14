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
 * @property autoClosed `true` if no tap ended this segment — the auto-close cap did, because the
 *   closing tap was forgotten. Such a figure is a cap, not a measurement, and is marked as one
 *   wherever it is shown.
 */
data class StudySegment(
    val category: Category,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val autoClosed: Boolean = false,
) {
    /**
     * How long the segment lasted.
     *
     * Floors at zero: if the clock moved backwards mid-session the segment is worth nothing,
     * but it must never subtract from a total.
     */
    val durationMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0)

    /**
     * The local day this segment counts towards: the one it *ended* on.
     *
     * A session running past midnight therefore lands entirely on the new day. Splitting it
     * across both would be more accurate and a lot more code — and unlike before, nothing is
     * lost either way, because every segment is kept.
     */
    val day: Long get() = LocalDays.of(endedAtMillis)
}

/**
 * Time spent per category over some set of segments — a day, a week, all of it.
 *
 * Purely derived: nothing is stored in this shape. The segment log is the only record, and
 * every figure on screen is computed from it, so there is no second copy to fall out of step.
 */
data class CategoryTotals(
    val schoolMillis: Long = 0L,
    val personalMillis: Long = 0L,
) {
    fun millisFor(category: Category): Long = when (category) {
        Category.SCHOOL -> schoolMillis
        Category.PERSONAL -> personalMillis
    }

    /** Every millisecond in these totals. Use this to ask *whether* anything was studied. */
    val rawTotalMillis: Long get() = schoolMillis + personalMillis

    /**
     * The total to *show*: the sum of the per-category figures as they will actually be printed,
     * via [snapForDisplay].
     *
     * Not the same as [rawTotalMillis] snapped, and deliberately so: summing what is on screen
     * is what guarantees the column adds up.
     */
    val shownTotalMillis: Long get() = Category.entries.sumOf { snapForDisplay(millisFor(it)) }
}

/** Adds up [segments] by category. */
fun totalsOf(segments: Iterable<StudySegment>): CategoryTotals {
    var school = 0L
    var personal = 0L
    segments.forEach { segment ->
        when (segment.category) {
            Category.SCHOOL -> school += segment.durationMillis
            Category.PERSONAL -> personal += segment.durationMillis
        }
    }
    return CategoryTotals(school, personal)
}

/** The segments that count towards local day [day]. */
fun segmentsOn(segments: Iterable<StudySegment>, day: Long): List<StudySegment> =
    segments.filter { it.day == day }

/**
 * The segments from the [days] days ending with [today] — so `days = 7` means today and the six
 * days before it.
 */
fun segmentsWithin(segments: Iterable<StudySegment>, today: Long, days: Int): List<StudySegment> =
    segments.filter { it.day > today - days && it.day <= today }

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

    /** Today, here, now. */
    fun today(): Long = of(System.currentTimeMillis())
}

private const val SECOND_MILLIS = 1_000L
private const val MINUTE_MILLIS = 60 * SECOND_MILLIS
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS

/**
 * A duration snapped to the nearest whole second — the precision everything is shown at.
 *
 * The single place that decides what a printed figure really means. Totals are summed from
 * *snapped* parts rather than from raw milliseconds, which is what keeps a column adding up —
 * see [CategoryTotals.shownTotalMillis].
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

/**
 * Splits a duration into whole hours and the minutes left over — what the auto-close cap is
 * edited as.
 */
fun hoursAndMinutesOf(millis: Long): Pair<Long, Long> {
    val safe = millis.coerceAtLeast(0)
    return safe / HOUR_MILLIS to (safe % HOUR_MILLIS) / MINUTE_MILLIS
}

/** The reverse of [hoursAndMinutesOf]. Negative inputs are treated as zero. */
fun millisOfHoursAndMinutes(hours: Long, minutes: Long): Long =
    hours.coerceAtLeast(0) * HOUR_MILLIS + minutes.coerceAtLeast(0) * MINUTE_MILLIS

/**
 * One segment as a line of the log file: `category,start,end,autoClosed`.
 *
 * A plain CSV line rather than JSON or a database, because the file is append-only and never
 * queried by anything but this app. Keeping the format this dull means a corrupt line costs one
 * segment instead of the whole history.
 */
fun formatSegmentLine(segment: StudySegment): String = listOf(
    segment.category.name,
    segment.startedAtMillis,
    segment.endedAtMillis,
    segment.autoClosed,
).joinToString(",")

/**
 * Reads a line written by [formatSegmentLine].
 *
 * @return the segment, or `null` for anything unreadable — a truncated final line after the
 *   process was killed mid-write, a category from a future version, hand-editing. The caller
 *   skips those rather than failing, so one bad line cannot make the history unreadable.
 */
fun parseSegmentLine(line: String): StudySegment? {
    val fields = line.trim().split(",")
    if (fields.size < 4) return null

    val category = Category.entries.firstOrNull { it.name == fields[0] } ?: return null
    val start = fields[1].toLongOrNull() ?: return null
    val end = fields[2].toLongOrNull() ?: return null
    val autoClosed = fields[3].toBooleanStrictOrNull() ?: return null

    return StudySegment(category, start, end, autoClosed)
}
