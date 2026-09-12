package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridBlindDayParityTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two background coefficients PHYSIO zeroes reach the model ONLY through the
 * artifact, and their absence must reproduce the shipped behaviour bit for bit.
 *
 * Measured on 366 non-overlapping quiet segments over 35 days:
 * restoring `sleep` and `hoursSinceWake` at measured values takes SSE from 731.0
 * to 685.0, while a free refit of the intercept on top buys 0.3 more — i.e. the
 * intercept needs no change and the whole deficit is the zeroing. The historical
 * coefficients from the shipped asset, fitted on a different corpus entirely,
 * independently take 38.5 of those 46 points.
 *
 * Mutation acceptance: replace either `backgroundCoefficients?.… ?: 0.0` in
 * `personModelAt` with a hard `0.0` and `measuredCoefficientsReachTheModel`
 * fails; drop the `?: 0.0` fallback and `absentCoefficientsReproduceShippedZeros`
 * fails.
 */
class BackgroundCoefficientsV1Test {

    private val person by lazy {
        HybridBlindDayParityTest().model(
            org.json.JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/hybrid_runtime_model_v11.json"))
                    .bufferedReader().readText(),
            ),
        ).let {
            // THE SAME COERCION PRODUCTION DOES, and it was missing here.
            // `PhysioRuntime.buildArtifact` moves the shipped mechanics into
            // `PhysioBoundsV1` before an artifact is constructed; this fixture
            // handed the raw JSON straight to the constructor, which only worked
            // while the end-of-action floor was below the fixture's own tail. The
            // floor is now 240 (audit M1) and the fixture is a golden file, so the
            // coercion belongs here rather than in the resource.
            it.copy(
                insulin = it.insulin.copy(
                    tailDurationMin =
                        it.insulin.tailDurationMin.coerceIn(PhysioBoundsV1().insulinTailMinRange),
                ),
            )
        }
    }

    private fun artifact(coefficients: BackgroundCoefficientsV1?) = PhysioArtifactV1(
        artifactId = "test",
        baseMechanics = person,
        globalCs = PosteriorV1(.165, .097, .257, 0, 0, null),
        globalIsf = PosteriorV1(1.85, 1.5, 2.4, 0, 0, null),
        variance = PHYSIO_BASE_VARIANCE_HEALTHY_V1,
        backgroundCoefficients = coefficients,
    )

    private val measured = BackgroundCoefficientsV1(
        sleepMmolPerHour = -0.4445,
        hoursSinceWakeMmolPerHourPerHour = -0.01935,
        evidenceIdentity = "quiet-segments-366-through-week-1",
    )

    @Test
    fun absentCoefficientsReproduceShippedZeros() {
        val c = artifact(null).personModelAt(12.0).joint.coefficients
        assertEquals(0.0, c.sleep, 0.0)
        assertEquals(0.0, c.hoursSinceWake, 0.0)
        assertEquals(0.0, c.activityDirect, 0.0)
        assertEquals(0.0, c.sleepDebt, 0.0)
    }

    @Test
    fun measuredCoefficientsReachTheModel() {
        val c = artifact(measured).personModelAt(12.0).joint.coefficients
        assertEquals(-0.4445, c.sleep, 1e-12)
        assertEquals(-0.01935, c.hoursSinceWake, 1e-12)
        assertNotEquals(0.0, c.sleep, 1e-9)
    }

    /** The two that were NOT measured stay zeroed even when the pair is carried. */
    @Test
    fun unmeasuredCoefficientsStayZeroed() {
        val c = artifact(measured).personModelAt(12.0).joint.coefficients
        assertEquals(0.0, c.activityDirect, 0.0)
        assertEquals(0.0, c.sleepDebt, 0.0)
    }

    /** The intercept and the level term are NOT part of this change — the
     *  measurement said the intercept is right and the deficit is the zeroing. */
    @Test
    fun theInterceptAndLevelTermAreUntouched() {
        val base = person.joint.coefficients
        val c = artifact(measured).personModelAt(12.0).joint.coefficients
        assertEquals(base.intercept, c.intercept, 0.0)
        assertEquals(base.stateReversion, c.stateReversion, 0.0)
        assertEquals(person.joint.targetGlucose, artifact(measured).personModelAt(12.0).joint.targetGlucose, 0.0)
    }

    @Test
    fun coefficientsOutsidePhysiologicalBoundsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundCoefficientsV1(-1.5, -0.01, "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundCoefficientsV1(-0.4, -0.5, "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundCoefficientsV1(-0.4, -0.01, "  ")
        }
    }
}

/**
 * The lifecycle contract around the pair. It governs stage, support gates and
 * revocation — and deliberately carries NO typed percent effect, because the
 * machinery's percent is a fraction of `displayReference` (0.5 mmol/L/h for
 * BACKGROUND_RATE) and the measured `sleep = -0.4445` would read as -88.9%
 * against caps that run 25-35%.
 *
 * Mutation acceptance: flip `applicationPolicy` off MEASURED_BASE and
 * `theContractIsAMeasuredBaseParameter` fails; drop `minimumIndependentDays`
 * back to the generic default and `theSupportGatesAreDayBased` fails.
 */
class ZeroedBackgroundCoefficientsContractV1Test {

    private val spec = ZeroedBackgroundCoefficientsContractV1.spec

    @Test
    fun theContractIsAMeasuredBaseParameter() {
        assertEquals(ApplicationPolicyV1.MEASURED_BASE, spec.applicationPolicy)
        assertEquals(ParameterTargetV1.BACKGROUND_RATE, spec.parameterTarget)
        assertEquals(EstimatorFamilyV1.BACKGROUND, spec.estimatorFamily)
        assertEquals(ImplementationStatusV1.EXECUTABLE, spec.implementationStatus)
    }

