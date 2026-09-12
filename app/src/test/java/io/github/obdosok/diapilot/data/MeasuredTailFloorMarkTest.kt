package io.github.obdosok.diapilot.data

import com.diapilot.core.physio.AssembledProfileV1
import com.diapilot.core.physio.ConditionedLandmarkV1
import com.diapilot.core.physio.LandmarkMedianV1
import com.diapilot.core.physio.PhysioBoundsV1
import com.diapilot.core.physio.ProfileArmV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "SHORTER THAN THE DOMAIN FLOOR" IS A STATEMENT ABOUT THE RULER.
 *
 * The settings card prints a measured end of action, and a reader has no way to
 * tell a measurement from the edge of the measurement window. For this
 * instrument that edge is four hours (audit M1), so below the domain floor the
 * number describes the window rather than the insulin, and the card has to say
 * so next to it.
 *
 * The condition is pinned here rather than in the composable because it is the
 * part that can be wrong: the mark must read the profile's own `tailEnd`, i.e.
 * the value BEFORE `coerceIntoDomain` lifts it to the floor. Reading the
 * coerced landmark instead would compare 240 against 240 and never fire — a
 * mark that is present in the source and silent on the screen.
 */
class MeasuredTailFloorMarkTest {

    private fun profile(endMin: Double?) = AssembledProfileV1(
        onset = ConditionedLandmarkV1(null, null, LandmarkMedianV1(20.0, 12, 0.0)),
        visibleFall = ConditionedLandmarkV1(null, null, LandmarkMedianV1(25.0, 12, 0.0)),
        peakRate = ConditionedLandmarkV1(null, null, LandmarkMedianV1(55.0, 12, 0.0)),
        slowdown = LandmarkMedianV1(90.0, 10, 0.0),
        tailEnd = endMin?.let { LandmarkMedianV1(it, 8, 0.0) },
        arm = ProfileArmV1.POOLED,
    )

    private fun state(endMin: Double?) = InsulinProfileRuntime.State(
        curve = null,
        profile = profile(endMin),
        refusals = emptyMap(),
        dosesConsidered = 40,
    )

    @Test fun `the floor the mark quotes is the artifact's own domain`() {
        assertEquals(
            PhysioBoundsV1().insulinTailMinRange.start,
            InsulinProfileRuntime.TAIL_DOMAIN_FLOOR_MIN,
            0.0,
        )
    }

    /** The case every measured corpus on this instrument actually produces. */
    @Test fun `a measured end inside the third hour is marked with the measured value`() {
        assertEquals(
            185.0,
            requireNotNull(InsulinProfileRuntime.measuredTailBelowFloorMin(state(185.0))),
            1e-9,
        )
    }

    @Test fun `an end at or above the floor is not marked`() {
        assertNull(InsulinProfileRuntime.measuredTailBelowFloorMin(state(240.0)))
        assertNull(InsulinProfileRuntime.measuredTailBelowFloorMin(state(320.0)))
    }

    /** No end landmark at all is a different sentence, already on the card —
     *  the mark must not claim a measurement that does not exist. */
    @Test fun `no measured end means nothing to mark`() {
        assertNull(InsulinProfileRuntime.measuredTailBelowFloorMin(state(null)))
        assertNull(
            InsulinProfileRuntime.measuredTailBelowFloorMin(
                InsulinProfileRuntime.State(null, null, emptyMap(), 0),
            ),
        )
    }
}
