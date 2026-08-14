package com.jelena.studytracker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Every segment ever recorded, one per line, in a file in the app's private storage.
 *
 * This is the app's whole history. It replaced three running totals that were wiped at midnight:
 * an append-only file costs almost nothing and means yesterday, last week and a session that ran
 * past midnight all survive.
 *
 * A plain text file rather than Room, because the access pattern is "append one line" and "read
 * everything" — there is nothing to query, and a database would be a dependency and a schema for
 * no benefit. At an hour of study a day the file grows by well under a kilobyte a month.
 *
 * Phase 2 replaces this as the *record* — the Google Sheet does that — but not as the queue.
 */
class SessionLog(context: Context) {

    /** `filesDir` is app-private storage: no permission needed, and wiped when the app is. */
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * Appends one segment.
     *
     * Failures are logged and swallowed: this is called at the end of a tap, where there is
     * nothing useful to do about a full disk, and throwing would crash the app mid-tap. The tap
     * itself has already taken effect by this point — the phone is silenced correctly either way,
     * and only the record of it is lost.
     */
    fun append(segment: StudySegment) {
        try {
            file.appendText(formatSegmentLine(segment) + "\n")
        } catch (e: IOException) {
            Log.e(TAG, "Appending to the session log failed: $segment", e)
        }
    }

    /**
     * Every readable segment, oldest first.
     *
     * Unreadable lines are skipped rather than fatal — see [parseSegmentLine]. A missing file is
     * simply an empty history, which is the honest answer on a fresh install.
     */
    fun readAll(): List<StudySegment> {
        if (!file.exists()) return emptyList()

        return try {
            file.readLines().mapNotNull(::parseSegmentLine)
        } catch (e: IOException) {
            Log.e(TAG, "Reading the session log failed", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "SessionLog"
        const val FILE_NAME = "sessions.log"
    }
}
