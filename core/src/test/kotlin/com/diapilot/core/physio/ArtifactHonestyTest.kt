package com.diapilot.core.physio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE ARTIFACT MUST NOT CARRY A NUMBER THAT THE APPLIED MODEL THROWS AWAY.
 *
 * This test was written after a real bug, and it was not an outsider's mistake.
 * A read of `person_model_v11_runtime.json` found `iob_gamma = 1.0`,
 * `food_gamma = 0.25`, `activity_direct = 0.4637`, and that was reported as
 * activity working in the model. It does not: `PhysioV1.personModelAt` zeroes
 * `iobGamma`, `foodGamma`, `activityDirect` and `sleepDebt`, while `sleep` and
 * `hoursSinceWake` come from `backgroundCoefficients`, which `PhysioRuntime`
 * never passes at all — so those are zero too.
 *
 * This is exactly the "tuned vs APPLIED" trap warned about elsewhere, fallen into
 * shortly after warning about it in writing. Two external reviews found the
 * same thing independently, and one of them correctly called it not a
 * simplification but silence: activity is recorded but the median line does
 * not use it.
 *
 * WHY A FILE, NOT A COMMENT. A comment is not read under the number. As long
 * as the JSON carries 0.4637, every next reader — human or reviewer — will
 * draw the same conclusion. The values were moved here, to the one place
 * where they belong: a description of what they WERE before being zeroed.
 *
 * Former values, if the axis is ever wired up: iob_gamma 1.0 · food_gamma 0.25 ·
 * activity_direct, sleep, sleep_debt and hours_since_wake each carried a small
 * nonzero fitted coefficient (the exact digits belonged to one person's fit
 * and are not reproduced here).
 * They cannot be trusted: `PhysioV1` itself states that they were «fitted as
 * part of a different factorization» and did not pass the versioned
 * promotion gates.
 *
 * WHEN THIS TEST FAILS — that is the right moment. It fails if someone puts a
 * value back into the artifact without wiring up the axis, or if the axis is
 * wired up and the value becomes meaningful. In the second case, the test is
 * rewritten, not deleted.
 */
class ArtifactHonestyTest {

    private val artifact = File("../app/src/main/assets/models/person_model_v11_runtime.json")

    private fun number(path: List<String>): Double {
        assertTrue("artifact not found: ${artifact.absolutePath}", artifact.exists())
        var node = org.json.JSONObject(artifact.readText())
        for (k in path.dropLast(1)) node = node.getJSONObject(k)
        return node.getDouble(path.last())
    }

    @Test
    fun `coefficients the applied model zeroes are zero in the artifact too`() {
        for (path in listOf(
            listOf("activity", "iob_gamma"),
            listOf("activity", "food_gamma"),
            listOf("joint", "coefficients", "activity_direct"),
            listOf("joint", "coefficients", "sleep"),
            listOf("joint", "coefficients", "sleep_debt"),
            listOf("joint", "coefficients", "hours_since_wake"),
        )) {
            assertEquals(
                "${path.joinToString(".")} carries a value that personModelAt " +
                    "throws away — a reader of the artifact will draw the wrong conclusion",
                0.0, number(path), 1e-12,
            )
        }
    }

    @Test
    fun `the circadian harmonics are zero, and that is a measurement not a gap`() {
        // THIS IS NOT AN UNWIRED CONNECTION. That is what the comment here used
        // to say, and it invited the next reader to wire it up. Checked — there
        // is nothing to wire, for three independent reasons, and any one of
        // them is sufficient.
        //
        // 1. THE CLOCK FACE IS NOT COVERED, AND WILL NOT BE. A clean background
        //    window needs 5 h without insulin and 3 h without food; that kind
        //    of fast happens at most once a day, overnight into the morning.
        //    Of 274 windows: 06:00-09:00 gives 172, while 13:00 to midnight
        //    gives three, and midnight itself gives zero — 11 covered hours out
        //    of 24. A sin24/cos24 pair sets a single period, and over half of
        //    it the other half is not recovered but inferred from the shape of
        //    the basis. R²=0.074 is gathered on the morning cluster; the
        //    predicted "−1.37 at midnight" is extrapolation dressed up as
        //    measurement. This is a property of the user's SCHEDULE, not of
        //    sample size: no amount of collection time will produce clean
        //    evening windows.
        //
        // 2. EVEN WITH FULL COVERAGE, THE HOUR IS NOT SEPARABLE FROM BASAL. The
        //    basal dose is injected at roughly the same time daily. "First 0-4 h
        //    from the basal dose" gives a slope of −0.57, "night 0-6" gives
        //    −0.37 — these are the SAME windows. Both axes are functions of the
        //    hour on the clock face, because the injection is pinned to the
        //    hour, and retrospectively nothing separates them. A fitted sin24
        //    would call circadian rhythm what may just be basal
        //    pharmacokinetics.
        //
        // 3. THE DEFECT THE HARMONICS WERE PROPOSED FOR SCALES WITH DOSE. The
        //    model line after 120 min is flat across all nine nights checked.
        //    But reality is flat too, where the dose is small: at <2.5 U the
        //    miss is +0.52, at >=2.5 U it is +2.24, dose r=+0.60 versus starting
        //    glucose r=+0.30 (n=8). The hourly dose term DOES NOT KNOW this: it
        //    is the same at 1.5 and at 4.0 U, so it would also flatten the two
        //    nights where the model already fits. The missing late mechanism is
        //    insulin, and the axis for it is
        //    `PhysioAutoFitV1.Knobs.tailShare`, not a harmonic.
        //
        // Measurement: the analysis runner's driftattr command (points 1-2)
        // and its isfshape command (point 3). Both print the numbers above
        // along with n.
        //
        // WHEN THIS TEST FAILS: if someone adds a harmonic back in. Before
        // rewriting the test, it must clear at least point 1 — that is, show
        // coverage of >=12 hours — otherwise the number written in was not
        // measured.
        for (k in listOf("sin24", "cos24", "sin12", "cos12")) {
            assertEquals(
                "harmonic $k stopped being zero — on this data the circadian term " +
                    "is not identifiable (11 covered hours out of 24) and is not " +
                    "separable from hours-since-basal-dose; see the three reasons above",
                0.0, number(listOf("joint", "coefficients", k)), 1e-12,
            )
        }
    }
}
