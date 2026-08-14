package com.jelena.studytracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.jelena.studytracker.databinding.ActivityMainBinding

/**
 * The setup screen. Four jobs, in the order a new phone needs them:
 *
 *  1. Get Do Not Disturb access granted — nothing works without it.
 *  2. Program the two tags.
 *  3. Set how long a session may run if the closing tap is forgotten.
 *  4. Show what study mode is doing and the hours recorded so far.
 *
 * The app is not used from this screen day to day: once the tags are written, the whole interaction
 * is tapping them, which [TagIntentActivity] handles. This class is wiring — the work is in
 * [NfcTagWriter], [HistorySummary], [DndController] and [AutoCloseScheduler].
 *
 * The class plays two roles at once:
 *  - [AppCompatActivity] makes it a screen, whose lifecycle Android drives.
 *  - [NfcAdapter.ReaderCallback] makes it the object NFC calls when a tag appears, which is why
 *    [onResume] can pass `this` as both the activity and the callback.
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    /**
     * Typed access to the views in `activity_main.xml`, generated at build time from that file's
     * name (`activity_main` -> `ActivityMainBinding`).
     *
     * `lateinit` rather than nullable: it cannot be built in the constructor, because inflating a
     * layout needs a context that only exists once [onCreate] runs.
     */
    private lateinit var binding: ActivityMainBinding

    /** The phone's NFC radio, or `null` on a device with no NFC hardware at all. */
    private var nfcAdapter: NfcAdapter? = null

    private lateinit var dndController: DndController
    private lateinit var stateStore: StudyStateStore
    private lateinit var autoClose: AutoCloseScheduler
    private lateinit var tagWriter: NfcTagWriter

    /**
     * Which tag the next write will program.
     *
     * A shadow copy of the radio buttons rather than a read of them, because tags arrive on a
     * background thread in [onTagDiscovered], and Android forbids touching views from any thread but
     * the main one. `@Volatile` guarantees that thread sees the latest value.
     */
    @Volatile
    private var selectedTag = StudyTag.STUDY

    /**
     * Drives the once-a-second refresh of the running line. Main-thread only, which is what makes it
     * safe to touch views from [tick].
     */
    private val ticker = Handler(Looper.getMainLooper())

    /** Whether a session was running the last time the screen was drawn. See [tick]. */
    private var wasActive = false

    /**
     * The Android 13+ notification permission dialog, and what to do with the answer.
     *
     * Registered as a field because [registerForActivityResult] must be called before the activity is
     * started — doing it inside the button's listener would crash.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Refused. Android stops showing the dialog after a couple of refusals, and
            // shouldShowRequestPermissionRationale going false is how it says so — at which point the
            // button would silently do nothing, so send the user to Settings instead.
            if (!granted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                openNotificationSettings()
            }

            showEverything()
        }

    /**
     * Refreshes the running line once a second for as long as this screen is in the foreground.
     *
     * Only that line: the totals cannot change while this screen is open, because it holds reader
     * mode and a tap therefore never reaches [TagIntentActivity]. Re-reading the log every second
     * would be work for nothing.
     *
     * The exception is the auto-close alarm, which *can* fire while the screen sits open and ends the
     * session from outside this activity. So the tick watches [StudyState.active] and redraws
     * everything when it changes; otherwise the totals would omit the stretch just recorded.
     */
    private val tick = object : Runnable {
        override fun run() {
            val state = stateStore.load()

            if (state.active != wasActive) showEverything() else showRunning(state)

            // Rescheduled from here rather than at a fixed period, so a slow frame delays the next
            // tick instead of queueing up behind it.
            ticker.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate turns the layout XML into real view objects; binding.root is the outermost one,
        // which we hand to Android as the content of this screen.
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        dndController = DndController(this)
        stateStore = StudyStateStore(this)
        autoClose = AutoCloseScheduler(this)
        tagWriter = NfcTagWriter(this)

        binding.tagChoice.setOnCheckedChangeListener { _, checkedId ->
            selectedTag = if (checkedId == R.id.chooseSwitch) StudyTag.SWITCH else StudyTag.STUDY
        }

        binding.grantDndButton.setOnClickListener {
            // No in-app dialog exists for this permission — it is granted from a system list.
            startActivity(dndController.settingsIntent())
        }

        binding.saveCapButton.setOnClickListener { saveCap() }
        binding.grantAlarmButton.setOnClickListener { askForAlarmPermission() }
    }

    /**
     * Asks for permission to sound the auto-close alarm.
     *
     * From Android 13 there is a permission to request. Before that there is not — notifications were
     * switched off in system settings, which is also the only place to switch them back on.
     */
    private fun askForAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    /**
     * Opens the system screen where notifications for this app can be turned back on.
     *
     * Two intents, because a per-app notification screen only exists from Android 8. Below that the
     * app's details page is the nearest equivalent, and it has always been there.
     */
    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        }

        // Some vendor ROMs ship without one of these screens. Failing to open Settings must not take
        // the app down with it, and saying so beats a button that appears to do nothing.
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No notification settings screen on this phone", e)
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Called whenever this screen becomes the foreground, interactive one — on launch, and again
     * after returning from the Settings screen the button above opens, which is when the permission
     * line needs refreshing.
     *
     * Reader mode is claimed here rather than in [onCreate] because it is an exclusive hold on the
     * NFC hardware, and Android only grants it to a foreground activity. Claiming it and never
     * releasing it would let this app intercept tags while in the background.
     */
    override fun onResume() {
        super.onResume()
        showEverything()

        // Delayed by one interval: showEverything just drew the current values, so ticking
        // immediately would redraw the same text.
        ticker.postDelayed(tick, TICK_MILLIS)

        val adapter = nfcAdapter
        binding.nfcStatusText.text = when {
            adapter == null -> getString(R.string.no_nfc)
            !adapter.isEnabled -> getString(R.string.nfc_off)
            else -> {
                // Reader mode gives this screen exclusive use of any tag while it is open. Without
                // it, tapping an already-programmed tag here would launch TagIntentActivity and
                // toggle study mode instead of rewriting the tag.
                //
                // Arguments: the activity requesting it, the callback to invoke on discovery (both
                // `this`), which tag families to listen for, and an options Bundle for tuning things
                // like presence-check delay — not needed here, so null.
                adapter.enableReaderMode(this, this, READER_FLAGS, null)
                getString(R.string.ready_to_write)
            }
        }
    }

    /**
     * Called when this screen stops being in the foreground. Releases both things claimed in
     * [onResume]: the NFC hardware, and the repeating refresh that would otherwise keep waking the
     * app to redraw a screen nobody is looking at.
     */
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        ticker.removeCallbacks(tick)
    }

    /**
     * Called by the NFC service each time a tag touches the phone, for as long as reader mode is
     * active.
     *
     * Runs on a background thread, never the main one — deliberate, since writing to a tag is
     * blocking radio I/O that would otherwise freeze the UI. That is also why the result has to be
     * posted back with [runOnUiThread] at the end.
     *
     * @param tag a handle to the tag now in range. It is only valid while the tag stays in the
     *   field, so all work with it must happen inside this call.
     */
    override fun onTagDiscovered(tag: Tag) {
        val target = selectedTag

        // Both `try` and `if` are expressions in Kotlin, so every path produces one string and the
        // status line is written in exactly one place.
        val status = try {
            tagWriter.write(tag, target.payload)
            getString(R.string.written, target.payload)
        } catch (e: Exception) {
            // Catching broadly is deliberate: this is the top of a callback, and an escaping
            // exception would crash the app mid-tap. Nothing is swallowed — the stack trace goes to
            // Logcat and the user gets a readable message.
            Log.e(TAG, "Writing the tag failed", e)
            getString(R.string.write_failed, e.message)
        }

        runOnUiThread { binding.writeStatusText.text = status }
    }

    /**
     * Stores the cap typed into the two fields and moves the alarm to match.
     *
     * Rescheduling immediately is the point of the feature: changing the cap during a session has to
     * affect *that* session, not only the next one.
     */
    private fun saveCap() {
        // Empty fields read as zero rather than refusing: "3 h" with the minutes box left blank is an
        // obvious intent, and zero in both is the documented way to turn the cap off.
        val hours = binding.capHoursInput.text.toString().trim().toLongOrNull() ?: 0
        val minutes = binding.capMinutesInput.text.toString().trim().toLongOrNull() ?: 0
        val cap = millisOfHoursAndMinutes(hours, minutes)

        stateStore.saveAutoCloseCapMillis(cap)
        autoClose.sync(stateStore.load(), cap)

        showEverything()
    }

    /** Draws the whole screen. Cheap enough to do wholesale, except once a second — see [tick]. */
    private fun showEverything() {
        val state = stateStore.load()
        wasActive = state.active

        showPermission()
        showCap()
        showAlarmPermission()

        binding.studyStateText.text = if (state.active) {
            getString(R.string.state_on, categoryLabel(this, state.category))
        } else {
            getString(R.string.state_off)
        }

        showRunning(state)
        binding.todayText.text = HistorySummary(this).text()
    }

    /** Says whether Do Not Disturb access is granted, and hides the button once it is. */
    private fun showPermission() {
        val granted = dndController.isGranted()

        binding.dndStatusText.text =
            getString(if (granted) R.string.dnd_granted else R.string.dnd_missing)
        binding.grantDndButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    /**
     * Says whether the auto-close can actually sound an alarm, and offers to fix it if not.
     *
     * Both the line and the button disappear once notifications are allowed, and also when the cap is
     * off — with no auto-close there is no alarm to ask about.
     */
    private fun showAlarmPermission() {
        val capEnabled = stateStore.loadAutoCloseCapMillis() > 0
        val allowed = NotificationManagerCompat.from(this).areNotificationsEnabled()

        binding.alarmStatusText.visibility = if (capEnabled) View.VISIBLE else View.GONE
        binding.grantAlarmButton.visibility = if (capEnabled && !allowed) View.VISIBLE else View.GONE
        binding.alarmStatusText.text =
            getString(if (allowed) R.string.alarm_granted else R.string.alarm_missing)
    }

    /**
     * Fills the cap fields and the line saying what the cap currently means.
     *
     * The plain digits are deliberate, which is why lint's locale warning is suppressed: these values
     * are read straight back with `toLongOrNull`, and locale-formatted digits — Arabic-Indic, say —
     * would fail to parse and silently reset the cap to zero.
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
     * The live part of the screen: how long the current stretch has been running, and how long until
     * the session gives up on itself.
     *
     * Blank when nothing is running — there is no sensible number to show, and a stale one would be
     * worse than none. The running stretch is deliberately kept apart from the totals below, because
     * it has not been recorded yet.
     */
    private fun showRunning(state: StudyState) {
        if (!state.active || state.segmentStartedAtMillis <= 0) {
            binding.runningText.text = ""
            return
        }

        val now = System.currentTimeMillis()
        val running = getString(R.string.state_running, formatDuration(now - state.segmentStartedAtMillis))
        val deadline = StudyModeController.autoCloseDeadline(state, stateStore.loadAutoCloseCapMillis())

        binding.runningText.text = when {
            deadline == null -> running
            deadline <= now -> "$running\n" + getString(R.string.state_closing_now)
            else -> "$running\n" + getString(R.string.state_closes_in, formatDuration(deadline - now))
        }
    }

    private companion object {
        /** Logcat tag. Filter on this to see only messages from this screen. */
        const val TAG = "MainActivity"

        /** How often the running line is redrawn. One second, because it shows seconds. */
        const val TICK_MILLIS = 1_000L

        /**
         * Which tag families reader mode should wake for — all four NFC Forum types. OR-ing flags
         * into a single int is the C-style convention Android inherited.
         */
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
    }
}
