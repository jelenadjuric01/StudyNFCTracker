package com.jelena.studytracker

/**
 * The two physical tags, identified by the text stored on them.
 *
 * The tags themselves are dumb: each carries one NDEF record whose MIME type is
 * [TAG_MIME_TYPE] and whose payload is one of these [payload] strings. All meaning lives
 * in the app.
 *
 * @property payload exactly what is written to, and read back from, the tag.
 */
enum class StudyTag(val payload: String) {

    /** Starts and ends a session. */
    STUDY("study"),

    /** Flips the category of a session that is already running. */
    SWITCH("switch"),
    ;

    companion object {

        /**
         * Maps a payload read off a tag back to the tag it identifies.
         *
         * Trims and lower-cases first, so a tag written by an older build or by a
         * third-party NFC writer with a trailing newline still works.
         *
         * @param payload the record's text, or `null` if the tag carried no usable record.
         * @return the matching tag, or `null` if this is not one of our tags — the caller
         *   must treat that as "unknown tag", never as a default.
         */
        fun fromPayload(payload: String?): StudyTag? {
            val cleaned = payload?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.payload == cleaned }
        }
    }
}

/**
 * The app-specific MIME type carried by both tags.
 *
 * Must match the `<data android:mimeType>` in the manifest exactly, or tapping a tag will
 * not launch the app. Being app-specific is the point: a generic type would hand the tap
 * to whatever else on the phone claims it.
 */
const val TAG_MIME_TYPE = "application/vnd.com.jelena.studytracker"
