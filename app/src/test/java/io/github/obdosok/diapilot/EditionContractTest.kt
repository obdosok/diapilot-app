package io.github.obdosok.diapilot

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.InsulinEvent
import com.diapilot.core.collector.Reading
import io.github.obdosok.diapilot.collect.CompanionSync
import io.github.obdosok.diapilot.collect.DataSourceId
import io.github.obdosok.diapilot.collect.DataSourceInputs
import io.github.obdosok.diapilot.collect.HealthConnectAccess
import io.github.obdosok.diapilot.collect.dataSourceRows
import io.github.obdosok.diapilot.collect.WatchServer
import io.github.obdosok.diapilot.data.HybridRuntimeMetrics
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.Stores
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * THE EDITION BOUNDARY, ASSERTED ON WHICHEVER EDITION IS BEING BUILT.
 *
 * This one class runs as part of both `testOssDebugUnitTest` and
 * `testStoreDebugUnitTest`: every assertion is written against
 * [oss], so the same source proves "the oss build has it" on one run and "the
 * store build does not" on the other. A gate that is quietly removed, or
 * pointed at the wrong flag, fails here rather than in a review.
 *
 * WHY THE PROSPECTIVE HALF IS ASSERTED ONE-SIDED, and it is worth being
 * explicit rather than pretending otherwise. A forecast needs a built person
 * model, which needs weeks of a real person's episodes; a unit test cannot
 * fabricate one, so "the oss build draws a 3 h line" is not a claim this test
 * can make — on an empty database both editions produce no line, for different
 * reasons. What it CAN assert, and does, is the direction that matters for a
 * store submission: with data in place, the store edition publishes and
 * computes nothing about later. The oss side is covered by the flag itself
 * plus the surfaces' own tests.
 *
 * The store runs on Robolectric's NATIVE SQLite, like `XdripBroadcastGateTest`:
 * the legacy in-memory engine predates `ON CONFLICT ... DO UPDATE`
 * (SQLite 3.24), which `upsertReading` has always used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditionContractTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Which edition is being built, read INDEPENDENTLY of [Edition] itself.
     *
     * `Edition` is the thing under test, so deriving the expectation from it
     * would make every assertion below a tautology. `BuildConfig.FLAVOR` is
     * written by Gradle from the flavor name.
     */
    private val oss: Boolean get() = BuildConfig.FLAVOR == "oss"

    // The watch token lives in SecretStore, whose real cipher needs
    // AndroidKeyStore; Robolectric has none, so a fake stands in.
    private val originalCipherFactory = io.github.obdosok.diapilot.data.Secrets.cipherFactory

    @Before fun fakeCipher() {
        io.github.obdosok.diapilot.data.Secrets.cipherFactory = {
            io.github.obdosok.diapilot.data.FakeSecretCipher()
        }
        io.github.obdosok.diapilot.data.Secrets.reset()
    }

    @After fun restore() {
        io.github.obdosok.diapilot.data.Secrets.cipherFactory = originalCipherFactory
        io.github.obdosok.diapilot.data.Secrets.reset()
        Stores.close()
    }

    /** A fresh reading and a dose, so every surface below has something to say. */
    private fun seedLiveData(nowMs: Long = System.currentTimeMillis()) {
        val store = Stores.get(context)
        store.upsertReading(
            Reading(nowMs - 60_000, 7.5 * 18.0182, 7.5, trend = "Flat", source = "xdrip_broadcast"),
        )
        store.upsertInsulin(InsulinEvent(nowMs - 30L * 60_000, 4.0, insulinType = null))
    }

    private fun requestedPermissions(): List<String> {
        @Suppress("DEPRECATION")
        return context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
    }

    /** Every key anywhere in [json], nested objects and arrays included. */
    private fun allKeys(json: Any?): List<String> = when (json) {
        is JSONObject -> json.keys().asSequence().toList() +
            json.keys().asSequence().flatMap { allKeys(json.get(it)).asSequence() }
        is JSONArray -> (0 until json.length()).flatMap { allKeys(json.get(it)) }
        else -> emptyList()
    }

    // --- the two booleans ---------------------------------------------------

    @Test fun `the edition object says what the flavor built`() {
        assertEquals(oss, Edition.prospective)
        assertEquals(oss, Edition.sensorDirect)
    }

    /**
     * The editions install side by side, so every derived name differs — the
     * FileProvider authority, the broadcast actions and the backup file names
     * all hang off the applicationId (see [AppIdentity]).
     */
    @Test fun `the store edition has its own applicationId`() {
        assertEquals(
            if (oss) "io.github.obdosok.diapilot" else "io.github.obdosok.diapilot.store",
            BuildConfig.APPLICATION_ID,
        )
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals(BuildConfig.APPLICATION_ID, AppIdentity.APPLICATION_ID)
    }

    /**
     * THE TABLE, IN CODE. Each gated capability is named here beside the flag
     * it rides, so re-pointing one at the wrong flag — or gating something on
     * a third, invented condition — has to be done in this list too. The list
     * is the same one `docs/editions.md` prints in prose.
     */
    @Test fun `every gated capability rides the edition it belongs to`() {
        val capabilities = mapOf(
            "3 h forecast line" to Edition.prospective,
            "uncertainty band" to Edition.prospective,
            "What-if" to Edition.prospective,
            "insulin on board" to Edition.prospective,
            "tuning surfaces, Auto-fit included" to Edition.prospective,
            "predictive low and predicted high alerts" to Edition.prospective,
            "predict* fields of /info.json" to Edition.prospective,
            "forecast fields of the companion push" to Edition.prospective,
            "background model builds" to Edition.prospective,
            "NFC Libre scanning" to Edition.sensorDirect,
            "OOP2 decoded minute stream" to Edition.sensorDirect,
            "own BLE link" to Edition.sensorDirect,
        )
        capabilities.forEach { (name, present) -> assertEquals(name, oss, present) }
    }

    // --- prospective: what is published ------------------------------------

    @Test fun `the local API carries no predict fields in the store edition`() {
        seedLiveData()
        val body = WatchServer(context).respond("GET", "/info.json").body
        assertTrue(body, body.startsWith("{"))
        val keys = allKeys(JSONObject(body))
        // There is a reading in the store, so this is a real payload, not "{}".
        assertTrue(keys.toString(), "bg" in keys)
        val predictKeys = keys.filter { it.startsWith("predict") }
        if (oss) {
            // Presence is not asserted: `predictIOB` needs a measured insulin
            // curve and `predictBWP` a forecast, neither of which exists on an
            // empty history. What is asserted is that nothing else forbids them.
            assertTrue(Edition.prospective)
        } else {
            assertEquals(emptyList<String>(), predictKeys)
        }
    }

    @Test fun `the watch graph carries no prediction lines in the store edition`() {
        seedLiveData()
        val body = WatchServer(context).respond("GET", "/info.json?graph=1").body
        val graph = JSONObject(body).optJSONObject("graph")
        val lines = graph?.optJSONArray("lines")
        val names = (0 until (lines?.length() ?: 0))
            .map { lines!!.getJSONObject(it).getString("name") }
        // The retrospective lines are the frozen watch feed and stay in both.
        assertTrue(names.toString(), "lineLow" in names)
        if (!oss) {
            listOf("predict", "predLo", "predHi").forEach {
                assertFalse(names.toString(), it in names)
            }
        }
    }

    @Test fun `the companion payload carries no forecast in the store edition`() {
        seedLiveData()
        val snapshot = CompanionSync.buildSnapshot(context, System.currentTimeMillis())
        assertTrue("a seeded reading must produce a payload", snapshot != null)
        val keys = allKeys(snapshot)
        assertTrue(keys.toString(), "history" in keys)
        assertEquals(oss, "forecast" in keys)
        assertEquals(oss, "status_line" in keys)
        assertEquals(emptyList<String>(), keys.filter { it.startsWith("predict") })
    }

    // --- prospective: insulin on board -------------------------------------

    /**
     * The ONE IOB entry every surface reads — header, status line, chart rail,
     * widget, watch, Ask-Claude briefing. Closed, it must answer «unknown»
     * (null / empty), never «zero»: a zero would tell the user the insulin has
     * finished acting.
     */
    @Test fun `insulin on board is unknown rather than zero in the store edition`() {
        val now = System.currentTimeMillis()
        seedLiveData(now)
        val store = Stores.get(context)
        if (!oss) {
            assertNull(HybridRuntimeMetrics.surfaceIobUnits(store, context, now))
            assertEquals(
                emptyList<Pair<Long, Double>>(),
                HybridRuntimeMetrics.surfaceIobSeries(store, context, now - 3_600_000, now),
            )
        }
    }

    // --- sensor-direct ------------------------------------------------------

    /**
     * Both switches stay off in the store edition even after being set — an
     * export restored from an oss install must not switch on a path that
     * edition has neither the permission nor the code entry point for. The
     * SETTERS keep working: the preference belongs to the user.
     */
    @Test fun `the sensor-direct switches follow the edition, not the preference`() {
        Settings.setLibreNfcEnabled(context, true)
        Settings.setOwnBleEnabled(context, true)
        assertEquals(oss, Settings.libreNfcEnabled(context))
        assertEquals(oss, Settings.ownBleEnabled(context))
    }

    /**
     * The Data sources screen lists sensor-direct sources only where the
     * build has them. `docs/editions.md` forbids a surface that opens onto
     * nothing, and a row explaining what breaks when OOPAlgorithm2 is silent
     * is exactly that on a phone whose build never reads OOP2.
     */
    @Test fun `the data sources screen lists sensor-direct rows only in the oss edition`() {
        val ids = dataSourceRows(
            dataSourceProbeInputs(),
        ).map { it.id }
        assertEquals(oss, DataSourceId.OOP2 in ids)
        assertEquals(oss, DataSourceId.OWN_BLE in ids)
        // The xDrip chain and the phone's own policies are in both editions:
        // collection is the store edition's entire job.
        assertTrue(DataSourceId.XDRIP_BROADCAST in ids)
        assertTrue(DataSourceId.NFC in ids)
    }

    /** Values are irrelevant here — only which rows exist is asserted. */
    private fun dataSourceProbeInputs() = DataSourceInputs(
        nowMs = 1_700_000_000_000L,
        sensorDirect = Edition.sensorDirect,
        xdripInstalled = false,
        lastBroadcastMs = 0,
        lastWebProbeMs = 0,
        lastWebOkMs = 0,
        nightscoutUrl = null,
        lastMinuteReadingMs = 0,
        ownBleEnabled = false,
        lastBlePacketMs = 0,
        batteryUnrestricted = true,
        notificationsEnabled = true,
        exactAlarmsAllowed = true,
        serviceStartedMs = 0,
        serviceStoppedMs = 0,
        healthConnect = HealthConnectAccess.UNAVAILABLE,
        overlayWanted = false,
        overlayGranted = false,
        nfcPresent = false,
        nfcEnabled = false,
    )

    // --- the manifest -------------------------------------------------------

    @Test fun `USE_EXACT_ALARM is oss-only and SCHEDULE_EXACT_ALARM is in both`() {
        val permissions = requestedPermissions()
        assertEquals(
            permissions.toString(),
            oss,
            "android.permission.USE_EXACT_ALARM" in permissions,
        )
        assertTrue(
            permissions.toString(),
            "android.permission.SCHEDULE_EXACT_ALARM" in permissions,
        )
    }

    @Test fun `the own-BLE permission is asked for only where there is a BLE client`() {
        val permissions = requestedPermissions()
        assertEquals(
            permissions.toString(),
            oss,
            "android.permission.BLUETOOTH_CONNECT" in permissions,
        )
        // NFC stays in both: the NovoPen dose-log scan is not sensor-direct.
        assertTrue(permissions.toString(), "android.permission.NFC" in permissions)
    }
}
