package com.example.diapilot.data

import com.diapilot.core.hybrid.HybridPersonModel
import com.diapilot.core.physio.PersonalInsulinCurveV1
import com.diapilot.core.physio.PhysioBoundsV1

/**
 * Installs a measured timing curve into the person model.
 *
 * All this object still does. The receipt-CDF reader that used to live here —
 * `state`, `curve`, `parse`, `support`, `explain` and their weights — had zero
 * production callers: `InsulinProfileRuntime` reads doses directly
 * and per segment. It was kept alive only by a settings preview that drew a
 * different curve beside the real one, which is how a dead path stays warm.
 */
object InsulinCurveRuntime {
    /** Tag recorded on the person model so a stored forecast says which curve
     * produced it. */
    const val VERSION = "segment-insulin-profile-v1"

    /** The model with the curve installed, or the model unchanged together with
     * the reason — never a silent pass-through. */
    data class Applied(val model: HybridPersonModel, val refusal: String? = null)

    fun applyWithReason(person:HybridPersonModel,curve:PersonalInsulinCurveV1?):Applied {
        if(curve==null)return Applied(person)
        if(!curve.ready)return Applied(person,"наблюдений ${curve.observations}/${PersonalInsulinCurveV1.MIN_SHAPE_OBSERVATIONS}, дней ${curve.independentDays}/${PersonalInsulinCurveV1.MIN_SHAPE_DAYS}")
        val knots=curve.knots
        // ONE landmark convention: they travel ON the curve. Deriving them
        // here independently is how a curve could pass one bounds check and be
        // refused by another, 2.5 minutes later, in silence.
        val landmarks=curve.landmarks
        val onset=landmarks.onsetMin
        val peak=landmarks.singlePeakMin
        val tail=landmarks.tailMin
        val bounds=PhysioBoundsV1()
        // Still a defensive boundary for persisted/old receipts: fail closed
        // rather than throw and collapse the selected PHYSIO arm — but say so.
        val broken=when{
            knots.size<4->"узлов ${knots.size}, нужно ≥4"
            knots.firstOrNull()?.let{it.minute==0.0&&it.fraction==0.0}!=true->"кривая не начинается с нуля"
            knots.lastOrNull()?.fraction!=1.0->"кривая не заканчивается на 1.0"
            knots.zipWithNext().any{(a,b)->b.minute<=a.minute||b.fraction<a.fraction}->"кривая не монотонна"
            onset !in bounds.insulinOnsetMinRange->"старт %.0f вне %.0f..%.0f".format(onset,bounds.insulinOnsetMinRange.start,bounds.insulinOnsetMinRange.endInclusive)
            peak !in bounds.insulinPeakMinRange->"пик %.0f вне %.0f..%.0f".format(peak,bounds.insulinPeakMinRange.start,bounds.insulinPeakMinRange.endInclusive)
            tail !in bounds.insulinTailMinRange->"хвост %.0f вне %.0f..%.0f".format(tail,bounds.insulinTailMinRange.start,bounds.insulinTailMinRange.endInclusive)
            peak>=tail->"пик не раньше хвоста"
            else->null
        }
        if(broken!=null)return Applied(person,broken)
        return Applied(person.copy(
            modelVersion="${person.modelVersion}+$VERSION:${curve.observations}/${curve.independentDays}",
            insulin=person.insulin.copy(onsetMin=onset,peakMin=peak,shortDurationMin=tail,tailDurationMin=tail,actionCdfKnots=knots),
        ))
    }

    fun apply(person:HybridPersonModel,curve:PersonalInsulinCurveV1?):HybridPersonModel =
        applyWithReason(person,curve).model
}
