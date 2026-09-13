package io.github.obdosok.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.Reading
import com.diapilot.core.physio.ManualInsulinParamsV1
import com.diapilot.core.twin.Corridor
import io.github.obdosok.diapilot.BuildConfig
import io.github.obdosok.diapilot.Edition
import io.github.obdosok.diapilot.data.Onboarding.Input.Parsed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * THE UNCALIBRATED MODE, END TO END (audit M2).
 *
 * A fresh install runs nothing forward: `Forecaster` — the one function every
 * consumer shares — answers null while the first-run pages are owed, and the
 * calibration status names the bundled example person. Once the pages have
 * written an ISF and an insulin preset through the same doors the Settings
 * screen uses, the status says hand-entered and the same call produces a line.
 *
 * Runs on both flavors. The Forecaster gate is deliberately edition-neutral
 * (the store edition keeps the retrospective replay), so the "produces a
 * forecast" half holds in both; what differs by edition is which PAGES exist
 * and whether the prospective SURFACES ask for a model at all —
 * [ModelCalibration.forecastAllowed], asserted against `BuildConfig.FLAVOR`
 * the way `EditionContractTest` does.
 *
 * Native SQLite for the same reason as `EditionContractTest`: `upsertReading`
 * uses `ON CONFLICT ... DO UPDATE`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OnboardingGateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val oss: Boolean get() = BuildConfig.FLAVOR == "oss"

    private lateinit var dbName: String
    private lateinit var store: SqliteCollectorStore

    private fun model() = context.assets.open("models/person_model_v11_runtime.json").use {
        HybridPersonModelJson.read(it)
    }

    /** The engine needs a twin only for its calibration lens; empty is a clean lens. */
    private fun twin() = TwinCache.Model(
        kernel = emptyList(),
        corridor = Corridor(0.5, 0.1),
        byTod = emptyMap(),
        contexts = emptyList(),
        carbSens = null,
    )

    @Before fun freshInstall() {
        dbName = "onboarding-gate-${System.nanoTime()}.sqlite"
        store = SqliteCollectorStore(context, dbName)
        PhysioForecastRegistry.install(model(), "onboarding-gate-test")
        Onboarding.reset(context)
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1.EMPTY)
        TwinCache.invalidate()
    }

    @After fun close() {
        store.close()
        context.deleteDatabase(dbName)
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1.EMPTY)
        Onboarding.reset(context)
    }

    /** Three hours of a flat 7.5 on the five-minute grid, ending [endMs]. */
    private fun seedReadings(endMs: Long, spanMs: Long = 3L * 3_600_000) {
        var ts = endMs - spanMs
        while (ts <= endMs) {
            store.upsertReading(Reading(ts, 7.5 * 18.0182, 7.5, trend = "Flat", source = "xdrip_broadcast"))
            ts += 5L * 60_000
        }
    }

    private fun forecastNow(nowMs: Long) = Forecaster.forecast(
        store, twin(), nowMs,
        anchorTsMs = store.lastSensorReading()!!.tsMs,
        anchorMmol = 7.5,
        recordAs = null,
    )

    // --- fresh install --------------------------------------------------------

    @Test fun `a fresh install owes the whole flow and runs no forecast`() {
        val now = System.currentTimeMillis()
        seedReadings(now - 60_000)
        assertEquals(Onboarding.Gate.FULL, Onboarding.gate(context, store))
        assertFalse(Onboarding.completed(context))
        assertEquals(ModelCalibrationStatus.EXAMPLE_PERSON, ModelCalibration.status(context, store, now))
        assertNull("no pass may run on the example person", forecastNow(now))
        assertFalse(ModelCalibration.forecastAllowed(context, store))
    }

    @Test fun `a refused pass is recorded in the ledger, not swallowed`() {
        val now = System.currentTimeMillis()
        seedReadings(now - 60_000)
        Forecaster.forecast(
            store, twin(), now,
            anchorTsMs = store.lastSensorReading()!!.tsMs, anchorMmol = 7.5,
            recordAs = "main",
        )
        val refusals = store.readableDatabase.rawQuery(
            // A refusal row carries its reason in `applied` (see ForecastLedger.recordRefusal).
            "SELECT COUNT(*) FROM forecast_runs WHERE consumer = 'main' AND applied = ?",
            arrayOf(Forecaster.UNCALIBRATED_REFUSAL),
        ).use { it.moveToFirst(); it.getLong(0) }
        assertEquals(1L, refusals)
    }

    // --- after the pages -------------------------------------------------------

    @Test fun `entering ISF and a preset makes the model hand-entered and the forecast run`() {
        val now = System.currentTimeMillis()
        seedReadings(now - 60_000)
        Onboarding.applyModelEntries(context, isfMmolPerU = 2.5, preset = Onboarding.InsulinPreset.TYPICAL, carbSensMmolPerG = null)
        Onboarding.markCompleted(context, now)

        assertTrue(Onboarding.completed(context))
        val report = ModelCalibration.report(context, store, now)
        assertEquals(ModelCalibrationStatus.HAND_ENTERED, report.status)
        assertEquals(2.5, checkNotNull(report.isfMmolPerU), 1e-9)
        assertEquals(15.0, checkNotNull(report.onsetMin), 1e-9)
        assertEquals(75.0, checkNotNull(report.peakMin), 1e-9)
        assertEquals(300.0, checkNotNull(report.tailMin), 1e-9)

        // The same doors the Settings card writes through: the manual tier.
        val manual = ManualInsulinRuntime.params(context)
        assertEquals(2.5, checkNotNull(manual.isfMmolPerU), 1e-9)
        assertNull("a preset is single-moded; no plateau is invented", manual.plateauEndMin)

        // And the artifact the app serves carries the entry, not the example.
        val served = checkNotNull(PhysioRuntime.artifact(store, now)).personModelAt(12.0)
        assertEquals(2.5, served.insulin.isf, 1e-9)
        assertEquals(300.0, served.insulin.tailDurationMin, 1e-9)

        val result = forecastNow(now)
        assertNotNull("the pass runs once the pages are done", result)
        assertTrue(result!!.points.isNotEmpty())
        assertEquals(oss, ModelCalibration.forecastAllowed(context, store))
    }

    @Test fun `the ultra-rapid preset states an end of action under 300 and is applied as entered`() {
        val now = System.currentTimeMillis()
        Onboarding.applyModelEntries(context, 3.0, Onboarding.InsulinPreset.ULTRA_RAPID, null)
        Onboarding.markCompleted(context, now)
        val served = checkNotNull(PhysioRuntime.artifact(store, now)).personModelAt(12.0)
        assertEquals(240.0, served.insulin.tailDurationMin, 1e-9)
    }

    // --- installs that pre-date the flow -------------------------------------

    @Test fun `a phone with a day of readings and a hand ISF is completed on its owner's behalf`() {
        val now = System.currentTimeMillis()
        seedReadings(now - 60_000, spanMs = 26L * 3_600_000)
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1(isfMmolPerU = 2.0))

        assertEquals(Onboarding.Gate.DISCLAIMER_ONLY, Onboarding.gate(context, store))
        assertTrue(Onboarding.completed(context))
        assertTrue(Onboarding.wasAdopted(context))
        assertNull("the disclaimer is still owed", Onboarding.disclaimerAcceptedAtMs(context))
        // Its forecast never stopped.
        assertNotNull(forecastNow(now))

        Onboarding.acceptDisclaimer(context, now)
        assertEquals(Onboarding.Gate.NONE, Onboarding.gate(context, store))
    }

    @Test fun `a day of readings on the example person is not adopted`() {
        val now = System.currentTimeMillis()
        seedReadings(now - 60_000, spanMs = 26L * 3_600_000)
        assertEquals(Onboarding.Gate.FULL, Onboarding.gate(context, store))
        assertFalse(Onboarding.completed(context))
        assertNull(forecastNow(now))
    }

    @Test fun `a hand ISF on a fresh database is not adopted`() {
        val now = System.currentTimeMillis()
        seedReadings(now - 60_000, spanMs = 60L * 60_000)
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1(isfMmolPerU = 2.0))
        assertEquals(Onboarding.Gate.FULL, Onboarding.gate(context, store))
        assertFalse(Onboarding.completed(context))
    }

    /** The background path: no activity has run [Onboarding.gate] yet. */
    @Test fun `the forecast permit itself adopts an existing install`() {
        val now = System.currentTimeMillis()
        seedReadings(now - 60_000, spanMs = 26L * 3_600_000)
        ManualInsulinRuntime.setParams(context, ManualInsulinParamsV1(isfMmolPerU = 2.0))
        assertTrue(Onboarding.forecastPermitted(context, store))
        assertTrue(Onboarding.completed(context))
    }

    // --- the pages, by edition ------------------------------------------------

    @Test fun `the store edition skips the model pages and keeps disclaimer, language and weight`() {
        val pages = Onboarding.pages(Edition.prospective)
        assertTrue(Onboarding.Page.DISCLAIMER in pages)
        assertTrue(Onboarding.Page.LANGUAGE in pages)
        assertTrue(Onboarding.Page.WEIGHT in pages)
        assertTrue(Onboarding.Page.SUMMARY in pages)
        assertEquals(oss, Onboarding.Page.ISF in pages)
        assertEquals(oss, Onboarding.Page.INSULIN_PRESET in pages)
        assertEquals(oss, Onboarding.Page.CARB_SENS in pages)
        assertEquals(Onboarding.Page.DISCLAIMER, pages.first())
        assertEquals(Onboarding.Page.SUMMARY, pages.last())
    }

    @Test fun `the disclaimer acceptance is stamped once`() {
        assertNull(Onboarding.disclaimerAcceptedAtMs(context))
        Onboarding.acceptDisclaimer(context, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, Onboarding.disclaimerAcceptedAtMs(context))
    }

    // --- the fields' domains ----------------------------------------------------

    @Test fun `the pages parse in the display units and store the model's`() {
        val i = Onboarding.Input
        assertEquals(Parsed.Empty, i.weightKg("  "))
        assertEquals(Parsed.Invalid, i.weightKg("abc"))
        assertEquals(Parsed.Invalid, i.weightKg("10"))
        assertEquals(80.0, (i.weightKg("80") as Parsed.Valid).value, 1e-9)

        assertEquals(2.5, (i.isfMmolPerU("2,5", mgdl = false) as Parsed.Valid).value, 1e-9)
        assertEquals(45.0 / 18.0182, (i.isfMmolPerU("45", mgdl = true) as Parsed.Valid).value, 1e-9)
        assertEquals(Parsed.Invalid, i.isfMmolPerU("0.1", mgdl = false))
        assertEquals(Parsed.Invalid, i.isfMmolPerU("9", mgdl = false))
        assertEquals(Parsed.Empty, i.isfMmolPerU("", mgdl = false))

        // Per 10 g in, per gram out.
        assertEquals(0.165, (i.carbSensMmolPerG("1.65", mgdl = false) as Parsed.Valid).value, 1e-9)
        assertEquals(30.0 / 18.0182 / 10.0, (i.carbSensMmolPerG("30", mgdl = true) as Parsed.Valid).value, 1e-9)
        assertEquals(Parsed.Invalid, i.carbSensMmolPerG("0.1", mgdl = false))
        assertEquals(Parsed.Empty, i.carbSensMmolPerG("", mgdl = false))
    }
}
