package io.github.obdosok.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.analysis.CARB_SENS_OVERRIDE_DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TIER ONE OF THE CARB CHAIN HAS A DOOR (audit M4).
 *
 * `carb_sens_override_mmol_per_g` had a getter whose default was the shipped
 * constant and no setter at all, so the "manual → weight → constant" chain
 * could never start at manual on a phone that had not run an older build.
 * What is pinned here: the setter round-trips, `storedCarbSensOverrideMmolPerG`
 * — the `contains`-based tier-one reader — reports it, and clearing REMOVES the
 * key rather than storing a zero, so the weight tier is consulted again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarbSensOverrideSetterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `a fresh install has no stored override and applies the shipped constant`() {
        assertNull(Settings.storedCarbSensOverrideMmolPerG(context))
        // Float storage: the getter reads a Float back, so the constant survives to ~1e-7.
        assertEquals(CARB_SENS_OVERRIDE_DEFAULT, checkNotNull(Settings.carbSensOverrideMmolPerG(context)), 1e-6)
    }

    @Test fun `the setter round-trips through both readers`() {
        Settings.setCarbSensOverrideMmolPerG(context, 0.21)
        assertEquals(0.21, checkNotNull(Settings.storedCarbSensOverrideMmolPerG(context)), 1e-6)
        assertEquals(0.21, checkNotNull(Settings.carbSensOverrideMmolPerG(context)), 1e-6)
    }

    @Test fun `clearing removes the key so the weight tier wins again`() {
        Settings.setCarbSensOverrideMmolPerG(context, 0.21)
        Settings.setCarbSensOverrideMmolPerG(context, null)
        assertNull(Settings.storedCarbSensOverrideMmolPerG(context))
        // Float storage: the getter reads a Float back, so the constant survives to ~1e-7.
        assertEquals(CARB_SENS_OVERRIDE_DEFAULT, checkNotNull(Settings.carbSensOverrideMmolPerG(context)), 1e-6)
    }

    @Test fun `a value outside the getter's domain is refused, not clamped`() {
        Settings.setCarbSensOverrideMmolPerG(context, 0.21)
        Settings.setCarbSensOverrideMmolPerG(context, 5.0)
        assertEquals("the previous entry stands", 0.21, checkNotNull(Settings.storedCarbSensOverrideMmolPerG(context)), 1e-6)
    }

    @Test fun `the first-run pages write the override through the same setter`() {
        Onboarding.applyModelEntries(context, 2.5, Onboarding.InsulinPreset.TYPICAL, carbSensMmolPerG = 0.18)
        assertEquals(0.18, checkNotNull(Settings.storedCarbSensOverrideMmolPerG(context)), 1e-6)
        Onboarding.applyModelEntries(context, 2.5, Onboarding.InsulinPreset.TYPICAL, carbSensMmolPerG = null)
        assertNull(Settings.storedCarbSensOverrideMmolPerG(context))
        ManualInsulinRuntime.setParams(context, com.diapilot.core.physio.ManualInsulinParamsV1.EMPTY)
    }
}
