package com.diapilot.core.analysis

import kotlin.math.max

const val FOOD_KINETICS_PREFIX_V2 = "KINETICS_V2"

enum class FoodPhysicalFormV2 { LIQUID, PUREE, SOFT_SOLID, SOLID, MIXED, UNKNOWN }

/**
 * Name-independent facts used to construct a food timing prior. Fractions
 * describe recorded carbohydrate, not a dish label and not glucose amplitude.
 * Every uncertain/LLM field carries confidence and provenance so observed CGM
 * may shrink it later without rewriting the original annotation.
 */
data class FoodKineticFeaturesV2(
    val fastFraction: Double,
    val mediumFraction: Double,
    val slowFraction: Double,
    val physicalForm: FoodPhysicalFormV2 = FoodPhysicalFormV2.UNKNOWN,
    val fiberG: Double? = null,
    val confidence: Double = 0.25,
    val provenance: String = "derived-unknown-v2",
    val alcoholPresent: Boolean = false,
    val proteinG:Double? = null,
    val fatG:Double? = null,
) {
    init {
        require(listOf(fastFraction,mediumFraction,slowFraction,confidence).all { it.isFinite() })
        require(fastFraction >= 0 && mediumFraction >= 0 && slowFraction >= 0)
        require(fastFraction + mediumFraction + slowFraction > 0)
        require(confidence in 0.0..1.0)
        require(fiberG == null || fiberG.isFinite() && fiberG >= 0)
        require(proteinG==null||proteinG.isFinite()&&proteinG>=0)
        require(fatG==null||fatG.isFinite()&&fatG>=0)
        require(provenance.isNotBlank())
    }
    fun normalized():FoodKineticFeaturesV2 {
        val sum=fastFraction+mediumFraction+slowFraction
        return copy(fastFraction=fastFraction/sum,mediumFraction=mediumFraction/sum,slowFraction=slowFraction/sum)
    }
    fun identity():String=normalized().let {
        "${it.fastFraction}:${it.mediumFraction}:${it.slowFraction}:${it.physicalForm}:${it.fiberG}:${it.confidence}:${it.provenance}:${it.alcoholPresent}:${it.proteinG}:${it.fatG}"
    }
}

fun foodKineticsLineV2(v:FoodKineticFeaturesV2):String {
    val n=v.normalized()
    fun d(x:Double)=String.format(java.util.Locale.ROOT,"%.4f",x)
    return buildList {
        add("fast=${d(n.fastFraction)}");add("medium=${d(n.mediumFraction)}");add("slow=${d(n.slowFraction)}")
        add("form=${n.physicalForm.name}");n.fiberG?.let{add("fiber=${d(it)}")}
        add("confidence=${d(n.confidence)}");add("source=${n.provenance.replace(';','_').replace('=','_')}")
        add("alcohol=${n.alcoholPresent}")
        n.proteinG?.let{add("protein=${d(it)}")};n.fatG?.let{add("fat=${d(it)}")}
    }.joinToString(";",prefix="$FOOD_KINETICS_PREFIX_V2: ")
}

const val ALCOHOL_LINE_PREFIX_V2 = "META_ALCOHOL"

/**
 * Alcohol as a fact of its own, independent of carbohydrate.
 *
 * Until this was added, the only carrier was the `alcohol=` field of a KINETICS_V2
 * line, and the composer's "alcohol" chip only reached storage in the label and
 * recipe evidence modes. Measured: `alcohol_present = 1`
 * appeared in ZERO of 45 evidence rows, against 37 notes mentioning beer. The user
 * also does not open a food record for dry wine at all — reasoning that it has no
 * carbs — and the evidence row is only written when carbs are above zero, so the fact
 * had no route into the record even in principle.
 *
 * A separate line rather than a synthesised KINETICS_V2: writing fractions we do
 * not know in order to carry a fact we do know would mark the record as
 * structurally described when it is not, and that provenance is load-bearing.
 */
