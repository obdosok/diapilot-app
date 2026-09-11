/**
 * Canonical food concepts — the model's UNITS (Phase 0 of the fingerprint
 * model). A meal's components are mapped to stable concept IDs (latin, locale-
 * independent), and the model learns/pools by ID, never by the free Russian
 * name. Localization later = add alias lists per locale; the IDs and everything
 * learned on them stay put.
 *
 * The table is deliberately small — only glycemically significant, recurring
 * staples + macro archetypes. Anything unmapped falls back to a generic macro
 * bucket, so it still contributes (carbs / protein / fat) without a concept-
 * specific curve.
 */
package com.diapilot.core.analysis

/**
 * Minutes between eating and the rise starting — the gastric-emptying delay the
 * absorption curve had no term for.
 *
 * SIXTEEN IS STILL THE RIGHT NUMBER, and it was re-measured against current data
 * and the v8 kernel.
 * Two separate questions live here — keep them apart.
 *
 * **THE LEVEL.** Do NOT read the deconvolution corpus's median (21 min today) as
 * a newer version of this constant: it is a different estimator, timing when the
 * RECOVERED curve crosses a fraction of its own peak, which is later than
 * absorption onset by construction. The estimator that produced this 16 is
 * note→detected-rise onset (originally median 16, n=62); re-taken on 1.7× more
 * pairs it reads 13–15 (n=101–114). And swept directly against forecast error on
 * FOOD-opened segments, at both kernel amplitudes, the bias crosses zero
 * BETWEEN 8 AND 16 MIN and mae is minimised AT 16 — every longer value measured
 * worse, 21 included. So 16 already sits at the far edge of the useful range.
 *
 * NOTE the backtest cannot see any of this (0.01 mmol from lag 0 to 33): almost
 * all of its food is detector-anchored and correctly carries no lag, because a
 * note only becomes a lagged food when no detector meal sits within 45 min and
 * 101 of 118 notes have one. Which also means the «bias +0.64 → +0.35» this
 * comment used to cite was produced by shifting EVERY food including detector
 * meals — the operation the next paragraph calls wrong. Do not quote it again.
 *
 * **PER-CONCEPT.** The spread is real and survives the kernel change (dextrose
 * 17, chips 12, smoothie 19, ice_cream 27, beer 32, hummus 40; the pooled median
 * moves only 22 → 21, so it was never a shadow of the weak kernel). Two things
 * block using it — and «it is just logging habit» is NOT one of them any more.
 * That argument was RETRACTED: the user timestamps a meal manually at the
 * moment eating starts, and 10 of 118 food notes run backwards against id
 * order, so the note time is an eating time. What still blocks it:
 *
 *  - the estimator that would ship is not robust — `predictKinetics` pools by a
 *    WEIGHTED median, and beer's onset lands at the 70th percentile of its own
 *    ten episodes;
 *  - there is nothing to judge it ON: two food-opened segments at h=60 whose
 *    dish would move (both beer), ZERO for breakfast.
 *
 * The leading explanation for the WITHIN-dish spread (beer 9..47, ice_cream
 * 9..51) is now CONSUMPTION DURATION — a drink taken over an hour is not an
 * impulse at t0 — which is an input the model does not have.
 *
 * Also retired: «split by CarbSpeed, SLOW is 12 min from four episodes, faster
 * than MED's 27». Today's corpus has no SLOW episodes at all, and FAST (18) sits
 * below MED (31) — the ordering physiology predicts.
 *
 * When the era can carry it, the lag prior belongs beside speedTtpPrior — and
 * ONE-SIDED, `max(16, measured)`, never shorter than this. A later meal draws a
 * DEEPER dip, so overstating the lag overstates hypo risk while understating it
 * hides risk.
 */
const val FOOD_ONSET_LAG_MIN = 16.0

/** How fast a component's carbs hit the blood — the shape prior. */
enum class CarbSpeed { NONE, SLOW, MED, FAST }

