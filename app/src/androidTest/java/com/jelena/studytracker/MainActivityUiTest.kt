package com.jelena.studytracker

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

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
    fun givenMainActivityLaunched_whenOpened_thenDisplaysInitialIdleSessionState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sessionPanel = activity.findViewById<View>(R.id.sessionPanel)
                val studyStateText = activity.findViewById<TextView>(R.id.studyStateText)
                val runningText = activity.findViewById<TextView>(R.id.runningText)

                assertEquals(View.VISIBLE, sessionPanel.visibility)
                assertEquals(activity.getString(R.string.state_off), studyStateText.text.toString())
                assertEquals(View.GONE, runningText.visibility)
            }
        }
    }

    @Test
    fun givenMainActivity_whenBottomNavigationTabsClicked_thenSwitchesPanelVisibility() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sessionPanel = activity.findViewById<View>(R.id.sessionPanel)
                val tagsPanel = activity.findViewById<View>(R.id.tagsPanel)
                val setupPanel = activity.findViewById<View>(R.id.setupPanel)
                val navTags = activity.findViewById<View>(R.id.navTags)
                val navSetup = activity.findViewById<View>(R.id.navSetup)
                val navSession = activity.findViewById<View>(R.id.navSession)

                // Initial tab is Session
                assertEquals(View.VISIBLE, sessionPanel.visibility)
                assertEquals(View.GONE, tagsPanel.visibility)
                assertEquals(View.GONE, setupPanel.visibility)

                // Switch to Tags tab
                navTags.performClick()
                assertEquals(View.GONE, sessionPanel.visibility)
                assertEquals(View.VISIBLE, tagsPanel.visibility)
                assertEquals(View.GONE, setupPanel.visibility)

                // Switch to Setup tab
                navSetup.performClick()
                assertEquals(View.GONE, sessionPanel.visibility)
                assertEquals(View.GONE, tagsPanel.visibility)
                assertEquals(View.VISIBLE, setupPanel.visibility)

                // Return to Session tab
                navSession.performClick()
                assertEquals(View.VISIBLE, sessionPanel.visibility)
                assertEquals(View.GONE, tagsPanel.visibility)
                assertEquals(View.GONE, setupPanel.visibility)
            }
        }
    }

    @Test
    fun givenSetupTab_whenAutoCloseCapEnteredAndSaved_thenPreferencesAndUiAreUpdated() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.navSetup).performClick()

                val hoursInput = activity.findViewById<EditText>(R.id.capHoursInput)
                val minutesInput = activity.findViewById<EditText>(R.id.capMinutesInput)
                val saveButton = activity.findViewById<Button>(R.id.saveCapButton)
                val capStatusText = activity.findViewById<TextView>(R.id.capStatusText)

                hoursInput.setText("2")
                minutesInput.setText("15")
                saveButton.performClick()

                // Assert persistence
                val expectedMillis = (2 * 60 + 15) * 60 * 1000L
                assertEquals(expectedMillis, stateStore.loadAutoCloseCapMillis())

                // Assert UI reflects saved cap
                assertTrue(capStatusText.text.contains("2 h 15 min"))
            }
        }
    }

    @Test
    fun givenMainActivityInForeground_whenTagIntentReceived_thenLiveStatusUpdatesOnScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val studyStateText = activity.findViewById<TextView>(R.id.studyStateText)
                assertEquals(activity.getString(R.string.state_off), studyStateText.text.toString())
            }

            // Simulate STUDY tag tap
            NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)

            // Wait for storage update
            assertTrue(
                "Study status should update to studying",
                NfcTestUtils.waitUntil(timeoutMillis = 4000) {
                    stateStore.load().active
                },
            )

            // Allow tick loop to update UI in MainActivity
            assertTrue(
                "UI text should reflect studying category",
                NfcTestUtils.waitUntil(timeoutMillis = 4000) {
                    var textMatches = false
                    scenario.onActivity { activity ->
                        val studyStateText = activity.findViewById<TextView>(R.id.studyStateText)
                        val schoolLabel = activity.getString(R.string.category_school)
                        textMatches = studyStateText.text.contains(schoolLabel)
                    }
                    textMatches
                },
            )
        }
    }

    @Test
    fun givenCompletedStudySession_whenMainActivityOpened_thenHistoryDisplaysUpdatedDuration() {
        // Log a prior session of 45 minutes
        val now = System.currentTimeMillis()
        val startedFortyFiveMinAgo = now - 45 * 60 * 1000L
        sessionLog.append(StudySegment(Category.SCHOOL, startedFortyFiveMinAgo, now))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "History text should display 45 min",
                NfcTestUtils.waitUntil(timeoutMillis = 4000) {
                    var containsDuration = false
                    scenario.onActivity { activity ->
                        val todayText = activity.findViewById<TextView>(R.id.todayText)
                        containsDuration = todayText.text.contains("45 min")
                    }
                    containsDuration
                },
            )
        }
    }

    /**
     * Visual demonstration test designed to be watched live on the emulator screen.
     * Contains pauses between actions so human observers can follow state changes,
     * live timer ticking, category switching, and tab transitions.
     */
    @Test
    fun demonstrateLiveWorkflowOnScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 1. Initial idle state
            Thread.sleep(2500)

            // 2. Simulate STUDY tag tap -> Live timer starts ticking on screen
            NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
            Thread.sleep(4000) // Watch the timer count up live on screen

            // 3. Simulate SWITCH tag tap -> Category switches to Personal
            NfcTestUtils.simulateTagTap(context, StudyTag.SWITCH.payload)
            Thread.sleep(4000) // Watch category change to Personal and timer continue

            // 4. Simulate STUDY tag tap -> Session completes and logs to history
            NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
            Thread.sleep(3000) // Watch status return to Not studying and history update

            // 5. Switch to Tags tab
            scenario.onActivity { it.findViewById<View>(R.id.navTags).performClick() }
            Thread.sleep(2500)

            // 6. Switch to Setup tab and configure 1h 30m auto-close cap
            scenario.onActivity {
                it.findViewById<View>(R.id.navSetup).performClick()
                it.findViewById<EditText>(R.id.capHoursInput).setText("1")
                it.findViewById<EditText>(R.id.capMinutesInput).setText("30")
                it.findViewById<Button>(R.id.saveCapButton).performClick()
            }
            Thread.sleep(3000)

            // 7. Return to Session tab
            scenario.onActivity { it.findViewById<View>(R.id.navSession).performClick() }
            Thread.sleep(3000)
        }
    }
}
