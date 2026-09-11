package com.diapilot.core.analysis

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeconvolutionTest {

    private val now = 1_700_000_000_000L
    private val t0 = now - 6L * 3_600_000   // meal 6h ago, fully observed

    private fun kp(tau: Double, m: Double) = KernelPoint(tau, m, m, m, 5)

    // Kernel median is NEGATIVE (insulin lowers BG); cumulative drop to ~-2.5.
    private val kernel = listOf(
        kp(30.0, -1.0), kp(60.0, -2.0), kp(90.0, -2.5), kp(120.0, -2.5),
        kp(180.0, -2.0), kp(240.0, -1.2), kp(300.0, -0.6),
    )

    /** Flat BG every 5 min across the episode window — the well-dosed case. */
    private fun flatReadings(mmol: Double): List<GlucosePoint> =
        (-15..305 step 5).map { GlucosePoint(t0 + it * 60_000L, mmol) }

    @Test
    fun `a well-dosed flat meal recovers its rise from the insulin curve`() {
        val note = Annotation(id = 1, tsMs = t0, kind = "food", content = "хлеб", estCarbs = 30.0)
        val bolus = BolusPoint(t0, 3.0, null)   // dosed at the meal
        val obs = deconvolvedMealObservations(
            notes = listOf(note),
            readings = flatReadings(6.0),
            boluses = listOf(bolus),
            kernel = kernel,
            nowMs = now,
        )
        assertEquals("one observation for the isolated note", 1, obs.size)
        val o = obs.single()
        // Glucose was FLAT, yet the meal's own rise ≈ the insulin drop it offset
        // (peak of the kernel ~2.5 mmol at 3 U → scaled by units). Must be > 0.
        assertTrue("recovered a positive rise, got ${o.peakRise}", o.peakRise > 1.5)
        assertTrue("bread → carb driver", "bread" in o.fingerprint.carbDrivers)
        assertEquals(30.0, o.carbGrams, 1e-9)
    }

    @Test
    fun `per-bolus selected-engine kernel overrides the legacy deconvolution kernel`() {
        val note=Annotation(id=1,tsMs=t0,kind="food",content="хлеб",estCarbs=30.0)
        val bolus=BolusPoint(t0,3.0)
        val weak=kernel.map{it.copy(median=it.median*.1,q1=it.q1*.1,q3=it.q3*.1)}
        val legacy=deconvolvedMealObservations(listOf(note),flatReadings(6.0),listOf(bolus),weak,now)
        val selected=deconvolvedMealObservations(
            listOf(note),flatReadings(6.0),listOf(bolus),weak,now,
            kernelForBolus={kernel},
        )
        assertTrue(legacy.isEmpty()||legacy.single().peakRise<selected.single().peakRise)
        assertTrue(selected.single().peakRise>1.5)
    }

    @Test
    fun `a confounder starting after the meal truncates the window, not the observation`() {
        // Activity begins 90 min after the meal. Its early absorption phase is
        // fully clean, so the observation must still be recovered (phase-limited),
        // not discarded the way an overlapping confounder is.
        val note = Annotation(id = 1, tsMs = t0, kind = "food", content = "смузи", estCarbs = 30.0)
        val bolus = BolusPoint(t0, 3.0, null)
        val lateActivity = FoodContaminationWindow(t0 + 90L * 60_000, t0 + 300L * 60_000)
        val obs = deconvolvedMealObservations(
            notes = listOf(note),
            readings = flatReadings(6.0),
            boluses = listOf(bolus),
            kernel = kernel,
            nowMs = now,
            contaminationWindows = listOf(lateActivity),
        )
        assertEquals("clean early phase → still one observation", 1, obs.size)
    }

    @Test
    fun `a confounder overlapping the baseline discards the observation`() {
        // Activity spanning the meal's baseline and rise corrupts the recovered
        // curve — this one must be dropped.
        val note = Annotation(id = 1, tsMs = t0, kind = "food", content = "смузи", estCarbs = 30.0)
        val bolus = BolusPoint(t0, 3.0, null)
        val overlapping = FoodContaminationWindow(t0 - 30L * 60_000, t0 + 200L * 60_000)
        val obs = deconvolvedMealObservations(
            notes = listOf(note),
            readings = flatReadings(6.0),
            boluses = listOf(bolus),
            kernel = kernel,
            nowMs = now,
            contaminationWindows = listOf(overlapping),
        )
        assertTrue("baseline-overlapping confounder drops the episode", obs.isEmpty())
    }

    @Test
    fun `no bolus, flat glucose → no meal signal`() {
        // Flat BG with NO insulin means nothing was absorbed — not a hidden meal.
        val note = Annotation(id = 1, tsMs = t0, kind = "food", content = "хлеб", estCarbs = 30.0)
        val obs = deconvolvedMealObservations(
            notes = listOf(note),
            readings = flatReadings(6.0),
            boluses = emptyList(),
            kernel = kernel,
            nowMs = now,
        )
        assertTrue("flat + no insulin is not a recovered rise", obs.isEmpty())
    }

    @Test
    fun `two notes minutes apart are ONE meal, with the summed carbs`() {
        // "meatballs" + "beetroot soup" a minute apart is one meal. Anchored
        // per NOTE they truncated each other to nothing; as a session it is a
        // single 60 g meal with a full window.
        val notes = listOf(
            Annotation(id = 1, tsMs = t0, kind = "food", content = "хлеб", estCarbs = 40.0),
            Annotation(id = 2, tsMs = t0 + 60_000, kind = "food", content = "паста тунца", estCarbs = 20.0),
        )
        val obs = deconvolvedMealObservations(
            notes = notes,
            readings = flatReadings(6.0),
            boluses = listOf(BolusPoint(t0, 3.0, null)),
            kernel = kernel,
            nowMs = now,
        )
        assertEquals("one session → one observation", 1, obs.size)
        val o = obs.single()
        assertEquals("carbs are the session total", 60.0, o.carbGrams, 1e-9)
        assertEquals("anchored at the session start", t0, o.onsetMs)
        // Both notes shape the fingerprint — it is one mixed meal, not two halves.
        assertTrue("bread is a driver", "bread" in o.fingerprint.carbDrivers)
    }

    @Test
    fun `rescue dextrose is not a course of the meal — it still truncates`() {
        // A hypo treated 20 min into dinner is NOT part of dinner: it must stay
        // its own anchor and cut dinner's window (its carbs would otherwise be
        // credited to the dish).
        val notes = listOf(
            Annotation(id = 1, tsMs = t0, kind = "food", content = "хлеб", estCarbs = 40.0),
            Annotation(id = 2, tsMs = t0 + 20 * 60_000, kind = "food", content = "декстроза ×2", estCarbs = 8.0),
        )
        val obs = deconvolvedMealObservations(
            notes = notes,
            readings = flatReadings(6.0),
            boluses = listOf(BolusPoint(t0, 3.0, null)),
            kernel = kernel,
            nowMs = now,
        )
        // The meal's window collapses to 5 min → dropped; the dextrose keeps its
        // own (it is last, so its window is open) — never a 48 g merged meal.
        assertTrue("never merged into one 48 g meal", obs.none { it.carbGrams > 40.0 })
        assertTrue("the truncated meal is not learned", obs.none { it.onsetMs == t0 })
    }

    /** A prior meal whose carbs map to NO concept — the "spelt porridge" case.
     *  Concept-keyed subtraction sees nothing there; the grams are logged. */
    private fun spelteryDinnerThenCake(priorGrams: Double = 46.0): List<Annotation> = listOf(
        Annotation(
            id = 1, tsMs = t0 - 60 * 60_000, kind = "food",
            content = "каша из спельты", estCarbs = priorGrams,
            analysis = "СОСТАВ: каша из спельты = ${priorGrams.toInt()} угл · 220 порц\nГИ: 45",
        ),
        Annotation(id = 2, tsMs = t0, kind = "food", content = "шоколад", estCarbs = 59.0),
    )

    @Test
    fun `an overlapping prior meal is never recorded as a clean episode`() {
        // REWRITTEN along with the removal of `roughNeighbours`.
        //
        // The test used to hold TWO arms: "as of today" — where the neighbour
        // is invisible and the episode is called clean (that was the pinned
        // defect) — and "with the fix", where a toggle turned on rough
        // subtraction. The toggle is gone: it used to choose between two
        // mechanisms, both replaced by the concept layer, and after that it
        // chose between two penalty formulas for the same mechanism.
        //
        // The invariant remains and is now unconditional: a meal under which
        // a prior one is still absorbing, with nothing to subtract it with,
        // is NEVER considered clean. Mutation: set `neighbourConfidence` back
        // to `1.0` in `FoodCurve`, and this test fails.
        val notes = spelteryDinnerThenCake()
        val readings = flatReadings(6.0)
        val boluses = listOf(BolusPoint(t0, 3.0, null))

        val obs = deconvolvedMealObservations(
            notes = notes, readings = readings, boluses = boluses,
            kernel = kernel, nowMs = now, carbSensPerGram = 0.116,
        ).filter { it.component == null }.single { it.onsetMs == t0 }

        assertEquals(
            "наложившийся сосед без построенного вклада — неразрешённый, а не чистый",
            OVERLAP_UNRESOLVED_CONFIDENCE, obs.confidence, 1e-9,
        )
        assertTrue("и он помечен как наложение", !obs.neighbourResolved)
    }

    @Test
    fun `the rough subtraction is off by default — the corpus is byte-identical`() {
        // The flag changes what the model LEARNS, so «off» must mean «nothing
        // moved», not «moved a little». Mutation check: flip the default in
        // deconvolvedMealObservations and this fails.
        val notes = spelteryDinnerThenCake()
        val readings = flatReadings(6.0)
        val boluses = listOf(BolusPoint(t0, 3.0, null))
        fun corpus(carbSens: Double?) = deconvolvedMealObservations(
            notes = notes, readings = readings, boluses = boluses,
            kernel = kernel, nowMs = now, carbSensPerGram = carbSens,
        )
        assertEquals(
            "passing carbSens alone must not enable anything",
            corpus(null).map { it.peakRise to it.confidence },
            corpus(0.116).map { it.peakRise to it.confidence },
        )
    }
}