/** Coarse macro load — humans/LLM judge the category better than grams. */
enum class MacroLevel { LOW, MED, HIGH }

data class FoodConcept(
    val id: String,
    val carbSpeed: CarbSpeed,
    val fat: MacroLevel,
    val protein: MacroLevel,
    val fiber: MacroLevel,
    val aliasesRu: List<String>,
)

/**
 * Curated staples. Grow by editing this list; localize by adding aliases.
 * carbSpeed=NONE marks a non-carb component (protein/fat/veg) — it never drives
 * the early rise, only the tail (fat/protein) or slowdown (fiber).
 */
val FOOD_CONCEPTS: List<FoodConcept> = listOf(
    // --- carbohydrate drivers (the early rise) ---
    FoodConcept("bread", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.MED, listOf("хлеб", "хлебушек", "багет", "булка", "тост")),
    FoodConcept("buckwheat", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.MED, MacroLevel.MED, listOf("гречка", "гречневая", "греча")),
    FoodConcept("rice", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("рис", "рисовая")),
    FoodConcept("pasta", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.MED, MacroLevel.LOW, listOf("паста", "макароны", "спагетти", "лапша")),
    FoodConcept("potato", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("картофель", "картошка", "картоф")),
    FoodConcept("potato_mash", CarbSpeed.FAST, MacroLevel.MED, MacroLevel.LOW, MacroLevel.LOW, listOf("пюре", "картофельное пюре")),
    FoodConcept("oats", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.MED, MacroLevel.HIGH, listOf("овсянка", "овсяная", "геркулес")),
    // Boiled whole-grain porridges the alias table simply did not cover. The gap
    // was not cosmetic: a spelt porridge note can carry the bulk of a meal's carbs,
    // and with no concept those grams are invisible to everything keyed on concepts —
    // the neighbour subtraction misattributed grams that belonged here to onion.
    // Physiologically a sibling of buckwheat/oats (MED, some protein and fibre), but
    // it gets its OWN id rather than an alias of buckwheat: merging them would silently
    // rewrite buckwheat's measured pool, and one concept must not inherit another's
    // episodes just because the model is short of data.
    // The word for "spelt flour" is DELIBERATELY ABSENT, do not "helpfully" add it:
    // a single-word alias matches as a word PREFIX, so that word would swallow the
    // phrases for "half a loaf of bread" (bread), "half a tub of hummus" (hummus)
    // and "half a jar of Nutella" — and that one DOES reach a grams number, offering
    // 21 g/100 g × 200 g = 42 g where bread wants 14. Zero occurrences of that word
    // in the corpus; the word for "spelt (grain)" is used instead. Re-add only
    // together with whole-word matching for it.
    FoodConcept("spelt", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.MED, MacroLevel.MED, listOf("спельта", "булгур", "перловка", "перловая")),
    FoodConcept("cereal_puff", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("хлопья", "мюсли", "сухарик", "сухарики", "кантуччи", "контучч")),
    FoodConcept("pancake", CarbSpeed.FAST, MacroLevel.MED, MacroLevel.MED, MacroLevel.LOW, listOf("блин", "блины", "оладьи", "панкейк")),
    FoodConcept("banana", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.MED, listOf("банан")),
    FoodConcept("berries", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.HIGH, listOf("ягоды", "черника", "клубника", "малина", "голубика")),
    // Sweet baked dough (the flour/sugar drives it fast) — a berry bun is pastry,
    // NOT berries. The alias is a Polish blueberry bun; the dough dominates.
    FoodConcept("pastry", CarbSpeed.FAST, MacroLevel.MED, MacroLevel.LOW, MacroLevel.LOW, listOf("ягодянка", "булочка", "плюшка", "пирожок", "пирог", "круассан", "маффин", "кекс",
        // Cake, EXPLICITLY. Tightening the prefix rule un-mapped a honey-cake-layers
        // note, which had been reaching `sugar` only because the word for "honey"
        // is a prefix of the word for "honeyed". Sponge layers + cream are
        // this archetype — flour-and-sugar dough, fat MED — not table sugar.
        "торт", "коржи", "корж", "медовик")),
    FoodConcept("smoothie", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.MED, listOf("смузи", "фреш")),
    FoodConcept("juice", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("сок", "апельсиновый сок")),
    FoodConcept("chocolate", CarbSpeed.FAST, MacroLevel.HIGH, MacroLevel.LOW, MacroLevel.LOW, listOf("шоколад", "шоколадка", "милка")),
    FoodConcept("nutella", CarbSpeed.FAST, MacroLevel.HIGH, MacroLevel.LOW, MacroLevel.LOW, listOf("нутелла")),
    FoodConcept("ice_cream", CarbSpeed.FAST, MacroLevel.HIGH, MacroLevel.LOW, MacroLevel.LOW, listOf("мороженое", "магнум", "пломбир")),
    FoodConcept("beer", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("пиво", "корона", "портер", "лагер", "эль")),
    FoodConcept("dextrose", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("декстроза", "dextrose", "dextro", "dextrosa", "глюкоза")),
    FoodConcept("sugar", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("сахар", "мёд", "мед", "варенье")),
    FoodConcept("chips", CarbSpeed.FAST, MacroLevel.HIGH, MacroLevel.LOW, MacroLevel.LOW, listOf("чипсы")),
    // Nuts/seeds: few carbs, and what there is crawls behind the fat and fibre.
    // A block of unnamed carbs (pistachios, sunflower seeds) had nowhere to go without this.
    FoodConcept("nuts", CarbSpeed.SLOW, MacroLevel.HIGH, MacroLevel.MED, MacroLevel.HIGH, listOf("орехи", "фисташки", "миндаль", "арахис", "кешью", "фундук", "семечки", "грецкий")),
    // SWEET yogurt is sugar wearing a dairy coat — the added sugar drives it,
    // fast. Kefir is lactose only: little of it, and slow. One «dairy» concept
    // would average the two into a lie (the user's call).
    FoodConcept("yogurt", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.MED, MacroLevel.LOW, listOf("йогурт", "йогуртный", "активиа", "данон")),
    FoodConcept("kefir", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.MED, MacroLevel.LOW, listOf("кефир", "ряженка", "простокваша", "айран", "молоко")),
    FoodConcept("pizza", CarbSpeed.MED, MacroLevel.HIGH, MacroLevel.MED, MacroLevel.LOW, listOf("пицца", "пиццы")),
    FoodConcept("legume", CarbSpeed.SLOW, MacroLevel.LOW, MacroLevel.MED, MacroLevel.HIGH, listOf("чечевица", "фасоль", "нут", "горох", "горошек")),
    // Quinoa is a real carb driver (MED speed), not a zero-carb «vegetable».
    FoodConcept("quinoa", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.MED, MacroLevel.HIGH, listOf("киноа", "кинва")),
    FoodConcept("soup", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.MED, listOf("суп", "щи", "борщ", "том ям", "свекольный суп")),
    // --- moderate carb + fat (spreads) ---
    FoodConcept("hummus", CarbSpeed.MED, MacroLevel.MED, MacroLevel.MED, MacroLevel.MED, listOf("хумус")),
    // --- protein / fat (tail modifiers, ~0 fast carbs) ---
    FoodConcept("egg", CarbSpeed.NONE, MacroLevel.MED, MacroLevel.HIGH, MacroLevel.LOW, listOf("скрэмбл", "яйцо", "яйца", "омлет", "яичница")),
    FoodConcept("chicken", CarbSpeed.NONE, MacroLevel.MED, MacroLevel.HIGH, MacroLevel.LOW, listOf("курица", "куриная", "грудка")),
    FoodConcept("meat_cutlet", CarbSpeed.NONE, MacroLevel.HIGH, MacroLevel.HIGH, MacroLevel.LOW, listOf("котлета", "котлеты", "отбивная", "отбивные", "фрикадельки", "мясо", "тефтели")),
    FoodConcept("fish", CarbSpeed.NONE, MacroLevel.MED, MacroLevel.HIGH, MacroLevel.LOW, listOf("рыба", "тунец", "паста тунца", "лосось")),
    FoodConcept("cheese", CarbSpeed.NONE, MacroLevel.HIGH, MacroLevel.HIGH, MacroLevel.LOW, listOf("сыр", "творог")),
    FoodConcept("butter", CarbSpeed.NONE, MacroLevel.HIGH, MacroLevel.LOW, MacroLevel.LOW, listOf("масло", "сливочное")),
    FoodConcept("pate", CarbSpeed.NONE, MacroLevel.HIGH, MacroLevel.MED, MacroLevel.LOW, listOf("паштет")),
    // --- fiber / negligible (slow the early phase, ~0 carbs) ---
    FoodConcept("salad", CarbSpeed.NONE, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.HIGH, listOf("салат", "зелень", "листья", "петрушка", "укроп", "базилик", "кинза")),
    FoodConcept("vegetable", CarbSpeed.NONE, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.HIGH, listOf("капуста", "огурец", "помидор", "томат", "перец", "кабачок", "брокколи")),
    // Root veg carries a modest SLOW carb load — not zero like leafy/watery veg.
    FoodConcept("root_veg", CarbSpeed.SLOW, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.HIGH, listOf("морковь", "свёкла", "свекла", "кольраби", "репа", "тыква", "пастернак")),
    FoodConcept("tea", CarbSpeed.NONE, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("чай", "кофе", "вода")),

    // --- added after the CONCEPT AUDIT (`compose` §1a) ---------------------------
    // These eight carried CARBS AND NO CONCEPT — invisible to every pool: they
    // inflated a meal's total while contributing to no dish's amplitude. That is a
    // third kind of mis-filing, worse than «the wrong pool»: it is NO pool, and no
    // amount of data fixes it because the grams never arrive.
    //
    // Densities follow the same reading as the rest of the table (as-eaten g/100 g),
    // never a guess tuned to a result. Each is defended in one line.

    // Beetroot kefir soup, eaten cold. Beet + kefir, so root-veg-ish carbs in a
    // liquid — one of the largest orphaned carb sources the audit found.
    FoodConcept("cold_soup", CarbSpeed.MED, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.MED, listOf("холодник", "холодный борщ", "окрошка")),
    // Berry jam/preserve: sugar in water, the fastest thing in this group. Kept
    // SEPARATE from `sugar` because the "preserve" alias already lives there — this is
    // the fruit-preserve form, and the audit found it orphaned.
    FoodConcept("jam", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("джем", "конфитюр", "брусничный", "ягодный джем", "повидло")),
    // Soured-cream confectioner's cream: sugar carried in fat, so the fat slows it.
    FoodConcept("cream_sweet", CarbSpeed.MED, MacroLevel.HIGH, MacroLevel.LOW, MacroLevel.LOW, listOf("крем", "крем сметанный", "сливки взбитые")),
    // Plain soured cream — a fat/protein tail modifier, milk sugar only.
    FoodConcept("sour_cream", CarbSpeed.NONE, MacroLevel.HIGH, MacroLevel.MED, MacroLevel.LOW, listOf("сметана", "йогурт греческий")),
    // Sausage: filler carbs only, and the fat dominates the response.
    FoodConcept("sausage", CarbSpeed.NONE, MacroLevel.HIGH, MacroLevel.HIGH, MacroLevel.LOW, listOf("сосиска", "сосиски", "колбаса", "шпикачки")),
    // Fried mushrooms: negligible carbs, mostly water and fat from the pan.
    FoodConcept("mushroom", CarbSpeed.NONE, MacroLevel.MED, MacroLevel.MED, MacroLevel.MED, listOf("грибы", "грибы жареные", "шампиньоны", "белые")),
    // Crisp fried onion topping — a little sugar, a lot of oil.
    FoodConcept("fried_onion", CarbSpeed.MED, MacroLevel.HIGH, MacroLevel.LOW, MacroLevel.MED, listOf("жареный лук", "лук жареный", "хрустящий лук")),
    // Gravy: thickened stock, starch is the carb.
    FoodConcept("gravy", CarbSpeed.MED, MacroLevel.MED, MacroLevel.LOW, MacroLevel.LOW, listOf("соус", "подливка", "подлива", "гравy")),

    // NON-ALCOHOLIC BEER IS A DIFFERENT PRODUCT, not a variant of `beer`.
    // The user asked for them not to be mixed; the audit showed it was mixed with
    // CHIPS instead, because the phrase for "non-alcoholic beer" matched no concept and its grams fell
    // out of the drivers entirely. Its carbs run two to three times ordinary beer's —
    // the recorded figure ran well above a typical serving's — because the sugars that
    // would have become alcohol are still there. Listed AFTER `beer` deliberately: `conceptFor`
    // resolves by stem, and the longer alias must not be shadowed, so it is checked in
    // its own right rather than as a beer alias.
    FoodConcept("beer_nonalc", CarbSpeed.FAST, MacroLevel.LOW, MacroLevel.LOW, MacroLevel.LOW, listOf("безалкогольное", "пиво безалкогольное", "безалкогольный")),
)