    /** The day-state confound is the danger, so the gate is days, not rows. */
    @Test
    fun theSupportGatesAreDayBased() {
        assertEquals(100, spec.minimumIndependentEpisodes)
        assertEquals(20, spec.minimumIndependentDays)
    }

    /**
     * A CLIFF, NOT A FADE. Three half-lives is what `demoteIfStale` uses, so ten
     * days here means the pair is dropped whole after thirty days without a
     * refit — rather than sliding back toward the known-wrong zeroed state.
     */
    @Test
    fun stalenessDropsThePairWholeAfterThirtyDays() {
        assertEquals(10.0, spec.decayHalfLifeDays, 0.0)
        assertEquals(30.0, spec.decayHalfLifeDays * 3.0, 0.0)
        // Ramp is inert: a partly applied collinear pair is a different and
        // worse correction than either endpoint, not a smaller one.
        assertEquals(1, spec.rampInDays)
    }

    /** It is NOT one of the 27 registry rows — like the other two base
     *  contracts, which is exactly why its exposure predicate is unreachable. */
    @Test
    fun itIsNotARegistryModifierRow() {
        assertTrue(
            PhysioHypothesisRegistryV1.definitions.none {
                it.id == ZeroedBackgroundCoefficientsContractV1.ID
            },
        )
        assertEquals(27, PhysioHypothesisRegistryV1.definitions.size)
    }
}

/**
 * The prospective gate, the evidence hash and the loud cliff.
 *
 * These exist because the segment gates (100/20) are passed by the very data the
 * pair was fitted on — the same ring the SSE table had, only here it would
 * govern APPLICATION. And because `rampInDays = 1` gives up «a bad effect acts
 * weakly while the ledger watches», for which a full-strength shadow arm before
 * promotion is the substitute.
 *
 * Mutation acceptance: replace `pair.evidenceIdentity` in
 * `sourceEvidenceIdentity` with a constant and `theHashCarriesTheSegmentSet`
 * fails; drop the size check in `prospectiveVerdict` and
 * `aPredictionUntestedOnFutureDaysIsInsufficient` fails; widen either band and
 * the band tests fail.
 */
class ZeroedBackgroundProspectiveGateTest {

    private val C = ZeroedBackgroundCoefficientsContractV1

    private fun days(value: Double, n: Int) = List(n) { value }

    @Test
    fun aPredictionUntestedOnFutureDaysIsInsufficient() {
        assertEquals(
            ZeroedBackgroundCoefficientsContractV1.ProspectiveVerdictV1.INSUFFICIENT,
            C.prospectiveVerdict(days(0.08, C.MINIMUM_PROSPECTIVE_DAYS - 1)),
        )
        assertEquals(
            ZeroedBackgroundCoefficientsContractV1.ProspectiveVerdictV1.CONFIRMED,
            C.prospectiveVerdict(days(0.08, C.MINIMUM_PROSPECTIVE_DAYS)),
        )
    }

    /** Over-correction is a FALSIFICATION, declared in advance, not «almost». */
    @Test
    fun overCorrectionIsFalsificationNotSuccess() {
        assertEquals(
            ZeroedBackgroundCoefficientsContractV1.ProspectiveVerdictV1.OVERCORRECTED,
            C.prospectiveVerdict(days(C.QUIET_OVERCORRECTION_BAND + 0.01, 20)),
        )
    }

    @Test
    fun underCorrectionMeansTheMechanismIsNotTheOneClaimed() {
        assertEquals(
            ZeroedBackgroundCoefficientsContractV1.ProspectiveVerdictV1.UNDERCORRECTED,
            C.prospectiveVerdict(days(C.QUIET_UNDERCORRECTION_BAND - 0.01, 20)),
        )
    }

    /** The pre-registered numbers are pinned so a later reading cannot soften
     *  them; the arm predicted these BEFORE it was built. */
    @Test
    fun thePreRegisteredNumbersArePinned() {
        assertEquals(0.082, C.PREDICTED_QUIET_DIFFERENTIAL, 1e-12)
        assertEquals(-0.790, C.PREDICTED_FOOD_DIFFERENTIAL, 1e-12)
        assertEquals(0.535, C.PREDICTED_INSULIN_DIFFERENTIAL, 1e-12)
        assertEquals(14, C.MINIMUM_PROSPECTIVE_DAYS)
        assertEquals(20, C.MINIMUM_PAIRED_ANCHORS_PER_DAY)
    }

    /** THE HASH MUST CARRY THE SEGMENT SET, not only the fitted numbers —
     *  otherwise a refit looks unchanged and the support ages out unseen. */
    @Test
    fun theHashCarriesTheSegmentSet() {
        val a = BackgroundCoefficientsV1(-0.4445, -0.01935, "segments-through-week-1")
        val b = BackgroundCoefficientsV1(-0.4445, -0.01935, "segments-through-week-3")
        assertNotEquals(
            "a refit on new segments must be NEW evidence",
            C.sourceEvidenceIdentity(a), C.sourceEvidenceIdentity(b),
        )
        val c = BackgroundCoefficientsV1(-0.5000, -0.01935, "segments-through-week-1")
        assertNotEquals(C.sourceEvidenceIdentity(a), C.sourceEvidenceIdentity(c))
        assertEquals(C.sourceEvidenceIdentity(a), C.sourceEvidenceIdentity(a.copy()))
    }

    /** The cliff is loud: it names what it did and why that is a regression. */
    @Test
    fun theStaleCliffNamesItsOwnRegression() {
        assertTrue(C.STALE_CLIFF_REASON.contains("dropped whole"))
        assertTrue(C.STALE_CLIFF_REASON.contains("zeroed"))
    }
}
