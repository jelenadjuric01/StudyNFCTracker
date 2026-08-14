package com.jelena.studytracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Where [StudyState] and the auto-close cap survive between taps.
 *
 * Each tap runs in a fresh, short-lived [TagIntentActivity] process, so nothing can be
 * held in memory: the state has to be read at the start of a tap and written at the end.
 *
 * [SharedPreferences] rather than DataStore because everything here happens on the main
 * thread of an activity that finishes immediately — there is no coroutine scope to suspend
 * in, and the payload is a handful of numbers. Phase 2 introduces DataStore alongside the
 * endpoint and token, which do need async access.
 *
 * Recorded time is *not* here: that lives in [SessionLog], because it grows.
 */
class StudyStateStore(context: Context) {

    /**
     * `applicationContext`, not the activity: an activity reference held past its lifetime
     * leaks the whole view hierarchy, and this object outlives the tap that created it only
     * by accident of garbage collection timing.
     */
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Reads the stored state, falling back to the cold-start default for anything missing
     * or unrecognised.
     *
     * A category string that no longer maps to an enum constant (an old build, a manual
     * edit) degrades to [Category.SCHOOL] rather than throwing — a corrupt preference must
     * not make the tap handler crash every time a tag is tapped.
     */
    fun load(): StudyState {
        val default = StudyState()
        return StudyState(
            active = prefs.getBoolean(KEY_ACTIVE, default.active),
            category = prefs.getString(KEY_CATEGORY, null).toCategory(default.category),
            lastTag = prefs.getString(KEY_LAST_TAG, null).let(StudyTag::fromPayload),
            lastTapAtMillis = prefs.getLong(KEY_LAST_TAP_AT, default.lastTapAtMillis),
            segmentStartedAtMillis = prefs.getLong(KEY_SEGMENT_STARTED_AT, default.segmentStartedAtMillis),
            sessionStartedAtMillis = prefs.getLong(KEY_SESSION_STARTED_AT, default.sessionStartedAtMillis),
        )
    }

    /**
     * Overwrites the stored state.
     *
     * Committed synchronously: the process may be killed the instant the tap activity
     * finishes, and losing the write would leave the phone muted with the app believing
     * the mode is off.
     */
    fun save(state: StudyState) {
        val written = prefs.edit()
            .putBoolean(KEY_ACTIVE, state.active)
            .putString(KEY_CATEGORY, state.category.name)
            .putString(KEY_LAST_TAG, state.lastTag?.payload)
            .putLong(KEY_LAST_TAP_AT, state.lastTapAtMillis)
            .putLong(KEY_SEGMENT_STARTED_AT, state.segmentStartedAtMillis)
            .putLong(KEY_SESSION_STARTED_AT, state.sessionStartedAtMillis)
            .commit()

        // A failed commit — a full disk, an I/O error — is exactly the case this class uses
        // commit() rather than apply() to avoid, so it must not pass unnoticed. There is nothing
        // useful to do about it at a tap, but it belongs in Logcat rather than nowhere.
        if (!written) Log.e(TAG, "Saving the study state failed: $state")
    }

    /**
     * How long a session may run before it is closed automatically, in milliseconds.
     *
     * Defaults to [DEFAULT_AUTO_CLOSE_MILLIS] on a fresh install — a cap has to be on by default,
     * since its whole purpose is to catch the tap you forgot to make. Zero means the user turned
     * it off deliberately.
     */
    fun loadAutoCloseCapMillis(): Long =
        prefs.getLong(KEY_AUTO_CLOSE_CAP, DEFAULT_AUTO_CLOSE_MILLIS)

    fun saveAutoCloseCapMillis(capMillis: Long) {
        val written = prefs.edit()
            .putLong(KEY_AUTO_CLOSE_CAP, capMillis.coerceAtLeast(0L))
            .commit()

        if (!written) Log.e(TAG, "Saving the auto-close cap failed: $capMillis")
    }

    private fun String?.toCategory(fallback: Category): Category =
        Category.entries.firstOrNull { it.name == this } ?: fallback

    private companion object {
        /** Logcat tag. Filter on this to see storage failures. */
        const val TAG = "StudyStateStore"

        const val FILE_NAME = "study_state"

        /** Three hours: long enough for a real session, short enough to save a forgotten night. */
        const val DEFAULT_AUTO_CLOSE_MILLIS = 3 * 60 * 60 * 1000L

        const val KEY_ACTIVE = "active"
        const val KEY_CATEGORY = "category"
        const val KEY_LAST_TAG = "last_tag"
        const val KEY_LAST_TAP_AT = "last_tap_at"
        const val KEY_SEGMENT_STARTED_AT = "segment_started_at"
        const val KEY_SESSION_STARTED_AT = "session_started_at"
        const val KEY_AUTO_CLOSE_CAP = "auto_close_cap"
    }
}
