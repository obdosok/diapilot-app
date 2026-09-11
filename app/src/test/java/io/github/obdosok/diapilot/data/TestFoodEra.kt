package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.api.FoodEra
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A fixed, synthetic food era for Robolectric tests whose data is laid out
 * relative to the era start. The app's own default (the first-run date) is
 * "today", which would put such data in the future.
 */
internal object TestFoodEra {
    val ERA = FoodEra(LocalDate.of(2025, 1, 6), ZoneOffset.UTC)

    /** Stores [ERA] as the user's explicit choice and returns it. */
    fun install(): FoodEra = FoodEraSettings.setByUser(
        ApplicationProvider.getApplicationContext(), ERA.startDate, ERA.zone,
    )
}
