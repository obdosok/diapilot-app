package com.diapilot.core.whatif

import com.diapilot.core.analysis.KernelPoint
import com.diapilot.core.analysis.kernelAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The What-if arithmetic is UI over an existing model, so it is pinned by
 * ARITHMETIC, not medicine (per the task): the shifted-dose and superposition
 * identities, and the "before it fires it does nothing" guard.
 *
 * Each identity is checked against the SAME formula the live forecast uses
 * inline (`units * kernelAt(kernel, τ) * scale`), so a regression in the helper
 * cannot hide behind a re-derivation that shares its bug.
 */
class WhatIfTest {
    // A stand-in kernel with a non-trivial shape: a small POSITIVE head (like
    // the real curve's absorption-lag bins), then deepens and plateaus. The head
    // being non-zero is deliberate — it makes the τ>0 fire guard OBSERVABLE, so
    // the "before it fires" test actually pins it under mutation. The exact
    // numbers do not matter, only that the identities hold on a varying curve.
    private val kernel = listOf(
        KernelPoint(tauMin = 0.0, median = 0.03, q1 = 0.0, q3 = 0.0, n = 5),
        KernelPoint(tauMin = 5.0, median = -0.02, q1 = 0.0, q3 = 0.0, n = 5),
        KernelPoint(tauMin = 30.0, median = -0.40, q1 = 0.0, q3 = 0.0, n = 5),
        KernelPoint(tauMin = 60.0, median = -0.90, q1 = 0.0, q3 = 0.0, n = 5),
        KernelPoint(tauMin = 120.0, median = -1.50, q1 = 0.0, q3 = 0.0, n = 5),
        KernelPoint(tauMin = 180.0, median = -1.60, q1 = 0.0, q3 = 0.0, n = 5),
    )
    private val scale = 1.3
    private val taus = (0..240 step 5).map { it.toDouble() }

    /** The exact expression the live forecast evaluates for a single dose at now. */
    private fun liveSingleDose(units: Double, tau: Double): Double =
        if (tau > 0) units * (kernelAt(kernel, tau) ?: 0.0) * scale else 0.0

    @Test
    fun `offset zero reproduces the live single-dose line exactly`() {
        val u = 4.0
        for (tau in taus) {
            assertEquals(
                "τ=$tau: offset-0 dose must equal the current What-if formula",
                liveSingleDose(u, tau),
                insulinDeltaAt(kernel, tau, listOf(WhatIfDose(u, 0.0)), scale),
                1e-12,
            )
        }
    }

    @Test
    fun `a dose offset by N equals the now-curve shifted right by N`() {
        val u = 3.5
        val offset = 45.0
        for (tau in taus) {
            // The shifted dose at τ is the offset-0 response evaluated at τ−N.
            assertEquals(
                "τ=$tau: an N-min-delayed dose is the now-curve shifted by N",
                liveSingleDose(u, tau - offset),
                insulinDeltaAt(kernel, tau, listOf(WhatIfDose(u, offset)), scale),
                1e-12,
            )
        }
    }

    @Test
    fun `two equal doses at the same offset equal one double dose`() {
        val x = 2.0
        val offset = 20.0
        for (tau in taus) {
            assertEquals(
                "τ=$tau: superposition — 2×(X at o) == (2X at o)",
                insulinDeltaAt(kernel, tau, listOf(WhatIfDose(2 * x, offset)), scale),
                insulinDeltaAt(
                    kernel, tau,
                    listOf(WhatIfDose(x, offset), WhatIfDose(x, offset)), scale,
                ),
                1e-12,
            )
        }
    }

    @Test
    fun `two doses at different offsets superpose their individual curves`() {
        val d1 = WhatIfDose(3.0, 0.0)
        val d2 = WhatIfDose(2.0, 40.0)
        for (tau in taus) {
            val combined = insulinDeltaAt(kernel, tau, listOf(d1, d2), scale)
            val summed = insulinDeltaAt(kernel, tau, listOf(d1), scale) +
                insulinDeltaAt(kernel, tau, listOf(d2), scale)
            assertEquals("τ=$tau: doses add independently", summed, combined, 1e-12)
        }
    }

    @Test
    fun `a dose contributes nothing before it fires`() {
        // MUTATION GUARD: this pins the `if (rel <= 0.0) continue` line. Delete
        // it and an offset dose leaks its τ≈0 kernel value (and, for a second
        // dose, mis-times the whole curve). Below the offset the contribution
        // MUST be exactly zero.
        val offset = 60.0
        val u = 5.0
        for (tau in taus.filter { it <= offset }) {
            assertEquals(
                "τ=$tau ≤ offset=$offset: a not-yet-fired dose contributes nothing",
                0.0,
                insulinDeltaAt(kernel, tau, listOf(WhatIfDose(u, offset)), scale),
                0.0,
            )
        }
        // And strictly after the offset it is non-zero (the kernel has bitten).
        assertTrue(
            "after firing the dose must pull glucose down",
            insulinDeltaAt(kernel, offset + 30.0, listOf(WhatIfDose(u, offset)), scale) < 0.0,
        )
    }

    @Test
    fun `zero-unit doses are ignored`() {
        for (tau in taus) {
            assertEquals(
                0.0,
                insulinDeltaAt(
                    kernel, tau,
                    listOf(WhatIfDose(0.0, 0.0), WhatIfDose(0.0, 30.0)), scale,
                ),
                0.0,
            )
        }
    }
}
