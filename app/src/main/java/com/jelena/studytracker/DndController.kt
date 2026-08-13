package com.jelena.studytracker

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Turns Do Not Disturb on and off.
 *
 * Wraps the one Android API the app cannot work without, and keeps its permission dance in
 * a single place: [isGranted] / [settingsIntent] for the setup screen, [apply] for the tap
 * handler.
 */
class DndController(context: Context) {

    private val notificationManager =
        context.applicationContext.getSystemService(NotificationManager::class.java)

    /**
     * Whether the user has granted notification-policy access in Settings.
     *
     * This is a special access grant: declaring the permission in the manifest is not
     * enough, and there is no runtime permission dialog for it. Without it, every call to
     * [apply] throws.
     */
    fun isGranted(): Boolean = notificationManager?.isNotificationPolicyAccessGranted == true

    /**
     * Silences or unsilences the phone to match [active].
     *
     * Uses `INTERRUPTION_FILTER_PRIORITY` rather than `_NONE`: priority mode still lets
     * through whatever the user has marked important — starred contacts, alarms — so a
     * study session cannot swallow a genuine emergency call. `_NONE` would.
     *
     * @param active `true` to silence, `false` to restore normal notifications.
     * @return `true` if the filter was set. `false` means the permission is missing or the
     *   platform refused, and the caller should tell the user rather than assume silence.
     */
    fun apply(active: Boolean): Boolean {
        val manager = notificationManager ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false

        val filter = if (active) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }

        return try {
            // Not a Kotlin property: the getter is getCurrentInterruptionFilter(), so the
            // setter does not pair up into one and has to be called by name.
            manager.setInterruptionFilter(filter)
            true
        } catch (e: SecurityException) {
            // The grant can be revoked between the check above and this call, and some
            // vendor ROMs refuse it outright. Report the failure rather than letting an
            // exception escape a tap and crash the app.
            Log.e(TAG, "Setting the interruption filter was refused", e)
            false
        }
    }

    /**
     * The Settings screen where notification-policy access is granted.
     *
     * There is no in-app dialog for this permission — the user has to find the app in a
     * system list. [MainActivity] explains why before sending them there.
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    private companion object {
        const val TAG = "DndController"
    }
}
