package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the safety net that ends a session whose closing tap never came.
 *
 * The deadline arithmetic and the closing itself are pure, so all of this runs on the JVM — which
 * matters more here than elsewhere, because the alternative is waiting three hours per test.
 */
class AutoCloseTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_800_000_000_000L

    private val threeHours = 3 * hour

    /** A session that started an hour ago, on school work. */
    private val running = StudyState(
        active = true,
        category = Category.SCHOOL,
        lastTag = StudyTag.STUDY,
        lastTapAtMillis = now - hour,
        segmentStartedAtMillis = now - hour,
        sessionStartedAtMillis = now - hour,
    )

    // --- The deadline ---

    @Test
    fun `the deadline is the cap measured from the start of the session`() {
        assertEquals(running.sessionStartedAtMillis + threeHours, StudyModeController.autoCloseDeadline(running, threeHours))
    }

    @Test
    fun `switching category does not push the deadline back`() {
        // The whole reason sessionStartedAtMillis exists. If the cap were measured from the current
        // segment, switching category every hour would keep a session alive indefinitely and the
        // safety net would never fire.
        val switched = StudyModeController.onTap(running, StudyTag.SWITCH, now).state

        assertEquals(running.sessionStartedAtMillis, switched.sessionStartedAtMillis)
        assertEquals(
            StudyModeController.autoCloseDeadline(running, threeHours),
            StudyModeController.autoCloseDeadline(switched, threeHours),
        )
    }

    @Test
    fun `raising the cap mid-session extends the same session rather than restarting it`() {
        // "3 hours, then change it to 6" means four hours left after two hours of study, not six.
        val sixHours = 6 * hour

        val deadline = StudyModeController.autoCloseDeadline(running, sixHours)

        assertEquals(running.sessionStartedAtMillis + sixHours, deadline)
    }

    @Test
    fun `a cap already exceeded gives a deadline in the past`() {
        // Lowering the cap below the time already elapsed should close the session almost at once,
        // not never. AlarmManager fires a past-due alarm immediately, so a past deadline is the
        // correct answer rather than a bug to guard against.
        val deadline = StudyModeController.autoCloseDeadline(running, 30 * minute)

        assertEquals(running.sessionStartedAtMillis + 30 * minute, deadline)
        assertTrue(deadline!! < now)
    }

    @Test
    fun `there is no deadline when there is nothing to close`() {
        assertNull(StudyModeController.autoCloseDeadline(StudyState(), threeHours))
    }

    @Test
    fun `a cap of zero or less turns the safety net off`() {
        assertNull(StudyModeController.autoCloseDeadline(running, 0))
        assertNull(StudyModeController.autoCloseDeadline(running, -hour))
    }

    @Test
    fun `a session with no recorded start has no deadline`() {
        // State restored from a build that did not track the session start. Better no safety net
        // than one anchored to the epoch, which would close every session instantly.
        val noStart = running.copy(sessionStartedAtMillis = 0L)

        assertNull(StudyModeController.autoCloseDeadline(noStart, threeHours))
    }

    // --- The closing ---

    @Test
    fun `auto-closing ends the session and records the capped stretch`() {
        val deadline = running.sessionStartedAtMillis + threeHours

        val result = StudyModeController.autoClose(running, deadline) as TapResult.Changed

        assertEquals(false, result.state.active)
        assertEquals(0L, result.state.segmentStartedAtMillis)
        assertEquals(0L, result.state.sessionStartedAtMillis)
        assertEquals(
            StudySegment(Category.SCHOOL, running.segmentStartedAtMillis, deadline, autoClosed = true),
            result.completed,
        )
    }

    @Test
    fun `the recorded stretch ends at the deadline, not whenever the alarm arrived`() {
        // Inexact alarms can be delayed for a long time in Doze — which is exactly the situation
        // this fires in. A three-hour cap must never record four hours.
        val deadline = running.sessionStartedAtMillis + threeHours
        val hoursLate = deadline + 5 * hour

        val result = StudyModeController.autoClose(running, deadline) as TapResult.Changed

        assertEquals(deadline, result.completed?.endedAtMillis)
        assertTrue(result.completed!!.endedAtMillis < hoursLate)
    }

    @Test
    fun `auto-closing records the category that was running, not the default`() {
        val onPersonal = running.copy(category = Category.PERSONAL)

        val result = StudyModeController.autoClose(onPersonal, now) as TapResult.Changed

        assertEquals(Category.PERSONAL, result.completed?.category)
        // The state still resets to SCHOOL, so the next session starts predictably.
        assertEquals(Category.SCHOOL, result.state.category)
    }

    @Test
    fun `auto-closing a session that is already over changes nothing`() {
        // Routine, not exceptional: a late alarm can arrive after a real closing tap.
        val result = StudyModeController.autoClose(StudyState(), now)

        assertEquals(TapResult.Ignored(StudyState(), IgnoredReason.MODE_OFF), result)
    }

    @Test
    fun `auto-closing needs the phone unsilenced, so it counts as a silencing change`() {
        val result = StudyModeController.autoClose(running, now) as TapResult.Changed

        assertTrue(StudyModeController.silencingChanged(running, result.state))
    }

    @Test
    fun `a deadline before the segment started records nothing rather than a negative`() {
        // Possible if the category was switched right at the cap boundary.
        val justSwitched = running.copy(segmentStartedAtMillis = now)

        val result = StudyModeController.autoClose(justSwitched, now - minute) as TapResult.Changed

        assertNull(result.completed)
        assertEquals(false, result.state.active)
    }
}
