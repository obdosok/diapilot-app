package io.github.obdosok.diapilot.data

import androidx.test.core.app.ApplicationProvider
import com.diapilot.core.hybrid.HybridPersonModelJson
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HOW MUCH THE MODEL GIVES BEYOND `grams × carbSens` — a measurement, not a
 * claim.
 *
 * A mass-balance check on a synthetic evening showed a gap between the
 * mass-balance estimate and the line the model actually drew. That
 * difference is what this probe looks for.
 *
 * Prints the amplitude of each dish next to its own `grams × carbSens`, i.e.
 * exactly the excess that the COB label cannot show: COB counts CARB GRAMS,
 * while amplitude also carries macro (`*MacroShare`), and these two dishes
 * carry 45 g of fat and 26 g of protein between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MacroBeyondCarbsProbeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `print the amplitude each dish delivers against grams times carbSens`() {
        val model = context.assets.open("models/person_model_v11_runtime.json")
            .use { HybridPersonModelJson.read(it) }
        PhysioForecastRegistry.install(model, "macro-probe")
        val artifact = requireNotNull(PhysioRuntime.artifact())
        val person = requireNotNull(artifact.personModelAt(21.0, emptySet()))
        val cs = person.food.globalFactor
        println("carbSens applied = %.4f mmol/g".format(cs))

        data class Dish(val text: String, val g: Double, val p: Double, val f: Double, val k: String)
        val dishes = listOf(
            Dish("Гречка с колбасками и салатом", 54.0, 22.0, 30.0, "fast=.05;medium=.90;slow=.05"),
            Dish("Chocolate Cherry Cornetto", 27.0, 4.0, 15.0, "fast=.80;medium=.15;slow=.05"),
            // Control: same grams, but no fat and no protein. If the excess is
            // from macro, it must vanish here.
            Dish("control: 54 g no fat", 54.0, 0.0, 0.0, "fast=.05;medium=.90;slow=.05"),
            Dish("control: 27 g no fat", 27.0, 0.0, 0.0, "fast=.80;medium=.15;slow=.05"),
        )
        var totalReal = 0.0
        for (d in dishes) {
            val r = requireNotNull(
                HybridRuntimeMetrics.foodReadoutForModel(
                    person, d.text, d.g,
                    "БЕЛКИ: ${d.p} г\nЖИРЫ: ${d.f} г\n" +
                        "KINETICS_V2: ${d.k};form=SOLID;confidence=.6;source=test;alcohol=false",
                    macroTiming = artifact.macroTiming, physioArtifact = artifact,
                ),
            ) { "readout did not assemble: ${d.text}" }
            val plain = d.g * cs
            println(
                "  %-32s amplitude %5.2f mmol (%3.0f mg/dL) · grams×cs %5.2f · EXCESS %+5.2f (%+3.0f mg/dL) · peak %d"
                    .format(d.text, r.amplitudeMmol, r.amplitudeMmol * 18.0182,
                        plain, r.amplitudeMmol - plain, (r.amplitudeMmol - plain) * 18.0182, r.peakMin),
            )
            if (d.f > 0.0) totalReal += r.amplitudeMmol
        }
        // WHEN CARBS ARRIVE — and what the SIEVE does about it.
        //
        // The question behind this probe: if the calorie queue drains several
        // hundred kcal over many hours, why does the line settle around
        // minute 90 — "do carbs move ahead through a sieve?". The sieve is
        // exactly "how many carbs move AHEAD of fat"
        // (`CarbAppearancePolicyV1.carbSieving`), so sweeping it answers
        // directly: at 0.0 carbs wait for fat, at 1.0 they overtake it
        // entirely. Prints the FRACTION ARRIVED, i.e. the shape of arrival
        // itself.
        val buckwheat = requireNotNull(
            com.diapilot.core.hybrid.hybridFoodEventFromNote(
                0L, "Гречка с колбасками и салатом", 54.0,
                "БЕЛКИ: 30.0 г\nЖИРЫ: 35.0 г\nККАЛ: 660\n" +
                    "KINETICS_V2: fast=0.0;medium=0.9074;slow=0.0926;form=SOLID;fiber=8.0;" +
                    "confidence=0.59;source=llm-structured-v4;alcohol=false;protein=30.0;fat=35.0",
            ),
        )
        val ages = listOf(30.0, 60.0, 90.0, 120.0, 180.0, 240.0, 300.0)
        println()
        println("ARRIVAL OF THE FATTY GRAIN DISH (54 g · 660 kcal), fraction arrived by minute:")
        println("  sieve |    30    60    90   120   180   240   300")
        for (sieve in listOf(0.0, 0.35, 0.65, 1.0)) {
            val eng = com.diapilot.core.hybrid.physioForecastEngine(
                person, artifact.macroTiming,
                appearance = com.diapilot.core.hybrid.CarbAppearancePolicyV1(180.0, sieve),
            )
            val cdf = eng.clusteredFoodCdfTimeline(buckwheat, listOf(buckwheat), ages)
            println("  %4.2f | %s".format(sieve, cdf.joinToString(" ") { "%5.2f".format(it) }))
        }
        println("TOTAL for the two real dishes: %.2f mmol = %.0f mg/dL".format(totalReal, totalReal * 18.0182))
        println("mass-balance estimate:         %.2f mmol = %.0f mg/dL".format(81 * cs, 81 * cs * 18.0182))
    }
}
