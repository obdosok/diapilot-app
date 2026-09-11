/**
 * PLAUSIBILITY GATE — de-trust sensor readings that are physically impossible,
 * without deleting them.
 *
 * Why this exists: overnight the ONLY glucose source is the raw
 * Libre BLE stream (`libre_ble`), and during dawn compression / end-of-life the
 * Libre sensor emits values glucose cannot take — down to −0.38 mmol on one
 * occasion, oscillating plunges 3.8→0.6→2.3→0.5 on another — while every
 * downstream series marks them `health='TRUSTED'`. OOP2 (`minute_readings`) reads the same low
 * (41→21 mg/dl), so arbitrating to OOP2 does not save it: the SENSOR is failing,
 * not one transport. A hypo alert — and worse, a fall-VELOCITY alert — that
 * trusts these fires on a dead sensor, and any drift/recall metric scored on them
 * measures sensor failure, not the model.
 *
 * The gate marks a reading SUSPECT; it never removes it (the point still shows on
 * the chart, it just stops being TRUSTED evidence for an alert or a measurement).
 * The direction of an artifact can be real — the user DID have a mild dip
 * to ~3.5–4 on the occasion that motivated this — but the magnitude (0.5) is
 * fiction, so a SUSPECT point must not anchor a forecast, open an onset, or
 * trigger a fall.
 *
 * Thresholds are calibrated against this user's food-era corpus with a hard
 * constraint: ZERO corroborated real hypos (2.2–3.9, dextrose/symptoms nearby)
 * may be flagged. See the `plausibility` harness study for the calibration table.
 */
package com.diapilot.core.analysis

import com.diapilot.core.collector.GlucosePoint

enum class Plausibility { OK, SUSPECT }

/** Why a reading was de-trusted — for the chart tooltip and the audit table. */
enum class SuspectReason {
    NONE, NON_PHYSICAL, BELOW_FLOOR, FAST_FALL_TO_LOW, EMERGES_LOW_FROM_GAP,
    // A low reading sitting inside a compression/EOL event — the SHOULDER of a
    // hard artifact. Glucose does not plunge to −0.4 and back to 6 in an hour, so
    // the 2.0–3.0 approach and recovery around that plunge are the same failure,
    // not a real low that happens to be nearby.
    ARTIFACT_NEIGHBOR,
}

data class GatedReading(
    val point: GlucosePoint,
    val plausibility: Plausibility,
    val reason: SuspectReason,
) {
    val ok: Boolean get() = plausibility == Plausibility.OK
}

/**
 * Tunables. Defaults calibrated on this user; a body whose real
 * lows genuinely reach ~2.0 would need [floorMmol] lowered — but the constraint
 * is always «0 corroborated real hypos flagged», measured, not assumed.
 */
data class PlausibilityConfig(
    // Beyond the measurement/physiological contract. This is deliberately a
    // population/device bound, not a value fitted to this person's outcomes.
    // 35 mmol/L is ~630 mg/dL; an activation packet shown as 1250 mg/dL is an
    // instrument artifact and must never stretch a chart or anchor a forecast.
    val ceilingMmol: Double = 35.0,
    // A calibrated reading below this is nonphysical for THIS user — real
    // sustained lows bottom ~2.5–2.9 (recall-ceiling finding), and the confirmed
    // dawn dip that read 0.5 raw was really ~3.5. Set to 2.2, the user's real
    // floor: the calibration LIFTS a raw-0.45 dawn artifact to a stored 2.11 (marked
    // TRUSTED), so a 2.0 floor missed it on the current sensor epoch — measured in
    // `gateaccept`. Corroborated real hypos sit ≥3.3, so 2.2 touches none of them.
    val floorMmol: Double = 2.2,
    // Physiological limit on how fast glucose falls, sustained (mmol per minute).
    val maxFallMmolPerMin: Double = 0.15,
    // The fall-rate rule only bites when it ARRIVES in low territory — a fast fall
    // that lands at 6.0 is just a fast fall; one that lands at 2.3 in two minutes
    // is the sensor letting go. Keeps genuine rapid falls into the 3–4 band safe.
    val fastFallArrivesBelowMmol: Double = 3.0,
    // A reading emerging from a gap longer than this, BELOW the last trusted value,
    // is suspect when it lands low — a dropout that resumes at a plausible level is
    // fine; one that resumes at 2.1 after 20 blind minutes is the sensor coming
    // back wrong.
    val gapMin: Double = 15.0,
    val gapEmergesBelowMmol: Double = 3.0,
    // CONTAGION by EXCURSION: a maximal run of readings below [excursionCeilingMmol]
    // (a below-range episode) that CONTAINS a hard artifact (a reading below
    // [hardArtifactMmol], ≤0 included) is one failure event — glucose does not
    // plunge to −0.4 and recover, so the whole episode is the compression, not a
    // real low with a bad point in it. The entire run is de-trusted. A below-range
    // episode with NO impossible value is a real low and is kept. Runs break on a
    // gap longer than [excursionGapMin] or a return above the ceiling.
    val hardArtifactMmol: Double = 1.5,
    val excursionCeilingMmol: Double = 3.9,
    val excursionGapMin: Double = 20.0,
)

