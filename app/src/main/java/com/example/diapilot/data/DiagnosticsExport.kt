package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.collector.CollectorStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One-shot JSON dump of the food-model diagnostics as the KOTLIN code computes
 * them — the deconvolution corpus + per-concept profiles + the paired shadow A/B
 * — so they can be exported/inspected without re-deriving the math by hand.
 * Written to filesDir/diag.json on every full model build (cheap, overwritten).
 */
object DiagnosticsExport {

    fun write(context: Context, store: CollectorStore, model: TwinCache.Model, nowMs: Long) {
        val corpus = model.fingerprintCorpus
        val o = JSONObject()
        o.put("builtAtMs", nowMs)
        o.put("fpShadowVersion", com.diapilot.core.twin.FORECAST_ALGO_VERSION_FP_SHADOW)

        // --- deconvolution corpus + summary stats ---
        val censored = corpus.count { !it.peakObserved }
        val ttp = corpus.filter { it.peakObserved }.map { it.ttpMin }.sorted()
        val perG = corpus.filter { it.carbGrams > 0 && it.peakRise > 0 }.map { it.peakRise / it.carbGrams }.sorted()
        fun med(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs[xs.size / 2]
        o.put("corpus", JSONObject().apply {
            put("n", corpus.size)
            put("censoredPct", if (corpus.isNotEmpty()) 100 * censored / corpus.size else 0)
            put("tailObservedN", corpus.count { it.tailObserved })
            put("ttpMedian", med(ttp))
            put("mmolPerGramMedian", med(perG))
            put("mmolPerGramMax", perG.lastOrNull() ?: 0.0)
            put("observations", JSONArray().apply {
                corpus.sortedByDescending { it.onsetMs }.forEach { e ->
                    put(JSONObject().apply {
                        put("onset", e.onsetMs)
                        put("onsetLagMin", e.onsetLagMin ?: JSONObject.NULL)
                        put("drivers", JSONArray(e.fingerprint.carbDrivers))
                        put("ttp", e.ttpMin)
                        put("peak", e.peakRise)
                        put("carbs", e.carbGrams)
                        put("mmolPerGram", if (e.carbGrams > 0) e.peakRise / e.carbGrams else 0.0)
                        put("tail", e.tailRise)
                        put("peakObserved", e.peakObserved)
                        put("tailObserved", e.tailObserved)
                        put("component", e.component ?: JSONObject.NULL)
                        put("fat", e.fingerprint.fatLevel.name)
                        put("protein", e.fingerprint.proteinLevel.name)
                        put("fiber", e.fingerprint.fiberLevel.name)
                    })
                }
            })
        })

        // PER-CONCEPT PROFILES ARE NO LONGER EXPORTED: the layer that computed
        // them was removed. The label "what the forecast would have applied"
        // was also wrong by then — the forecast had stopped applying them.

        // --- paired shadow A/B from the ledger (base vs current fp shadow) ---
        (store as? SqliteCollectorStore)?.writableDatabase?.let { db ->
            val since = nowMs - 30L * 24 * 3_600_000
            // THE PAIRED EXPORT IS GONE. `pairedQuality` compared a
            // base tag against a variant tag on the same anchors, and since the
            // ledger records only the arm that is displayed there is no second
            // tag to pair with. The historical pairs it used to read belonged
            // to the prospective A/B, removed around the same time.

            o.put("pairedAB", JSONObject().apply {
            })
        }

        File(context.filesDir, "diag.json").writeText(o.toString(2))
    }
}
