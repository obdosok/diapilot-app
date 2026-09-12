package io.github.obdosok.diapilot.collect

import android.content.Context
import android.util.Log
import com.diapilot.core.collector.CollectorStore
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.CalibratedGlucose
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.Stores
import io.github.obdosok.diapilot.data.trustedHistory
import io.github.obdosok.diapilot.diag.DiagLog
import io.github.obdosok.diapilot.diag.Redact
import io.github.obdosok.diapilot.widget.BgWidget
import java.util.concurrent.Executors

/**
 * THE ONE PLACE THE ALERTS ARE EVALUATED FROM.
 *
 * Before this existed, `HypoAlertNotifier.maybeNotify`,
 * `RapidFallNotifier.maybeNotify` and `CompanionSync.pushIfDue` had exactly
 * one caller each, and it was the tail of the OOPAlgorithm2 minute receiver in
 * [CollectorService]. On a phone without OOPAlgorithm2 — every stranger's
 * phone, and the whole point of the store edition — the app kept collecting,
 * drawing and answering the watch while NO alarm of any kind could fire, and
 * nothing on screen said so. See `docs/audit.md`, P7.
 *
 * So every path on which a glucose value reaches the app now calls [fire]
 * after the reading is stored, and the 15-minute [TreatmentsPollWorker] calls
 * it whether or not anything arrived — which is the backstop for a phone whose
 * only source is the web poll, and the only thing that notices a stream that
 * has gone quiet.
 *
 * WHAT THIS FILE DECIDES: nothing about an alert. Every threshold, cooldown,
 * snooze and refire rule stays inside the notifiers and the pure `:core` state
 * machines they call. What lives here is who calls them, in which order, off
 * which thread, and how often — plus the one instrument choice the caller has
 * always owned, which series the rapid-fall slope is measured over.
 */
object AlertTick {

    private const val TAG = "AlertTick"

    /**
     * Which path delivered the value. Carried for the log line, and for the
     * one source that must never be de-duplicated.
     */
    enum class Source(val label: String, val force: Boolean = false) {
        /** The OOPAlgorithm2 decoded per-minute stream (sensor-direct). */
        MINUTE_STREAM("minute stream"),

        /** xDrip's `BgEstimate` broadcast — the main five-minute feed. */
        XDRIP_BROADCAST("xDrip broadcast"),

        /**
         * The xDrip web service poll (`/sgv.json`, `/pebble`) and, because it
         * is periodic and runs whether or not it collected anything, the
         * backstop.
         */
        WEB_POLL("xDrip web poll"),

        /** An NFC read of the sensor's own memory (sensor-direct). */
        SENSOR_SCAN("sensor scan"),

        /**
         * A value the user typed — a fingerstick. Never de-duplicated: a
         * fingerstick does not advance the sensor clock the de-duplication key
         * is built from, and it is exactly the value that can CLEAR a live
         * alert (an in-range meter reading newer than the anchor silences the
         * low side).
         */
        MANUAL("manual entry", force = true),
    }

    /**
     * How long a landed reading stays "already judged".
     *
     * Two sources delivering the SAME reading is the normal case, not an edge
     * one: every accepted broadcast triggers a catch-up poll, and that poll's
     * `/sgv.json` answer contains the reading the broadcast just stored. One
     * of the two evaluations is enough.
     *
     * It is a window and not a plain "same timestamp" test because the
     * backstop must get through it. When nothing is arriving the freshest
     * reading never changes, and that is precisely when the stall notification
     * and a confirmed low's refire have to keep being considered — so an
     * unchanged reading is re-judged once this has passed. Five minutes is the
     * main grid's own cadence and the default low-refire interval.
     */
    const val REEVALUATE_AFTER_MS = 5L * 60_000

    /**
     * Serial worker. The body below touches SQLite, may build a person model
     * and posts notifications, so it must not run on a caller's thread: the
     * xDrip receiver is a manifest receiver on the main thread, and the
     * WorkManager poll is on a shared IO dispatcher. Serial rather than
     * pooled, so the de-duplication memory needs no lock and two sources
     * landing at once cannot evaluate concurrently.
     */
    private val EXECUTOR = Executors.newSingleThreadExecutor()

    /**
     * How a tick reaches the worker thread. Production posts to [EXECUTOR]; a
     * test replaces it with an inline runner so the assertion follows the
     * call, the way `Secrets.cipherFactory` stands in for the keystore.
     */
    @Volatile
    var dispatcher: (Runnable) -> Unit = { EXECUTOR.execute(it) }

    /** The freshest reading the last evaluation judged, and when it ran. */
    @Volatile private var judgedReadingMs = 0L
    @Volatile private var judgedAtMs = 0L

    /** Drop the de-duplication memory. For tests; nothing in the app calls it. */
    fun reset() {
        judgedReadingMs = 0
        judgedAtMs = 0
    }

    /**
     * Evaluate the alert set. Returns immediately; the work runs on
     * [EXECUTOR]. Call it AFTER the reading is stored — the notifiers read the
     * store, not the value that was just parsed.
     */
    fun fire(context: Context, source: Source) {
        val app = context.applicationContext
        dispatcher(Runnable { evaluate(app, source) })
    }

