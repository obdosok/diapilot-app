package com.diapilot.core.analysis

import com.diapilot.core.collector.BolusPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IOB from the person's own kernel. The textbook curve (DIA 5 h, peak 75 min)
 * is a population guess; the kernel is what this body actually did.
 */
class KernelIobTest {

    /** Shaped like a real learned kernel: cumulative DROP (negative), half the
     *  effect in by ~65 min, plateau ~-1.7 mmol/U at ~125 min. */
    private val kernel = listOf(
        KernelPoint(0.0, 0.0, 0.0, 0.0, 100),
        KernelPoint(30.0, -0.3, -0.3, -0.3, 100),
        KernelPoint(65.0, -0.85, -0.85, -0.85, 100),
        KernelPoint(125.0, -1.70, -1.7, -1.7, 100),
        KernelPoint(240.0, -1.70, -1.7, -1.7, 100),
    )

    @Test
    fun `fresh dose is fully on board, spent dose is not`() {
        assertEquals(1.0, kernelIobFraction(kernel, 0.0)!!, 1e-9)
        assertEquals(0.0, kernelIobFraction(kernel, 240.0)!!, 1e-9)
    }

    @Test
    fun `half the effect in means half the dose still on board`() {
        // -0.85 of a -1.70 plateau = 50% spent → 50% left.
        assertEquals(0.5, kernelIobFraction(kernel, 65.0)!!, 1e-6)
    }

    /** The real learned kernel, tail and all: -1.70 at 125 min, then a rebound
     *  to -1.34 by 200 (n halves out there, and three hours after a correction
     *  people eat). Field-shaped, because this is what broke. */
    private val noisyTail = listOf(
        KernelPoint(0.0, 0.0, 0.0, 0.0, 118),
        KernelPoint(60.0, -0.83, -0.83, -0.83, 225),
        KernelPoint(125.0, -1.70, -1.7, -1.7, 229),
        KernelPoint(160.0, -1.58, -1.58, -1.58, 227),
        KernelPoint(200.0, -1.34, -1.34, -1.34, 228),
        KernelPoint(240.0, -1.48, -1.48, -1.48, 113),
    )

    @Test
    fun `a rebounding kernel tail does not put insulin back on board`() {
        // The bug: reading the CURRENT point against the deepest made IOB CLIMB
        // (7% at 160 → 21% at 200) and stick at 13% forever — the endless flat
        // line on the chart. Insulin cannot un-act.
        val at125 = kernelIobFraction(noisyTail, 125.0)!!
        assertEquals("fully spent at the deepest point", 0.0, at125, 1e-9)
        for (t in listOf(160.0, 200.0, 240.0, 600.0)) {
            assertEquals("IOB came back at $t min", 0.0, kernelIobFraction(noisyTail, t)!!, 1e-9)
        }
    }

    @Test
    fun `spent never decreases, even on the noisy tail`() {
        var prev = 1.0
        for (t in 0..600 step 5) {
            val f = kernelIobFraction(noisyTail, t.toDouble())!!
            assertTrue("IOB rose at $t min: $prev → $f", f <= prev + 1e-9)
            prev = f
        }
    }

    @Test
    fun `it never runs backwards`() {
        var prev = 1.0
        for (t in 0..240 step 10) {
            val f = kernelIobFraction(kernel, t.toDouble())!!
            assertTrue("IOB rose at $t: $prev → $f", f <= prev + 1e-9)
            prev = f
        }
    }

    @Test
    fun `units scale with the dose and future shots are not on board`() {
        val now = 1_700_000_000_000L
        val boluses = listOf(
            BolusPoint(now - 65 * 60_000, 4.0, null),   // half spent → 2.0 left
            BolusPoint(now + 30 * 60_000, 3.0, null),   // not given yet
        )
        assertEquals(2.0, kernelIobUnits(boluses, now, kernel)!!, 1e-6)
    }

    @Test
    fun `an unlearned kernel says nothing rather than guessing`() {
        // Fresh install / no clean corrections: fall back to the textbook curve,
        // don't invent a personal one out of a flat kernel.
        assertNull(kernelIobFraction(emptyList(), 30.0))
        assertNull(kernelIobFraction(listOf(KernelPoint(30.0, 0.0, 0.0, 0.0, 3)), 30.0))
        assertNull(kernelIobUnits(listOf(BolusPoint(0L, 3.0, null)), 1L, emptyList()))
    }

    @Test
    fun `this body differs from the textbook — which is the point`() {
        // The learned curve peaks earlier (65 vs 75) and plateaus by 125 min,
        // while the textbook keeps trickling to DIA 300. At 3 h they disagree.
        val personal = kernelIobFraction(kernel, 180.0)!!
        val textbook = iobFraction(180.0)
        assertTrue(
            "personal=$personal textbook=$textbook — the whole reason to show both",
            kotlin.math.abs(personal - textbook) > 0.05,
        )
    }
}
