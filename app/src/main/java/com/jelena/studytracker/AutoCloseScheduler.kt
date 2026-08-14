package com.jelena.studytracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Sets and cancels the alarm that gives up on a session nobody closed.
 *
 * One alarm exists at most, because there is only ever one session. Re-scheduling replaces it
 * rather than adding another: the [PendingIntent] is built with the same request code and
 * `FLAG_UPDATE_CURRENT`, so Android treats it as the same alarm.
 */
class AutoCloseScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    /**
     * Points the alarm at [state]'s deadline, or cancels it if there is no session to close.
     *
     * Call this after anything that could move the deadline: a tap, a change to the cap, a reboot.
     * It is idempotent, so calling it when nothing changed is free.
     */
    fun sync(state: StudyState, capMillis: Long) {
        val deadline = StudyModeController.autoCloseDeadline(state, capMillis)
        if (deadline == null) {
            cancel()
        } else {
            schedule(deadline)
        }
    }

    private fun schedule(deadlineMillis: Long) {
        val manager = alarmManager ?: return

        // setAndAllowWhileIdle, not setExact: an exact alarm needs the SCHEDULE_EXACT_ALARM
        // permission on Android 12+, and a safety net for a forgotten tap does not need to be
        // punctual to the second. allowWhileIdle is the part that matters — a plain set() can be
        // deferred indefinitely while the phone is in Doze, which is exactly the situation this
        // alarm exists for (phone untouched on a desk all night).
        //
        // A deadline already in the past is legal and fires almost immediately, which is the
        // correct behaviour when the cap is shortened below the time already elapsed.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineMillis, pendingIntent())
        Log.i(TAG, "Auto-close scheduled for $deadlineMillis")
    }

    private fun cancel() {
        alarmManager?.cancel(pendingIntent())
        Log.i(TAG, "Auto-close cancelled")
    }

    /**
     * The alarm's payload: a broadcast to [AutoCloseReceiver].
     *
     * `FLAG_IMMUTABLE` is required from Android 12 and is correct here — nothing needs to fill in
     * fields on this intent. The fixed [REQUEST_CODE] is what makes every call refer to the same
     * single alarm.
     */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, AutoCloseReceiver::class.java).setAction(AutoCloseReceiver.ACTION_AUTO_CLOSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "AutoCloseScheduler"
        const val REQUEST_CODE = 1
    }
}
