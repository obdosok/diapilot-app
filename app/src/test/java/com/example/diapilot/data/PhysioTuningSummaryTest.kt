package com.example.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "applied" summary of the tuning card is UI text: it follows the app language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhysioTuningSummaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `untouched tuning reads as shipped`() {
        assertEquals("as shipped", PhysioTuning.summary(PhysioTuning.Values(), context))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `untouched tuning reads as shipped in Russian`() {
        assertEquals("как отгружено", PhysioTuning.summary(PhysioTuning.Values(), context))
    }

    @Test
    fun `touched values are listed in the UI language`() {
        val s = PhysioTuning.summary(PhysioTuning.Values(emptyingKcalPerHour = 300.0), context)
        assertEquals("300 kcal/h", s)
    }
}
