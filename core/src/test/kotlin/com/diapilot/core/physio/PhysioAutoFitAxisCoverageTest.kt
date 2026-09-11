package com.diapilot.core.physio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EVERY AXIS REACHES THE MEDIAN — otherwise the fit turns the knob and the
 * score does not see it.
 *
 * `PhysioAutoFitV1.medianOf` reassembles `Knobs` field by field, and its own
 * comment warns: an axis added to the data class and forgotten here does NOT
 * break compilation — it silently falls back to the shipped default. The
 * warning came true twice within two days:
 *
 *  - `tailShare` was added and forgotten. The "tail share is free" arm
 *    compared the model at share 0.20 against the same share; the conclusion
 *    "the share by itself is harmful" was later retracted.
 *  - `activityDirect` was added and forgotten. Both activity arms from that
 *    day are invalid: the pinned coefficient never reached the score.
 *
 * Both bugs are visible only in a column of the drift table, and both were
 * found by accident. A comment does not catch this; a test does.
 *
 * The check is built so that ANY future axis falls under it automatically:
 * it takes [PhysioAutoFitV1.AXES] — a list that is already required to be
 * complete, or the search would never touch the axis — and sets each one to
 * a value different from its default. The median of a single element must
 * return exactly that element.
 */
class PhysioAutoFitAxisCoverageTest {

    @Test
    fun `medianOf carries every axis the search can move`() {
        val start = PhysioAutoFitV1.Knobs(
            isf = 2.0, onsetMin = 15.0, fullSpeedMin = 50.0, phaseMin = 30.0, tailMin = 150.0,
            emptyingKcalPerHour = 150.0, carbSieving = 0.6, carbSpread = 1.0, trustRamp = 1.0,
        )
        // Shift by a third of the range from the current value: guaranteed NOT
        // the default and guaranteed within bounds, whatever the range is.
        val moved = PhysioAutoFitV1.AXES.fold(start) { k, axis ->
            val (lo, hi) = PhysioAutoFitV1.rangeOf(axis)
            PhysioAutoFitV1.withAxis(k, axis, lo + (hi - lo) / 3.0)
        }
        val back = requireNotNull(
            PhysioAutoFitV1.medianOf(listOf(PhysioAutoFitV1.Fit(moved, PhysioAutoFitV1.Score(0.0, 0.0), 0))),
        )
        for (axis in PhysioAutoFitV1.AXES) {
            assertEquals(
                "ось «$axis» не доходит до `medianOf` — подгонка её крутит, а в счёт " +
                    "уходит отгруженное умолчание, и арм сравнивает модель сам с собой",
                PhysioAutoFitV1.axisValue(moved, axis),
                PhysioAutoFitV1.axisValue(back, axis), 1e-9,
            )
        }
    }
}
