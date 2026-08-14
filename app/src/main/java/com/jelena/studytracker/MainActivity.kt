package com.jelena.studytracker

import android.annotation.SuppressLint
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.jelena.studytracker.databinding.ActivityMainBinding
import java.io.IOException

/**
 * The setup screen. Three jobs, in the order a new phone needs them:
 *
 *  1. Get Do Not Disturb access granted — nothing works without it.
 *  2. Program the two tags.
 *  3. Show what study mode is currently doing.
 *
 * The app is not used from this screen day to day: once the tags are written, the whole
 * interaction is tapping them, which [TagIntentActivity] handles.
 *
 * The class plays two roles at once:
 *  - [AppCompatActivity] makes it a screen, whose lifecycle Android drives.
 *  - [NfcAdapter.ReaderCallback] makes it the object NFC calls when a tag appears, which is
 *    why [onResume] can pass `this` as both the activity and the callback.
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    /**
     * Typed access to the views in `activity_main.xml`, generated at build time from that
     * file's name (`activity_main` -> `ActivityMainBinding`).
     *
     * `lateinit` rather than nullable: it cannot be built in the constructor, because
     * inflating a layout needs a context that only exists once [onCreate] runs.
     */
    private lateinit var binding: ActivityMainBinding

    /** The phone's NFC radio, or `null` on a device with no NFC hardware at all. */
    private var nfcAdapter: NfcAdapter? = null

    private lateinit var dndController: DndController
    private lateinit var stateStore: StudyStateStore

    /**
     * Which tag the next write will program.
     *
     * A shadow copy of the radio buttons rather than a read of them, because tags arrive on
     * a background thread in [onTagDiscovered], and Android forbids touching views from any
     * thread but the main one. `@Volatile` guarantees that thread sees the latest value.
     */
    @Volatile
    private var selectedTag = StudyTag.STUDY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate turns the layout XML into real view objects; binding.root is the outermost
        // one, which we hand to Android as the content of this screen.
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        dndController = DndController(this)
        stateStore = StudyStateStore(this)

        binding.tagChoice.setOnCheckedChangeListener { _, checkedId ->
            selectedTag = if (checkedId == R.id.chooseSwitch) StudyTag.SWITCH else StudyTag.STUDY
        }

        binding.grantDndButton.setOnClickListener {
            // No in-app dialog exists for this permission — it is granted from a system list.
            startActivity(dndController.settingsIntent())
        }

        binding.saveCapButton.setOnClickListener { saveCap() }
    }

    /**
     * Stores the cap typed into the two fields and moves the alarm to match.
     *
     * Rescheduling immediately is the point of the feature: changing the cap during a session has
     * to affect *that* session, not only the next one. The deadline stays anchored to the session
     * start, so raising three hours to six two hours in leaves four hours, and lowering it below
     * the time already elapsed closes the session almost at once.
     */
    private fun saveCap() {
        // Empty fields read as zero rather than refusing: "3 h" with the minutes box left blank is
        // an obvious intent, and zero in both is the documented way to turn the cap off.
        val hours = binding.capHoursInput.text.toString().trim().toLongOrNull() ?: 0
        val minutes = binding.capMinutesInput.text.toString().trim().toLongOrNull() ?: 0
        val cap = millisOfHoursAndMinutes(hours, minutes)

        stateStore.saveAutoCloseCapMillis(cap)
        AutoCloseScheduler(this).sync(stateStore.load(), cap)

        showCap()
        showStudyState()
    }

    /**
     * Fills the cap fields and the line that says what the cap currently means.
     *
     * The plain digits are deliberate, which is why lint's locale warning is suppressed here: these
     * values are read straight back with `toLongOrNull`, and locale-formatted digits — Arabic-Indic,
     * say — would fail to parse and silently reset the cap to zero.
     */
    @SuppressLint("SetTextI18n")
    private fun showCap() {
        val cap = stateStore.loadAutoCloseCapMillis()
        val (hours, minutes) = hoursAndMinutesOf(cap)

        binding.capHoursInput.setText(hours.toString())
        binding.capMinutesInput.setText(minutes.toString())

        binding.capStatusText.text = if (cap <= 0L) {
            getString(R.string.cap_off)
        } else {
            getString(R.string.cap_on, formatDuration(cap))
        }
    }

    /**
     * Called whenever this screen becomes the foreground, interactive one — on launch, and
     * again after returning from the Settings screen the button above opens, which is when
     * the permission line needs refreshing.
     *
     * Reader mode is claimed here rather than in [onCreate] because it is an exclusive hold
     * on the NFC hardware, and Android only grants it to a foreground activity. Claiming it
     * and never releasing it would let this app intercept tags while in the background.
     */
    override fun onResume() {
        super.onResume()
        showPermissionState()
        showCap()
        showStudyState()

        val adapter = nfcAdapter
        binding.nfcStatusText.text = when {
            adapter == null -> getString(R.string.no_nfc)
            !adapter.isEnabled -> getString(R.string.nfc_off)
            else -> {
                // Reader mode gives this screen exclusive use of any tag while it is open.
                // Without it, tapping an already-programmed tag here would launch
                // TagIntentActivity and toggle study mode instead of rewriting the tag.
                //
                // Arguments: the activity requesting it, the callback to invoke on discovery
                // (both `this`), which tag families to listen for, and an options Bundle for
                // tuning things like presence-check delay — not needed here, so null.
                adapter.enableReaderMode(this, this, READER_FLAGS, null)
                getString(R.string.ready_to_write)
            }
        }
    }

    /**
     * Called when this screen stops being in the foreground. Releases the NFC hardware
     * claimed in [onResume] — the other half of acquire-in-resume, release-in-pause.
     */
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Called by the NFC service each time a tag touches the phone, for as long as reader
     * mode is active.
     *
     * Runs on a background thread, never the main one — deliberate, since writing to a tag
     * is blocking radio I/O that would otherwise freeze the UI. That is also why the result
     * has to be posted back with [runOnUiThread] at the end.
     *
     * @param tag a handle to the tag now in range. It is only valid while the tag stays in
     *   the field, so all work with it must happen inside this call.
     */
    override fun onTagDiscovered(tag: Tag) {
        val target = selectedTag

        // Both `try` and `if` are expressions in Kotlin, so every path produces one string
        // and the status line is written in exactly one place.
        val status = try {
            writePayload(tag, target.payload)
            getString(R.string.written, target.payload)
        } catch (e: Exception) {
            // Catching broadly is deliberate: this is the top of a callback, and an escaping
            // exception would crash the app mid-tap. Nothing is swallowed — the stack trace
            // goes to Logcat and the user gets a readable message.
            Log.e(TAG, "Writing the tag failed", e)
            getString(R.string.write_failed, e.message)
        }

        runOnUiThread { binding.writeStatusText.text = status }
    }

    /**
     * Writes [payload] to [tag] as a single NDEF record carrying [TAG_MIME_TYPE], formatting
     * the tag first if it has never been written to.
     *
     * The MIME type is the important part: it is what makes Android launch this app on a tap.
     * A plain text or URL record would be handed to some other app instead.
     *
     * Blocking radio I/O — only safe to call from [onTagDiscovered]'s thread.
     *
     * @throws IOException if the tag is locked, is not NDEF capable, or the write fails
     *   partway through — including when the tag is pulled out of range mid-write.
     */
    private fun writePayload(tag: Tag, payload: String) {
        val record = NdefRecord.createMime(TAG_MIME_TYPE, payload.toByteArray(Charsets.UTF_8))
        val message = NdefMessage(arrayOf(record))

        // Ndef.get returns non-null only for a tag that already carries an NDEF structure,
        // i.e. one that has been written to before.
        //
        // `use` is Kotlin's try-with-resources — tag technologies are Closeable, so the radio
        // connection closes even if something throws. The `return` is a non-local return: it
        // exits writePayload entirely rather than just the lambda, which is legal because
        // `use` is inline, and the connection still closes on the way out.
        Ndef.get(tag)?.use { ndef ->
            ndef.connect()
            if (!ndef.isWritable) throw IOException(getString(R.string.tag_locked))
            ndef.writeNdefMessage(message)
            return
        }

        // A factory-blank tag has no NDEF structure yet, so it needs formatting and writing
        // in one operation. Skipping this branch is a common bug — every brand-new tag would
        // fail for no visible reason.
        val formatable = NdefFormatable.get(tag) ?: throw IOException(getString(R.string.tag_unsupported))
        formatable.use {
            it.connect()
            it.format(message)
        }
    }

    /** Shows whether Do Not Disturb access is granted, and hides the button once it is. */
    private fun showPermissionState() {
        val granted = dndController.isGranted()
        binding.dndStatusText.text =
            getString(if (granted) R.string.dnd_granted else R.string.dnd_missing)
        binding.grantDndButton.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * Shows what the last tap left study mode doing, and how much today has added up to.
     *
     * Recomputed in [onResume] rather than ticking on a timer: a running session's elapsed
     * time is a minute-scale number nobody needs to watch move, and a repeating timer would be
     * one more thing to cancel correctly.
     */
    private fun showStudyState() {
        val state = stateStore.load()

        binding.studyStateText.text = if (state.active) {
            getString(R.string.state_on, categoryLabel(state.category))
        } else {
            getString(R.string.state_off)
        }

        // The stretch running right now is deliberately shown apart from the totals below:
        // it is not recorded anywhere yet, and will only join them when a tap closes it.
        binding.runningText.text = if (state.active && state.segmentStartedAtMillis > 0) {
            val now = System.currentTimeMillis()
            val running = getString(R.string.state_running, formatDuration(now - state.segmentStartedAtMillis))

            // While a session is running, when it will be given up on is the most useful thing the
            // cap setting has to say — more so than the setting itself.
            val deadline = StudyModeController.autoCloseDeadline(state, stateStore.loadAutoCloseCapMillis())
            when {
                deadline == null -> running
                deadline <= now -> "$running\n" + getString(R.string.state_closing_now)
                else -> "$running\n" + getString(R.string.state_closes_in, formatDuration(deadline - now))
            }
        } else {
            ""
        }

        binding.todayText.text = historyText()
    }

    /**
     * Today, yesterday and the last seven days, all computed from the session log.
     *
     * Every figure is derived rather than stored, so there is no running total to drift or be
     * wiped — and unlike the version this replaced, a day rolling over loses nothing.
     */
    private fun historyText(): String {
        val segments = SessionLog(this).readAll()
        if (segments.isEmpty()) return getString(R.string.history_nothing)

        val today = LocalDays.today()
        val week = segmentsWithin(segments, today, days = 7)

        return buildString {
            append(block(getString(R.string.today_heading), segmentsOn(segments, today)))

            // Only worth a block if there is something in it — an empty "Yesterday" is noise.
            val yesterday = segmentsOn(segments, today - 1)
            if (yesterday.isNotEmpty()) {
                append("\n\n")
                append(block(getString(R.string.yesterday_heading), yesterday))
            }

            append("\n\n")
            append(block(getString(R.string.week_heading), week))

            // An auto-closed stretch is a cap, not a measurement, so anything containing one has
            // to say so rather than quietly presenting it as time studied.
            if (week.any { it.autoClosed }) {
                append("\n\n")
                append(getString(R.string.history_auto_closed_note))
            }
        }
    }

    /** One heading, a per-category breakdown, and a total under it. */
    private fun block(heading: String, segments: List<StudySegment>): String {
        val totals = totalsOf(segments)

        return buildString {
            append(heading)

            if (totals.rawTotalMillis == 0L) {
                append("\n")
                append(getString(R.string.history_nothing_line))
                return@buildString
            }

            Category.entries.forEach { category ->
                val millis = totals.millisFor(category)
                if (millis > 0) {
                    append("\n")
                    append(getString(R.string.history_line, categoryLabel(category), formatDuration(millis)))
                }
            }
            append("\n")
            append(getString(R.string.history_total, formatDuration(totals.shownTotalMillis)))
        }
    }

    private fun categoryLabel(category: Category): String = getString(
        when (category) {
            Category.SCHOOL -> R.string.category_school
            Category.PERSONAL -> R.string.category_personal
        },
    )

    private companion object {
        /** Logcat tag. Filter on this to see only messages from this screen. */
        const val TAG = "MainActivity"

        /**
         * Which tag families reader mode should wake for — all four NFC Forum types.
         * OR-ing flags into a single int is the C-style convention Android inherited.
         */
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
    }
}