/**
 * Rough carbs per 100 g (cooked/as-eaten) — turns a natural PORTION into carbs
 * the model needs, so the user enters a food name and grams (e.g. "buckwheat
 * 175 g"), not carbs directly. Approximate; the personal carbSens + measured
 * curves calibrate the rest.
 * ~0 for pure protein/fat/veg. mmol/liquid concepts are per 100 ml.
 */
val CARB_PER_100G: Map<String, Double> = mapOf(
    "bread" to 48.0, "buckwheat" to 20.0, "rice" to 28.0, "pasta" to 25.0,
    "potato" to 17.0, "potato_mash" to 15.0, "oats" to 12.0, "spelt" to 21.0, "cereal_puff" to 70.0,
    "pancake" to 30.0, "banana" to 23.0, "berries" to 10.0, "pastry" to 50.0, "smoothie" to 12.0,
    "juice" to 11.0, "chocolate" to 55.0, "nutella" to 57.0, "ice_cream" to 24.0,
    "beer" to 4.0, "dextrose" to 90.0, "sugar" to 95.0, "chips" to 53.0,
    "legume" to 15.0, "soup" to 6.0, "hummus" to 15.0, "pizza" to 30.0, "quinoa" to 21.0,
    "egg" to 1.0, "chicken" to 0.0, "meat_cutlet" to 6.0, "fish" to 0.0,
    "cheese" to 2.0, "butter" to 0.0, "pate" to 2.0,
    "nuts" to 8.0, "yogurt" to 12.0, "kefir" to 4.0,
    "salad" to 3.0, "vegetable" to 4.0, "root_veg" to 8.0, "tea" to 0.0,
    // Added with the concepts above. Same convention: carbs per 100 g AS EATEN.
    "cold_soup" to 6.0,      // beet + kefir, liquid — between `soup` 6 and `kefir` 4
    "jam" to 60.0,           // fruit preserve, just under `sugar` 95 and above `nutella` 57
    "cream_sweet" to 25.0,   // sugared cream: sugar diluted in fat
    "sour_cream" to 3.0,     // milk sugar only, like `kefir` 4
    "sausage" to 3.0,        // filler/starch binder only
    "mushroom" to 3.0,       // near-zero, mostly water
    "fried_onion" to 12.0,   // onion sugars concentrated by frying, above `vegetable` 4
    "gravy" to 8.0,          // thickened stock — starch is the carb
    "beer_nonalc" to 8.0,    // 2-3x ordinary `beer` 4.0: the unfermented sugars remain
)

