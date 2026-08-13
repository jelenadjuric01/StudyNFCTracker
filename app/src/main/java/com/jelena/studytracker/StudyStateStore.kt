package com.jelena.studytracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Where [StudyState] and today's [DailyTotals] survive between taps.
 *
 * Each tap runs in a fresh, short-lived [TagIntentActivity] process, so nothing can be
 * held in memory: the state has to be read at the start of a tap and written at the end.
 *
 * [SharedPreferences] rather than DataStore because everything here happens on the main
 * thread of an activity that finishes immediately — there is no coroutine scope to suspend
 * in, and the payload is a handful of numbers. Phase 2 introduces DataStore alongside the
 * endpoint and token, which do need async access.
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
            .commit()

        // A failed commit — a full disk, an I/O error — is exactly the case this class uses
        // commit() rather than apply() to avoid, so it must not pass unnoticed. There is nothing
        // useful to do about it at a tap, but it belongs in Logcat rather than nowhere.
        if (!written) Log.e(TAG, "Saving the study state failed: $state")
    }

    /** Reads today's totals, or an empty set of totals if nothing has been recorded yet. */
    fun loadTotals(): DailyTotals = DailyTotals(
        day = prefs.getLong(KEY_TOTALS_DAY, 0L),
        schoolMillis = prefs.getLong(KEY_TOTALS_SCHOOL, 0L),
        personalMillis = prefs.getLong(KEY_TOTALS_PERSONAL, 0L),
    )

    /** Overwrites today's totals. Synchronous for the same reason as [save]. */
    fun saveTotals(totals: DailyTotals) {
        val written = prefs.edit()
            .putLong(KEY_TOTALS_DAY, totals.day)
            .putLong(KEY_TOTALS_SCHOOL, totals.schoolMillis)
            .putLong(KEY_TOTALS_PERSONAL, totals.personalMillis)
            .commit()

        if (!written) Log.e(TAG, "Saving today's totals failed: $totals")
    }

    /**
     * Adds a finished segment to the day's totals.
     *
     * The segment counts towards the day it *ended* on, so a session running past midnight
     * lands entirely on the new day. Splitting it across both would be more accurate and a lot
     * more code; the Google Sheet in phase 2 gets that right from the raw timestamps.
     */
    fun record(segment: StudySegment) {
        val day = LocalDays.of(segment.endedAtMillis)
        saveTotals(loadTotals().plus(segment, day))
    }

    private fun String?.toCategory(fallback: Category): Category =
        Category.entries.firstOrNull { it.name == this } ?: fallback

    private companion object {
        /** Logcat tag. Filter on this to see storage failures. */
        const val TAG = "StudyStateStore"

        const val FILE_NAME = "study_state"

        const val KEY_ACTIVE = "active"
        const val KEY_CATEGORY = "category"
        const val KEY_LAST_TAG = "last_tag"
        const val KEY_LAST_TAP_AT = "last_tap_at"
        const val KEY_SEGMENT_STARTED_AT = "segment_started_at"

        const val KEY_TOTALS_DAY = "totals_day"
        const val KEY_TOTALS_SCHOOL = "totals_school"
        const val KEY_TOTALS_PERSONAL = "totals_personal"
    }
}
