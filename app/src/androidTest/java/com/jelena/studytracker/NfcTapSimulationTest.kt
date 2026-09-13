package com.jelena.studytracker

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NfcTapSimulationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val stateStore = StudyStateStore(context)
    private val sessionLog = SessionLog(context)

    @Before
    fun setUp() {
        NfcTestUtils.grantDndPermission()
        NfcTestUtils.clearStorage(context)
    }

    @After
    fun tearDown() {
        NfcTestUtils.grantDndPermission()
        NfcTestUtils.clearStorage(context)
    }

    @Test
    fun givenIdleState_whenStudyTagTapped_thenStudySessionStarts() {
        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)

        assertTrue(
            "State should transition to active",
            NfcTestUtils.waitUntil { stateStore.load().active },
        )
        val state = stateStore.load()
        assertEquals(Category.SCHOOL, state.category)
        assertTrue(state.segmentStartedAtMillis > 0)
        assertTrue(sessionLog.readAll().isEmpty())
    }

    @Test
    fun givenActiveStudyingState_whenStudyTagTapped_thenSessionEndsAndStudySegmentLogged() {
        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
        assertTrue(NfcTestUtils.waitUntil { stateStore.load().active })

        // Wait past debounce threshold (2000ms)
        Thread.sleep(StudyModeController.DEBOUNCE_MILLIS + 100L)

        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)

        assertTrue(
            "State should transition to inactive",
            NfcTestUtils.waitUntil { !stateStore.load().active },
        )

        assertTrue(
            "Completed study segment should be logged to disk",
            NfcTestUtils.waitUntil { sessionLog.readAll().isNotEmpty() },
        )
        val segments = sessionLog.readAll()
        assertEquals(1, segments.size)
        assertEquals(Category.SCHOOL, segments[0].category)
        assertTrue(segments[0].durationMillis >= 2000)
        assertFalse("Manual segment autoClosed must be false", segments[0].autoClosed)
    }

    @Test
    fun givenActiveStudyingState_whenSwitchTagTapped_thenSwitchesToPersonalAndSchoolSegmentLogged() {
        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
        assertTrue(NfcTestUtils.waitUntil { stateStore.load().active })

        Thread.sleep(StudyModeController.DEBOUNCE_MILLIS + 100L)

        NfcTestUtils.simulateTagTap(context, StudyTag.SWITCH.payload)

        assertTrue(
            "State should switch category to PERSONAL",
            NfcTestUtils.waitUntil { stateStore.load().category == Category.PERSONAL },
        )
        val state = stateStore.load()
        assertTrue(state.active)

        val segments = sessionLog.readAll()
        assertEquals(1, segments.size)
        assertEquals(Category.SCHOOL, segments[0].category)
    }

    @Test
    fun givenActivePersonalState_whenSwitchTagTapped_thenSwitchesBackToSchoolAndPersonalSegmentLogged() {
        // Start studying (default: SCHOOL)
        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
        assertTrue(NfcTestUtils.waitUntil { stateStore.load().active })
        Thread.sleep(StudyModeController.DEBOUNCE_MILLIS + 100L)

        // Switch to PERSONAL
        NfcTestUtils.simulateTagTap(context, StudyTag.SWITCH.payload)
        assertTrue(NfcTestUtils.waitUntil { stateStore.load().category == Category.PERSONAL })
        Thread.sleep(StudyModeController.DEBOUNCE_MILLIS + 100L)

        // Switch back to SCHOOL
        NfcTestUtils.simulateTagTap(context, StudyTag.SWITCH.payload)
        assertTrue(NfcTestUtils.waitUntil { stateStore.load().category == Category.SCHOOL })

        val state = stateStore.load()
        assertTrue(state.active)

        val segments = sessionLog.readAll()
        assertEquals(2, segments.size)
        assertEquals(Category.SCHOOL, segments[0].category)
        assertEquals(Category.PERSONAL, segments[1].category)
    }

    @Test
    fun givenIdleState_whenSwitchTagTapped_thenTapIsIgnoredAndStateRemainsInactive() {
        NfcTestUtils.simulateTagTap(context, StudyTag.SWITCH.payload)

        // Allow looper to process intent
        Thread.sleep(500)

        val state = stateStore.load()
        assertFalse("State should remain inactive", state.active)
        assertTrue("Session log must remain empty", sessionLog.readAll().isEmpty())
    }

    @Test
    fun givenRapidConsecutiveTaps_whenDuplicateTapReceived_thenDebounceIgnoresSecondTap() {
        // First tap: starts study session
        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
        assertTrue(NfcTestUtils.waitUntil { stateStore.load().active })

        // Immediate second tap within debounce window (< 1500ms)
        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
        Thread.sleep(300)

        // Session must still be active (not toggled off by accidental bounce)
        val state = stateStore.load()
        assertTrue("Session must remain active due to debounce", state.active)
        assertTrue("No segments should be recorded", sessionLog.readAll().isEmpty())
    }

    @Test
    fun givenUnrecognizedTagPayload_whenTapped_thenStateRemainsUnchangedAndNoLogAppended() {
        NfcTestUtils.simulateTagTap(context, "some_unrecognized_custom_tag")
        Thread.sleep(500)

        val state = stateStore.load()
        assertFalse(state.active)
        assertTrue(sessionLog.readAll().isEmpty())
    }

    @Test
    fun givenIntentWithMissingNdefPayload_whenReceived_thenHandledSafelyWithoutCrash() {
        NfcTestUtils.simulateTagTap(context, null)
        Thread.sleep(500)

        val state = stateStore.load()
        assertFalse(state.active)
        assertTrue(sessionLog.readAll().isEmpty())
    }

    @Test
    fun givenDndPermissionRevoked_whenStudyTagTapped_thenSessionDoesNotStart() {
        NfcTestUtils.revokeDndPermission()

        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
        Thread.sleep(600)

        val state = stateStore.load()
        assertFalse("Session must not start when DND permission is missing", state.active)
        assertTrue(sessionLog.readAll().isEmpty())
    }
}