    private fun evaluate(context: Context, source: Source) {
        try {
            val store = Stores.get(context)
            val now = System.currentTimeMillis()
            // The same pair the stall notification and the Today banner ask
            // about, so the three cannot disagree about what "the freshest
            // reading" is.
            val freshest = maxOf(
                store.lastSensorReading()?.tsMs ?: 0L,
                store.lastMinuteReading()?.tsMs ?: 0L,
            )
            if (!source.force && freshest == judgedReadingMs &&
                now - judgedAtMs < REEVALUATE_AFTER_MS
            ) {
                // ON `Log`, NOT `DiagLog`: the minute stream ticks ~1440 times
                // a day, nearly twice the diagnostics ring's whole cap, so
                // this line would push every other one out of a shared bug
                // report. What the ring carries is the alerts' own decisions.
                Log.d(TAG, "tick from ${source.label}: already judged")
                return
            }
            judgedReadingMs = freshest
            judgedAtMs = now
            DiagState.lastAlertTickMs = now
            Log.d(TAG, "tick from ${source.label}")

            // ORDER AS IT WAS ON THE MINUTE BEAT. Rapid fall first (it is the
            // earliest warning there is), then the hypo/hyper set, then the
            // equipment alert, then the surfaces those imply.
            //
            // A BACKFILL OF OLD DATA FIRES NOTHING, and it is the notifiers'
            // own staleness rules that see to it rather than a check here: a
            // 14-day `/sgv.json` backfill leaves the freshest reading days
            // old, `detectRapidFall` refuses a series whose last point is
            // stale, and `HypoAlertNotifier` returns on an anchor older than
            // ten minutes BEFORE it writes any state — so no episode opens, no
            // cooldown starts and no dextrose snooze is consumed. The stall
            // notification does fire there, which is the truth about such a
            // phone: readings from last week arrived, glucose is not.
            rapidFall(context, store, now)
            HypoAlertNotifier.maybeNotify(context, store)
            stall(context)
            BgWidget.updateAll(context)
            CompanionSync.pushIfDue(context)
        } catch (e: Exception) {
            DiagLog.w(TAG, "tick failed: ${e.message}")
        }
    }

    /**
     * The rapid-fall instrument, per cadence.
     *
     * While the per-minute stream is live it is the input, with the same
     * series and the same arguments the minute beat always used — a phone with
     * OOPAlgorithm2 sees no change at all. Without it the app has only the
     * five-minute grid, on which the default ten-minute window can never hold
     * the five points the detector needs, so on such a phone a rapid fall was
     * undetectable rather than merely quiet.
     *
     * THE DECISION IS UNTOUCHED in both cases: the slope threshold, the
     * twenty-minute projection and the warn/urgent levels are the defaults.
     * What follows the cadence is the window the slope is fitted over and how
     * old the last point may be — thirty minutes gives the coarse grid the
     * same five points, and one missed five-minute round is not a stale
     * series (the same allowance [DATA_SOURCE_BROADCAST_LATE_MIN] makes).
     */
    private fun rapidFall(context: Context, store: CollectorStore, now: Long) {
        // BOTH INPUTS ON THE USER'S BLOOD SCALE — the level from the
        // meter-calibrated reading when fresh, the slope from a calibrated
        // series. Against fingersticks the sensor reads low, so an
        // uncalibrated pair both triggered early and printed a number the
        // app's own screen disagreed with.
        val calibrated = CalibratedGlucose
            .lastReading(store, context)
            ?.takeIf { now - it.tsMs < 10 * 60_000 }?.mmol
        val minute = CalibratedGlucose
            .minutePoints(store, context, now - 15L * 60_000, now)
        val minuteLive = minute.isNotEmpty() &&
            now - minute.maxOf { it.tsMs } <= DATA_SOURCE_MINUTE_LATE_MIN * 60_000
        val fall = if (minuteLive) {
            com.diapilot.core.analysis.detectRapidFall(
                minuteReadings = minute,
                calibratedMmol = calibrated,
                nowMs = now,
            )
        } else {
            com.diapilot.core.analysis.detectRapidFall(
                minuteReadings = trustedHistory(
                    store, context, now - GRID_FALL_WINDOW_MIN * 60_000, now,
                ),
                calibratedMmol = calibrated,
                nowMs = now,
                windowMin = GRID_FALL_WINDOW_MIN.toDouble(),
                staleMin = DATA_SOURCE_BROADCAST_LATE_MIN.toDouble(),
            )
        } ?: return
        // The decision and its shape. The slope and the projection are both
        // glucose on the user's scale.
        DiagLog.i(
            TAG,
            "rapid fall detected: slope ${Redact.perUnit("mmol/min")}, " +
                "projected ${Redact.glucose(mgdl = false)}",
        )
        RapidFallNotifier.maybeNotify(context, fall)
    }

    /**
     * The equipment alert, with the sentence that is true on THIS phone.
     *
     * The notifier's threshold, cooldown and its refusal to fire while data is
     * fresh are its own and unchanged. What the tick chooses is the advice:
     * "bring the phone to the sensor" is an instruction only where this app
     * owns the sensor link, and on a phone fed by another app the stream that
     * stopped is not one an NFC scan can restart.
     *
     * `Settings.ownBleEnabled` is false for the whole store edition, so this
     * asks about a switch rather than about [io.github.obdosok.diapilot.Edition]
     * — the backstop itself must work the same in both.
     */
    private fun stall(context: Context) {
        val ownSensor = Settings.ownBleEnabled(context)
        StreamStallNotifier.maybeNotify(
            context,
            reason = if (ownSensor) {
                R.string.stream_stall_notifier_reason_stopped
            } else {
                R.string.stream_stall_notifier_reason_no_glucose
            },
            body = if (ownSensor) {
                R.string.stream_stall_notifier_body
            } else {
                R.string.stream_stall_notifier_body_sources
            },
        )
    }

    /**
     * The window the rapid-fall slope is fitted over on the five-minute grid.
     * Thirty minutes is six or seven points there, against the five the
     * detector asks for; the ten-minute default would be three at best.
     */
    private const val GRID_FALL_WINDOW_MIN = 30L
}
