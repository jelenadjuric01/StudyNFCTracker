package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the time arithmetic in `StudyTime.kt`: the day boundary, the duration wording, and the
 * hours-and-minutes cap fields. All pure functions, so no device and no clock control needed.
 */
class StudyTimeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

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
        // floorDiv, not integer division: plain division truncates towards zero and would put every
        // negative timestamp on day 0.
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
}
