package com.example.diapilot.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A-03: an unpromoted candidate run must never publish the interactive What-if
 * profile. A live check found that all of the user's What-if intents
 * were computed while candidate passes (up to ±35% on ISF/CS) kept overwriting
 * `latestPhysioWhatIf` after the promoted arm wrote it — the publication was
 * keyed to `fullCausalInsulinDisplay`, which candidates also need for pairing.
 *
 * Source-level, because the defect is which FLAG the publication hangs on, and
 * a runtime fixture for the full shadow pipeline would pin far less directly.
 * Mutation check: gate `updateWhatIf` back on `fullCausalInsulinDisplay`, or
 * drop `publishWhatIf=false` from the candidate call — either fails here.
 */
class WhatIfPublicationTest {
    private fun source(rel: String): String =
        File(rel).let { if (it.exists()) it else File("app/$rel") }.readText()

    @Test fun `candidate runs cannot publish the What-if profile`() {
        val shadow = source("src/main/java/com/example/diapilot/data/HybridShadow.kt")
        val gate = shadow.indexOf("if (publishWhatIf) {")
        val call = shadow.indexOf("HybridShadowRegistry.updateWhatIf(")
        assertTrue("updateWhatIf must be gated on publishWhatIf", gate in 0 until call)
        // CODE, NOT LINES. The check used to count all lines in a row and broke
        // on a comment — the explanation of the bug where "What-if" fell back
        // to the legacy core once tripped it. The invariant is the same: there
        // must be NO LOGIC at all between the gate and the call, or something
        // will slip into the protected section and run on a non-displayed pass.
        val between = shadow.substring(gate, call).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        assertTrue(
            "code appeared between the gate and the call: $between",
            between.size <= 1,
        )
        // THE CANDIDATE HALF OF THIS TEST IS GONE, along with the
        // prospective A/B it guarded: there are no candidate runs left to opt
        // out of What-if publication. The gate above is what still matters —
        // it is what keeps ANY non-displayed run from overwriting the profile.

    }
}
