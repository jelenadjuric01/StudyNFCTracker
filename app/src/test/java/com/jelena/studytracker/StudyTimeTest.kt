package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the time arithmetic: segment lengths, aggregation over days and weeks, the day
 * boundary, the duration wording and the log file format. All pure functions, so no device.
 */
class StudyTimeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** Midday on some arbitrary day, so that adding or subtracting hours stays on that day. */
    private val midday = 1_800_000_000_000L

    private fun segment(
        category: Category = Category.SCHOOL,
        endedAt: Long = midday,
        lasting: Long = 30 * minute,
        autoClosed: Boolean = false,
    ) = StudySegment(category, endedAt - lasting, endedAt, autoClosed)

    // --- StudySegment ---

    @Test
    fun `a segment lasts the gap between its ends`() {
        assertEquals(90 * minute, segment(lasting = 90 * minute).durationMillis)
    }

    @Test
    fun `a backwards segment is worth zero, never a negative`() {
        // A negative duration would subtract from a total and produce an hour count lower than
        // what was actually studied.
        assertEquals(0L, StudySegment(Category.SCHOOL, 10_000L, 1_000L).durationMillis)
    }

    @Test
    fun `a segment counts towards the day it ended on`() {
        val acrossMidnight = segment(endedAt = midday, lasting = 20 * hour)

        assertEquals(LocalDays.of(midday), acrossMidnight.day)
    }

    // --- Aggregation ---

    @Test
    fun `totals add up per category`() {
        val segments = listOf(
            segment(Category.SCHOOL, lasting = 30 * minute),
            segment(Category.PERSONAL, lasting = 20 * minute),
            segment(Category.SCHOOL, lasting = 15 * minute),
        )

        val totals = totalsOf(segments)

        assertEquals(45 * minute, totals.schoolMillis)
        assertEquals(20 * minute, totals.personalMillis)
        assertEquals(65 * minute, totals.rawTotalMillis)
        assertEquals(45 * minute, totals.millisFor(Category.SCHOOL))
    }

    @Test
    fun `totals of nothing are zero rather than absent`() {
        assertEquals(CategoryTotals(), totalsOf(emptyList()))
        assertEquals(0L, totalsOf(emptyList()).rawTotalMillis)
    }

    @Test
    fun `a day holds only the segments that ended on it`() {
        val today = LocalDays.of(midday)
        val segments = listOf(
            segment(endedAt = midday),
            segment(endedAt = midday - day),
            segment(endedAt = midday - 2 * day),
        )

        assertEquals(1, segmentsOn(segments, today).size)
        assertEquals(1, segmentsOn(segments, today - 1).size)
        assertEquals(0, segmentsOn(segments, today - 5).size)
    }

    @Test
    fun `the last seven days include today and exclude the eighth`() {
        val today = LocalDays.of(midday)
        val segments = (0L..8L).map { segment(endedAt = midday - it * day) }

        val week = segmentsWithin(segments, today, days = 7)

        assertEquals(7, week.size)
        // The oldest kept is six days back; seven and eight days back are out.
        assertEquals(today - 6, week.minOf { it.day })
        assertEquals(today, week.maxOf { it.day })
    }

    @Test
    fun `a segment from the future is not counted as this week`() {
        // A clock set forward and back again should not smear study time into days that have not
        // happened, where it would silently vanish from every total.
        val today = LocalDays.of(midday)
        val segments = listOf(segment(endedAt = midday + 2 * day))

        assertEquals(0, segmentsWithin(segments, today, days = 7).size)
    }

    // --- LocalDays ---

    @Test
    fun `a day boundary falls at local midnight, not UTC midnight`() {
        val twoHoursAhead = 2 * hour.toInt()

        // 23:30 local on the day that starts at epoch millis 0 in that zone.
        val beforeMidnight = LocalDays.of(21 * hour + 30 * minute, twoHoursAhead)
        // 00:30 local, half an hour later.
        val afterMidnight = LocalDays.of(22 * hour + 30 * minute, twoHoursAhead)

        assertEquals(0L, beforeMidnight)
        assertEquals(1L, afterMidnight)
    }

    @Test
    fun `timestamps before the epoch still map to whole days`() {
        // floorDiv, not integer division: plain division truncates towards zero and would put
        // every negative timestamp on day 0.
        assertEquals(-1L, LocalDays.of(-1L, 0))
        assertEquals(-1L, LocalDays.of(-day, 0))
        assertEquals(-2L, LocalDays.of(-day - 1, 0))
    }

    // --- formatDuration ---

    @Test
    fun `durations read the way a person says them`() {
        assertEquals("0 s", formatDuration(0))
        assertEquals("1 min", formatDuration(minute))
        assertEquals("47 min", formatDuration(47 * minute))
        assertEquals("2 h 14 min 3 s", formatDuration(2 * hour + 14 * minute + 3_000))
        assertEquals("5 min 12 s", formatDuration(5 * minute + 12_000))
    }

    @Test
    fun `units that are zero are left out entirely`() {
        assertEquals("1 h", formatDuration(hour))
        assertEquals("1 h 5 s", formatDuration(hour + 5_000))
        assertEquals("2 h 30 min", formatDuration(2 * hour + 30 * minute))
        assertEquals("3 min", formatDuration(3 * minute))
        assertEquals("40 s", formatDuration(40_000))
    }

    @Test
    fun `durations snap to the nearest second`() {
        assertEquals("2 min 29 s", formatDuration(2 * minute + 29_400))
        assertEquals("2 min 30 s", formatDuration(2 * minute + 29_600))
        assertEquals("6 min", formatDuration(5 * minute + 59_999))
    }

    @Test
    fun `a stretch under a minute reads in seconds`() {
        // The reason this exists: a short demo tap must not read "0 min" and look like nothing
        // happened.
        assertEquals("40 s", formatDuration(40_000))
        assertEquals("59 s", formatDuration(59_000))
    }

    @Test
    fun `the seconds-to-minutes handover reads as one minute, never sixty seconds`() {
        assertEquals("59 s", formatDuration(59_400))
        assertEquals("1 min", formatDuration(59_600))
        assertEquals("1 min 1 s", formatDuration(minute + 1_000))
    }

    @Test
    fun `a negative duration reads as zero rather than a minus sign`() {
        assertEquals("0 s", formatDuration(-hour))
    }

    // --- Column consistency ---

    @Test
    fun `the shown total is the sum of the shown parts`() {
        val totals = CategoryTotals(
            schoolMillis = 2 * minute + 50_000,
            personalMillis = 2 * minute + 55_000,
        )

        assertEquals("2 min 50 s", formatDuration(totals.schoolMillis))
        assertEquals("2 min 55 s", formatDuration(totals.personalMillis))
        assertEquals("5 min 45 s", formatDuration(totals.shownTotalMillis))
    }

    @Test
    fun `a column adds up exactly, whatever the parts are`() {
        // Brute force over second-level values: the seconds the total prints must equal the sum of
        // the seconds the parts print. A property, not an example.
        (0..7300 step 37).forEach { schoolSeconds ->
            (0..7300 step 53).forEach { personalSeconds ->
                val totals = CategoryTotals(schoolSeconds * 1_000L, personalSeconds * 1_000L)
                val shownParts = Category.entries.sumOf { snapForDisplay(totals.millisFor(it)) }

                assertEquals(
                    "school ${schoolSeconds}s + personal ${personalSeconds}s",
                    shownParts / 1_000,
                    snapForDisplay(totals.shownTotalMillis) / 1_000,
                )
            }
        }
    }

    // --- The cap, as hours and minutes ---

    @Test
    fun `a cap splits into hours and minutes and back again`() {
        assertEquals(3L to 30L, hoursAndMinutesOf(3 * hour + 30 * minute))
        assertEquals(0L to 45L, hoursAndMinutesOf(45 * minute))
        assertEquals(6L to 0L, hoursAndMinutesOf(6 * hour))
        assertEquals(0L to 0L, hoursAndMinutesOf(0))

        assertEquals(3 * hour + 30 * minute, millisOfHoursAndMinutes(3, 30))
        assertEquals(6 * hour, millisOfHoursAndMinutes(6, 0))
        assertEquals(0L, millisOfHoursAndMinutes(0, 0))
    }

    @Test
    fun `a cap ignores nonsense rather than producing a negative deadline`() {
        assertEquals(0L, millisOfHoursAndMinutes(-3, -30))
        assertEquals(0L to 0L, hoursAndMinutesOf(-hour))
    }

    @Test
    fun `more than sixty minutes is accepted and normalises on the way back`() {
        // Nothing stops someone typing 90 into the minutes box; it means an hour and a half.
        assertEquals(90 * minute, millisOfHoursAndMinutes(0, 90))
        assertEquals(1L to 30L, hoursAndMinutesOf(millisOfHoursAndMinutes(0, 90)))
    }

    // --- Log file format ---

    @Test
    fun `a segment survives a round trip through the log format`() {
        listOf(
            segment(Category.SCHOOL),
            segment(Category.PERSONAL, autoClosed = true),
            segment(lasting = 0),
        ).forEach { original ->
            assertEquals(original, parseSegmentLine(formatSegmentLine(original)))
        }
    }

    @Test
    fun `the log line is the documented shape`() {
        val line = formatSegmentLine(StudySegment(Category.PERSONAL, 1_000, 61_000, true))

        assertEquals("PERSONAL,1000,61000,true", line)
    }

    @Test
    fun `an unreadable line is skipped rather than fatal`() {
        // One truncated line — the process died mid-write — must not cost the whole history.
        assertNull(parseSegmentLine(""))
        assertNull(parseSegmentLine("SCHOOL,1000"))
        assertNull(parseSegmentLine("SCHOOL,1000,2000"))
        assertNull(parseSegmentLine("HOMEWORK,1000,2000,false"))
        assertNull(parseSegmentLine("SCHOOL,not-a-number,2000,false"))
        assertNull(parseSegmentLine("SCHOOL,1000,2000,maybe"))
    }

    @Test
    fun `a line with trailing whitespace still parses`() {
        assertEquals(
            StudySegment(Category.SCHOOL, 1_000, 2_000, false),
            parseSegmentLine("SCHOOL,1000,2000,false\n"),
        )
    }
}
