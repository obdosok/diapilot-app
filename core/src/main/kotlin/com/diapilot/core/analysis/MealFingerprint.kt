/**
 * The physiological fingerprint of a meal — the model's primary object (not the
 * name, not an exhaustive component list). Built from the component split: each
 * component maps to a concept, carbs drive the amplitude, the carb-WEIGHTED
 * speed drives the shape, and the fat/protein load modifies the tail. Low-carb
 * components (salad, egg) self-silence on the amplitude/speed by construction —
 * no manual pruning.
 */
package com.diapilot.core.analysis

/** One component reduced to what the model needs. */
data class FingerComponent(
    val conceptId: String?,   // null = unmapped (generic bucket)
    val displayName: String,
    val carbGrams: Double,    // absorbable carbs (composition-block grams are carb grams)
    val carbSpeed: CarbSpeed,
)

data class MealFingerprint(
    val totalCarbs: Double,
    val carbSpeed: CarbSpeed,        // carb-weighted dominant speed
    val fatLevel: MacroLevel,        // tail modifier
    val proteinLevel: MacroLevel,    // late-phase modifier
    val fiberLevel: MacroLevel,      // early-phase slowdown
    val components: List<FingerComponent>,
    /** Concept IDs of the CARB DRIVERS (carbs>0) — the pooling/transfer key. */
    val carbDrivers: List<String>,
    /** Amount-aware macro loads (grams): level × portion, so 5 g of cheese or a
     *  sprig of parsley no longer flags the whole plate. Backs the level buckets. */
    val fatGrams: Double = 0.0,
    val proteinGrams: Double = 0.0,
    val fiberGrams: Double = 0.0,
)

/** One component with its NATURAL portion — the amount that makes a macro load
 *  meaningful. portionGrams null → the concept's typical portion is assumed. */
data class FingerInput(val name: String, val carbGrams: Double, val portionGrams: Double? = null)

// Rough grams of macro per 100 g at each concept level — a proxy so the load is
// physical (grams), not just a level. Gentle; measured curves calibrate the rest.
private fun fatPer100(l: MacroLevel) = when (l) { MacroLevel.LOW -> 1.0; MacroLevel.MED -> 5.0; MacroLevel.HIGH -> 18.0 }
private fun proteinPer100(l: MacroLevel) = when (l) { MacroLevel.LOW -> 2.0; MacroLevel.MED -> 8.0; MacroLevel.HIGH -> 20.0 }
private fun fiberPer100(l: MacroLevel) = when (l) { MacroLevel.LOW -> 0.5; MacroLevel.MED -> 2.5; MacroLevel.HIGH -> 5.0 }
private fun fatBucket(g: Double) = when { g >= 12 -> MacroLevel.HIGH; g >= 6 -> MacroLevel.MED; else -> MacroLevel.LOW }
private fun proteinBucket(g: Double) = when { g >= 13 -> MacroLevel.HIGH; g >= 7 -> MacroLevel.MED; else -> MacroLevel.LOW }
private fun fiberBucket(g: Double) = when { g >= 5 -> MacroLevel.HIGH; g >= 3 -> MacroLevel.MED; else -> MacroLevel.LOW }

/** (name, carbGrams) convenience — portion assumed typical per concept. */
fun mealFingerprint(components: List<Pair<String, Double>>): MealFingerprint =
    mealFingerprint(components.map { FingerInput(it.first, it.second) })

/**
 * Physiological fingerprint. Carbs drive amplitude + carb-weighted speed; the
 * fat/protein/fiber loads are AMOUNT-aware (level × portion, summed), so a big
 * fatty plate reads HIGH fat while a garnish does not.
 */
// Carb density (g per 100 g) below which a concept is too carb-light to be a
// pooling KEY — it would pool the meal with anything else that merely contained
// it. Matches the portion-estimate threshold above. A meal made ONLY of such
// concepts still keeps them (fallback), so a salad is not left key-less.
const val MIN_DRIVER_DENSITY_G = 10.0

@JvmName("mealFingerprintInputs")
fun mealFingerprint(inputs: List<FingerInput>): MealFingerprint {
    val fcs = inputs.map { inp ->
        val c = conceptFor(inp.name)
        FingerComponent(
            conceptId = c?.id,
            displayName = inp.name,
            carbGrams = inp.carbGrams.coerceAtLeast(0.0),
            carbSpeed = c?.carbSpeed ?: if (inp.carbGrams > 0) CarbSpeed.MED else CarbSpeed.NONE,
        )
    }
    val totalCarbs = fcs.sumOf { it.carbGrams }
    // Speed is a property of the CARBS only — weight each carb component's speed
    // by its carb grams; non-carb components (NONE) don't vote.
    val carbSpeed = run {
        val voters = fcs.filter { it.carbGrams > 0 && it.carbSpeed != CarbSpeed.NONE }
        if (voters.isEmpty()) CarbSpeed.NONE
        else {
            val w = voters.sumOf { it.carbGrams }
            val avg = voters.sumOf { it.carbSpeed.ordinal * it.carbGrams } / w
            CarbSpeed.entries[kotlin.math.round(avg).toInt().coerceIn(1, 3)]
        }
    }
    // Amount-aware macro loads: for each mapped component, level × its portion
    // (real when logged, else the concept's typical serving), summed → grams.
    var fatG = 0.0; var proteinG = 0.0; var fiberG = 0.0
    inputs.forEachIndexed { i, inp ->
        val c = fcs[i].conceptId?.let { id -> effectiveConcepts().firstOrNull { it.id == id } } ?: return@forEachIndexed
        // Portion drives the macro load. Real portion wins; else for a proper
        // carb food derive it from THIS entry's carbs (so ½/1½/2× scale the load,
        // not just the carbs); low-carb concepts (cheese/meat) fall to typical.
        val portion = inp.portionGrams
            ?: carbPer100gOf(c.id)?.takeIf { it >= 10.0 && inp.carbGrams > 0 }?.let { inp.carbGrams * 100.0 / it }
            ?: typicalPortionOf(c.id) ?: 60.0
        fatG += fatPer100(c.fat) * portion / 100.0
        proteinG += proteinPer100(c.protein) * portion / 100.0
        fiberG += fiberPer100(c.fiber) * portion / 100.0
    }
    return MealFingerprint(
        totalCarbs = totalCarbs,
        carbSpeed = carbSpeed,
        fatLevel = fatBucket(fatG),
        proteinLevel = proteinBucket(proteinG),
        fiberLevel = fiberBucket(fiberG),
        components = fcs,
        carbDrivers = run {
            val carbBearing = fcs.filter { it.carbGrams > 0 }.mapNotNull { it.conceptId }.distinct()
            // A pooling KEY must carry REAL carbs. Without an explicit composition
            // block the estimate is split across every named component, so a
            // near-zero-carb concept (cucumber, leafy veg) picks up a few grams
            // and then pools the meal with every unrelated dish that also had a
            // vegetable — a "buckwheat with cucumber" note dragged pizza and
            // potato into buckwheat's pool via the shared "vegetable" concept.
            // Keep only carb-DENSE concepts as keys; fall back
            // to all carb-bearing when the meal is genuinely low-carb (a salad).
            val dense = carbBearing.filter { (carbPer100gOf(it) ?: 0.0) >= MIN_DRIVER_DENSITY_G }
            dense.ifEmpty { carbBearing }
        },
        fatGrams = fatG,
        proteinGrams = proteinG,
        fiberGrams = fiberG,
    )
}
