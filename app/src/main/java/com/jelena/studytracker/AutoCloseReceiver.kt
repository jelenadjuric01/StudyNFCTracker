package com.jelena.studytracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Ends a session the user forgot to end, and puts the alarm back after a reboot.
 *
 * Two jobs in one receiver because they are two halves of the same guarantee: the alarm closes
 * the session, and alarms do not survive a restart, so something has to re-arm it.
 */
class AutoCloseReceiver : BroadcastReceiver() {

    /**
     * Hands the work to [WORKER] and returns immediately.
     *
     * The work itself is storage — two preference reads, a line appended to a file — which does not
     * belong on the main thread. Moving it off is only safe because of [goAsync]: normally the
     * process may be killed the moment `onReceive` returns, which would abandon the write halfway
     * and leave the phone silenced with the app believing the session is over. The [PendingResult]
     * [goAsync] returns keeps the process alive, and at raised priority, until it is finished.
     *
     * @param intent belongs to this broadcast and must not be touched once this method has
     *   returned, so the action is read out here and the rest is passed the application context.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val action = intent.action

        try {
            WORKER.execute {
                try {
                    handle(appContext, action)
                } catch (e: RuntimeException) {
                    // Logged rather than allowed to escape, so a failure names the broadcast that
                    // caused it instead of just killing the process. Note this is *not* a rollback:
                    // a throw from the last steps of closeForgottenSession leaves the state already
                    // saved. Every path is recoverable — the next alarm or tap reads the state as it
                    // now is — so there is nothing to undo, only something to say out loud.
                    //
                    // Errors are deliberately not caught: an OutOfMemoryError is not something this
                    // receiver can carry on through.
                    Log.e(TAG, "Handling $action failed", e)
                } finally {
                    // In a finally, and not optional: a PendingResult that is never finished holds
                    // the process up until the system times it out.
                    pending?.finish()
                }
            }
        } catch (e: RuntimeException) {
            // The submission itself failed, so the block above will never run and nothing else will
            // ever finish the result. The one path that would otherwise hold the broadcast open
            // until the system force-finishes it.
            Log.e(TAG, "Could not hand off $action", e)
            pending?.finish()
        }
    }

    /** The receiver's actual work, on [WORKER] rather than the main thread. */
    private fun handle(context: Context, action: String?) {
        when (action) {
            ACTION_AUTO_CLOSE -> closeForgottenSession(context)

            // Alarms are dropped on shutdown. Without this, one reboot would silently disable the
            // safety net for the running session and nothing would ever say so.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                synchronized(StudyStateLock) {
                    val store = StudyStateStore(context)
                    AutoCloseScheduler(context).sync(store.load(), store.loadAutoCloseCapMillis())
                }
            }

            else -> Log.w(TAG, "Ignoring unexpected action: $action")
        }
    }

    /**
     * Held under [StudyStateLock] from the first read to the last write: this runs on a worker
     * thread, and [TagIntentActivity] is doing the same load-decide-save sequence on the main
     * thread of the same process. Without the lock a cap expiring just as the user taps STUDY can
     * record the same stretch twice and save a state derived from a snapshot the tap has already
     * replaced.
     */
    private fun closeForgottenSession(context: Context) = synchronized(StudyStateLock) {
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

        // An alarm rather than a silent close: studying may still be going on, and the user needs to
        // know the counting stopped and the STUDY tag needs tapping again.
        //
        // The toast is the fallback for a phone that will not show notifications at all. It is worth
        // little — invisible if the screen is off, which is the likely case here — but it is better
        // than nothing, and the setup screen still labels the recorded stretch as auto-closed.
        if (!AutoCloseAlarm(context).alert(result.completed)) {
            // Back to the main thread: a Toast needs a Looper, and this code is on [WORKER].
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, R.string.auto_closed_toast, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_AUTO_CLOSE = "com.jelena.studytracker.AUTO_CLOSE"

        private const val TAG = "AutoCloseReceiver"

        /**
         * Where [handle] runs.
         *
         * Single-threaded and shared by every broadcast, so two of them can never be part-way
         * through reading and rewriting the same state at once. A receiver instance is thrown away
         * after each broadcast, which is why this lives on the companion and not on the instance.
         */
        private val WORKER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "auto-close").apply { isDaemon = true }
        }
    }
}
