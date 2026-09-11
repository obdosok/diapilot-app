package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSummaryTest {
    @Test
    fun fullPictureReadsNaturally() {
        val lines = statusSummary(
            StatusInput(
                iobUnits = 4.5, lastBolusUnits = 5.0, lastBolusAgeMin = 40,
                foodLabel = "ягодянка", foodAgeMin = 65, foodCarbs = 150.0,
                predMmolIn60 = 7.5, predLoIn60 = 6.6, predHiIn60 = 8.4,
            ),
        )
        assertEquals(2, lines.size)
        assertEquals("«ягодянка» 1ч05м ~150г · IOB 4,5 · укол 40м", lines[0])
        assertEquals("Через час ~7,5 (6,6–8,4)", lines[1])
    }

    @Test
    fun predictionAloneMakesOneLine() {
        val lines = statusSummary(
            StatusInput(
                iobUnits = null, lastBolusUnits = null, lastBolusAgeMin = null,
                foodLabel = null, foodAgeMin = null, foodCarbs = null,
                predMmolIn60 = 9.0,
            ),
        )
        assertEquals(1, lines.size)  // nothing acting -> no first line
        assertTrue(lines[0].startsWith("Через час"))
    }

    @Test
    fun emptyInputSaysNothing() {
        assertTrue(
            statusSummary(
                StatusInput(null, null, null, null, null, null, null),
            ).isEmpty(),
        )
    }

    @Test
    fun settledForecastNamesTheMoment() {
        val lines = statusSummary(
            StatusInput(
                null, null, null, null, null, null,
                predMmolIn60 = 6.1, predLoIn60 = 5.2, predHiIn60 = 7.0,
                predSettleMin = 95, predSettled = true,
            ),
        )
        assertEquals("Выровняется на ~6,1 (5,2–7,0) через 1ч35м", lines[0])
    }

    @Test
    fun alreadyFlatSaysSo() {
        val lines = statusSummary(
            StatusInput(
                null, null, null, null, null, null,
                predMmolIn60 = 6.1, predSettleMin = 5, predSettled = true,
            ),
        )
        assertEquals("Дальше ровно: ~6,1", lines[0])
    }

    @Test
    fun stillMovingAtHorizonIsHonest() {
        val lines = statusSummary(
            StatusInput(
                null, null, null, null, null, null,
                predMmolIn60 = 4.9, predSettleMin = 180, predSettled = false,
            ),
        )
        assertEquals("Через 3ч00м ~4,9, ещё в движении", lines[0])
    }

    @Test
    fun settleIndexFindsTheKnee() {
        // falling, then flat: the 0.5-band (what the eye calls flat) absorbs
        // the 7.2 shoulder, so the knee lands at index 2.
        assertEquals(2, settleIndex(listOf(9.0, 8.0, 7.2, 6.9, 6.9, 6.88, 6.9)))
        // never settles -> last index
        assertEquals(4, settleIndex(listOf(9.0, 8.0, 7.0, 6.0, 5.0)))
        // flat all along -> index 0
        assertEquals(0, settleIndex(listOf(6.0, 6.01, 6.0, 6.02, 6.0)))
        // too short
        assertEquals(null, settleIndex(listOf(6.0, 6.0)))
    }

    @Test
    fun settleIndexSurvivesKernelStepArtifacts() {
        // Kernel-step teeth (±0.15) inside a visually flat tail — a per-step
        // criterion would call this "still moving"; the median-smoothed band
        // reads it as flat, and the 6.5 shoulder still fits the 0.5 band.
        assertEquals(
            1,
            settleIndex(listOf(7.0, 6.5, 6.2, 6.1, 6.05, 6.2, 6.05, 6.1, 6.1)),
        )
    }

    @Test
    fun shortFlatTailBeforeHorizonProvesNothing() {
        // Slow drift whose last two steps happen to sit close together:
        // a sub-15-min "flat" stretch must not read as equilibrium.
        assertEquals(
            5,
            settleIndex(listOf(9.0, 8.4, 7.8, 7.2, 6.9, 6.8)),
        )
    }
}
