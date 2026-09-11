/**
 * What-if superposition math — the arithmetic behind the in-app "what if" simulator.
 *
 * This is NOT a new model: the insulin kernel is the learned impulse response of
 * a unit dose at τ=0 (see [com.diapilot.core.analysis.buildKernel]). Everything
 * here is a consequence of that curve being an impulse response —
 *
 *  - a dose delayed by `offsetMin` is the SAME response with its argument
 *    shifted: G(τ − offset), zero before the dose fires;
 *  - the model is linear in units, so several doses SUPERPOSE (add), and two
 *    coincident doses of X are indistinguishable from one dose of 2X.
 *
 * Kept out of `core/analysis` and `core/twin` on purpose: it changes nothing the
 * forecast or the alerts compute — it only lets the UI draw a hypothetical the
 * user proposes. The app never solves for a dose (hard rule: never dosing
 * advice); the user drags, this superposes, the chart shows the consequence.
 */
package com.diapilot.core.whatif

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.kernelAt

/**
 * One hypothetical insulin dose in the What-if simulator: [units] injected
 * [offsetMin] minutes AFTER the forecast anchor (offset 0 = "now").
 */
data class WhatIfDose(val units: Double, val offsetMin: Double)

/**
 * Glucose delta at τ minutes past the anchor from a set of hypothetical doses,
 * by superposition of the learned kernel.
 *
 * For each dose: shift the impulse response by the dose's offset and evaluate at
 * τ, scaled by the live forecast's autosens ([scale]) so the simulator and the
 * real forecast agree on today's sensitivity. A dose contributes nothing until
 * it fires (τ ≤ offset), and `kernelAt` returns null past the kernel's horizon
 * (treated as 0).
 *
 * Properties, pinned by [com.diapilot.core.whatif.WhatIfTest]:
 *  - offset 0 reproduces the single-dose formula exactly (τ > 0 gate);
 *  - offset N equals the offset-0 curve shifted right by N;
 *  - doses superpose, so two X at the same offset equal one 2X.
 */
fun insulinDeltaAt(
    kernel: List<KernelPoint>,
    tauMin: Double,
    doses: List<WhatIfDose>,
    scale: Double,
): Double {
    var delta = 0.0
    for (dose in doses) {
        if (dose.units <= 0.0) continue
        val rel = tauMin - dose.offsetMin
        // Zero while the dose has not fired. rel > 0 (not ≥) matches the live
        // forecast's τ>0 gate, so an offset-0 dose is byte-identical to the
        // pre-existing single-dose What-if line.
        if (rel <= 0.0) continue
        delta += dose.units * (kernelAt(kernel, rel) ?: 0.0) * scale
    }
    return delta
}
