package com.jelena.studytracker

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runner methods designed to be triggered individually via ADB or Android Studio
 * while MainActivity is actively running on the emulator screen.
 *
 * Unlike test suites, these methods DO NOT clear storage in tearDown, allowing you to
 * see persistent state changes, live timer updates, and history logs on screen.
 */
@RunWith(AndroidJUnit4::class)
class ManualTapRunner {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun tapStudy() {
        NfcTestUtils.grantDndPermission()
        NfcTestUtils.simulateTagTap(context, StudyTag.STUDY.payload)
    }

    @Test
    fun tapSwitch() {
        NfcTestUtils.grantDndPermission()
        NfcTestUtils.simulateTagTap(context, StudyTag.SWITCH.payload)
    }

    @Test
    fun triggerAutoClose() {
        AutoCloseReceiver().onReceive(
            context,
            Intent("com.jelena.studytracker.ACTION_AUTO_CLOSE"),
        )
    }

    @Test
    fun resetStorage() {
        NfcTestUtils.clearStorage(context)
    }
}
