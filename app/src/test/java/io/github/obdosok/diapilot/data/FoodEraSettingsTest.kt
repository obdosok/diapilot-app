package io.github.obdosok.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.api.FoodEra
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodEraSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val day = 86_400_000L

    /** A fresh install: nothing stored yet (the Application already ran init). */
    @Before fun freshInstall() {
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `a fresh install defaults to the first-run date in the system zone`() {
        val firstRun = 1_736_244_900_000L // 2025-01-07T10:15Z
        val era = FoodEraSettings.init(context, firstRun)
        assertEquals(FoodEra.startingOnDayOf(firstRun, ZoneId.systemDefault()), era)
        assertEquals(era, FoodEraSettings.current())
        assertFalse(FoodEraSettings.isUserSet(context))
    }

    @Test fun `the first-run date is stored once, not recomputed on later starts`() {
        val firstRun = 1_736_244_900_000L
        val era = FoodEraSettings.init(context, firstRun)
        assertEquals(era, FoodEraSettings.init(context, firstRun + 40 * day))
    }

    @Test fun `a default era is never a purge boundary`() {
        FoodEraSettings.init(context)
        assertNull(FoodEraSettings.explicitStartMs(context))
        assertNull(PreEraPurge.boundary(context))
    }

    @Test fun `an explicit choice becomes the era and survives a restart`() {
        FoodEraSettings.init(context)
        val chosen = FoodEraSettings.setByUser(context, LocalDate.of(2025, 1, 6), ZoneOffset.UTC)
        assertEquals(FoodEra(LocalDate.of(2025, 1, 6), ZoneOffset.UTC), chosen)
        assertEquals(chosen, FoodEraSettings.current())
        assertTrue(FoodEraSettings.isUserSet(context))
        assertEquals(chosen.startMs, FoodEraSettings.explicitStartMs(context))
        // A process restart re-reads storage and keeps the user's choice.
        assertEquals(chosen, FoodEraSettings.init(context))
    }

    @Test fun `an era start in the future is refused and changes nothing`() {
        val before = FoodEraSettings.init(context)
        assertThrows(IllegalArgumentException::class.java) {
            FoodEraSettings.setByUser(context, LocalDate.now(ZoneOffset.UTC).plusDays(3), ZoneOffset.UTC)
        }
        assertEquals(before, FoodEraSettings.era(context))
        assertFalse(FoodEraSettings.isUserSet(context))
    }
}
