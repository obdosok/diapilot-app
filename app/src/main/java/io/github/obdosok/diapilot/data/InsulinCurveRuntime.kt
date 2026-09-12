package io.github.obdosok.diapilot.data

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
        // The refusal is developer text: PhysioRuntime logs it as a model conflict.
        if(!curve.ready)return Applied(person,"observations ${curve.observations}/${PersonalInsulinCurveV1.MIN_SHAPE_OBSERVATIONS}, days ${curve.independentDays}/${PersonalInsulinCurveV1.MIN_SHAPE_DAYS}")
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
        //
        // The tail is checked against `insulinTailMinRange`, the INSTRUMENT's
        // domain, and that is correct here: the only curve that reaches this
        // function is a measured one (`InsulinProfileRuntime`), which has
        // already been through `coerceIntoDomain`. A hand entry never arrives
        // here — it is applied afterwards, in `ManualInsulinRuntime.apply`,
        // against its own wider domain. And this check REFUSES with a named
        // reason rather than clamping, which is what the measured-to-applied
        // promotion owes the user either way.
        val broken=when{
            knots.size<4->"knots ${knots.size}, need ≥4"
            knots.firstOrNull()?.let{it.minute==0.0&&it.fraction==0.0}!=true->"curve does not start at zero"
            knots.lastOrNull()?.fraction!=1.0->"curve does not end at 1.0"
            knots.zipWithNext().any{(a,b)->b.minute<=a.minute||b.fraction<a.fraction}->"curve is not monotonic"
            onset !in bounds.insulinOnsetMinRange->"onset %.0f outside %.0f..%.0f".format(onset,bounds.insulinOnsetMinRange.start,bounds.insulinOnsetMinRange.endInclusive)
            peak !in bounds.insulinPeakMinRange->"peak %.0f outside %.0f..%.0f".format(peak,bounds.insulinPeakMinRange.start,bounds.insulinPeakMinRange.endInclusive)
            tail !in bounds.insulinTailMinRange->"tail %.0f outside %.0f..%.0f".format(tail,bounds.insulinTailMinRange.start,bounds.insulinTailMinRange.endInclusive)
            peak>=tail->"peak is not before tail"
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
