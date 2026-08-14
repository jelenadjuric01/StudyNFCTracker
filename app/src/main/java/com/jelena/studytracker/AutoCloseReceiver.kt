package com.jelena.studytracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Ends a session the user forgot to end, and puts the alarm back after a reboot.
 *
 * Two jobs in one receiver because they are two halves of the same guarantee: the alarm closes
 * the session, and alarms do not survive a restart, so something has to re-arm it.
 */
class AutoCloseReceiver : BroadcastReceiver() {

    /**
     * Runs on the main thread with a time budget of a few seconds, so everything here must be
     * quick and blocking-free. It is: two small preference reads, one line appended to a file, and
     * one call into [AlarmManager][android.app.AlarmManager].
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_AUTO_CLOSE -> closeForgottenSession(context)

            // Alarms are dropped on shutdown. Without this, one reboot would silently disable the
            // safety net for the running session and nothing would ever say so.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val store = StudyStateStore(context)
                AutoCloseScheduler(context).sync(store.load(), store.loadAutoCloseCapMillis())
            }

            else -> Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
        }
    }

    private fun closeForgottenSession(context: Context) {
        val store = StudyStateStore(context)
        val state = store.load()
        val deadline = StudyModeController.autoCloseDeadline(state, store.loadAutoCloseCapMillis())

        // Nothing to close. Routine rather than exceptional: an inexact alarm can arrive minutes
        // after a real closing tap already ended the session.
        if (deadline == null) return

        // The session is cut off at the deadline, not at now. An alarm delayed by Doze must not
        // turn a three-hour cap into a four-hour recorded session.
        val result = StudyModeController.autoClose(state, deadline)
        if (result !is TapResult.Changed) return

        // Unsilencing is the point: the phone must not stay quiet all night. If it fails, the state
        // is left alone so a later tap can still fix things, exactly as on the tap path.
        if (!DndController(context).apply(false)) {
            Log.e(TAG, "Auto-close could not restore notifications; leaving the session open")
            return
        }

        store.save(result.state)
        result.completed?.let { SessionLog(context).append(it) }
        AutoCloseScheduler(context).sync(result.state, store.loadAutoCloseCapMillis())

        Log.i(TAG, "Session auto-closed at $deadline: ${result.completed}")

        // Best effort only — invisible if the screen is off, which is the likely case. The real
        // signal is notifications starting to arrive again, and the setup screen labels the
        // recorded stretch as auto-closed so the number is never mistaken for a measurement.
        Toast.makeText(context, R.string.auto_closed_toast, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_AUTO_CLOSE = "com.jelena.studytracker.AUTO_CLOSE"

        private const val TAG = "AutoCloseReceiver"
    }
}