/** Carbs (g) for a portion of a concept, or null when the concept/density is
 *  unknown (caller falls back to the user's explicit carbs). */
fun carbsForPortion(conceptId: String?, portionGrams: Double): Double? =
    conceptId?.let { carbPer100gOf(it) }?.let { it * portionGrams / 100.0 }

/**
 * Typical portion (g) of ONE serving/piece — lets a "cutlets x2"-style note
 * estimate carbs without the user knowing grams (2 × piece × density). A rough default;
 * entering an explicit portion overrides it.
 */
val TYPICAL_PORTION_G: Map<String, Double> = mapOf(
    "bread" to 30.0, "buckwheat" to 150.0, "rice" to 150.0, "pasta" to 150.0,
    "potato" to 150.0, "potato_mash" to 200.0, "oats" to 200.0, "spelt" to 200.0, "cereal_puff" to 12.0,
    "pancake" to 60.0, "banana" to 120.0, "berries" to 100.0, "pastry" to 80.0, "smoothie" to 250.0,
    "juice" to 200.0, "chocolate" to 20.0, "nutella" to 20.0, "ice_cream" to 80.0,
    "beer" to 330.0, "dextrose" to 5.0, "sugar" to 5.0, "chips" to 30.0,
    "legume" to 150.0, "soup" to 300.0, "hummus" to 40.0, "pizza" to 120.0, "quinoa" to 150.0,
    "egg" to 60.0, "chicken" to 120.0, "meat_cutlet" to 70.0, "fish" to 120.0,
    "cheese" to 30.0, "butter" to 10.0, "pate" to 20.0,
    "nuts" to 30.0, "yogurt" to 150.0, "kefir" to 250.0,
    "salad" to 80.0, "vegetable" to 80.0, "root_veg" to 80.0, "tea" to 200.0,
    "cold_soup" to 300.0, "jam" to 30.0, "cream_sweet" to 30.0, "sour_cream" to 30.0,
    "sausage" to 50.0, "mushroom" to 80.0, "fried_onion" to 20.0, "gravy" to 50.0,
    "beer_nonalc" to 500.0,
)

