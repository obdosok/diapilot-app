/**
 * Local insulin-on-board: sum of the remaining fraction of every recent
 * bolus. xDrip's IOB stopped being usable the day doses started arriving
 * from the pen over NFC — xDrip never sees them, so DiaPilot computes IOB
 * itself over its own store (pen + watch + manual + synced history).
 *
 * Model: the standard exponential insulin-activity curve (OpenAPS/AAPS),
 * parameterized by total duration (DIA) and time-to-peak. Defaults fit a
 * rapid-acting analog (NovoRapid): DIA 5 h, peak ~75 min.
 *
 * Display/analytics only — never a dose recommendation.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import kotlin.math.exp

const val DIA_MIN = 300.0
const val PEAK_MIN = 75.0

/** Fraction of a bolus still active [1..0] at ageMin minutes after injection. */
fun iobFraction(ageMin: Double, diaMin: Double = DIA_MIN, peakMin: Double = PEAK_MIN): Double {
    if (ageMin <= 0) return 1.0
    if (ageMin >= diaMin) return 0.0
    val tau = peakMin * (1 - peakMin / diaMin) / (1 - 2 * peakMin / diaMin)
    val a = 2 * tau / diaMin
    val s = 1 / (1 - a + (1 + a) * exp(-diaMin / tau))
    return (
        1 - s * (1 - a) *
            ((ageMin * ageMin / (tau * diaMin * (1 - a)) - ageMin / tau - 1) * exp(-ageMin / tau) + 1)
        ).coerceIn(0.0, 1.0)
}

/**
 * IOB in units at nowMs. Pass body-effective boluses only (store.boluses()
 * already excludes air shots); basal never belongs here.
 */
fun iobUnits(
    boluses: List<BolusPoint>,
    nowMs: Long,
    diaMin: Double = DIA_MIN,
    peakMin: Double = PEAK_MIN,
): Double = boluses.sumOf { b ->
    val ageMin = (nowMs - b.tsMs) / 60_000.0
    if (ageMin < 0) 0.0 else b.units * iobFraction(ageMin, diaMin, peakMin)
}

/**
 * The same fraction, but from THIS BODY's learned kernel instead of the
 * textbook curve.
 *
 * The kernel is the cumulative BG change per unit, measured on this person's
 * own clean corrections — so the share of a dose already spent at τ is
 * g(τ)/plateau, and what is left is 1 − that. No DIA, no peak: the shape is
 * whatever the episodes said it was.
 *
 * Null when the kernel has not learned a usable drop yet — the caller then
 * keeps [iobFraction]. This is display/analytics only; the forecast reads the
 * kernel directly and never goes through IOB.
 */
fun kernelIobFraction(kernel: List<KernelPoint>, ageMin: Double): Double? {
    if (kernel.isEmpty()) return null
    // Median is NEGATIVE (insulin lowers BG); full effect = the deepest point.
    val plateau = kernel.minOf { it.median }
    if (plateau > -0.05) return null
    if (ageMin <= 0.0) return 1.0
    // SPENT IS MONOTONE — insulin cannot un-act.
    //
    // The kernel's tail is noisy: it reaches -1.70 at 125 min, then rebounds to
    // -1.34 by 200 (n halves out there, 228 → 113, and three hours after a
    // correction people eat — the rebound is contamination, not pharmacology).
    // Reading the CURRENT point against the deepest one made IOB climb back —
    // 7% at 160 min, 21% at 200 — and settle on 13% of every bolus, on board
    // forever. That was the endless flat line on the chart.
    //
    // The deepest drop SO FAR is what has actually been spent; the rebound is
    // someone else's glucose, not the dose coming back.
    val deepestSoFar = kernel.filter { it.tauMin <= ageMin }.minOfOrNull { it.median } ?: 0.0
    val spent = (deepestSoFar / plateau).coerceIn(0.0, 1.0)
    return 1.0 - spent
}

/** IOB in units at [nowMs] from the learned kernel; null when it can't say. */
fun kernelIobUnits(
    boluses: List<BolusPoint>,
    nowMs: Long,
    kernel: List<KernelPoint>,
): Double? {
    if (kernelIobFraction(kernel, 1.0) == null) return null
    return boluses.sumOf { b ->
        val ageMin = (nowMs - b.tsMs) / 60_000.0
        if (ageMin < 0) 0.0 else b.units * (kernelIobFraction(kernel, ageMin) ?: 0.0)
    }
}
