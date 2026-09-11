package com.example.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.collector.Annotation
import com.diapilot.core.hybrid.HybridPersonModelJson
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE COB LABEL AND THE LINE MUST READ ONE STOMACH.
 *
 * A live check showed the forecast line drawing a much higher reading than the
 * COB label beside it implied. Three readers built the food queue differently,
 * and the calorie queue — the STOMACH — was shared only by the line:
 *
 *  - `Forecaster` -> `foodDelta(..., state.foodHistory)` — all dishes in one queue;
 *  - the COB ribbon -> `clusteredFoodCdfTimeline(event, listOf(event), ...)` — one dish;
 *  - the COB number -> `foodRemainingFraction(event, age)` — one dish, undecorated.
 *
 * The fixture is a synthetic evening: a fatty grain-and-sausage dish (54 g,
 * medium fast fraction, fatty) followed 67 minutes later by a fast, fatty
 * dessert. The order is not for looks: the dessert sits in the queue BEHIND
 * the fatty dish, and this is the only arrangement where the two curves
 * separate — behind a lean meal the pipe drains fast enough that the dessert
 * waits for nothing, and the defect would pass unnoticed.
 *
 * MUTATION THIS TEST CATCHES: put back in `cobGrams` the call
 * `engine.foodRemainingFraction(event, ageMin)` instead of the clustered one — the
 * test fails on the first assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CobSharesTheLineStomachTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The fixture evening: a medium-fast fatty dish, then a fast fatty dessert. */
    private fun note(tsMs: Long, text: String, carbs: Double, p: Double, f: Double, fast: String) =
        Annotation(
            tsMs, "food", text, estCarbs = carbs,
            analysis = "БЕЛКИ: $p г\nЖИРЫ: $f г\n" +
                "KINETICS_V2: $fast;form=SOLID;confidence=.6;source=test;alcohol=false",
        )

    private fun <T> withStore(name: String, body: (SqliteCollectorStore) -> T): T {
        context.deleteDatabase(name)
        return SqliteCollectorStore(context, name).use(body).also { context.deleteDatabase(name) }
    }

    private fun install() {
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        HybridShadowRegistry.install(model, "cob-shares-stomach")
    }

    @Test
    fun `a dish queued behind a fatty meal is still on board when the empty-stomach rule says it is gone`() =
        withStore("cob-stomach.sqlite") { store ->
            install()
            val now = 1_787_000_000_000L
            val buckwheat = now - 130 * 60_000L   // first dish
            val cone = now - 63 * 60_000L         // dessert, 67 minutes later
            // GRAMS MUST BE "KNOWN" — `cobEvents` drops a note with an empty
            // `carbs_known_at_ms`, because null means "origin unknown", not
            // "known immediately". Without this line the cluster is empty and
            // the test would pass without checking anything.
            listOf(
                buckwheat to note(buckwheat, "Гречка с колбасками и салатом", 54.0, 22.0, 30.0,
                    "fast=.05;medium=.90;slow=.05"),
                cone to note(cone, "Chocolate Cherry Cornetto", 27.0, 4.0, 15.0,
                    "fast=.80;medium=.15;slow=.05"),
            ).forEach { (ts, n) ->
                val id = store.addAnnotation(n)
                store.writableDatabase.execSQL(
                    "UPDATE annotations SET carbs_known_at_ms=? WHERE id=?",
                    arrayOf<Any?>(ts, id),
                )
            }

            val lens = requireNotNull(HybridRuntimeMetrics.cobLens(store, now)) {
                "lens did not assemble — without an artifact the test checks nothing"
            }
            assertTrue("both meals must land in the cluster", lens.cluster.size == 2)

            // ONE STOMACH vs TWO EMPTY ONES, on the same events.
            fun remaining(shared: Boolean) = lens.cluster.sumOf { event ->
                val age = (now - event.tsMs) / 60_000.0
                val arrived = lens.engine.clusteredFoodCdfTimeline(
                    event, if (shared) lens.cluster else listOf(event), listOf(age),
                ).first()
                event.carbsG * (1.0 - arrived).coerceIn(0.0, 1.0)
            }
            val shared = remaining(true)
            val alone = remaining(false)
            val cob = requireNotNull(HybridRuntimeMetrics.cobGrams(store, now))
            println("COB shared queue %.1f g · alone %.1f g · cobGrams %.1f g"
                .format(shared, alone, cob))

            assertTrue(
                "the shared queue must delay arrival: with it %.1f g remained, " +
                    "alone %.1f g — if not more, the fixture does not distinguish the two models"
                        .format(shared, alone),
                shared > alone + 1.0,
            )
            assertTrue(
                "`cobGrams` must use the shared queue: it gave %.1f g, shared gives %.1f"
                    .format(cob, shared),
                kotlin.math.abs(cob - shared) < 0.01,
            )
            assertTrue(
                "`cobGrams` must not match the empty-stomach rule (%.1f g) — " +
                    "that is exactly what disagreed with the line".format(alone),
                kotlin.math.abs(cob - alone) > 1.0,
            )
        }
}
