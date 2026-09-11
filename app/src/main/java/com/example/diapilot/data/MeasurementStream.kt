package com.example.diapilot.data

import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.GlucosePoint

/**
 * The ONE sensor stream every measurement is read off.
 *
 * `glucose_readings` is a union of feeds, not a series. On a real device
 * several source apps (`libre_ble`, `xdrip_sgv`, `xdrip_broadcast`,
 * `libre_nfc`, `meter`) can all write it, and for a stretch two of them wrote
 * the SAME sensor simultaneously — seconds apart, disagreeing by a fraction
 * of an mmol. Measured on the same doses, one source can report a
 * meaningfully larger fall than another for the identical event.
 *
 * Every consequence of that is an error in a measured quantity:
 *
 *  - read one window off a mix and the reader measures the sawtooth between
 *    two calibrations as if it were glucose. Corrections computed this way
 *    can diverge sharply from the single-stream value, with one outlier
 *    discarded as "noisy" rather than recognised as a calibration artifact;
 *  - read different doses off different feeds and the corpus mixes calibrations,
 *    so its median is not a measurement of anything. This is the subtler half
 *    and it survived the first fix: choosing the per-WINDOW dominant source
 *    could still pick different feeds for different doses within the same
 *    corpus, because one source stops writing partway through the span.
 *
 * So the choice is made once, over the whole span, and every measurement follows
 * it. A dose the primary stream cannot carry is refused rather than quietly
 * measured on the other calibration — a missing dose is a gap, a substituted one
 * is a wrong number.
 *
 * Deliberately NOT applied to the live forecast anchor. There the question is
 * "what is the glucose right now", and the freshest reading answers it whichever
 * feed carried it. Here the question is "how far did it fall", and an answer
 * assembled from two rulers is not an answer.
 */
object MeasurementStream {
    data class Chosen(val source: String?, val readings: List<GlucosePoint>) {
        val isEmpty: Boolean get() = readings.isEmpty()
    }

    /**
     * The primary feed over `[fromMs, toMs]` and its readings.
     *
     * "Primary" is simply the one that contributed most readings — no feed is
     * named in code, because naming one would work for this user and silently
     * mis-serve anyone whose phone carries a different set.
     *
     * Deliberately NOT cached. The first version was, keyed on the span and the
     * reading count, and a test caught it immediately: two stores with the same
     * shape of data collide on that key and one is served the other's readings.
     * Both callers already cache at their own level and the whole era costs
     * milliseconds, so the cache bought nothing and risked serving a
     * measurement from data that is not the user's.
     */
    fun choose(store: CollectorStore, fromMs: Long, toMs: Long): Chosen {
        // The DECISION moved to core so the laptop stand can obey the
        // same rule; the harness could not follow it while it lived behind a
        // store-shaped signature. This stays as the store-shaped entry point.
        val chosen = com.diapilot.core.analysis.MeasurementStreamCore
            .chooseFrom(store.sensorReadingsWithSource(fromMs, toMs))
        return Chosen(chosen.source, chosen.readings)
    }
}