/**
 * Annotate [readings] (ascending, on the calibrated analysis scale) with
 * plausibility. Rate/gap rules are evaluated against the last OK reading so an
 * artifact cannot become the baseline that hides the next one.
 */
fun plausibilityGate(
    readings: List<GlucosePoint>,
    cfg: PlausibilityConfig = PlausibilityConfig(),
): List<GatedReading> {
    val out = ArrayList<GatedReading>(readings.size)
    var lastOk: GlucosePoint? = null
    for (p in readings) {
        var reason = SuspectReason.NONE
        val prev = lastOk
        when {
            p.mmol <= 0.0 || p.mmol > cfg.ceilingMmol ->
                reason = SuspectReason.NON_PHYSICAL
            p.mmol < cfg.floorMmol -> reason = SuspectReason.BELOW_FLOOR
            prev != null -> {
                val dtMin = (p.tsMs - prev.tsMs) / 60_000.0
                val drop = prev.mmol - p.mmol
                if (dtMin in 0.1..20.0 && drop / dtMin > cfg.maxFallMmolPerMin &&
                    p.mmol < cfg.fastFallArrivesBelowMmol
                ) {
                    reason = SuspectReason.FAST_FALL_TO_LOW
                } else if (dtMin > cfg.gapMin && p.mmol < prev.mmol &&
                    p.mmol < cfg.gapEmergesBelowMmol
                ) {
                    reason = SuspectReason.EMERGES_LOW_FROM_GAP
                }
            }
        }
        val plaus = if (reason == SuspectReason.NONE) Plausibility.OK else Plausibility.SUSPECT
        if (plaus == Plausibility.OK) lastOk = p
        out.add(GatedReading(p, plaus, reason))
    }

    // CONTAGION pass by excursion: any below-ceiling run that contains a hard
    // artifact is de-trusted whole.
    val gapMs = (cfg.excursionGapMin * 60_000).toLong()
    var i = 0
    while (i < out.size) {
        if (out[i].point.mmol >= cfg.excursionCeilingMmol) { i++; continue }
        var j = i
        var hasHard = false
        while (j < out.size && out[j].point.mmol < cfg.excursionCeilingMmol &&
            (j == i || out[j].point.tsMs - out[j - 1].point.tsMs <= gapMs)
        ) {
            if (out[j].point.mmol < cfg.hardArtifactMmol) hasHard = true
            j++
        }
        if (hasHard) {
            for (k in i until j) if (out[k].ok) {
                out[k] = out[k].copy(plausibility = Plausibility.SUSPECT, reason = SuspectReason.ARTIFACT_NEIGHBOR)
            }
        }
        i = j
    }
    return out
}

/** Convenience: the trusted (OK) subset, for measurement arms that de-trust. */
fun List<GatedReading>.trusted(): List<GlucosePoint> = this.filter { it.ok }.map { it.point }
