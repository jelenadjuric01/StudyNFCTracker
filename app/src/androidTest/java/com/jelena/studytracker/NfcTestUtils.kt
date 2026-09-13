package com.jelena.studytracker

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream

/**
 * Test utilities to construct NDEF NFC intents and simulate system events on the emulator.
 */
object NfcTestUtils {
    const val MIME_TYPE = "application/vnd.com.jelena.studytracker"

    /**
     * Creates an [Intent] containing a simulated NDEF payload matching the app's MIME filter.
     */
    fun createNdefIntent(
        payload: String?,
        mimeType: String = MIME_TYPE,
        action: String = NfcAdapter.ACTION_NDEF_DISCOVERED,
    ): Intent {
        val intent = Intent(action).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (payload != null) {
            val record = NdefRecord.createMime(mimeType, payload.toByteArray(Charsets.UTF_8))
            val message = NdefMessage(arrayOf(record))
            intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(message))
            intent.setType(mimeType)
        }
        return intent
    }

    /**
     * Simulates an NFC tag tap by launching [TagIntentActivity] with a mock NDEF payload.
     */
    fun simulateTagTap(context: Context, payload: String?) {
        val intent = createNdefIntent(payload).apply {
            setClass(context, TagIntentActivity::class.java)
        }
        context.startActivity(intent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Grants the ACCESS_NOTIFICATION_POLICY (Do Not Disturb) permission via ADB shell.
     */
    fun grantDndPermission() {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        executeShell("cmd notification allow_dnd $targetPackage")
    }

    /**
     * Revokes the ACCESS_NOTIFICATION_POLICY (Do Not Disturb) permission via ADB shell.
     */
    fun revokeDndPermission() {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        executeShell("cmd notification disallow_dnd $targetPackage")
    }

    /**
     * Clears shared preferences and the sessions.log file to ensure a clean test state.
     */
    fun clearStorage(context: Context) {
        synchronized(StudyStateLock) {
            val store = StudyStateStore(context)
            store.save(StudyState())
            store.saveAutoCloseCapMillis(0L)
            val logFile = File(context.filesDir, "sessions.log")
            if (logFile.exists()) {
                logFile.delete()
            }
        }
    }

    /**
     * Executes an ADB shell command using the active UiAutomation bridge.
     */
    fun executeShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return pfd.use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText()
        }
    }

    /**
     * Polls until [condition] evaluates to true or [timeoutMillis] elapses.
     */
    fun waitUntil(timeoutMillis: Long = 3000, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
