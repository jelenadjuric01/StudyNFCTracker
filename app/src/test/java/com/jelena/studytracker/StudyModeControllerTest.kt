package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven tests over every (state x tag) combination.
 *
 * [StudyModeController] holds the only real rules in the app, and it has no Android
 * dependencies, so it can be covered exhaustively by plain JVM tests — no Robolectric,
 * no device, no NFC hardware.
 */
class StudyModeControllerTest {

    /**
     * An arbitrary "now" — a plausible epoch timestamp, so that subtracting an hour from it
     * still lands in positive territory. A small value would make earlier segment starts
     * negative, which the controller rejects as unusable state.
     */
    private val now = 1_800_000_000_000L

    /** A tap long enough after [now] that the debounce window has certainly expired. */
    private val later = now + StudyModeController.DEBOUNCE_MILLIS * 2

    private companion object {
        const val MINUTE = 60_000L
    }

    @Test
    fun `study tag from off starts a school session and ends nothing`() {
        val result = StudyModeController.onTap(StudyState(), StudyTag.STUDY, now)

        assertEquals(
            TapResult.Changed(
                state = StudyState(
                    active = true,
                    category = Category.SCHOOL,
                    lastTag = StudyTag.STUDY,
                    lastTapAtMillis = now,
                    segmentStartedAtMillis = now,
                    // Both start together; only the segment moves when the category is switched.
                    sessionStartedAtMillis = now,
                ),
                completed = null,
            ),
            result,
        )
    }

    @Test
    fun `study tag while on ends the session and returns the segment`() {
        val on = StudyState(
            active = true,
            category = Category.PERSONAL,
            segmentStartedAtMillis = now - 30 * MINUTE,
        )

        val result = StudyModeController.onTap(on, StudyTag.STUDY, now)

        assertEquals(
            TapResult.Changed(
                state = StudyState(
                    active = false,
                    category = Category.SCHOOL,
                    lastTag = StudyTag.STUDY,
                    lastTapAtMillis = now,
                    segmentStartedAtMillis = 0L,
                ),
                completed = StudySegment(Category.PERSONAL, now - 30 * MINUTE, now),
            ),
            result,
        )
    }

    @Test
    fun `switch tag closes the old category and opens the new one at the same instant`() {
        val on = StudyState(
            active = true,
            category = Category.SCHOOL,
            segmentStartedAtMillis = now - 45 * MINUTE,
        )

        val result = StudyModeController.onTap(on, StudyTag.SWITCH, now) as TapResult.Changed

        assertEquals(StudySegment(Category.SCHOOL, now - 45 * MINUTE, now), result.completed)
        assertEquals(Category.PERSONAL, result.state.category)
        assertEquals(now, result.state.segmentStartedAtMillis)
    }

    @Test
    fun `a segment with no measurable length is not reported`() {
        // Two taps in the same millisecond, and a state restored without a start time. Neither
        // is a real stretch of study time, and neither may reach the day's totals.
        val instant = StudyState(active = true, segmentStartedAtMillis = now)
        assertNull((StudyModeController.onTap(instant, StudyTag.STUDY, now) as TapResult.Changed).completed)

        val noStart = StudyState(active = true, segmentStartedAtMillis = 0L)
        assertNull((StudyModeController.onTap(noStart, StudyTag.STUDY, now) as TapResult.Changed).completed)
    }

    @Test
    fun `a segment that ends before it started is not reported`() {
        val on = StudyState(active = true, segmentStartedAtMillis = now)

        val result = StudyModeController.onTap(on, StudyTag.STUDY, now - MINUTE) as TapResult.Changed

        assertNull(result.completed)
    }

    @Test
    fun `switch tag toggles category while on`() {
        val school = StudyState(active = true, category = Category.SCHOOL)

        val toPersonal = StudyModeController.onTap(school, StudyTag.SWITCH, now)
        assertEquals(Category.PERSONAL, toPersonal.state.category)

        val backToSchool = StudyModeController.onTap(toPersonal.state, StudyTag.SWITCH, later)
        assertEquals(Category.SCHOOL, backToSchool.state.category)
    }

