package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the time arithmetic: segment lengths, the day's totals, the day boundary and the
 * duration wording. All pure functions, so no device and no clock control needed.
 */
class StudyTimeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // --- StudySegment ---

    @Test
    fun `a segment lasts the gap between its ends`() {
        val segment = StudySegment(Category.SCHOOL, 1_000L, 1_000L + 90 * minute)

        assertEquals(90 * minute, segment.durationMillis)
    }

    @Test
    fun `a backwards segment is worth zero, never a negative`() {
        // A negative duration would subtract from the day's total and produce an hour count
        // lower than what was actually studied.
        val segment = StudySegment(Category.SCHOOL, 10_000L, 1_000L)

        assertEquals(0L, segment.durationMillis)
    }

    // --- DailyTotals ---

    @Test
    fun `segments accumulate into their own category`() {
        val totals = DailyTotals(day = 100)
            .plus(StudySegment(Category.SCHOOL, 0, 30 * minute), day = 100)
            .plus(StudySegment(Category.PERSONAL, 0, 20 * minute), day = 100)
            .plus(StudySegment(Category.SCHOOL, 0, 15 * minute), day = 100)

        assertEquals(45 * minute, totals.schoolMillis)
        assertEquals(20 * minute, totals.personalMillis)
        assertEquals(65 * minute, totals.rawTotalMillis)
        assertEquals(45 * minute, totals.millisFor(Category.SCHOOL))
    }

    @Test
    fun `the shown total is the sum of the shown parts`() {
        // The bug this pins down: flooring each category but rounding the raw sum printed
        // "2 min", "2 min", "5 min" — a column that visibly does not add up.
        val totals = DailyTotals(
            day = 100,
            schoolMillis = 2 * minute + 50_000,
            personalMillis = 2 * minute + 55_000,
        )

        assertEquals("2 min 50 s", formatDuration(totals.schoolMillis))
        assertEquals("2 min 55 s", formatDuration(totals.personalMillis))
        assertEquals("5 min 45 s", formatDuration(totals.shownTotalMillis))
    }

    @Test
    fun `two sub-minute stretches add up in seconds`() {
        val totals = DailyTotals(day = 100, schoolMillis = 20_000, personalMillis = 20_000)

        assertEquals("20 s", formatDuration(totals.schoolMillis))
        assertEquals("20 s", formatDuration(totals.personalMillis))
        assertEquals("40 s", formatDuration(totals.shownTotalMillis))
    }

    @Test
    fun `a column adds up exactly, whatever the parts are`() {
        // Brute force over second-level values: the seconds the total prints must equal the sum
        // of the seconds the parts print. This is the invariant that was broken — a property,
        // not an example.
        (0..7300 step 37).forEach { schoolSeconds ->
            (0..7300 step 53).forEach { personalSeconds ->
                val totals = DailyTotals(
                    day = 1,
                    schoolMillis = schoolSeconds * 1_000L,
                    personalMillis = personalSeconds * 1_000L,
                )

                val shownSeconds = Category.entries.sumOf { secondsShown(totals.millisFor(it)) }

                assertEquals(
                    "school ${schoolSeconds}s + personal ${personalSeconds}s",
                    shownSeconds,
                    secondsShown(totals.shownTotalMillis),
                )
            }
        }
    }

    /** The duration [formatDuration] will actually print for [millis], in whole seconds. */
    private fun secondsShown(millis: Long): Long = snapForDisplay(millis) / 1_000

    @Test
    fun `a segment on a new day replaces the old day instead of adding to it`() {
        val yesterday = DailyTotals(day = 100, schoolMillis = 5 * hour, personalMillis = 2 * hour)

        val today = yesterday.plus(StudySegment(Category.SCHOOL, 0, 10 * minute), day = 101)

        assertEquals(101L, today.day)
        assertEquals(10 * minute, today.schoolMillis)
        assertEquals(0L, today.personalMillis)
    }

    @Test
    fun `the first segment of all adopts the day it lands on`() {
        val fresh = DailyTotals()

        val totals = fresh.plus(StudySegment(Category.PERSONAL, 0, 25 * minute), day = 20_000)

        assertEquals(20_000L, totals.day)
        assertEquals(25 * minute, totals.personalMillis)
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
        assertEquals("29 s", formatDuration(29_000))
        assertEquals("31 s", formatDuration(31_000))
        assertEquals("59 s", formatDuration(59_000))
    }

    @Test
    fun `the seconds-to-minutes handover reads as one minute, never sixty seconds`() {
        assertEquals("59 s", formatDuration(59_400))
        assertEquals("1 min", formatDuration(59_600))
        assertEquals("1 min 1 s", formatDuration(minute + 1_000))
    }

    @Test
    fun `sub-second durations still read as zero seconds`() {
        assertEquals("0 s", formatDuration(400))
        assertEquals("1 s", formatDuration(600))
        assertEquals("0 s", formatDuration(-5_000))
    }

    @Test
    fun `a negative duration reads as zero rather than a minus sign`() {
        assertEquals("0 s", formatDuration(-hour))
    }
}
