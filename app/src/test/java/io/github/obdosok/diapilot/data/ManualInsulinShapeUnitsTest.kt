package io.github.obdosok.diapilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "PHASE" IS A DURATION, THE STORE EXPECTS AN ABSOLUTE MINUTE.
 *
 * This test exists because of a specific defect that lived unnoticed for a day.
 * The tuning card labels a field "phase, min" and explains "holds for the whole
 * phase" — how long it HOLDS. `ManualInsulinParamsV1.plateauEndMin` is when it
 * ENDED, a minute from the injection. The field went to the store as-is.
 *
 * With the card values used below (19·53·31·156) that produced `onset 19 < peak
 * 53 > plateauEnd 31`: order violated, `shapeUsable` = false, the shape was
 * rejected as `SHAPE_OUT_OF_DOMAIN` and silently replaced by the measured one.
 * The model applied the measured shape while the screen claimed the entered
 * one was "currently applied".
 *
 * Why this test lives here and not on the UI: the conversion lived inside a
 * Compose handler, and nothing guarded it — the mutation "return
 * `plateauEndMin = num(phase)`" compiled and passed every test. So the
 * conversion was extracted into
 * [ManualInsulinRuntime.shapeFromCard], and this is its contract.
 */
class ManualInsulinShapeUnitsTest {

    @Test
    fun `phase is a duration and lands as an absolute plateau end`() {
        val p = ManualInsulinRuntime.shapeFromCard(
            onsetMin = 19.0, peakMin = 53.0, phaseDurationMin = 31.0,
            tailMin = 156.0, isfMmolPerU = null, fallbackPeakMin = null,
        )
        assertEquals("end of the active phase = peak + phase", 84.0, p.plateauEndMin!!, 1e-9)
        assertEquals(19.0, p.onsetMin!!, 1e-9)
        assertEquals(53.0, p.peakMin!!, 1e-9)
        assertEquals(156.0, p.tailMin!!, 1e-9)
    }

    @Test
    fun `the resulting landmarks are ordered — the property the defect broke`() {
        val p = ManualInsulinRuntime.shapeFromCard(
            onsetMin = 19.0, peakMin = 53.0, phaseDurationMin = 31.0,
            tailMin = 156.0, isfMmolPerU = null, fallbackPeakMin = null,
        )
        val landmarks = com.diapilot.core.physio.InsulinShapeLandmarksV1(
            onsetMin = p.onsetMin!!,
            peakMin = p.peakMin!!,
            plateauEndMin = p.plateauEndMin,
            tailMin = p.tailMin!!,
        )
        // Exactly what used to be false and dropped the whole shape to rejection.
        org.junit.Assert.assertTrue(
            "landmarks must be increasing: ${p.onsetMin} < ${p.peakMin} " +
                "< ${p.plateauEndMin} < ${p.tailMin}",
            landmarks.ordered,
        )
    }

    @Test
    fun `an empty peak falls back to the applied one rather than dropping the phase`() {
        val p = ManualInsulinRuntime.shapeFromCard(
            onsetMin = null, peakMin = null, phaseDurationMin = 31.0,
            tailMin = null, isfMmolPerU = null, fallbackPeakMin = 55.0,
        )
        assertEquals("duration is measured from the applied peak", 86.0, p.plateauEndMin!!, 1e-9)
    }

    @Test
    fun `with no peak anywhere the phase yields nothing rather than an absolute minute`() {
        // Nothing to fall back to — but writing 31 as an absolute minute is
        // also wrong: that was the defect. Empty is more honest.
        val p = ManualInsulinRuntime.shapeFromCard(
            onsetMin = null, peakMin = null, phaseDurationMin = 31.0,
            tailMin = null, isfMmolPerU = null, fallbackPeakMin = null,
        )
        assertNull(p.plateauEndMin)
    }
}

/**
 * THE OLD STORE IS SHOWN WHILE THE NEW ONE IS EMPTY.
 *
 * Before the model was moved onto a live store, the card wrote the shape to
 * `physio_*`, while the model reads `manual_insulin_*`. The old store could
 * hold values while the new one was empty, so the device ran on the measured
 * curve. When the read was switched to the live store, the fields went
 * empty: the entered numbers were not lost, but they disappeared from the
 * screen. This is a test that they are visible and carry over in one tap.
 */
class ManualInsulinLegacyShapeTest {

    @Test
    fun `an empty live store falls back to what was entered before`() {
        val q = ManualInsulinRuntime.shapeForCard(
            stored = com.diapilot.core.physio.ManualInsulinParamsV1.EMPTY,
            legacyOnset = 19.0, legacyPeak = 53.0, legacyPhase = 31.0, legacyTail = 156.0,
            appliedOnset = 11.0, appliedPeak = 55.0, appliedTail = 130.0,
        )
        assertEquals(19.0, q.onsetMin!!, 1e-9)
        assertEquals(53.0, q.peakMin!!, 1e-9)
        assertEquals("phase stays a duration", 31.0, q.phaseDurationMin!!, 1e-9)
        assertEquals(156.0, q.tailMin!!, 1e-9)
    }

    @Test
    fun `a live store wins and its absolute end is shown as a duration`() {
        val q = ManualInsulinRuntime.shapeForCard(
            stored = com.diapilot.core.physio.ManualInsulinParamsV1(
                onsetMin = 11.0, peakMin = 55.0, plateauEndMin = 86.0, tailMin = 130.0,
            ),
            legacyOnset = 19.0, legacyPeak = 53.0, legacyPhase = 31.0, legacyTail = 156.0,
            appliedOnset = 11.0, appliedPeak = 55.0, appliedTail = 130.0,
        )
        assertEquals(11.0, q.onsetMin!!, 1e-9)
        assertEquals("86 - 55 = 31 minute phase", 31.0, q.phaseDurationMin!!, 1e-9)
    }
}

/**
 * A FIELD IS NEVER EMPTY WHILE THE MODEL HOLDS SOMETHING.
 *
 * An empty field means "clear the override", so showing emptiness where
 * there is no override means offering the person a one-tap way to erase the
 * value in effect. That is exactly what happened once: fields showed empty,
 * "Apply" wrote zeros, and the previously entered shape disappeared
 * from both stores.
 */
class ManualInsulinCardFallbackTest {

    @Test
    fun `with both stores empty the card shows what is applied`() {
        val q = ManualInsulinRuntime.shapeForCard(
            stored = com.diapilot.core.physio.ManualInsulinParamsV1.EMPTY,
            legacyOnset = null, legacyPeak = null, legacyPhase = null, legacyTail = null,
            appliedOnset = 11.0, appliedPeak = 55.0, appliedTail = 130.0,
        )
        assertEquals(11.0, q.onsetMin!!, 1e-9)
        assertEquals(55.0, q.peakMin!!, 1e-9)
        assertEquals(130.0, q.tailMin!!, 1e-9)
        assertNull("плато у применённой модели отдельно не хранится", q.phaseDurationMin)
    }

    @Test
    fun `the legacy store still wins over the applied model`() {
        val q = ManualInsulinRuntime.shapeForCard(
            stored = com.diapilot.core.physio.ManualInsulinParamsV1.EMPTY,
            legacyOnset = 19.0, legacyPeak = 53.0, legacyPhase = 31.0, legacyTail = 156.0,
            appliedOnset = 11.0, appliedPeak = 55.0, appliedTail = 130.0,
        )
        assertEquals("введённое человеком важнее применённого", 19.0, q.onsetMin!!, 1e-9)
        assertEquals(31.0, q.phaseDurationMin!!, 1e-9)
    }
}
