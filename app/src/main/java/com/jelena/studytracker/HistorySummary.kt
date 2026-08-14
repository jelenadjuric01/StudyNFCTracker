package com.jelena.studytracker

import android.content.Context

/**
 * Builds the block of text on the setup screen showing today, yesterday and the last seven days.
 *
 * Separate from [MainActivity] because it is string assembly rather than screen behaviour, and it is
 * the part most worth reading on its own: every figure is computed from the log here and now, so
 * there is no stored total that can drift or be wiped.
 */
class HistorySummary(context: Context) {

    /**
     * `applicationContext`, matching every other collaborator in the app — see [StudyStateStore] for
     * why holding an activity past its lifetime is a leak. Nothing here needs the activity: strings
     * come from resources, which any context can reach.
     */
    private val context = context.applicationContext

    /** The whole summary, or a nudge to get started if nothing has been recorded yet. */
    fun text(): String {
        val segments = SessionLog(context).readAll()
        if (segments.isEmpty()) return context.getString(R.string.history_nothing)

        val today = LocalDays.today()
        val yesterday = segmentsOn(segments, today - 1)
        val week = segmentsWithin(segments, today, days = 7)

        return buildString {
            append(block(R.string.today_heading, segmentsOn(segments, today)))

            // Only worth a block if there is something in it — an empty "Yesterday" is noise.
            if (yesterday.isNotEmpty()) {
                append("\n\n")
                append(block(R.string.yesterday_heading, yesterday))
            }

            append("\n\n")
            append(block(R.string.week_heading, week))

            // An auto-closed stretch is a cap, not a measurement, so anything containing one has to
            // say so rather than quietly presenting it as time studied.
            if (week.any { it.autoClosed }) {
                append("\n\n")
                append(context.getString(R.string.history_auto_closed_note))
            }
        }
    }

    /** One heading, a line per category that has time against it, and a total. */
    private fun block(headingRes: Int, segments: List<StudySegment>): String {
        val totals = totalsOf(segments)

        return buildString {
            append(context.getString(headingRes))

            if (segments.isEmpty()) {
                append("\n")
                append(context.getString(R.string.history_nothing_line))
                return@buildString
            }

            printableTotals(segments).forEach { (category, millis) ->
                append("\n")
                append(
                    context.getString(
                        R.string.history_line,
                        categoryLabel(context, category),
                        formatDuration(millis),
                    ),
                )
            }

            append("\n")
            append(context.getString(R.string.history_total, formatDuration(totals.totalMillis)))
        }
    }
}

/**
 * The name shown to the user for a category.
 *
 * A free function rather than a method on [Category], because [Category] is deliberately free of
 * Android imports so it can be unit-tested without a device. Both screens and the tap toasts need
 * this, which is why it does not live inside either of them.
 */
fun categoryLabel(context: Context, category: Category): String = context.getString(
    when (category) {
        Category.SCHOOL -> R.string.category_school
        Category.PERSONAL -> R.string.category_personal
    },
)
