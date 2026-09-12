package io.github.obdosok.diapilot.collect

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.analysis.MGDL_PER_MMOL_F
import com.diapilot.core.collector.ACTION_BG
import com.diapilot.core.collector.EXTRA_BG
import com.diapilot.core.collector.EXTRA_TIME
import com.diapilot.core.collector.Reading
import io.github.obdosok.diapilot.data.Stores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * ALERTS FIRE WITHOUT OOPAlgorithm2.
 *
 * The defect these tests exist for: `HypoAlertNotifier.maybeNotify`,
 * `RapidFallNotifier.maybeNotify` and `CompanionSync.pushIfDue` had exactly
 * one caller each, and it was the tail of the OOP2 minute receiver. On a phone
 * without that third-party app — every stranger's phone, and the whole point
 * of the store edition — the app kept collecting, drawing and answering the
 * watch while no alarm of any kind could fire (docs/audit.md, P7).
 *
 * NOTHING HERE WRITES A MINUTE READING, on purpose. `minute_readings` is the
 * OOP2 stream and the own-BLE stream; leaving that table empty is what "no
 * OOP2, no BLE" means, and it is the state both editions are in on a phone
 * that has only xDrip. So these assertions hold in `testOssDebugUnitTest` and
 * in `testStoreDebugUnitTest` alike — the store run is the one that matters,
 * because there the predictive half of the low and high sides is gated off and
 * every alarm asserted below has to come from the readings themselves.
 *
 * The store runs on Robolectric's NATIVE SQLite, like `XdripBroadcastGateTest`:
 * the legacy in-memory engine predates `ON CONFLICT ... DO UPDATE`
 * (SQLite 3.24), which `upsertReading` has always used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlertTickTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /**
     * The notification channels the four alerts post on. Duplicated from the
     * notifiers rather than exposed by them: a channel id is a promise to the
     * phone (Android remembers its importance from the first creation), so an
     * edit to one should have to be made here too.
     */
    private val hypoChannel = "hypo_alert_v2"
    private val hyperChannel = "hyper_alert"
    private val fallChannel = "rapid_fall"
    private val stallChannel = "stream_stall_v2"

    private val originalDispatcher = AlertTick.dispatcher

    @Before fun inlineTicks() {
        // The tick is asynchronous in production (SQLite and a possible model
        // build must not ride a broadcast receiver's thread). Run it inline so
        // the assertion follows the call.
        AlertTick.dispatcher = { it.run() }
        AlertTick.reset()
        XdripApp.reset()
        // A clean preferences file: the alert state machines, the rapid-fall
        // debounce and the stall cooldown all live there, and so do the
        // settings, which must read as their defaults (both alerts on, own BLE
        // off, range 3.9..10.0).
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            // Every accepted broadcast doubles as a catch-up trigger for the
            // treatments poll. Marking the poll as just-run keeps WorkManager
            // (not initialized in a unit test) out of this test's way.
            .putLong(TreatmentsPollWorker.PREF_LAST_POLL_TS, System.currentTimeMillis())
            .commit()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun cleanUp() {
        AlertTick.dispatcher = originalDispatcher
        AlertTick.reset()
        XdripApp.reset()
        DiagState.lastAlertTickMs = 0
        Stores.close()
        context.deleteDatabase("diapilot.sqlite")
    }

    private fun installXdrip() = Shadows.shadowOf(context.packageManager).installPackage(
        android.content.pm.PackageInfo().apply { packageName = XdripApp.PACKAGE },
    )

    private val notifications: List<android.app.Notification>
        get() = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
            .allNotifications

    private fun fired(channel: String) = notifications.any { it.channelId == channel }

    /**
     * A main-grid series ending [endMmol] at [endAgoMin] ago, walking back at
     * five minutes and [stepMmol] per step. The main grid only — see the class
     * comment.
     */
    private fun seedGrid(
        points: Int,
        endMmol: Double,
        stepMmol: Double = 0.0,
        endAgoMin: Long = 1,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val store = Stores.get(context)
        repeat(points) { k ->
            val mmol = endMmol + stepMmol * k
            store.upsertReading(
                Reading(
                    nowMs - (endAgoMin + k * 5L) * 60_000,
                    mmol * MGDL_PER_MMOL_F, mmol,
                    trend = null, source = "xdrip_sgv",
                ),
            )
        }
    }

    /** The whole persisted hypo state, so a test can prove it was not touched. */
    private fun hypoState(): Map<String, Long> {
        val prefs = context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
        return listOf(
            "hypo_alert_last_ms", "hypo_episode_start_ms", "hypo_low_since_ms",
            "hypo_last_low_ms", "hypo_low_observed_ms", "hypo_last_pass_ms",
            "hypo_gentle_at_ms", "hypo_artifact_last_ms",
        ).associateWith { prefs.getLong(it, 0) }
    }

    // --- the defect itself --------------------------------------------------

    /**
     * THE REGRESSION TEST FOR THE WHOLE PACKAGE. One xDrip broadcast, no OOP2
     * anywhere, and the low alarm fires. Before the tick existed this receiver
     * stored the reading and returned.
     */
    @Test fun `a stored xDrip broadcast below the threshold fires the low alert`() {
        installXdrip()
        val now = System.currentTimeMillis()
        seedGrid(points = 6, endMmol = 3.4, endAgoMin = 6, nowMs = now)
        val intent = Intent(ACTION_BG).apply {
            putExtra(EXTRA_BG, 3.0 * MGDL_PER_MMOL_F)
            putExtra(EXTRA_TIME, now - 30_000)
        }
        assertNotNull(XdripBgReceiver.handle(context, intent))
        assertTrue(notifications.map { it.channelId }.toString(), fired(hypoChannel))
        assertTrue("the watch signal follows the alert", HypoAlertNotifier.alertActive)
    }

    /**
     * Every accepted broadcast also triggers a catch-up poll, whose
     * `/sgv.json` answer carries the reading the broadcast just stored — so
     * two sources deliver the same value seconds apart as a matter of course.
     * The second one must not reach the notifiers at all.
     */
    @Test fun `the same reading arriving from two sources is judged once`() {
        seedGrid(points = 6, endMmol = 3.0)
        AlertTick.fire(context, AlertTick.Source.XDRIP_BROADCAST)
        assertTrue("the first tick must evaluate", DiagState.lastAlertTickMs > 0)
        DiagState.lastAlertTickMs = 0
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertEquals(
            "the duplicate must not reach the notifiers",
            0L, DiagState.lastAlertTickMs,
        )
    }

    /**
     * A fingerstick is the one source that is never de-duplicated: it does not
     * advance the sensor clock the key is built from, and it is exactly the
     * value that can CLEAR a live alert.
     */
    @Test fun `a hand-entered value is judged even when the sensor clock has not moved`() {
        seedGrid(points = 6, endMmol = 3.0)
        AlertTick.fire(context, AlertTick.Source.XDRIP_BROADCAST)
        DiagState.lastAlertTickMs = 0
        AlertTick.fire(context, AlertTick.Source.MANUAL)
        assertTrue(DiagState.lastAlertTickMs > 0)
    }

    // --- the four alerts, on a phone with only the five-minute grid ---------

    @Test fun `a confirmed low fires with no minute stream`() {
        seedGrid(points = 8, endMmol = 3.2)
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertTrue(fired(hypoChannel))
    }

    /**
     * The high side without a forecast is the sustained-high observation —
     * seventy minutes above 13.9. It deliberately does not read the model, so
     * it is the high alert both editions have.
     */
    @Test fun `a sustained high fires with no minute stream`() {
        seedGrid(points = 16, endMmol = 15.0)
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertTrue(fired(hyperChannel))
        assertFalse("a high is not a hypo", fired(hypoChannel))
    }

    /**
     * Rapid fall on the five-minute cadence. The detector's default window is
     * ten minutes, which can never hold the five points it asks for on this
     * grid — so on a phone without the minute stream this alert was
     * undetectable rather than merely quiet, and the tick widens the window to
     * thirty minutes without touching the slope threshold or the projection.
     */
    @Test fun `a rapid fall fires with no minute stream`() {
        // 0.7 mmol per five minutes: fast enough for the detector (-0.12),
        // slow enough to stay under the plausibility gate's 0.15/min fall rule.
        seedGrid(points = 7, endMmol = 5.0, stepMmol = 0.7)
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertTrue(fired(fallChannel))
        assertFalse("5.0 is not below the floor", fired(hypoChannel))
    }

    /**
     * The stall notification used to hang off the own-BLE watchdog inside the
     * poll worker, so a phone fed by xDrip went blind in silence. It is now
     * part of the same tick as the glucose alerts, in both editions.
     */
    @Test fun `a stalled stream is announced with no minute stream`() {
        seedGrid(points = 6, endMmol = 6.0, endAgoMin = StreamStallNotifier.STALL_MIN + 5)
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertTrue(fired(stallChannel))
        assertFalse("a stale reading cannot be judged", fired(hypoChannel))
    }

    // --- the backstop ------------------------------------------------------

    /**
     * A phone whose only source is the 15-minute web poll. No broadcast ever
     * arrives, so nothing else could have driven an evaluation; the periodic
     * worker calls the tick with this source at the end of every run, whether
     * or not it collected anything.
     */
    @Test fun `the periodic backstop evaluates when no broadcast has arrived`() {
        seedGrid(points = 8, endMmol = 3.1)
        assertEquals(0L, DiagState.lastXdripBroadcastMs)
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertTrue(DiagState.lastAlertTickMs > 0)
        assertTrue(fired(hypoChannel))
    }

    /**
     * The backstop has to get through the de-duplication window, because the
     * state it exists to notice — a stalled stream, a low that keeps re-firing
     * — is exactly the state in which the freshest reading never changes.
     */
    @Test fun `an unchanged reading is re-judged once the window has passed`() {
        seedGrid(points = 6, endMmol = 3.0)
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        DiagState.lastAlertTickMs = 0
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertEquals("still inside the window", 0L, DiagState.lastAlertTickMs)
        AlertTick.reset()   // stands in for the window elapsing
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertTrue(DiagState.lastAlertTickMs > 0)
    }

    // --- the backfill ------------------------------------------------------

    /**
     * A DEEP BACKFILL MUST NOT ALARM, AND MUST NOT SPEND ANY ALERT STATE.
     *
     * `/sgv.json?count=4032` is ~14 days, fetched once per install and again
     * after long downtime. Here the whole fortnight is below the floor and the
     * newest point is two days old — the shape of a first run on a phone whose
     * sensor has since stopped. No glucose alert may fire on last week's
     * numbers, and no episode, cooldown or dextrose snooze may be spent: the
     * next genuine low has to be treated as the first.
     *
     * The STALL notification does fire, and that is the honest answer rather
     * than an exception to the rule — readings from last week arrived, glucose
     * is not arriving, and saying so is this notifier's whole job.
     */
    @Test fun `a backfill of old readings fires nothing and leaves the alert state untouched`() {
        val now = System.currentTimeMillis()
        val store = Stores.get(context)
        var ts = now - 14L * 24 * 3_600_000
        while (ts < now - 2L * 24 * 3_600_000) {
            store.upsertReading(
                Reading(ts, 3.0 * MGDL_PER_MMOL_F, 3.0, trend = null, source = "xdrip_sgv"),
            )
            ts += 5L * 60_000
        }
        val before = hypoState()
        AlertTick.fire(context, AlertTick.Source.WEB_POLL)
        assertFalse(fired(hypoChannel))
        assertFalse(fired(hyperChannel))
        assertFalse(fired(fallChannel))
        // The watch flag is NOT asserted here, and that is a statement about
        // the code rather than a gap in the test: the staleness return leaves
        // `HypoAlertNotifier.alertActive` exactly as it was, so a low that was
        // live when the stream went quiet keeps signalling the wrist. Assuming
        // the worst while blind is the safe direction, and changing it is not
        // this package's business — what this test owns is that nothing NEW
        // fires and nothing is spent.
        assertEquals("no alert state may be spent", before, hypoState())
        assertTrue("the app has gone blind and must say so", fired(stallChannel))
    }
}
