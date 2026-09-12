package io.github.obdosok.diapilot.ui

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.collect.DataSourceFix
import io.github.obdosok.diapilot.collect.DataSourceId
import io.github.obdosok.diapilot.collect.DataSourceInputs
import io.github.obdosok.diapilot.collect.HealthConnectAccess
import io.github.obdosok.diapilot.collect.dataSourceRows
import io.github.obdosok.diapilot.i18n.DataSourceText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Data sources screen renders every row on a phone that has none of the
 * things the rows are about.
 *
 * This is the screen a stranger opens when nothing arrives, so the failure it
 * exists to prevent is the screen itself not composing — an unmapped status, a
 * missing string, a row whose Fix has neither a control nor a sentence. No
 * live device takes part: the rows come from the pure derivation and the
 * content composable takes them as an argument.
 *
 * Presence is asserted over ALL matching nodes rather than one, because
 * several rows legitimately show the same words — three streams read "nothing
 * yet" on a phone that has never collected anything, and both permission rows
 * read "not granted".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSourcesRenderTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun SemanticsNodeInteractionsProvider.count(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    private fun assertShown(text: String) {
        assertTrue("not rendered: \"$text\"", compose.count(text) > 0)
    }

    private fun assertNotShown(text: String) {
        assertTrue("unexpectedly rendered: \"$text\"", compose.count(text) == 0)
    }

    /** A phone where every link is broken, so every row has something to say. */
    private fun brokenInputs(sensorDirect: Boolean) = DataSourceInputs(
        nowMs = 1_700_000_000_000L,
        sensorDirect = sensorDirect,
        xdripInstalled = false,
        lastBroadcastMs = 0,
        lastWebProbeMs = 1_699_999_000_000L,
        lastWebOkMs = 0,
        nightscoutUrl = null,
        lastMinuteReadingMs = 0,
        ownBleEnabled = true,
        lastBlePacketMs = 0,
        batteryUnrestricted = false,
        notificationsEnabled = false,
        exactAlarmsAllowed = false,
        serviceStartedMs = 0,
        serviceStoppedMs = 0,
        healthConnect = HealthConnectAccess.UNAVAILABLE,
        overlayWanted = true,
        overlayGranted = false,
        nfcPresent = true,
        nfcEnabled = false,
    )

    @Test fun everyRowIsComposedWithItsStatusAndExplanation() {
        val rows = dataSourceRows(brokenInputs(sensorDirect = true))
        compose.setContent { DataSourcesContent(rows = rows, sensorDirect = true) }
        assertTrue(rows.size == DataSourceId.entries.size)
        rows.forEach { row ->
            assertShown(DataSourceText.title(context, row.id))
            assertShown(DataSourceText.status(context, row))
            assertShown(DataSourceText.why(context, row.id, true))
            // Every row on this phone is broken, so each one either offers a
            // control or says what to do instead — never neither.
            val action = DataSourceText.fixLabel(context, row.fix)
                ?: DataSourceText.advice(context, row)
                ?: error("row ${row.id} offers neither a fix nor advice")
            assertShown(action)
        }
        assertShown(context.getString(R.string.data_sources_title))
        assertShown(context.getString(R.string.data_sources_intro))
        assertShown(context.getString(R.string.data_sources_recheck))
    }

    @Test fun theStoreEditionHasNoSensorDirectRows() {
        val rows = dataSourceRows(brokenInputs(sensorDirect = false))
        compose.setContent { DataSourcesContent(rows = rows, sensorDirect = false) }
        listOf(DataSourceId.OOP2, DataSourceId.OWN_BLE).forEach { id ->
            assertNotShown(DataSourceText.title(context, id))
        }
        // The NovoPen scan needs NFC in both editions, and its line says so
        // without mentioning a sensor scan this edition cannot perform.
        assertShown(context.getString(R.string.data_sources_row_nfc_why))
        assertNotShown(context.getString(R.string.data_sources_row_nfc_why_sensor_direct))
    }

    /** A working row shows no Fix control: there is nothing to fix. */
    @Test fun aHealthyRowOffersNoControl() {
        val rows = dataSourceRows(
            brokenInputs(sensorDirect = true).copy(notificationsEnabled = true),
        )
        compose.setContent { DataSourcesContent(rows = rows, sensorDirect = true) }
        assertNotShown(context.getString(R.string.data_sources_fix_notifications))
        // …while the restricted battery next to it still offers one.
        assertTrue(rows.any { it.fix == DataSourceFix.BATTERY_SETTINGS })
        assertShown(context.getString(R.string.data_sources_fix_battery))
    }
}
