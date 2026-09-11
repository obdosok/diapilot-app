package com.diapilot.core.physio

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.twin.HypoAction
import com.diapilot.core.twin.HypoAlertInputs
import com.diapilot.core.twin.HypoAlertState
import com.diapilot.core.twin.decideHypoAlert

/** One executable implementation for Android receipts, tests and laptop evaluator. */
object Stage9SharedCoreV1 {
    fun episodeKernel(model:VersionedEpisodeKernelModelV1,timestampMs:Long,context:List<EpisodeContextFactV1>,asOfMs:Long,doseU:Double?)=
        model.kernelForEpisode(timestampMs,context,asOfMs,doseU)
    /** [appearance] is required: see [com.diapilot.core.hybrid.CarbAppearancePolicyV1].
     *  Stage9 is what the model LEARNS from, so it must decompose a meal by the
     *  same rules the forecast draws it with. */
    fun jointReceipt(notes:List<Annotation>,readings:List<GlucosePoint>,boluses:List<BolusPoint>,globalCs:PosteriorV1,asOfMs:Long,
        kernelForBolus:(BolusPoint)->EpisodeKernelResultV1,
        appearance:com.diapilot.core.hybrid.CarbAppearancePolicyV1,
        config:JointAttributionConfigV1=JointAttributionConfigV1())=
        jointMealAttributionV1(notes,readings,boluses,globalCs,asOfMs,kernelForBolus,config,appearance)
    /** Computed through production alert state machine; separate from predictive replay. */
    fun currentLowAction(tsMs:Long,mmol:Double,threshold:Double=3.9):HypoAction = decideHypoAlert(
        HypoAlertState(),HypoAlertInputs(tsMs,tsMs,mmol,threshold,predictedHit=false,night=false,nightGentle=false),
    ).action
}
