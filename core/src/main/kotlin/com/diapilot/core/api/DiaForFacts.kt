package com.diapilot.core.api

import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint

/**
 * DiaPilot's storage → the facts `/api/v1/events` publishes.
 *
 * Everything here is a projection. No number is recomputed, rounded or
 * re-scaled on the way out; the only judgement in this file is WHICH series is
 * published and what each row means.
 *
 * It takes ROWS rather than a store on purpose. The phone reads them from
 * `SqliteCollectorStore` and the laptop harness from a pulled `.sqlite` over
 * JDBC — and this repo has already paid for the alternative: a harness that
 * PORTS a mapping instead of calling it drifts, and then measures a model the
 * phone never ran (`HarnessFoods` lacked the food onset lag for days). One
 * copy, both callers.
 */
object DiaForFacts {

    /** The air shot that purges a fresh cartridge — insulin that left the pen
     *  but never entered the body. It is the one `prime` DiaPilot records.
     *  The stored key; test purposes with [com.diapilot.core.analysis.isPrimePurpose],
     *  which also accepts the older Russian token. */
    const val PURPOSE_AIR = "prime"

    /*
     * THE FLOOR. Nothing before the food-era start ([FoodEra.startMs]) is
     * published, and every builder below enforces it: each takes the era as an
     * argument and drops anything older, so no caller can leak an earlier fact
     * by declaring a wider window. The era is the user's setting and is passed
     * in by the caller; this object holds no date of its own.
     *
     * The era is the day regular food logging started. Everything before it is
     * glucose and insulin with no food at all, which makes it a different
     * regime rather than merely older data, and it may also span unrecorded
     * changes in body state and lifestyle that alter how insulin and glucose
     * relate. A consumer pooling it with the present would be averaging across
     * regimes without being told.
     *
     * The era is anchored in a local zone (see [FoodEra]), because "from the
     * era start" is a statement about the user's days, not about UTC.
     */

    /**
     * The published glucose is the app's own 5-minute series, in mmol/L, at
     * full stored precision — pass `sensorReadingsWithSource`, which already
     * excludes fingersticks.
     *
     * Three deliberate exclusions, each one a scale question:
     *
     *  - **fingersticks** (`source='meter'`). A different instrument answering a
     *    different question; the wire has no field to mark one, and mixing them
     *    into the sensor series would put step changes into a consumer's slope.
     *  - **the per-minute OOP2 stream** (`minute_readings`). That is the raw
     *    ALGORITHM scale, a different scale from this series — which is exactly
     *    why the app keeps it in its own table and never feeds it to analytics.
     *    Publishing it as `glucose` would silently mix two scales, and calling
     *    it `sensor_raw` would be the mislabelling the contract warns against.
     *  - **the meter-calibration lens**. It is refitted continuously from the
     *    latest fingerstick, so applying it would rewrite the value of every
     *    historical point on each refit — tens of thousands of revisions
     *    describing a change in our lens rather than in the user.
     *
     * `source` rides along on each point. Not in the minimal contract, and
     * included anyway: this history contains stretches where two sensors wrote
     * at once on a +0.58 mmol offset, and a consumer that cannot tell them
     * apart will read our transport change as a step in the user.
     */
    fun glucose(readings: List<Pair<GlucosePoint, String?>>, era: FoodEra): List<Fact> =
        readings.mapNotNull { (p, source) ->
            if (!era.contains(p.tsMs)) return@mapNotNull null
            if (!p.mmol.isFinite()) return@mapNotNull null
            Fact(
                id = glucoseId(p.tsMs),
                type = EventPayloads.TYPE_GLUCOSE,
                occurredAtMs = p.tsMs,
                payload = EventPayloads.glucose(p.mmol, GlucoseUnit.MMOL_L, source),
            )
        }

