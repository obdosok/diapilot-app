package io.github.obdosok.diapilot.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Model acceptance: the bundled synthetic model is accepted for any user, and
 * an imported model must carry a well-formed training window inside the
 * user's food era. No person's date is pinned anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridModelStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun bundled(): JSONObject = JSONObject(
        context.assets.open("models/person_model_v11_runtime.json").use { it.readBytes() }
            .toString(Charsets.UTF_8),
    )

    private fun withProvenance(vararg fields: Pair<String, Any?>): ByteArray {
        val root = bundled()
        val p = root.getJSONObject("training_provenance")
        fields.forEach { (k, v) -> p.put(k, v ?: JSONObject.NULL) }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun install(bytes: ByteArray) =
        HybridModelStore.install(context, bytes.inputStream(), "manual-import")

    @Test fun `the bundled synthetic model is accepted on a fresh install`() {
        // The default era is today; the synthetic training window lies long
        // before it, and the bundled prior must load regardless.
        val status = HybridModelStore.loadOrInstallBundled(context)
        assertEquals("2025-01-06", status.eligibleFrom)
        assertEquals("UTC", status.eligibleTimezone)
        assertEquals("2025-02-23", status.trainedThrough)
        assertEquals(49, status.trainingDays)
        assertTrue(status.source.startsWith("bundled"))
    }

    @Test fun `an imported model trained inside the food era is accepted`() {
        HybridModelStore.loadOrInstallBundled(context)
        FoodEraSettings.setByUser(context, LocalDate.of(2025, 1, 1), ZoneOffset.UTC)
        val status = install(bundled().toString().toByteArray(Charsets.UTF_8))
        assertEquals("manual-import", status.source)
        assertEquals("2025-01-06", status.eligibleFrom)
    }

    @Test fun `an imported model trained on history before the food era is rejected`() {
        HybridModelStore.loadOrInstallBundled(context)
        FoodEraSettings.setByUser(context, LocalDate.of(2025, 2, 1), ZoneOffset.UTC)
        val error = assertThrows(IllegalArgumentException::class.java) {
            install(bundled().toString().toByteArray(Charsets.UTF_8))
        }
        assertTrue(error.message!!, error.message!!.contains("before the food era"))
        // The rejected import did not replace the active generation.
        assertTrue(HybridModelStore.status(context)!!.source.startsWith("bundled"))
    }

    @Test fun `malformed training provenance is rejected`() {
        HybridModelStore.loadOrInstallBundled(context)
        FoodEraSettings.setByUser(context, LocalDate.of(2024, 1, 1), ZoneOffset.UTC)
        val bad = listOf(
            withProvenance("eligible_from" to "06.01.2025"),
            withProvenance("eligible_timezone" to "Not/AZone"),
            withProvenance("trained_through" to "2025-01-01"),                 // ends before it starts
            withProvenance("days" to 500),                                     // more days than the window
            withProvenance("days" to -1),
            withProvenance("trained_through" to null, "days" to 3),           // days without a window end
            withProvenance(
                "trained_through" to LocalDate.now(ZoneOffset.UTC).plusDays(10).toString(),
            ),                                                                  // trained on future days
        )
        bad.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { install(bytes) }
        }
    }
}