    @Test
    fun `switch tag never turns the mode on`() {
        val toPersonal = StudyModeController.onTap(StudyState(active = true), StudyTag.SWITCH, now)
        assertEquals(true, toPersonal.state.active)
    }

    @Test
    fun `switch tag while off does nothing`() {
        val off = StudyState()

        val result = StudyModeController.onTap(off, StudyTag.SWITCH, now)

        assertEquals(TapResult.Ignored(off, IgnoredReason.MODE_OFF), result)
    }

    @Test
    fun `same tag within the debounce window is ignored`() {
        val afterStudyTap = StudyModeController.onTap(StudyState(), StudyTag.STUDY, now).state

        val bounce = StudyModeController.onTap(
            afterStudyTap,
            StudyTag.STUDY,
            now + StudyModeController.DEBOUNCE_MILLIS - 1,
        )

        assertEquals(TapResult.Ignored(afterStudyTap, IgnoredReason.DUPLICATE_TAP), bounce)
    }

    @Test
    fun `same tag exactly at the debounce boundary is accepted`() {
        val afterStudyTap = StudyModeController.onTap(StudyState(), StudyTag.STUDY, now).state

        val second = StudyModeController.onTap(
            afterStudyTap,
            StudyTag.STUDY,
            now + StudyModeController.DEBOUNCE_MILLIS,
        )

        assertEquals(false, second.state.active)
        assertTrue(second is TapResult.Changed)
    }

    @Test
    fun `a different tag within the debounce window is not a bounce`() {
        val afterStudyTap = StudyModeController.onTap(StudyState(), StudyTag.STUDY, now).state

        val switch = StudyModeController.onTap(afterStudyTap, StudyTag.SWITCH, now + 100)

        assertEquals(Category.PERSONAL, switch.state.category)
    }

    @Test
    fun `a tap timestamped before the previous one is not treated as a bounce`() {
        // A backwards clock (manual change, DST, NTP correction) must not be able to
        // lock out taps: a negative gap is not "within 2 seconds".
        val afterStudyTap = StudyModeController.onTap(StudyState(), StudyTag.STUDY, now).state
        val earlierTap = now - 60_000

        val earlier = StudyModeController.onTap(afterStudyTap, StudyTag.STUDY, earlierTap)

        assertEquals(
            TapResult.Changed(
                state = StudyState(
                    active = false,
                    category = Category.SCHOOL,
                    lastTag = StudyTag.STUDY,
                    lastTapAtMillis = earlierTap,
                    segmentStartedAtMillis = 0L,
                ),
                // The session it ended appears to have negative length, so it is worth nothing.
                completed = null,
            ),
            earlier,
        )
    }

    @Test
    fun `only the study tag needs Do Not Disturb to change`() {
        // The rule behind this: losing notification-policy access mid-session must not block
        // category changes, because a session is equally silenced either way.
        val off = StudyState()
        val started = StudyModeController.onTap(off, StudyTag.STUDY, now).state
        assertTrue(StudyModeController.silencingChanged(off, started))

        val switched = StudyModeController.onTap(started, StudyTag.SWITCH, later).state
        assertFalse(StudyModeController.silencingChanged(started, switched))

        val ended = StudyModeController.onTap(switched, StudyTag.STUDY, later + later).state
        assertTrue(StudyModeController.silencingChanged(switched, ended))
    }

    @Test
    fun `the documented tap sequence produces the documented states`() {
        // tap STUDY -> on/school, SWITCH -> personal, SWITCH -> school, STUDY -> off.
        val taps = listOf(StudyTag.STUDY, StudyTag.SWITCH, StudyTag.SWITCH, StudyTag.STUDY)
        val expected = listOf(
            true to Category.SCHOOL,
            true to Category.PERSONAL,
            true to Category.SCHOOL,
            false to Category.SCHOOL,
        )

        // Wide enough that no tap in the sequence is swallowed by the debounce rule.
        val gap = StudyModeController.DEBOUNCE_MILLIS * 2

        var state = StudyState()
        taps.forEachIndexed { index, tag ->
            state = StudyModeController.onTap(state, tag, now + index * gap).state
            assertEquals(expected[index], state.active to state.category)
        }
    }
}
