package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import com.diapilot.core.collector.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.exp

/**
 * The method, pinned as a contract rather than as prose.
 *
 * Settled with the user: timing from every dose. These tests exist
 * because the method was described, agreed, and then
 * NOT wired: the app went on computing the shape by receipt-CDF aggregation
 * while the segment reader lived only in the harness. Two implementations of one
 * quantity is the failure this repo has paid for four times over, and prose in a
 * design document does not prevent it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InsulinProfileContractTest {
    /** Data below is laid out relative to a fixed synthetic era start. */
    @Before fun fixFoodEra() { TestFoodEra.install() }


    private fun store(name: String) =
        SqliteCollectorStore(ApplicationProvider.getApplicationContext(), name)

    /** A dose whose action is visible: flat before, logistic fall after. */
    private fun writeFall(
        s: SqliteCollectorStore,
        atMs: Long,
        units: Double,
        purpose: String?,
        spanMin: Int = 240,
        inflection: Double = 50.0,
    ) {
        fun l(t: Double) = 1.0 / (1.0 + exp(-(t - inflection) / 12.0))
        val at0 = l(0.0)
        val db = s.writableDatabase
        (-6..(spanMin / 5)).forEach { i ->
            val m = i * 5.0
            val mmol = if (m <= 0.0) 12.0 else 12.0 - 4.0 * (l(m) - at0)
            db.execSQL(
                "INSERT OR REPLACE INTO glucose_readings(ts_ms,mgdl,mmol,trend,source) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(atMs + (m * 60_000).toLong(), mmol * 18.0, mmol, "Flat", "test"),
            )
        }
        db.execSQL(
            "INSERT OR REPLACE INTO insulin_events(ts_ms,units,insulin_type,source,purpose) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(atMs, units, "rapid", "test", purpose),
        )
    }

    private fun profile(s: SqliteCollectorStore, now: Long) =
        InsulinProfileRuntime.state(s, s.writableDatabase, now)

    /**
     * Step 1 of the method: EVERY dose is a candidate, not only labelled
     * corrections. Mutation check: filter to `isTrustedDosePurposeV1` in
     * `InsulinProfileRuntime.compute` and this fails.
     */
    @Test fun `unlabelled doses build the profile`() {
        store("prof-unlabelled-${System.nanoTime()}.sqlite").use { s ->
            val base = TestFoodEra.ERA.startMs + 3 * 86_400_000L
            (0..3).forEach { d -> writeFall(s, base + d * 86_400_000L, 3.0, purpose = null) }
            val st = profile(s, base + 10 * 86_400_000L)
            assertNotNull("four unlabelled doses must be enough", st.curve)
            assertTrue(st.curve!!.observations >= 4)
            assertTrue("and they span independent days", st.curve.independentDays >= 3)
        }
    }

    /**
     * Step 2: each landmark on its own horizon. A dose with an hour of clean
     * line contributes its onset and no tail — under whole-episode admission it
     * contributed nothing at all.
     */
    @Test fun `a short window contributes an onset but no tail`() {
        store("prof-short-${System.nanoTime()}.sqlite").use { s ->
            val base = TestFoodEra.ERA.startMs + 3 * 86_400_000L
            // 75 clean minutes, then the next injection closes the window.
            writeFall(s, base, 3.0, null, spanMin = 75)
            s.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO insulin_events(ts_ms,units,insulin_type,source) VALUES(?,?,?,?)",
                arrayOf<Any?>(base + 75 * 60_000L, 2.0, "rapid", "test"),
            )
            val st = profile(s, base + 86_400_000L)
            assertNotNull(st.profile)
            assertNotNull("the onset is inside 75 minutes", st.profile!!.onset.pooled)
            assertNull("the tail is not", st.profile.tailEnd)
        }
    }

    /**
     * Step 3 + 4: food acting at a landmark confounds it, and the curve is built
     * on the food-free arm — the only one that is identified.
     */
    @Test fun `food already absorbing marks the onset confounded`() {
        store("prof-food-${System.nanoTime()}.sqlite").use { s ->
            val base = TestFoodEra.ERA.startMs + 3 * 86_400_000L
            writeFall(s, base, 3.0, null)
            // Eaten half an hour BEFORE the dose, so it is acting by the time
            // insulin's onset arrives.
            s.addAnnotation(Annotation(base - 30 * 60_000L, "food", "тест", estCarbs = 40.0))
            val onset = profile(s, base + 86_400_000L).profile?.onset
            assertNotNull(onset)
            assertEquals("absorbing food confounds the onset", 0, onset!!.flat?.samples ?: 0)
            assertTrue("and the confounded arm carries it", (onset.rising?.samples ?: 0) >= 1)
        }
    }

    /**
     * The user's correction: food does not act for its first
     * quarter hour, so a pre-meal bolus's own onset lands in the quiet gap
     * between the injection and the food appearing — and is CLEAN.
     *
     * The constant this replaces widened the confounded interval 20 minutes
     * BEFORE a meal, justified by sensor lag; glucose cannot rise from food that
     * has not been absorbed. Mutation check: make food confound from its own
     * timestamp and this fails.
     */
    @Test fun `a meal just after the dose does not confound an onset before it acts`() {
        store("prof-premeal-${System.nanoTime()}.sqlite").use { s ->
            val base = TestFoodEra.ERA.startMs + 3 * 86_400_000L
            writeFall(s, base, 3.0, null)
            s.addAnnotation(Annotation(base + 5 * 60_000L, "food", "тест", estCarbs = 40.0))
            val onset = profile(s, base + 86_400_000L).profile?.onset
            assertNotNull(onset)
            assertTrue(
                "an onset before the food appears is clean evidence",
                (onset!!.flat?.samples ?: 0) >= 1,
            )
        }
    }

    /**
     * Step 5: amplitude does NOT come from here. The profile object carries
     * timing and nothing else — a source-level check, because the failure mode
     * is a future edit quietly adding an ISF field to the timing path.
     */
    @Test fun `the timing profile carries no amplitude`() {
        val src = File("src/main/java/io/github/obdosok/diapilot/data/InsulinProfileRuntime.kt")
            .let { if (it.exists()) it else File("app/src/main/java/io/github/obdosok/diapilot/data/InsulinProfileRuntime.kt") }
            .readText()
        listOf("isf", "Isf", "ISF", "mmolPerU", "amplitude").forEach {
            assertTrue(
                "amplitude must not appear in the timing path: found '$it'",
                !src.contains("$it =") && !src.contains("$it="),
            )
        }
    }

    /**
     * And the one that matters most: exactly ONE implementation reaches
     * `person.insulin`. The receipt-CDF aggregator must not be wired in beside
     * the segment reader.
     */
    @Test fun `only one implementation feeds the person model`() {
        val src = File("src/main/java/io/github/obdosok/diapilot/data/PhysioRuntime.kt")
            .let { if (it.exists()) it else File("app/src/main/java/io/github/obdosok/diapilot/data/PhysioRuntime.kt") }
            .readText()
        assertTrue("the segment profile must feed the model", src.contains("InsulinProfileRuntime.state"))
        assertTrue(
            "the receipt-CDF aggregator must not also feed it",
            !src.contains("InsulinCurveRuntime.curve") && !src.contains("PersonalInsulinCurveEstimatorV1"),
        )
    }
}
