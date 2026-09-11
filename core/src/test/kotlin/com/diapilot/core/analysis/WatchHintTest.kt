package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("гипо? план: 10 г сока", hint)
    }

    @Test
    fun predictedLowWithoutProtocolAsksToCheckNeverComputes() {
        val hint = watchHint(
            WatchHintInput(
                predMmolIn60 = 3.0, predLoIn60 = 1.0,
                iobUnits = 5.0, carbSensMmolPerGram = 0.05,
            ),
        )!!
        assertEquals("риск гипо — проверьте", hint)
        assertTrue("no digits allowed in an uncomputed hint", hint.none { it.isDigit() })
    }

    @Test
    fun lowWithoutCalibrationStillWarns() {
        assertEquals(
            "риск гипо — проверьте",
            watchHint(WatchHintInput(4.0, 3.2, null, null)),
        )
    }

    @Test
    fun uncoveredHighNamesTheSettleValueNeverUnits() {
        // Predicted to settle at 12.5 — the wrist gets the destination
        // itself, in glucose, never insulin units.
        val hint = watchHint(
            WatchHintInput(12.5, 10.5, 0.2, 0.1, targetMmol = 5.5),
        )!!
        assertEquals("выше цели, к ~12,5", hint)
        assertTrue(!hint.contains("ед") && !hint.lowercase().contains("u"))
    }

    @Test
    fun highBelowTriggerStaysQuietEvenIfAboveIdeal() {
        // 8.0 is well above the 5.5 ideal but inside the range — no nagging.
        assertNull(watchHint(WatchHintInput(8.0, 6.0, 1.0, 0.1, targetMmol = 5.5)))
    }

    @Test
    fun lowBeatsHighAndQuietModelSaysNothing() {
        // Corridor floor low even though midline is high → low wins.
        assertTrue(watchHint(WatchHintInput(11.0, 3.5, 1.0, 0.1))!!.startsWith("риск гипо"))
        assertNull(watchHint(WatchHintInput(7.0, 5.5, 1.0, 0.1)))
        assertNull(watchHint(WatchHintInput(null, null, null, null)))
    }
}