    /**
     * Boluses and the long-acting shot, both as `insulin`.
     *
     * Pass `bolusesAll` (not `boluses`): the air shot is excluded from every
     * analytic path inside the app, but it IS a real event a consumer should
     * see — labelled `prime`, so it can exclude it for its own reasons.
     *
     * `delivery_status` is always `delivered`, and that is a statement about
     * the sources rather than a convenient default: a pen scan reports doses
     * the pen has ALREADY pushed, xDrip treatments and manual entries are
     * recorded after the fact. DiaPilot holds no notion of a programmed dose,
     * so nothing here could be one wearing the wrong label.
     *
     * The id keys the STORAGE ROW — `insulin:event:<ts>` for `insulin_events`,
     * `insulin:basal:<ts>` for `basal_events` — and never the kind. Marking a
     * bolus as an air shot ([PURPOSE_AIR]) flips `kind` to `prime`, and that is a correction of
     * one fact, not the birth of another.
     */
    fun insulin(
        boluses: List<BolusPoint>,
        basal: List<BolusPoint>,
        bolusProduct: String?,
        basalProduct: String?,
        era: FoodEra,
    ): List<Fact> {
        val out = ArrayList<Fact>(boluses.size + basal.size)
        for (b in boluses) {
            if (!era.contains(b.tsMs) || !b.units.isFinite()) continue
            out += Fact(
                id = bolusId(b.tsMs),
                type = EventPayloads.TYPE_INSULIN,
                occurredAtMs = b.tsMs,
                payload = EventPayloads.insulin(
                    b.units,
                    if (com.diapilot.core.analysis.isPrimePurpose(b.purpose)) InsulinKind.PRIME else InsulinKind.BOLUS,
                    bolusProduct,
                    DeliveryStatus.DELIVERED,
                ),
            )
        }
        for (e in basal) {
            if (!era.contains(e.tsMs) || !e.units.isFinite()) continue
            out += Fact(
                id = basalId(e.tsMs),
                type = EventPayloads.TYPE_INSULIN,
                occurredAtMs = e.tsMs,
                payload = EventPayloads.insulin(
                    e.units, InsulinKind.BASAL, basalProduct, DeliveryStatus.DELIVERED,
                ),
            )
        }
        return out
    }

    /**
     * Meals — what the user actually logged, from `annotations`.
     *
     * Only `kind='food'` rows. The other kinds are context notes and tags; none
     * of them carries carbs (measured: 0 of 5 non-food rows), and publishing a
     * note like "walk" as a meal would put phantom food in a consumer's
     * timeline.
     *
     * **The id is the ROW id, not the timestamp** — unlike glucose and insulin,
     * a note's time is editable (`updateAnnotation`). Keying on `ts_ms` would
     * turn a correction of the eating time into a delete plus a new meal;
     * keyed on the row, it is one fact at revision 2, which is what it is.
     *
     * What is NOT published: the photo (`media_ref` is a path inside DiaPilot's
     * private storage — meaningless to another app, and handing out the pointer
     * invites a second copy of the user's photos elsewhere).
     *
     * Grams are the user's RECORDED figure, not a weighing — `carbs_source`
     * says which kind: `manual` typed in, `preset` a stored constant, `llm` the
     * model's estimate, `anchor` the legacy compatibility convention. Consumers
     * must inspect `carb_evidence`: an old `anchor` without recomputable evidence
     * is not promoted to a measured gram count.
     */
    fun meals(
        annotations: List<com.diapilot.core.collector.Annotation>,
        era: FoodEra,
        evidenceByAnnotation: Map<Long, com.diapilot.core.collector.CarbEvidenceV1> = emptyMap(),
    ): List<Fact> =
        annotations.mapNotNull { a ->
            if (a.kind != "food") return@mapNotNull null
            if (!era.contains(a.tsMs)) return@mapNotNull null
            Fact(
                id = mealId(a.id),
                type = EventPayloads.TYPE_MEAL,
                occurredAtMs = a.tsMs,
                payload = EventPayloads.meal(
                    text = a.content,
                    carbsG = a.estCarbs?.takeIf { it.isFinite() },
                    carbsSource = a.carbsSource,
                    analysis = a.analysis,
                    carbEvidence = evidenceByAnnotation[a.id],
                ),
            )
        }

    fun glucoseId(tsMs: Long) = "glucose:$tsMs"
    fun bolusId(tsMs: Long) = "insulin:event:$tsMs"
    fun basalId(tsMs: Long) = "insulin:basal:$tsMs"
    fun mealId(rowId: Long) = "meal:$rowId"
}