fun parseAlcoholPresentV2(analysis:String?):Boolean {
    val line=analysis?.lineSequence()?.lastOrNull{it.trim().startsWith("$ALCOHOL_LINE_PREFIX_V2:",true)}?:return false
    return line.substringAfter(':').trim().lowercase() in setOf("true","1","да","yes")
}

private fun explicitFoodKineticsV2(analysis:String):FoodKineticFeaturesV2? {
    val line=analysis.lineSequence().lastOrNull{it.trim().startsWith("$FOOD_KINETICS_PREFIX_V2:",true)}?:return null
    val fields=line.substringAfter(':').split(';').mapNotNull{part->
        val i=part.indexOf('=');if(i<=0)null else part.substring(0,i).trim().lowercase() to part.substring(i+1).trim()
    }.toMap()
    fun d(k:String)=fields[k]?.replace(',','.')?.toDoubleOrNull()
    val fast=d("fast")?:return null;val medium=d("medium")?:return null;val slow=d("slow")?:return null
    return runCatching { FoodKineticFeaturesV2(
        fastFraction=fast,mediumFraction=medium,slowFraction=slow,
        physicalForm=fields["form"]?.let{FoodPhysicalFormV2.valueOf(it.uppercase())}?:FoodPhysicalFormV2.UNKNOWN,
        fiberG=d("fiber"),confidence=d("confidence")?.coerceIn(0.0,1.0)?:.25,provenance=fields["source"]?:"stored-v2",
        alcoholPresent=fields["alcohol"]?.toBooleanStrictOrNull()?:false,proteinG=d("protein"),fatG=d("fat"),
    ).normalized() }.getOrNull()
}

/**
 * A FATTY DISH CANNOT BE PURE FAST CARBOHYDRATE — the user's design rule, and the
 * only one of three proposals that measured a win.
 *
 * WHAT THE LABELLER ACTUALLY DOES on this database: 54% of food-era
 * carbohydrate is filed as fast, and of the 59 dishes at `fast >= 0.95`, 54
 * carry more than 5 g of fat and 20 more than 15 g. The list is pancakes with
 * cheese and ham (30 g fat), crisps with beer (21 g), ice cream (15 g), a
 * burger at fast 0.92 with 35 g. None of those is a fast carbohydrate, and the
 * jam pancake is stored that way as an ACCEPTED dish, so it repeats.
 *
 * WHY IT MATTERS BEYOND ONE MEAL. The fitted fast triangle had to grow to ~104
 * minutes to serve that bucket, and shipping such a triangle writes a labelling
 * error into the physiology — which is where it stops being visible. With the
 * guard applied the SHIPPED 74-minute triangle scores exactly as well as the
 * stretched one (MAE@60 0.693 both), i.e. the stretch was never about fast
 * carbohydrate at all.
 *
 * MEASURED on 24 held-out episodes, everything else held at the shipped
 * triangles and the fitted amplitude and ISF:
 *
 *   labels believed literally     MAE@60 1.047 · @120 2.262 · @180 3.868
 *   this guard                    MAE@60 0.693 · @120 2.262 · @180 3.868
 *   quantised to thirds           MAE@60 1.623 · @120 2.457 · @180 4.112
 *   one class per dish            MAE@60 1.584 · @120 2.464 · @180 4.184
 *
 * So the guard is free at the longer horizons and buys a third of the error at
 * the hour, while both COARSENINGS also proposed lose — they destroy the
 * mixture where the labeller is right, whereas this refuses only what is
 * physically impossible. That asymmetry is the whole design: a rule that fires
 * on 14 episodes of 24 must not touch the other ten.
 *
 * APPLIED AT READ TIME, so it also governs notes already stored — which is
 * where the measurement came from. It therefore CHANGES WHAT AN OLD NOTE MEANS
 * and is recorded in the project's change history rather than only in git.
 *
 * NOT A CLAIM THAT FAT SLOWS CARBOHYDRATE. That effect exists and lives in the
 * gastric term. This is narrower: a dish carrying real fat is not made ONLY of
 * sugar, so a label saying it is, is a parse error rather than a physiology.
 */
