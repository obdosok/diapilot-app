package com.diapilot.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The wording of these summaries is rendered and tested in :app
// (io.github.obdosok.diapilot.i18n.StatusTextTest, English and Russian).
class StatusSummaryTest {
    @Test
    fun fullPictureReadsNaturally() {
        val s = statusSummary(
            StatusInput(
                iobUnits = 4.5, lastBolusUnits = 5.0, lastBolusAgeMin = 40,
                foodLabel = "ягодянка", foodAgeMin = 65, foodCarbs = 150.0,
                predMmolIn60 = 7.5, predLoIn60 = 6.6, predHiIn60 = 8.4,
            ),
        )
        assertEquals(
            listOf(
                StatusActing.Food("ягодянка", 65, cobGrams = null, eatenGrams = 150.0),
                StatusActing.Iob(4.5, lastBolusAgeMin = 40),
            ),
            s.acting,
        )
        assertEquals(
            StatusForecast(StatusForecast.Kind.IN_AN_HOUR, 7.5, 6.6, 8.4, settleMin = null, mgdl = false),
            s.forecast,
        )
    }

    @Test
    fun predictionAloneMakesOneLine() {
        val s = statusSummary(
            StatusInput(
                iobUnits = null, lastBolusUnits = null, lastBolusAgeMin = null,
                foodLabel = null, foodAgeMin = null, foodCarbs = null,
                predMmolIn60 = 9.0,
            ),
        )
        assertTrue(s.acting.isEmpty())  // nothing acting -> no first line
        assertEquals(StatusForecast.Kind.IN_AN_HOUR, s.forecast!!.kind)
    }

    @Test
    fun emptyInputSaysNothing() {
        assertTrue(
            statusSummary(
                StatusInput(null, null, null, null, null, null, null),
            ).isEmpty,
        )
    }

    @Test
    fun settledForecastNamesTheMoment() {
        val s = statusSummary(
            StatusInput(
                null, null, null, null, null, null,
                predMmolIn60 = 6.1, predLoIn60 = 5.2, predHiIn60 = 7.0,
                predSettleMin = 95, predSettled = true,
            ),
        )
        assertEquals(StatusForecast(StatusForecast.Kind.SETTLES, 6.1, 5.2, 7.0, 95, false), s.forecast)
    }

    @Test
    fun alreadyFlatSaysSo() {
        val s = statusSummary(
            StatusInput(
                null, null, null, null, null, null,
                predMmolIn60 = 6.1, predSettleMin = 5, predSettled = true,
            ),
        )
        assertEquals(StatusForecast(StatusForecast.Kind.FLAT, 6.1, null, null, 5, false), s.forecast)
    }

    @Test
    fun stillMovingAtHorizonIsHonest() {
        val s = statusSummary(
            StatusInput(
                null, null, null, null, null, null,
                predMmolIn60 = 4.9, predSettleMin = 180, predSettled = false,
            ),
        )
        assertEquals(StatusForecast(StatusForecast.Kind.STILL_MOVING, 4.9, null, null, 180, false), s.forecast)
    }

    @Test
    fun carbsOnBoardReplaceTheEatenTotalAndHideBelowThreeGrams() {
        fun food(cob: Double?) = statusSummary(
            StatusInput(
                null, null, null, foodLabel = "суп", foodAgeMin = 30, foodCarbs = 40.0,
                predMmolIn60 = null, cobGrams = cob,
            ),
        ).acting.single() as StatusActing.Food
        assertEquals(25.0, food(25.0).cobGrams!!, 0.0)
        assertNull("the eaten total is history once COB is known", food(25.0).eatenGrams)
        assertNull(food(2.0).cobGrams)
        assertNull(food(2.0).eatenGrams)
        assertEquals(40.0, food(null).eatenGrams!!, 0.0)
    }

    @Test
    fun anOldDoseIsNotMentioned() {
        val s = statusSummary(
            StatusInput(
                iobUnits = null, lastBolusUnits = 3.0, lastBolusAgeMin = 30,
                foodLabel = null, foodAgeMin = null, foodCarbs = null, predMmolIn60 = null,
            ),
        )
        assertEquals(listOf(StatusActing.LastBolus(3.0, 30)), s.acting)
        val old = statusSummary(
            StatusInput(
                iobUnits = null, lastBolusUnits = 3.0, lastBolusAgeMin = 10_000,
                foodLabel = null, foodAgeMin = null, foodCarbs = null, predMmolIn60 = null,
            ),
        )
        assertTrue(old.isEmpty)
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
