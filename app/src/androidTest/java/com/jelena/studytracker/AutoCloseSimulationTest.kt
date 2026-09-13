package com.jelena.studytracker

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoCloseSimulationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
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

    private fun sendBroadcast(action: String) {
        AutoCloseReceiver().onReceive(context, Intent(action))
    }

    @Test
    fun givenActiveSessionExceedingCap_whenAutoCloseBroadcastReceived_thenSessionClosesAndSegmentLogged() {
        val now = System.currentTimeMillis()
        val twoHours = 2 * 3600 * 1000L
        val startedThreeHoursAgo = now - 3 * 3600 * 1000L

        synchronized(StudyStateLock) {
            stateStore.saveAutoCloseCapMillis(twoHours)
            stateStore.save(
                StudyState(
                    active = true,
                    category = Category.SCHOOL,
                    segmentStartedAtMillis = startedThreeHoursAgo,
                    sessionStartedAtMillis = startedThreeHoursAgo,
                    lastTapAtMillis = startedThreeHoursAgo,
                ),
            )
        }

        sendBroadcast(AutoCloseReceiver.ACTION_AUTO_CLOSE)

        assertTrue(
            "Session should be closed by auto-close receiver",
            NfcTestUtils.waitUntil { !stateStore.load().active },
        )

        assertTrue(
            "Session log should contain the auto-closed segment",
            NfcTestUtils.waitUntil { sessionLog.readAll().isNotEmpty() },
        )

        val segments = sessionLog.readAll()
        assertEquals(1, segments.size)
        assertEquals(Category.SCHOOL, segments[0].category)
        assertEquals("Segment should be capped at exact cap duration", twoHours, segments[0].durationMillis)
        assertTrue("Segment must be flagged as autoClosed", segments[0].autoClosed)
    }

    @Test
    fun givenActiveSessionWithCapDisabled_whenAutoCloseBroadcastReceived_thenSessionRemainsActive() {
        val now = System.currentTimeMillis()

        synchronized(StudyStateLock) {
            stateStore.saveAutoCloseCapMillis(0L)
            stateStore.save(
                StudyState(
                    active = true,
                    category = Category.SCHOOL,
                    segmentStartedAtMillis = now,
                    sessionStartedAtMillis = now,
                    lastTapAtMillis = now,
                ),
            )
        }

        sendBroadcast(AutoCloseReceiver.ACTION_AUTO_CLOSE)
        Thread.sleep(600)

        val state = stateStore.load()
        assertTrue("Session must remain active when cap is disabled", state.active)
        assertTrue(sessionLog.readAll().isEmpty())
    }

    @Test
    fun givenIdleState_whenAutoCloseBroadcastReceived_thenNoSegmentIsLogged() {
        sendBroadcast(AutoCloseReceiver.ACTION_AUTO_CLOSE)
        Thread.sleep(600)

        val state = stateStore.load()
        assertFalse(state.active)
        assertTrue(sessionLog.readAll().isEmpty())
    }

    @Test
    fun givenActiveSession_whenBootCompletedBroadcastReceived_thenReceiverHandlesWithoutError() {
        val now = System.currentTimeMillis()
        val twoHours = 2 * 3600 * 1000L

        synchronized(StudyStateLock) {
            stateStore.saveAutoCloseCapMillis(twoHours)
            stateStore.save(
                StudyState(
                    active = true,
                    category = Category.SCHOOL,
                    segmentStartedAtMillis = now,
                    sessionStartedAtMillis = now,
                    lastTapAtMillis = now,
                ),
            )
        }

        sendBroadcast(Intent.ACTION_BOOT_COMPLETED)
        Thread.sleep(600)

        val state = stateStore.load()
        assertTrue("Session must remain active after boot sync", state.active)
    }

    @Test
    fun givenActiveSession_whenPackageReplacedBroadcastReceived_thenReceiverHandlesWithoutError() {
        val now = System.currentTimeMillis()
        val twoHours = 2 * 3600 * 1000L

        synchronized(StudyStateLock) {
            stateStore.saveAutoCloseCapMillis(twoHours)
            stateStore.save(
                StudyState(
                    active = true,
                    category = Category.SCHOOL,
                    segmentStartedAtMillis = now,
                    sessionStartedAtMillis = now,
                    lastTapAtMillis = now,
                ),
            )
        }

        sendBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED)
        Thread.sleep(600)

        val state = stateStore.load()
        assertTrue("Session must remain active after package replacement sync", state.active)
    }
}
