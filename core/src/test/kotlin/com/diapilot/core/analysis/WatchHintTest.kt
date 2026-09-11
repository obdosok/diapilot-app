package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The wording is rendered and tested in :app (com.example.diapilot.i18n.WatchHintTextTest).
class WatchHintTest {
    @Test
    fun predictedLowRemindsTheUsersOwnProtocol() {
        // NO computed grams — the app reminds the pre-agreed plan verbatim.
        val hint = watchHint(
            WatchHintInput(
                predMmolIn60 = 4.5, predLoIn60 = 3.0,
                iobUnits = 2.0, carbSensMmolPerGram = 0.1,
                hypoProtocol = "10 г сока",
            ),
        )
        assertEquals(WatchHint.HypoPlan("10 г сока"), hint)
    }

    @Test
    fun predictedLowWithoutProtocolAsksToCheckNeverComputes() {
        val hint = watchHint(
            WatchHintInput(
                predMmolIn60 = 3.0, predLoIn60 = 1.0,
                iobUnits = 5.0, carbSensMmolPerGram = 0.05,
            ),
        )
        // A check carries no number at all: nothing was computed.
        assertEquals(WatchHint.HypoCheck, hint)
    }

    @Test
    fun lowWithoutCalibrationStillWarns() {
        assertEquals(WatchHint.HypoCheck, watchHint(WatchHintInput(4.0, 3.2, null, null)))
    }

    @Test
    fun blankProtocolFallsBackToTheCheck() {
        assertEquals(
            WatchHint.HypoCheck,
            watchHint(WatchHintInput(4.0, 3.2, null, null, hypoProtocol = "  ")),
        )
    }

    @Test
    fun uncoveredHighNamesTheSettleValueNeverUnits() {
        // Predicted to settle at 12.5 — the wrist gets the destination
        // itself, in glucose, never insulin units.
        val hint = watchHint(
            WatchHintInput(12.5, 10.5, 0.2, 0.1, targetMmol = 5.5),
        )
        assertEquals(WatchHint.AboveTarget(settleMmol = 12.5, mgdl = false), hint)
    }

    @Test
    fun highBelowTriggerStaysQuietEvenIfAboveIdeal() {
        // 8.0 is well above the 5.5 ideal but inside the range — no nagging.
        assertNull(watchHint(WatchHintInput(8.0, 6.0, 1.0, 0.1, targetMmol = 5.5)))
    }

    @Test
    fun lowBeatsHighAndQuietModelSaysNothing() {
        // Corridor floor low even though midline is high → low wins.
        assertTrue(watchHint(WatchHintInput(11.0, 3.5, 1.0, 0.1)) is WatchHint.HypoCheck)
        assertNull(watchHint(WatchHintInput(7.0, 5.5, 1.0, 0.1)))
        assertNull(watchHint(WatchHintInput(null, null, null, null)))
    }
}
