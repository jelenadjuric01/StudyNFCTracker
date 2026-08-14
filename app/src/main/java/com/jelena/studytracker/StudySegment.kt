package com.jelena.studytracker

/**
 * A finished stretch of time spent on one category.
 *
 * Produced whenever a tap closes something: the SWITCH tag ends a school segment and starts a
 * personal one, and the STUDY tag ends whatever was running. A session that was switched twice
 * therefore produces three segments, which is what keeps the two categories' totals honest.
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
     * Floors at zero: if the clock moved backwards mid-session the segment is worth nothing, but it
     * must never subtract from a total.
     */
    val durationMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0)

    /**
     * The local day this segment counts towards: the one it *ended* on.
     *
     * A session running past midnight therefore lands entirely on the new day. Splitting it across
     * both would be more accurate and a lot more code — and nothing is lost either way, because
     * every segment is kept.
     */
    val day: Long get() = LocalDays.of(endedAtMillis)
}

/**
 * Time spent per category over some set of segments — a day, a week, all of it.
 *
 * Purely derived: nothing is stored in this shape. The segment log is the only record, and every
 * figure on screen is computed from it, so there is no second copy to fall out of step.
 */
data class CategoryTotals(
    val schoolMillis: Long = 0L,
    val personalMillis: Long = 0L,
) {
    fun millisFor(category: Category): Long = when (category) {
        Category.SCHOOL -> schoolMillis
        Category.PERSONAL -> personalMillis
    }

    /**
     * The two categories added up — each snapped to the precision it will be printed at, so that a
     * column always adds up on screen.
     *
     * Snapping first is the whole point. Adding the raw milliseconds and rounding the result lets
     * the total disagree with the figures above it, which reads as a bug even when the arithmetic
     * is technically closer.
     */
    val totalMillis: Long get() = Category.entries.sumOf { snapForDisplay(millisFor(it)) }
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

/**
 * The categories worth printing for [segments], each with its total, in a fixed order.
 *
 * A category with no time against it is left out rather than shown as zero — an empty line says
 * nothing. Pulled out of the screen code so this rule can be unit-tested without a phone, since it
 * decides what the user actually sees.
 */
fun printableTotals(segments: Iterable<StudySegment>): List<Pair<Category, Long>> {
    val totals = totalsOf(segments)
    return Category.entries
        .filter { totals.millisFor(it) > 0 }
        .map { it to totals.millisFor(it) }
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
 * One segment as a line of the log file: `category,start,end,autoClosed`.
 *
 * A plain CSV line rather than JSON or a database, because the file is append-only and never read
 * by anything but this app. Keeping the format this dull means a corrupt line costs one segment
 * instead of the whole history.
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
 * @return the segment, or `null` for anything unreadable — a truncated final line after the process
 *   was killed mid-write, a category from a future version, hand-editing. The caller skips those
 *   rather than failing, so one bad line cannot make the history unreadable.
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