/** Carbs (g) for ONE serving of a concept when the portion is unknown — the
 *  «in pieces» fallback. Null when unknown. */
fun typicalCarbs(conceptId: String?): Double? =
    conceptId?.let { TYPICAL_PORTION_G[it]?.let { p -> carbsForPortion(conceptId, p) } }

private val CONCEPT_BY_STEM: Map<String, FoodConcept> = buildMap {
    for (c in FOOD_CONCEPTS) for (a in c.aliasesRu) {
        val stem = normalizeFoodName(a).split(" ").firstOrNull()?.take(5).orEmpty()
        if (stem.length >= 3) putIfAbsent(stem, c)
    }
}

/**
 * Does alias [na] match name [n] WITHOUT crossing into an unrelated word? A
 * single-word alias must match a whole word (the word for "juice" must NOT
 * match inside the word for "piece"); a multi-word alias (e.g. "orange juice",
 * "tom yum") matches as a phrase substring; a name shorter than the alias is
 * allowed inside it.
 */
// The word-prefix rule exists for ONE reason: Russian declension (a noun's genitive
// form is still the same word). An inflectional ending is short. Anything longer is
// a DIFFERENT WORD that merely starts the same way, and letting it through is theft,
// not tolerance — measured: the prefix for "honey" claimed "honey cake layers" for
// sugar, the prefix for "chickpea" claimed a form of "Nutella" for legume (opposite
// kinetics: nutella is FAST/HIGH-fat, legume SLOW/HIGH-protein), and the prefix for
// "spelt flour" would have claimed "half a loaf of bread" — the last one reaching a
// GRAMS number in the composer. Truncated stub aliases like the one for "potato" do
// not need the looser rule: their concept is reached by the 5-char stem step below
// (the stems of the adjective and noun forms of "potato" both truncate to the same
// 5 characters).
private const val MAX_INFLECTION_SUFFIX = 3