const val FAT_NOT_FAST_THRESHOLD_G = 15.0

/** What a fatty dish's fast fraction is capped at. Not zero: chips with beer
 *  really do carry some quick starch, and a rule that erased it would be
 *  trading one confident wrong answer for another. */
const val FAT_NOT_FAST_MAX_FAST = 0.2

/**
 * Moves the excess fast fraction into MEDIUM rather than spreading it, because
 * the dishes this fires on (pancakes with cheese, ice cream, chips) are medium
 * foods mislabelled fast, not slow ones. Identity when the dish is lean, when
 * the fat is unknown, or when the label was already modest.
 */
fun capFastForFat(
    v: FoodKineticFeaturesV2,
    fatFallbackG: Double? = null,
): FoodKineticFeaturesV2 {
    // THE LARGER OF THE TWO RECORDS, not the first one found. Measured on the
    // database: 54 notes of 187 carry two different fat figures — the one inside
    // the KINETICS_V2 line and the one on the FATS line — because an ACCEPTED
    // dish stores the template's macros while the FATS line describes the plate
    // actually eaten (e.g. ice cream: a lower figure in the kinetics line, a
    // higher one on the note). Taking
    // whichever came first left a third of the gain on the table at two and
    // three hours. Either record calling the dish fatty is enough, because the
    // guard only ever REFUSES an implausible label and never assigns one.
    val fat = listOfNotNull(v.fatG, fatFallbackG).maxOrNull() ?: return v
    if (fat <= FAT_NOT_FAST_THRESHOLD_G) return v
    val n = v.normalized()
    if (n.fastFraction <= FAT_NOT_FAST_MAX_FAST) return v
    val moved = n.fastFraction - FAT_NOT_FAST_MAX_FAST
    return n.copy(
        fastFraction = FAT_NOT_FAST_MAX_FAST,
        mediumFraction = n.mediumFraction + moved,
    )
}

private data class MetaSpeed(val speed:CarbSpeed,val confidence:Double)

/** Read the component-level structured tool output already stored in META. */
private fun componentSpeedMeta(analysis:String):Map<String,MetaSpeed> {
    val line=analysis.lineSequence().lastOrNull{isMarkerLine(it,META_LINE_PREFIX)}?:return emptyMap()
    return line.substringAfter(':').split(';').mapNotNull { raw ->
        val name=normalizeFoodName(raw.substringBefore('[').trim());if(name.isBlank())return@mapNotNull null
        val body=raw.substringAfter('[',"").substringBeforeLast(']',"").lowercase()
        val speed=when { Regex("(?:^|[^a-z])fast(?:$|[^a-z])").containsMatchIn(body)->CarbSpeed.FAST
            Regex("(?:^|[^a-z])slow(?:$|[^a-z])").containsMatchIn(body)->CarbSpeed.SLOW
            Regex("(?:^|[^a-z])med(?:$|[^a-z])").containsMatchIn(body)->CarbSpeed.MED
            else->return@mapNotNull null }
        val confidence=Regex("conf\\s+([0-9]+(?:[.,][0-9]+)?)").find(body)?.groupValues?.get(1)
            ?.replace(',','.')?.toDoubleOrNull()?.coerceIn(0.0,1.0)?:.45
        name to MetaSpeed(speed,confidence)
    }.toMap()
}

/**
 * Prefer explicit V2 facts, then component-level LLM facts. GI is a numeric
 * fallback. No title, ingredient name or language dictionary is consulted.
 */
