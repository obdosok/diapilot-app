package com.diapilot.core.physio

import com.diapilot.core.hybrid.HybridForecastState
import com.diapilot.core.hybrid.HybridGlucosePoint
import com.diapilot.core.hybrid.HybridForecastPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysioContractsTest {
    @Test fun everyRetainedRegistryRowHasExecutableTypedLearningContract() {
        val specs=PhysioHypothesisRegistryV1.definitions.map{it.executableSpec}
        assertEquals(PhysioHypothesisRegistryV1.definitions.size,specs.size)
        assertTrue(specs.all{it.implementationStatus==ImplementationStatusV1.EXECUTABLE})
        assertEquals(1,specs.count{it.applicationPolicy==ApplicationPolicyV1.ATTRIBUTION_ONLY})
        assertEquals(ParameterTargetV1.EFFECTIVE_RESPONSE_ONLY,specs.single{it.applicationPolicy==ApplicationPolicyV1.ATTRIBUTION_ONLY}.parameterTarget)
        assertTrue(specs.filter{it.applicationPolicy==ApplicationPolicyV1.MEDIAN}.all{it.implementationStatus==ImplementationStatusV1.EXECUTABLE})
        assertTrue(specs.map{it.estimatorFamily}.containsAll(EstimatorFamilyV1.entries))
    }

    // everyRegistryDefinitionEstimatesItsOwnPhysicalUnitFromSyntheticOutcomes
    // USED TO STAND HERE.
    //
    // It ran `estimateTypedPosteriorV1` over synthetic observations and
    // checked that every registry row estimates its own physical quantity.
    // The estimator was removed: it was not reachable from the app by any
    // path, and this test was its only reader. A green test on unreachable
    // code is not coverage but its imitation; removed along with it.
    //
    // The remaining registry checks below touch LIVE code and stay.

    @Test fun typedPayloadCannotBeAppliedToWrongPhysiologicalParameter() {
        val d=PhysioHypothesisRegistryV1.definitions.first{it.id=="activity_same_isf"}
        assertThrows(IllegalArgumentException::class.java){
            AppliedModifierV1(d.id,EffectPayloadV1.Minutes(ParameterTargetV1.INSULIN_PEAK,10.0,5.0,15.0),1,1,"hash")
        }
        assertThrows(IllegalArgumentException::class.java){
            EffectPayloadV1.Minutes(ParameterTargetV1.ISF_MULTIPLIER,10.0,5.0,15.0)
        }
    }

    @Test fun promotionLifecycleRequiresProspectiveLossThenRampsAndDemotes() {
        val d=PhysioHypothesisRegistryV1.definitions.first()
        val e=FactorEvidenceV1(d.factor,d.targetParameter,PosteriorV1(20.0,10.0,30.0,12,7,100),EvidenceStatus.SUPPORTED,"prequential",emptyList(),
            rawExposures=12,usableEpisodes=12,minimumPracticalEffectPercent=d.minimumPracticalEffectPercent,dataSource=d.id,
            permutationP=.01,stableAcrossFolds=true,heldOutDirectionalStable=true,evidenceProvenance=EvidenceProvenanceV1.PROSPECTIVE_CHECKPOINT,prospectiveEpisodes=12)
        val shadow=PromotionLifecycleV1.next(d.executableSpec,e,null,null,100)
        assertEquals(PromotionStageV1.SHADOW_CANDIDATE,shadow.stage)
        val stable=PromotionLifecycleV1.next(d.executableSpec,e,shadow,1.0,200)
        assertEquals(PromotionStageV1.PROSPECTIVE_STABLE,stable.stage)
        val promoted=PromotionLifecycleV1.next(d.executableSpec,e,stable,1.0,300)
        assertEquals(PromotionStageV1.PROMOTED_MEDIAN,promoted.stage)
        assertTrue(promoted.boundedEffectPercent in 0.0..d.executableSpec.maxMedianEffectPercent)
        val conflict=PromotionLifecycleV1.next(d.executableSpec,e.copy(status=EvidenceStatus.CONFLICTING),promoted,1.0,400)
        assertEquals(PromotionStageV1.DEMOTED,conflict.stage)
        assertEquals(0.0,conflict.boundedEffectPercent,0.0)
    }

    /** The ordinary modifier contracts keep the mandatory shadow cycle. */
    @Test fun aModifierHypothesisStillOwesTheShadowCycle() {
        val d = PhysioHypothesisRegistryV1.definitions.first { it.executableSpec.applicationPolicy == ApplicationPolicyV1.MEDIAN }
        val e = FactorEvidenceV1(
            d.factor, d.targetParameter, PosteriorV1(20.0, 10.0, 30.0, 12, 7, 100),
            EvidenceStatus.SUPPORTED, "prequential", emptyList(),
            rawExposures = 12, usableEpisodes = 12,
            minimumPracticalEffectPercent = d.minimumPracticalEffectPercent, dataSource = d.id,
            permutationP = .01, stableAcrossFolds = true, heldOutDirectionalStable = true,
            evidenceProvenance = EvidenceProvenanceV1.PROSPECTIVE_CHECKPOINT, prospectiveEpisodes = 12,
        )
        assertEquals(
            PromotionStageV1.SHADOW_CANDIDATE,
            PromotionLifecycleV1.next(d.executableSpec, e, null, null, 100).stage,
        )
    }

    @Test fun selectionCannotChangeCausalInputHash() {
        val snapshot = CausalSnapshotV1(HybridForecastState(1_000, listOf(HybridGlucosePoint(1_000, 6.0))), listOf("carb:1:2"))
        val legacy = ParallelForecastLedgerRowV1("r", 1_000, "legacy", "v11", snapshot.canonicalHash(), ForecastArmSelection.COMPARE, 1_001)
        val physio = legacy.copy(engine = "physio", artifactId = "p1")
        assertEquals(legacy.inputHash, physio.inputHash)
        assertNotEquals(legacy.engine, physio.engine)
    }

    @Test fun weakFactorCannotAffectMedianOrClaimSupport() {
        val weak = PosteriorV1(5.0, -2.0, 12.0, 2, 2, null)
        assertThrows(IllegalArgumentException::class.java) {
            FactorEvidenceV1("activity", "ISF", weak, EvidenceStatus.SUPPORTED, "7d", emptyList(),
                rawExposures = 2, usableEpisodes = 2, forecastUse = ForecastUse.MEDIAN,
                permutationP = .01, stableAcrossFolds = true, heldOutDirectionalStable = true,
                evidenceProvenance = EvidenceProvenanceV1.PROSPECTIVE_CHECKPOINT, prospectiveEpisodes = 5)
        }
        FactorEvidenceV1("activity", "ISF", weak, EvidenceStatus.INSUFFICIENT, "7d", emptyList())
    }

    @Test fun completeRegistryRemainsVisibleWithEmptyAndPartialData() {
        val empty = PhysioHypothesisRegistryV1.emptyMatrix()
        assertEquals(PhysioHypothesisRegistryV1.definitions.size, empty.size)
        assertEquals(setOf(EvidenceStatus.SOURCE_MISSING), empty.map { it.status }.toSet())
        val partial = PhysioHypothesisRegistryV1.emptyMatrix(setOf("activity_same_isf"))
        assertEquals(EvidenceStatus.NO_EXPOSURES, partial.first().status)
        assertEquals(EvidenceStatus.SOURCE_MISSING, partial[1].status)
        assertEquals(0, partial.count { it.forecastUse == ForecastUse.MEDIAN })
    }

    @Test fun uncertaintyOnlyAndNullCompatibleRemainDistinctFromMedianSupport() {
        val unidentified = FactorEvidenceV1(
            "sleep", "ISF", PosteriorV1(0.0, -30.0, 30.0, 0, 0, null),
            EvidenceStatus.NOT_IDENTIFIABLE, "30d", listOf("meal overlap"),
            rawExposures = 8, usableEpisodes = 0, forecastUse = ForecastUse.UNCERTAINTY_ONLY,
        )
        assertEquals(ForecastUse.UNCERTAINTY_ONLY, unidentified.forecastUse)
        val nullCompatible = FactorEvidenceV1(
            "time", "onset", PosteriorV1(0.5, -2.0, 3.0, 10, 6, 1_000),
            EvidenceStatus.NULL_COMPATIBLE, "30d", emptyList(), rawExposures = 20,
            usableEpisodes = 10, minimumPracticalEffectPercent = 5.0,
        )
        assertEquals(ForecastUse.NOT_USED, nullCompatible.forecastUse)
    }

    @Test fun everyVarianceChannelWidensWithoutChangingMedianAndIsCountedOnce() {
        val point = HybridForecastPoint(
            minutes = 120, tsMs = 1, baseline = 7.0, scenario = 7.0,
            low = 7.0, high = 7.0, foodDelta = 2.0,
            insulinActualDelta = -1.5, insulinScenarioDelta = -0.5,
            residualDrift = .2, backgroundDelta = .4, activityDirectDelta = 0.0,
        )
        val channels = listOf(
            VarianceLedgerV1(.2,0.0,0.0,0.0,0.0,0.0),
            VarianceLedgerV1(0.0,.2,0.0,0.0,0.0,0.0),
            VarianceLedgerV1(0.0,0.0,.2,0.0,0.0,0.0),
            VarianceLedgerV1(0.0,0.0,0.0,.2,0.0,0.0),
            VarianceLedgerV1(0.0,0.0,0.0,0.0,.2,0.0),
            VarianceLedgerV1(0.0,0.0,0.0,0.0,0.0,.2),
        )
        val widths = channels.map { intervalContributionsV1(point, it).halfWidth() }
        assertTrue(widths.all { it > 0.0 })
        val combined = intervalContributionsV1(point, VarianceLedgerV1(.2,.2,.2,.2,.2,.2)).halfWidth()
        assertEquals(kotlin.math.sqrt(widths.sumOf { it * it }), combined, 1e-12)
        assertEquals(7.0, point.scenario, 0.0)
    }

    @Test fun promotedContextWidthIsConditionalAndAbsentExposureIsNoOp() {
        val d=PhysioHypothesisRegistryV1.definitions.first{it.id=="activity_same_isf"}
        val modifier=AppliedModifierV1(d.id,EffectPayloadV1.Fraction(ParameterTargetV1.ISF_MULTIPLIER,.1,.02,.22),1,1,"eval")
        val base=VarianceLedgerV1(.1,.1,.1,.1,.1,.1)
        assertEquals(base,conditionalVarianceV1(base,listOf(modifier),emptySet()))
        val active=conditionalVarianceV1(base,listOf(modifier),setOf(d.id))
        assertTrue(active.insulinTiming>base.insulinTiming)
        assertEquals(base.carbAmount,active.carbAmount,0.0)
        assertEquals(base.foodTiming,active.foodTiming,0.0)
        assertEquals(base.backgroundHepatic,active.backgroundHepatic,0.0)
    }
}