private fun aliasHit(n: String, na: String): Boolean {
    if (na.isEmpty()) return false
    if (n in na) return true                       // short name inside a longer alias phrase
    return if (na.contains(' ')) na in n           // multi-word alias → phrase substring
    else n.split(' ').any {                        // single-word → word-prefix, DECLENSION-sized
        it.startsWith(na) && it.length - na.length <= MAX_INFLECTION_SUFFIX
    }
}

// Map a free component name to a concept. Exact alias / word-boundary substring
// first, then a 5-char stem (declension-tolerant: an inflected form of "buckwheat"
// still stems to buckwheat). Null = unmapped → the caller uses a generic macro
// bucket.
/**
 * User overrides: normalized-name → concept-id, loaded from on-device storage.
 * Checked BEFORE the built-in table so the user can correct any mapping from the
 * UI without a code change (the concept archetypes stay in code; the NAME→concept
 * assignment is the user's data). Empty until [setUserConceptAliases] is called.
 */
@Volatile
private var USER_ALIASES: Map<String, String> = emptyMap()

/** Install user name→concept-id overrides (keys are normalized on the way in). */
fun setUserConceptAliases(aliases: Map<String, String>) {
    USER_ALIASES = aliases.entries.mapNotNull { (k, v) ->
        val nk = normalizeFoodName(k)
        if (nk.isEmpty() || v.isBlank()) null else nk to v
    }.toMap()
    invalidateConceptCaches()
}

