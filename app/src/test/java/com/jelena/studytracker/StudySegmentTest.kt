package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for what the app records: how long a segment lasted, which day it counts towards, how
 * segments add up over a day and a week, and the log file format. All pure functions, so no device.
 */
class StudySegmentTest {

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

    // --- One segment ---

    @Test
    fun `a segment lasts the gap between its ends`() {
        assertEquals(90 * minute, segment(lasting = 90 * minute).durationMillis)
    }

    @Test
    fun `a backwards segment is worth zero, never a negative`() {
        // A negative duration would subtract from a total and produce an hour count lower than what
        // was actually studied.
        assertEquals(0L, StudySegment(Category.SCHOOL, 10_000L, 1_000L).durationMillis)
    }

    @Test
    fun `a segment counts towards the day it ended on`() {
        val acrossMidnight = segment(endedAt = midday, lasting = 20 * hour)

        assertEquals(LocalDays.of(midday), acrossMidnight.day)
    }

    // --- Adding them up ---

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
        assertEquals(65 * minute, totals.totalMillis)
        assertEquals(45 * minute, totals.millisFor(Category.SCHOOL))
    }

    @Test
    fun `totals of nothing are zero rather than absent`() {
        assertEquals(CategoryTotals(), totalsOf(emptyList()))
        assertEquals(0L, totalsOf(emptyList()).totalMillis)
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

    // --- What actually gets printed ---

    @Test
    fun `only categories with time against them are printed`() {
        val schoolOnly = listOf(segment(Category.SCHOOL, lasting = 20 * minute))

        assertEquals(listOf(Category.SCHOOL to 20 * minute), printableTotals(schoolOnly))
    }

    @Test
    fun `both categories are printed in a fixed order`() {
        val both = listOf(
            segment(Category.PERSONAL, lasting = 10 * minute),
            segment(Category.SCHOOL, lasting = 20 * minute),
        )

        // School first regardless of which was studied first, so the column does not reshuffle
        // between one look at the screen and the next.
        assertEquals(
            listOf(Category.SCHOOL to 20 * minute, Category.PERSONAL to 10 * minute),
            printableTotals(both),
        )
    }

    @Test
    fun `nothing is printed for no segments, or for segments worth no time`() {
        assertEquals(emptyList<Pair<Category, Long>>(), printableTotals(emptyList()))
        assertEquals(emptyList<Pair<Category, Long>>(), printableTotals(listOf(segment(lasting = 0))))
    }

    // --- The column on screen adds up ---

    @Test
    fun `a total is the sum of the figures printed above it`() {
        val totals = CategoryTotals(
            schoolMillis = 2 * minute + 50_000,
            personalMillis = 2 * minute + 55_000,
        )

        assertEquals("2 min 50 s", formatDuration(totals.schoolMillis))
        assertEquals("2 min 55 s", formatDuration(totals.personalMillis))
        assertEquals("5 min 45 s", formatDuration(totals.totalMillis))
    }

    @Test
    fun `a column adds up exactly, whatever the parts are`() {
        // Brute force over second-level values: the seconds the total prints must equal the sum of
        // the seconds the parts print. A property, not an example — this is the invariant that broke
        // when the parts were rounded one way and the total another.
        (0..7300 step 37).forEach { schoolSeconds ->
            (0..7300 step 53).forEach { personalSeconds ->
                val totals = CategoryTotals(schoolSeconds * 1_000L, personalSeconds * 1_000L)
                val printedParts = Category.entries.sumOf { snapForDisplay(totals.millisFor(it)) }

                assertEquals(
                    "school ${schoolSeconds}s + personal ${personalSeconds}s",
                    printedParts / 1_000,
                    snapForDisplay(totals.totalMillis) / 1_000,
                )
            }
        }
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
