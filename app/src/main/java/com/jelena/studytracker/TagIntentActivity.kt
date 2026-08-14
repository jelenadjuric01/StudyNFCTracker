package com.jelena.studytracker

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The tap handler. An activity with no visible window: Android launches it when one of the
 * tags is tapped, it applies the tap, says what happened in a toast, and finishes.
 *
 * It is an activity rather than a service or a broadcast receiver because `NDEF_DISCOVERED`
 * is only delivered to activities — that intent filter is also the reason a tap can launch
 * the app when it is closed.
 *
 * Everything here is glue: the rules live in [StudyModeController], the storage in
 * [StudyStateStore], the muting in [DndController].
 */
class TagIntentActivity : AppCompatActivity() {

    /**
     * The whole lifetime of this screen. Handles the launching intent and finishes before
     * anything is drawn, so the phone shows no window at all.
     *
     * @param savedInstanceState always `null` in practice — the activity never survives long
     *   enough to be recreated. Passed up to `super` regardless.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTap(intent)
        finish()
    }

    /**
     * Called instead of [onCreate] when a tag is tapped while an instance is somehow still
     * around (`launchMode="singleTop"`). Rare, but without this the second tap would be
     * silently dropped.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTap(intent)
        finish()
    }

    /**
     * Reads the tag out of [intent], applies it, and reports the outcome.
     *
     * The order matters: Do Not Disturb is applied before the state is saved, so a failure
     * to mute is reported as a failure rather than being recorded as success.
     */
    private fun handleTap(intent: Intent) {
        val tag = StudyTag.fromPayload(firstNdefPayload(intent))
        if (tag == null) {
            // Either a tag that is not ours, or an empty record. Change nothing: a tag we
            // cannot identify must not be allowed to mean anything.
            toast(getString(R.string.tap_unknown_tag))
            return
        }

        val store = StudyStateStore(this)
        val dnd = DndController(this)

        val previous = store.load()
        val result = StudyModeController.onTap(previous, tag, System.currentTimeMillis())

        when (result) {
            is TapResult.Ignored -> toast(ignoredMessage(result.reason))

            is TapResult.Changed -> {
                // Silencing is only attempted when this tap actually starts or ends a session.
                // A SWITCH tap leaves the phone exactly as silenced as it already was, so it
                // must not be blocked by Do Not Disturb being unavailable.
                //
                // When silencing *is* needed and fails, nothing is saved: otherwise the app
                // would believe a session is running while notifications still arrive, and the
                // user would have no way to reconcile the two but to tap twice more. Leaving the
                // state alone means granting the permission and tapping again just works.
                val silencingNeeded = StudyModeController.silencingChanged(previous, result.state)
                if (silencingNeeded && !dnd.apply(result.state.active)) {
                    toast(getString(R.string.tap_no_dnd))
                    return
                }

                store.save(result.state)
                // Only now that the tap has definitely taken effect do the minutes count.
                result.completed?.let { SessionLog(this).append(it) }

                // Arm or cancel the safety net for whatever the state is now. Doing this on every
                // accepted tap means a session started, switched or ended always leaves the alarm
                // consistent with reality, with no separate bookkeeping to get wrong.
                AutoCloseScheduler(this).sync(result.state, store.loadAutoCloseCapMillis())

                toast(changedMessage(result.state, result.completed))
            }
        }
    }

    /** What to say about a tap that was deliberately dropped. */
    private fun ignoredMessage(reason: IgnoredReason): String = when (reason) {
        // Worth a message rather than silence: otherwise a double read looks like the tap
        // simply did not register, and the user taps again — which really would toggle.
        IgnoredReason.DUPLICATE_TAP -> getString(R.string.tap_duplicate)
        IgnoredReason.MODE_OFF -> getString(R.string.tap_switch_while_off)
    }

    /**
     * What to say about a tap that moved the state.
     *
     * Whatever just ended is worth saying out loud — it is the only feedback on how long the
     * stretch lasted, and the moment the phone is being picked up to read it.
     */
    private fun changedMessage(state: StudyState, completed: StudySegment?): String {
        val finished = completed?.let {
            getString(R.string.tap_finished, formatDuration(it.durationMillis), categoryLabel(it.category))
        }
        val now = if (state.active) {
            getString(R.string.tap_on, categoryLabel(state.category))
        } else {
            getString(R.string.tap_off)
        }
        return if (finished == null) now else "$now\n$finished"
    }

    private fun categoryLabel(category: Category): String = getString(
        when (category) {
            Category.SCHOOL -> R.string.category_school
            Category.PERSONAL -> R.string.category_personal
        },
    )

    /**
     * The text of the first NDEF record on the tapped tag, or `null` if the intent carries
     * no readable record.
     *
     * Both tags hold exactly one record, so anything beyond the first is ignored.
     */
    private fun firstNdefPayload(intent: Intent): String? {
        val messages = ndefMessages(intent) ?: return null
        val record = messages.firstOrNull()?.records?.firstOrNull() ?: return null
        return String(record.payload, Charsets.UTF_8)
    }

    /**
     * Pulls the NDEF messages out of the intent extras.
     *
     * Two code paths because the type-safe overload only exists from API 33. The older one
     * is deprecated but is the only option on the minimum supported version, so the
     * suppression is narrowed to exactly that call.
     */
    private fun ndefMessages(intent: Intent): Array<NdefMessage>? {
        val extras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                ?.filterIsInstance<NdefMessage>()
                ?.toTypedArray()
        }
        return extras?.takeIf { it.isNotEmpty() }
    }

    /**
     * The app's only output during a tap. Long rather than short: the phone is usually being
     * put down at this moment, and a message about being muted is worth catching.
     */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