/**
 * A user's edit to a concept — or a concept the user made up entirely.
 *
 * The built-in list is a curated guess at an average kitchen; the person eating
 * knows their own. Density above all: concepts are FIRST a carb-density table
 * (it is what turns a food name and grams into a carb figure, and that figure
 * carries most of the forecast's sensitivity to input error) and only second a
 * pooling key for the model (measured ~0). So the field that
 * matters most is the one nobody could edit.
 *
 * Every field is nullable = "keep whatever the built-in says". A row for an id
 * that isn't built in creates a NEW concept, and then speed/macros are required
 * or it can't answer anything.
 */
data class ConceptOverride(
    val id: String,
    val carbPer100g: Double? = null,
    val portionG: Double? = null,
    val carbSpeed: CarbSpeed? = null,
    val fat: MacroLevel? = null,
    val protein: MacroLevel? = null,
    val fiber: MacroLevel? = null,
    val aliasesRu: List<String> = emptyList(),
)

@Volatile
private var USER_OVERRIDES: Map<String, ConceptOverride> = emptyMap()

fun setUserConceptOverrides(overrides: List<ConceptOverride>) {
    USER_OVERRIDES = overrides.filter { it.id.isNotBlank() }.associateBy { it.id }
    invalidateConceptCaches()
}

/**
 * MEMOISATION OF THE TWO PURE LOOKUPS BELOW, and the reason it is safe.
 *
 * [effectiveConcepts] and [conceptFor] are functions of (their argument,
 * [USER_ALIASES], [USER_OVERRIDES]) and NOTHING else. Those two globals change
 * through exactly two setters, both of which clear this cache — so a cached
 * answer can only be stale if a new setter is added without a clear, which is
 * why the clear sits inside the setters rather than at the call sites.
 *
 * MEASURED, which is why this exists at all: one `conceptFor` call allocated
 * **896 KB** and took **0.68 ms** — it rebuilt the whole concept table, then
 * re-normalised every alias of every concept. A twin build fingerprints each
 * meal twice per carryover pass, over four passes, and the deconvolution runs
 * twice (corpus + dossier audit), so the same handful of names went through that
 * ~16 times each: 144 MB of churn per fingerprint sweep.
 */
private val CONCEPTS_CACHE = java.util.concurrent.atomic.AtomicReference<List<FoodConcept>?>(null)
private val CONCEPT_FOR_CACHE = java.util.concurrent.ConcurrentHashMap<String, Any>()

/** Stand-in for «this name maps to no concept» — ConcurrentHashMap forbids nulls. */
private val NO_CONCEPT = Any()

private fun invalidateConceptCaches() {
    CONCEPTS_CACHE.set(null)
    CONCEPT_FOR_CACHE.clear()
}

