package com.diapilot.core.physio

/**
 * THE END OF INSULIN ACTION, READ FROM THE FALL INSTEAD OF FROM THE RATE.
 *
 * Every landmark in [SegmentLandmarkReaderV1] is read off the RATE, and M-55
 * showed why that cannot find the tail: a tail removes glucose slowly and
 * steadily, so the rate is already flat while the fall is still accumulating.
 * Both rate definitions tried — return to the pre-dose slope, and the
 * background-free plateau — landed within five minutes of each other at ~140,
 * while the accumulated fall keeps growing until 240-300 (M-53).
 *
 * So the tail is measured here from the quantity that still moves: the total
 * drop per unit, read at growing horizons. Where it stops growing, insulin has
 * stopped working. That yields the DURATION and the AMPLITUDE from one reading,
 * which is a property of the method rather than a coincidence — the plateau
 * level IS the ISF.
 *
 * The corpus is thin by construction and that is not a flaw to be relaxed away:
 * only doses with no other insulin and no food anywhere inside the horizon can
 * say anything about one dose's own action. On a typical user's record that is a handful.
 *
 * TWO REQUIREMENTS ON THE CALLER, both learned by getting them wrong (M-61):
 *
 *  1. **Gate insulin in BOTH directions**, seven hours before as well as after.
 *     The first caller checked only forward, and every dose it admitted had a
 *     LARGER dose 158-200 minutes earlier. That is circular — under the
 *     incumbent short tail those look finished, so a corpus assembled to test a
 *     LONG tail was gated by the short one it was trying to replace. With the
 *     gate closed on the record used to test it, the corpus came back EMPTY.
 *  2. **Subtract a matched no-insulin control.** Glucose falls ~1.5 mmol
 *     over four hours overnight with no bolus at all, monotonically — the same
 *     shape this method reads as «still working». Without a placebo the reading
 *     attributes the night to the injection. Match it on starting glucose:
 *     renal spillage makes the background level-dependent, so a control taken at
 *     5 mmol understates the background at 11.
 *
 * Neither belongs inside this object — it sees only falls per unit and cannot
 * know what produced them — which is exactly why they are stated here.
 */
object InsulinTailFromAmplitudeV1 {

    /** A dose with nothing else acting, and the glucose at each horizon. */
    data class CleanDose(val tsMs: Long, val units: Double, val fallByHorizon: Map<Int, Double>)

    data class TailReadingV1(
        /** Where the fall stops growing, in minutes. Null when it never settles. */
        val endMin: Double?,
        /** Fall per unit at the plateau — the ISF the same reading gives. */
        val isfAtPlateau: Double?,
        val doses: Int,
        /** Every horizon's median fall per unit, so the plateau can be seen. */
        val curve: List<Pair<Int, Double>>,
        val refusal: TailRefusal? = null,
    )

    /** Why no tail was read; the app renders the sentence. */
    enum class TailRefusal {
        /** Fewer than three clean doses. */
        NOT_ENOUGH_DOSES,
        /** The fall grows to the end of the horizon: the tail is longer than the observations. */
        NEVER_SETTLES,
    }

    val NOT_ENOUGH_DOSES = TailRefusal.NOT_ENOUGH_DOSES
    val NEVER_SETTLES = TailRefusal.NEVER_SETTLES

    /**
     * A horizon counts as the end when the fall stops growing MATERIALLY: the
     * gain over the next step falls under [SETTLE_SHARE] of the total climb so
     * far, and stays under it. A fixed mmol threshold would call a big responder
     * settled and a small one still working.
     */
    const val SETTLE_SHARE = 0.05

    fun read(doses: List<CleanDose>, horizons: List<Int>): TailReadingV1 {
        if (doses.size < 3) return TailReadingV1(null, null, doses.size, emptyList(), NOT_ENOUGH_DOSES)
        val curve = horizons.sorted().mapNotNull { h ->
            val v = doses.mapNotNull { d -> d.fallByHorizon[h]?.let { it / d.units } }
            if (v.size < 3) null else h to median(v)
        }
        if (curve.size < 3) return TailReadingV1(null, null, doses.size, curve, NOT_ENOUGH_DOSES)
        val total = curve.last().second - curve.first().second
        if (total <= 0.0) return TailReadingV1(null, null, doses.size, curve, NEVER_SETTLES)
        val step = total * SETTLE_SHARE
        val settled = curve.zipWithNext().firstOrNull { (a, b) ->
            b.second - a.second <= step &&
                // and it must STAY settled: one flat step inside a still-rising
                // curve is noise, not a plateau.
                //
                // The tail is taken FROM b inclusive, not from the horizon after
                // it: filtering on `> b.first` skipped the b -> next step, so one
                // step in the middle of the curve was checked by neither clause.
                // Latent when found — on the fitted data the answer did not
                // move — but a settle rule with a hole in it is not a rule.
                curve.dropWhile { it.first < b.first }
                    .zipWithNext().all { (c, d) -> d.second - c.second <= step * 1.5 }
        }?.first
        return if (settled == null) {
            TailReadingV1(null, null, doses.size, curve, NEVER_SETTLES)
        } else {
            TailReadingV1(
                endMin = settled.first.toDouble(),
                // The plateau LEVEL, not the level at the settling horizon: the
                // last horizons are the same quantity measured longer, so their
                // median is the steadier estimate of the same number.
                isfAtPlateau = median(curve.filter { it.first >= settled.first }.map { it.second }),
                doses = doses.size,
                curve = curve,
            )
        }
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
