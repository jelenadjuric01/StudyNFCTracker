package com.jelena.studytracker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Tells the user, audibly, that a session was closed for them.
 *
 * Without this the only sign is notifications starting to arrive again, which is easy to miss and
 * says nothing about *why* — and the point of the message is that studying may still be going on and
 * the STUDY tag needs tapping again to keep counting.
 *
 * It is a notification rather than a played sound because a notification survives being missed: the
 * phone is likely face-down or across the room when the cap expires, and a sound heard by nobody is
 * gone forever while a notification is still there afterwards.
 */
class AutoCloseAlarm(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /**
     * Rings, buzzes and posts the notification.
     *
     * @param completed the stretch that was recorded, or `null` if the session turned out to be worth
     *   no time. The duration is worth saying out loud — it is the number that just went into the log
     *   without anybody watching.
     * @return `false` if the phone would not show it, which on Android 13+ means the notification
     *   permission was never granted. The caller then falls back to a toast.
     */
    fun alert(completed: StudySegment?): Boolean {
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled; cannot alert about the auto-close")
            return false
        }

        createChannel()

        val text = if (completed == null) {
            appContext.getString(R.string.auto_closed_body_plain)
        } else {
            appContext.getString(
                R.string.auto_closed_body,
                formatDuration(completed.durationMillis),
                categoryLabel(appContext, completed.category),
            )
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.auto_closed_title))
            .setContentText(text)
            // The text runs past one line on most phones, so give it somewhere to expand to rather
            // than letting the important half be cut off.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            // Dismissed when tapped: it has been read at that point, and a stuck notification about a
            // session that ended hours ago is just noise.
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    /**
     * Creates the channel the notification is posted to. Safe to call repeatedly — an existing channel
     * with the same id is left alone.
     *
     * From Android 8 a notification's sound and vibration are properties of its channel, not of the
     * notification, and they are fixed once the channel exists. That is why the alarm sound is set
     * here: changing it later would need a new channel id.
     *
     * `NotificationChannelCompat` rather than the platform class so there is no version check — on
     * older phones the call does nothing and the notification's own priority carries the behaviour.
     */
    private fun createChannel() {
        // USAGE_ALARM, not USAGE_NOTIFICATION: it plays at alarm volume, which is what makes this
        // audible from another room, and alarms are the one thing Do Not Disturb never silences.
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(appContext.getString(R.string.auto_closed_channel_name))
            .setDescription(appContext.getString(R.string.auto_closed_channel_description))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audioAttributes)
            .setVibrationEnabled(true)
            .build()

        manager.createNotificationChannel(channel)
    }

    /** Tapping the notification opens the setup screen, where the recorded hours are. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        appContext,
        REQUEST_CODE,
        Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "AutoCloseAlarm"

        const val CHANNEL_ID = "auto_close"
        const val NOTIFICATION_ID = 1
        const val REQUEST_CODE = 2
    }
}