/** Built-ins with the user's edits applied, plus any concept they invented. */
fun effectiveConcepts(): List<FoodConcept> =
    CONCEPTS_CACHE.get() ?: buildEffectiveConcepts().also { CONCEPTS_CACHE.set(it) }

private fun buildEffectiveConcepts(): List<FoodConcept> {
    val built = FOOD_CONCEPTS.map { c ->
        val o = USER_OVERRIDES[c.id] ?: return@map c
        c.copy(
            carbSpeed = o.carbSpeed ?: c.carbSpeed,
            fat = o.fat ?: c.fat,
            protein = o.protein ?: c.protein,
            fiber = o.fiber ?: c.fiber,
            // The user's aliases ADD to the built-in ones; they never erase a
            // word that already works.
            aliasesRu = (c.aliasesRu + o.aliasesRu).distinct(),
        )
    }
    val ids = FOOD_CONCEPTS.mapTo(HashSet()) { it.id }
    val invented = USER_OVERRIDES.values
        .filter { it.id !in ids && it.carbSpeed != null }
        .map {
            FoodConcept(
                it.id, it.carbSpeed!!,
                it.fat ?: MacroLevel.LOW, it.protein ?: MacroLevel.LOW, it.fiber ?: MacroLevel.LOW,
                it.aliasesRu,
            )
        }
    return built + invented
}

/** Carb density with the user's edit applied. */
fun carbPer100gOf(conceptId: String): Double? =
    USER_OVERRIDES[conceptId]?.carbPer100g ?: CARB_PER_100G[conceptId]

/** Typical one-portion grams with the user's edit applied. */
fun typicalPortionOf(conceptId: String): Double? =
    USER_OVERRIDES[conceptId]?.portionG ?: TYPICAL_PORTION_G[conceptId]

fun conceptFor(name: String): FoodConcept? {
    val n = normalizeFoodName(name)
    if (n.isEmpty()) return null
    // Keyed on the NORMALISED name, so capitalization and trailing spaces don't split
    // one food name into two cache entries —
    // which is the whole point: the deconvolution asks the same handful of names
    // over and over. See CONCEPT_FOR_CACHE for why this cannot go stale.
    CONCEPT_FOR_CACHE[n]?.let { return if (it === NO_CONCEPT) null else it as FoodConcept }
    val hit = conceptForUncached(n)
    CONCEPT_FOR_CACHE[n] = hit ?: NO_CONCEPT
    return hit
}

/** [conceptFor]'s rules, on an already-normalised name. */
private fun conceptForUncached(n: String): FoodConcept? {
    // 0) User override — a hand-assigned concept beats every built-in rule.
    val concepts = effectiveConcepts()
    USER_ALIASES[n]?.let { id -> concepts.firstOrNull { it.id == id }?.let { return it } }
    // 1) Exact alias — wins over any substring ("tuna paste" = fish, not the
    //    "paste"/"pasta" substring it shares with the pasta alias).
    concepts.firstOrNull { c -> c.aliasesRu.any { normalizeFoodName(it) == n } }?.let { return it }
    // 2) Word-boundary substring — boundary-aware so a short alias never matches
    //    inside an unrelated longer word. A WHOLE-WORD alias beats a mere prefix
    //    (the word for "soup" wins over the prefix it shares with the adjective
    //    "tomato-ish"); ties break on length.
    val words = n.split(' ')
    concepts
        .flatMap { c ->
            c.aliasesRu.mapNotNull { a ->
                val na = normalizeFoodName(a)
                if (!aliasHit(n, na)) return@mapNotNull null
                val whole = n == na || na in words || (na.contains(' ') && na in n)
                Triple(c, if (whole) 1 else 0, na.length)
            }
        }
        .maxWithOrNull(compareBy({ it.second }, { it.third }))
        ?.let { return it.first }
    // 3) 5-char stem of any word (declension-tolerant).
    for (w in n.split(" ")) {
        val stem = w.take(5)
        if (stem.length >= 3) CONCEPT_BY_STEM[stem]?.let { return it }
    }
    return null
}