fun parseFoodKineticsV2(analysis:String?,proteinG:Double?=null,fatG:Double?=null):FoodKineticFeaturesV2 {
    if(analysis.isNullOrBlank()) return FoodKineticFeaturesV2(.20,.60,.20,confidence=.15)
    // The alcohol line is orthogonal to how carb speed was established, so it
    // rides along every branch below instead of only the explicit one.
    val alcohol=parseAlcoholPresentV2(analysis)
    // Both post-processing steps ride every branch below. The fat guard is
    // applied HERE rather than at write time so that notes already stored are
    // governed by it too — the branch that returns them (`explicitFoodKineticsV2`)
    // returns early, and a guard placed anywhere else would silently miss the
    // entire existing corpus, which is exactly the case it was measured on.
    fun withAlcohol(v:FoodKineticFeaturesV2)=
        capFastForFat(if(alcohol) v.copy(alcoholPresent=true) else v, fatG)
    explicitFoodKineticsV2(analysis)?.let{return withAlcohol(it)}
    val meta=componentSpeedMeta(analysis);val components=parseComponentsEstimate(analysis)
    var fast=0.0;var medium=0.0;var slow=0.0;var total=0.0;var confidenceMass=0.0
    components.forEach{(raw,grams)->
        if(grams<=0)return@forEach
        val m=meta[normalizeFoodName(raw)]?:return@forEach
        when(m.speed){CarbSpeed.FAST->fast+=grams;CarbSpeed.MED->medium+=grams;CarbSpeed.SLOW->slow+=grams;else->Unit}
        total+=grams;confidenceMass+=grams*m.confidence
    }
    if(total>0)return withAlcohol(FoodKineticFeaturesV2(fast,medium,slow,confidence=(confidenceMass/total).coerceIn(.1,.9),provenance="component-tool-v2").normalized())
    val gi=parseGiEstimate(analysis)
    if(gi!=null){val fractions=when{gi>=70->Triple(.70,.25,.05);gi<=45->Triple(.10,.35,.55);else->Triple(.25,.60,.15)}
        return withAlcohol(FoodKineticFeaturesV2(fractions.first,fractions.second,fractions.third,confidence=.30,provenance="numeric-gi-v2"))}
    // Macros deliberately do not invent carb speed. They are consumed by the
    // continuous timing adapter; keep the neutral mixture here.
    val macroKnown=proteinG!=null||fatG!=null
    return withAlcohol(FoodKineticFeaturesV2(.20,.60,.20,confidence=if(macroKnown).22 else .15,provenance=if(macroKnown)"macro-neutral-v2" else "unknown-neutral-v2"))
}

/** Build the stored whole-meal feature line from validated tool output. */
fun kineticsFromStructuredFoodV2(a:FoodAnalysisOut):FoodKineticFeaturesV2 {
    var fast=0.0;var medium=0.0;var slow=0.0;var total=0.0;var confidenceMass=0.0
    a.components.forEach{c->val grams=max(0.0,c.carbsPerUnit*c.count);if(grams<=0)return@forEach
        when(c.speed?.uppercase()){ "FAST"->fast+=grams;"SLOW"->slow+=grams;else->medium+=grams }
        total+=grams;confidenceMass+=grams*(c.confidence?:.35)}
    if(total<=0){fast=.2;medium=.6;slow=.2;total=1.0;confidenceMass=.2}
    return FoodKineticFeaturesV2(fast/total,medium/total,slow/total,a.physicalForm,a.totalFiberG,
        // v3: the tool's `speed` and `physical_form` descriptions
        // changed materially (carb speed decoupled from fat; a deterministic
        // form ladder), so a note parsed after this point answers a different
        // question than one parsed before. The tag is stored in the note, which
        // is the only way a later corpus can tell the two generations apart.
        // v4 (F-05 layers 4-5): the parse now consults the user's
        // OWN component library instead of reference values, and must NAME what
        // it had to guess (assumptions) instead of assuming silently — the
        // wholegrain-bread class of error becomes a question, not a silent 61%
        // shift into FAST. A different generation of answers, hence a new tag.
        (confidenceMass/total).coerceIn(.05,.95),"llm-structured-v4",a.alcoholPresent,a.totalProteinG,a.totalFatG)
}
