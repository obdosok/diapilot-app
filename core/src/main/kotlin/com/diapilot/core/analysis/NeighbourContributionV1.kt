package com.diapilot.core.analysis

import com.diapilot.core.hybrid.HybridForecastEngine
import com.diapilot.core.hybrid.hybridFoodEventFromNote
import kotlin.math.abs

/**
 * WHAT A NEIGHBOURING MEAL WAS STILL POURING IN, in mmol.
 *
 * The deconvolution subtracted a neighbour only when every one of its
 * ingredients had a measured concept profile. A one-off dish never has one, so
 * it was subtracted as ZERO — and a measured case showed a pizza-type dish
 * counted as finished 113 minutes in while its rise went to the dish behind it:
 * a large apparent rise for a modest gram count, several times the carb
 * sensitivity, shown on the card as measured fact.
 *
 * Two decisions are encoded here, both measured on real data:
 *
 *  - SHAPE comes from the forecast's own engine, so the corpus and the forecast
 *    stop holding two different pizzas. Nothing here re-implements a food model.
 *  - AMPLITUDE is the neighbour's OWN recovered per-gram where the previous pass
 *    found one, and the global scale only where it did not. Subtracting every
 *    predecessor at `grams x global CS` over-corrected exactly where it matters:
 *    meals whose neighbour had just started went from 48% above the
 *    no-neighbour reference to 30% below it. With its own amplitude that
 *    residual halves.
 *
 * It is a PASS, never a recursion: [factory] is handed the previous pass's
 * corpus and pass 0 receives an empty one, so nothing reads an amplitude that is
 * being computed from it. Measured convergence on 156 observations: the largest
 * per-episode change falls 0.105 -> 0.034 -> 0.003 over passes 2..4.
 *
 * TABULATED PER MEAL deliberately. The deconvolution asks this for every
 * session at every checkpoint of every other session, and each engine call
 * simulates a gastric queue from the meal's start — live calls did not finish a
 * laptop run in ten minutes, and the phone is slower.
 */
object NeighbourContributionV1 {

    private const val STEP_MIN = 5.0
    private const val SPAN_MIN = 8 * 60.0
    private const val MATCH_MS = 20L * 60_000L

    /** A recovered amplitude above this multiple of the global scale is itself
     *  contamination and must not become a subtractor. */
    private const val MAX_PER_GRAM_FACTOR = 2.0

    /**
     * [useOwnAmplitude] = false subtracts every neighbour at the GLOBAL scale
     * instead of its own recovered per-gram.
     *
     * Its own is not always the better number, and on one measured evening it is
     * the worse one: the pizza-type dish's recovered amplitude is 4.35 mmol for 69 g
     * (0.063 per gram, a quarter of the global scale) precisely BECAUSE the dish
     * behind it took its rise. Subtracting it at that value cannot give the rise
     * back — the iteration then converges to the contaminated solution, which is
     * what "it converges" measured and did not notice.
     */
    fun factory(
        engine: HybridForecastEngine,
        globalPerGram: Double,
        useOwnAmplitude: Boolean = true,
    ): (List<MealObservation>) -> (MealSession, Double) -> Double {
        val cells = (SPAN_MIN / STEP_MIN).toInt() + 1
        val shapeCache = HashMap<Long, DoubleArray>()

        fun shape(session: MealSession, ageMin: Double): Double {
            val table = shapeCache.getOrPut(session.startMs) {
                val events = session.notes.mapNotNull { n ->
                    hybridFoodEventFromNote(n.tsMs, n.content, n.estCarbs, n.analysis)
                }
                val grams = events.sumOf { it.carbsG }
                // ONE PASS PER EVENT INSTEAD OF ONE PER CELL. `foodCdf` walks the
                // caloric queue from the meal's start every time it is asked, so
                // filling 97 cells this way re-walked the same prefix 97 times.
                // `foodCdfSeries` returns the identical values from a single
                // walk — see its note.
                val ages = (0 until cells).map { it * STEP_MIN }
                val series = events.map { engine.foodCdfSeries(it, ages) }
                DoubleArray(cells) { i ->
                    if (grams <= 0.0) 0.0
                    else events.indices.sumOf { j -> events[j].carbsG * series[j][i] } / grams
                }
            }
            return when {
                ageMin <= 0.0 -> 0.0
                ageMin >= (cells - 1) * STEP_MIN -> table[cells - 1]
                else -> {
                    val x = ageMin / STEP_MIN
                    val i = x.toInt()
                    table[i] + (table[i + 1] - table[i]) * (x - i)
                }
            }
        }

        return { corpus ->
            val measured = if (!useOwnAmplitude) emptyMap() else corpus.filter { it.component == null }.associate { obs ->
                val amplitude = obs.peakRise + if (obs.tailObserved) obs.tailRise else 0.0
                obs.onsetMs to (amplitude / obs.carbGrams.coerceAtLeast(1e-9))
                    .coerceIn(0.02, MAX_PER_GRAM_FACTOR * globalPerGram)
            }
            val f: (MealSession, Double) -> Double = { session, ageMin ->
                val grams = session.totalCarbs ?: 0.0
                val perGram = measured.entries
                    .firstOrNull { abs(it.key - session.startMs) < MATCH_MS }?.value
                    ?: globalPerGram
                grams * perGram * shape(session, ageMin)
            }
            f
        }
    }
}
