package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint

/**
 * The one-calibration rule, as a pure function.
 *
 * `MeasurementStream` (app) already owns this decision for on-device code, and
 * it is the ONLY way a measurement may read glucose: the
 * `glucose_readings` table is a union of feeds, two of which wrote the same sensor six
 * seconds apart disagreeing by ~1.4 mmol, and reading a fall off two rulers
 * produced published wrong numbers (ISF 3.35/3.12 against a single-stream 2.50,
 * insulin peak 39 against 51).
 *
 * It lived behind an Android-facing signature, so `tools` could not follow the
 * rule even while the docs required it. The DECISION moves here; the app keeps
 * its store-shaped entry point and delegates, so there is one rule rather than a
 * second copy in the harness.
 *
 * The choice is made ONCE over the whole span, never per window: per-window
 * selection fixes the sawtooth and leaves the deeper error, where the corpus
 * mixes calibrations ACROSS episodes and its median measures nothing.
 */
object MeasurementStreamCore {
    /**
     * Physiological bounds for a value that may be MEASURED on, chosen by the
     * user. Below 1.5 nobody walks around conscious; the record
     * has never exceeded 28.
     *
     * These are not a display filter and not a write filter. `upsertReading`
     * applies no plausibility check of any kind, and it must not start: the raw
     * fact is the record of what the sensor said, and deleting it would destroy
     * the evidence that a sensor failed. What is refused here is *measuring* on
     * an impossible value — the same stance the stream already takes toward a
     * dose the primary feed cannot carry.
     *
     * Found by accident: nine libre_ble readings at or below ZERO
     * (minimum -0.46) and ninety below 1.5, concentrated on eight days, were
     * feeding ISF, the corpus and a nocturnal-nadir study that reported 2.43
     * mmol as if it were physiology.
     */
    const val MIN_MEASURABLE_MMOL = 1.5
    const val MAX_MEASURABLE_MMOL = 28.0

    fun isMeasurable(mmol: Double): Boolean =
        mmol.isFinite() && mmol >= MIN_MEASURABLE_MMOL && mmol <= MAX_MEASURABLE_MMOL

    data class Chosen(
        val source: String?,
        val readings: List<GlucosePoint>,
        /** Readings the primary feed carried but that are not physiological.
         *  Reported rather than silently dropped: a feed producing them is
         *  failing, and a caller may want to refuse the whole window. */
        val refusedImplausible: Int = 0,
    ) {
        val isEmpty: Boolean get() = readings.isEmpty()
    }

    /**
     * TRANSPORTS THAT READ THE SAME PHYSICAL SENSOR COUNT AS ONE FEED.
     *
     * `libre_nfc` is the same sensor scanned by phone rather than a rival
     * series, so splitting them would make one calibration lose to itself: on
     * the record 11 527 BLE readings and 334 NFC ones describe one sensor.
     *
     * This is the ONE place a feed is named in code, and it is a deliberate
     * exception to the rule below rather than an oversight. It states a
     * PHYSICAL fact — which transports touch which sensor — that no count can
     * recover. A different user's phone with a different set simply finds no
     * group here and falls back to per-source counting, which is the previous
     * behaviour.
     *
     * Moved here from `SensorPreference` (later deleted) together with the
     * decision it carried. That file kept ONE SERIES PER DAY, already ruled
     * insufficient — "per-window selection
     * fixes the sawtooth and leaves the deeper error". Measured before the
     * change: on 20 of 49 food-era days the two rules kept different sets,
     * so the deconvolution corpus and every landmark measurement were reading
     * different glucose two days in five.
     */
    val SAME_SENSOR_GROUPS: List<Set<String>> = listOf(setOf("libre_ble", "libre_nfc"))

    private fun groupKey(source: String): String =
        SAME_SENSOR_GROUPS.firstOrNull { source in it }?.sorted()?.joinToString("+") ?: source

    /**
     * The feed that contributed most readings over the span, and its readings.
     *
     * Beyond [SAME_SENSOR_GROUPS] no feed is named: naming one would work for
     * this user and silently mis-serve anyone whose phone carries a
     * different set.
     *
     * THE COST OF THE ERA-WIDE RULE, measured rather than assumed: on the record
     * it drops 832 readings across three days where the Libre feed had
     * not started yet and only xDrip wrote. Those days are written in a
     * different calibration, and mixing them into a corpus is exactly the error
     * the rule exists to prevent — so the loss is the point, not a side effect.
     */
    fun chooseFrom(tagged: List<Pair<GlucosePoint, String?>>): Chosen {
        // The primary feed is chosen on the RAW count, before the plausibility
        // filter. A feed must not win by having its bad readings removed first,
        // and it must not lose either — the choice is about which calibration
        // this record is written in, not about who is cleaner.
        val primary = tagged.groupingBy { groupKey(it.second.orEmpty()) }.eachCount()
            .maxWithOrNull(compareBy({ it.value }, { it.key }))?.key
        val mine = tagged.filter { groupKey(it.second.orEmpty()) == primary }.map { it.first }
        val usable = mine.filter { isMeasurable(it.mmol) }
        return Chosen(primary, usable, refusedImplausible = mine.size - usable.size)
    }
}
