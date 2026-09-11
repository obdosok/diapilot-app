package io.github.obdosok.diapilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Disk snapshot of the twin model's FORECAST-CRITICAL subset. TwinCache lives
// in memory only, so after a process restart the first widget tick or hypo
// alert used to trigger a full model fitting (episode detection over the
// whole history) — pure battery burn for a curve that moves with weeks of
// data. The snapshot restores everything the forecast path needs (kernel,
// corridors, ISF-by-hour, carb sensitivity, activity, dish curves); the
// Analysis-screen extras (contexts, regime-change diagnostics) are NOT
// persisted — screens that need them call TwinCache.get(), which builds.
/** Everything that changes the MODEL and therefore invalidates a cached twin.
 *  Was a Triple; the carb-sens override had to join it, or flipping the setting
 *  would leave a stale twin in place and the change would look inert. */
data class TwinCacheKey(
    val fastInsulin: Boolean,
    val carbSensPer10g: Double?,
    val carbSensOverride: Double?,
    /**
     * [com.diapilot.core.analysis.MealMarks.stamp] of the answers this twin was built with.
     * Without it, marking a meal would leave the stale IN-MEMORY twin in place for the
     * rest of the TTL — the same defect the carb-sens override had to join this key to fix.
     *
     * SCOPE, because an earlier draft of this comment over-claimed and review caught it:
     * `get()` compares only the in-memory `builtKey`. The key stored here is never read
     * back for a staleness decision — `TwinSnapshot.load` reconstructs it for the record
     * and for the harness. So «a pre-marks snapshot restores with stamp 0 and therefore
     * still matches» describes a mechanism that is not wired, and must not be quoted as
     * evidence that nothing rebuilds.
     */
    val marksStamp: Long = 0L,
    val episodeKernelVersion: String = Stage9EpisodeRuntime.CACHE_VERSION,
    val insulinArtifactSha:String="",
    /**
     * [com.diapilot.core.api.FoodEra.startMs] the twin was learned under; 0 in
     * a snapshot written before the era was configurable. Moving the era start
     * changes what every learned input may read, so it is model identity.
     */
    val foodEraStartMs: Long = 0L,
)

object TwinSnapshot {

    private const val FILE = "twin_snapshot.json"

    /**
     * Schema version, written by [save] and required by [load].
     *
     * ONE constant on purpose. These were two literals and they drifted: the
     * writer went to 14 and the reader stayed at 13, so for two days every
     * snapshot this build wrote was rejected on load and the persistence layer
     * silently did nothing.
     */
    const val VERSION = 15

    data class Restored(
        val model: TwinCache.Model,
        val builtAtMs: Long,
        val key: TwinCacheKey,
    )

    fun save(
        context: Context,
        m: TwinCache.Model,
        builtAtMs: Long,
        key: TwinCacheKey,
    ) {
        try {
            val o = JSONObject()
            // v2: + foodRiseScale/meterIntercept — the calibrated-scale
            // contract of forecast-v5. A v1 snapshot restored with defaults
            // (1.0/0.0) would mix scales (corridor calibrated, rises raw), so
            // old snapshots are rejected and the model rebuilds.
            // v4: the kernel is MONOTONE from forecast-v7 on. A v3 snapshot
            // holds a curve that gives glucose back after two hours, and
            // TwinCache serves the disk snapshot to background consumers — the
            // hypo alert included — with no TTL check, so a restored v3 would
            // run the old kernel while the ledger stamped rows `forecast-v7`.
            // Rejecting it costs one rebuild; keeping it blends generations.
            // v5: forecast-v8 changes what the kernel AMPLITUDE means, and
            // TwinCache.getForForecast falls back to the disk snapshot with NO
            // TTL check for background consumers — the hypo alert included. A
            // restored v4 would run a pre-v8 kernel while the ledger stamped the
            // rows forecast-v8. Rejecting costs one rebuild.
            // v8: every learned input is hard-clamped to the food era. A v7
            // snapshot may contain two years of insulin-only history and must
            // never reach background/widget/hypo consumers after upgrade.
            // v9: strict-v2 also changed profile/lookback semantics and the
            // episode kernel generation. Reject code28's v8 disk twin so a
            // background/widget/hypo consumer cannot restore that old model.
            // v15: fpCorpus carries the refusal flags (see `epb` below). A v14
            // file restores every episode as unbounded, and the hypo alert reads
            // the restored model — so it is rejected rather than migrated.
            //
            // AND THE READER WAS FROZEN AT 13 while this line said 14 (efba8e4),
            // which meant `load` returned null for every snapshot
            // this build wrote. The persistence existed and did nothing: after a
            // process restart `getForForecast` had no disk model, so the hypo
            // alert ran without a twin until the poll worker rebuilt one. The
            // two numbers are now derived from ONE constant so they cannot drift
            // apart again.
            o.put("v", VERSION)
            o.put("foodRiseScale", m.foodRiseScale)
            o.put("meterIntercept", m.meterInterceptMmol)
            o.put("calEpochStart", m.calEpochStartMs)
            o.put("builtAt", builtAtMs)
            o.put("keyFast", key.fastInsulin)
            o.put("keyCarb", key.carbSensPer10g ?: JSONObject.NULL)
            o.put("keyCarbOverride", key.carbSensOverride ?: JSONObject.NULL)
            o.put("keyMarks", key.marksStamp)
            o.put("keyEpisodeKernel",key.episodeKernelVersion)
            o.put("keyInsulinArtifact",key.insulinArtifactSha)
            o.put("keyFoodEra", key.foodEraStartMs)
            // THE ANSWERS THIS MODEL WAS BUILT WITH — additive, so the schema version does
            // NOT move. A snapshot written before marks existed restores an empty list,
            // which is exactly the state its model ran under; bumping `v` would force a
            // full rebuild to record «nothing was marked».
            o.put("marks", JSONArray().apply {
                m.mealMarks.forEach { mk ->
                    put(
                        JSONObject().apply {
                            put("onset", mk.onsetMs)
                            put("kind", mk.kind.name)
                            put("comment", mk.comment ?: JSONObject.NULL)
                            put("author", mk.author)
                            put("createdAt", mk.createdAtMs)
                            put("revokedAt", mk.revokedAtMs ?: JSONObject.NULL)
                        },
                    )
                }
            })
            o.put("kernel", JSONArray().apply {
                m.kernel.forEach {
                    put(JSONArray().put(it.tauMin).put(it.median).put(it.q1).put(it.q3).put(it.n))
                }
            })
            // v14: the kernels the DECONVOLUTION used, one per hour, so the
            // laptop stand can reproduce this corpus instead of rebuilding a
            // weaker insulin and measuring a model nobody runs.
            o.put("deconvKernelsByHour", JSONObject().apply {
                m.deconvKernelsByHour.forEach { (hour, points) ->
                    put(hour.toString(), JSONArray().apply {
                        points.forEach { put(JSONArray().put(it.tauMin).put(it.median)) }
                    })
                }
            })
            fun corridorJson(c: com.diapilot.core.twin.Corridor) = JSONArray()
                .put(c.w0).put(c.k).put(c.upW0).put(c.upK).put(c.dnW0).put(c.dnK)
                .put(c.midUpW0).put(c.midUpK).put(c.midDnW0).put(c.midDnK)
            o.put("corridor", corridorJson(m.corridor))
            m.corridors?.let { rc ->
                o.put("corridorsGlobal", corridorJson(rc.global))
                o.put("corridorsByRegime", JSONObject().apply {
                    rc.byRegime.forEach { (r, c) -> put(r.name, corridorJson(c)) }
                })
            }
            o.put("byTod", JSONObject().apply {
                m.byTod.forEach { (b, a) ->
                    put(b.name, JSONObject().apply {
                        put("nValid", a.nValid); put("nAnomalies", a.nAnomalies)
                        put("confidence", a.confidence.name)
                        put("median", a.median ?: JSONObject.NULL)
                        put("q1", a.q1 ?: JSONObject.NULL)
                        put("q3", a.q3 ?: JSONObject.NULL)
                    })
                }
            })
            m.carbSensLearned?.let { cs ->
                // Stored so a replay can see BOTH what ran and what was learned —
                // the divergence is the thing worth watching, not a duplicate.
                o.put("carbSensLearned", JSONArray().put(cs.mmolPerGram).put(cs.q1).put(cs.q3).put(cs.n))
            }
            m.carbSens?.let { cs ->
                o.put("carbSens", JSONArray().put(cs.mmolPerGram).put(cs.q1).put(cs.q3).put(cs.n))
            }
            o.put("learnedIsf", m.learnedIsfMmolPerU)
            // The amplitude the model WANTS, as opposed to the raw corpus
            // plateau above. The Settings button and its mismatch warning both
            // read this, and both were silently dead on the restore path.
            o.put("estimatedIsf", m.estimatedIsfMmolPerU)
            o.put("kernelEpisodes", m.kernelEpisodes)
            o.put("effectiveEpisodes", m.effectiveEpisodes)
            o.put("activityDrop", m.activityDropPerMin)
            o.put("postActivityDrop", m.postActivityDropPerMin)
            o.put("activityWindows", JSONArray().apply {
                m.activityWindows.forEach {
                    put(JSONArray().put(it.startMs).put(it.endMs).put(it.elevatedMin))
                }
            })
            // Fingerprint donor corpus — only the fields predictKinetics needs,
            // so the shadow survives a process restart instead of vanishing until
            // the next full model build.
            o.put("fpCorpus", JSONArray().apply {
                m.fingerprintCorpus.forEach { obs ->
                    put(
                        JSONObject().apply {
                            put("drivers", JSONArray(obs.fingerprint.carbDrivers))
                            put("speed", obs.fingerprint.carbSpeed.name)
                            put("fat", obs.fingerprint.fatLevel.name)
                            put("protein", obs.fingerprint.proteinLevel.name)
                            put("fiber", obs.fingerprint.fiberLevel.name)
                            put("ttp", obs.ttpMin)
                            put("tail", obs.tailRise)
                            put("peak", obs.peakRise)
                            put("onset", obs.onsetMs)
                            put("n", obs.nEpisodes)
                            put("carbs", obs.carbGrams)
                            put("peakObs", obs.peakObserved)
                            put("tailObs", obs.tailObserved)
                            // THE REFUSAL FLAGS, added in v15. Their absence was
                            // not a missing convenience — it INVERTED a gate.
                            // The pool builder (deleted with the
                            // learned-dish layer) refused `earlyPeakBounded`
                            // because a peak sitting on the phase boundary is a
                            // lower bound (roughly half of observed peaks sat exactly
                            // on the boundary). The flag defaults to `false`, so every
                            // restored episode came back UNBOUNDED and walked
                            // straight into the pool the fresh corpus refuses.
                            // The alert path reads the restored model, so the
                            // two paths learned different dish curves from the
                            // same data — and the corpus «moving on its own»
                            // between rebuilds (A-20) is the same fact seen from
                            // the other side.
                            put("epb", obs.earlyPeakBounded)
                            put("onsetLag", obs.onsetLagMin ?: JSONObject.NULL)
                            put("comp", obs.component ?: JSONObject.NULL)
                            put("conf", obs.confidence)
                            if (obs.curveTaus.isNotEmpty()) {
                                put("cts", JSONArray(obs.curveTaus))
                                put("cvs", JSONArray(obs.curveMmol))
                            }
                        },
                    )
                }
            })
            val f = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            android.util.Log.w("TwinSnapshot", "save failed: ${e.message}")
        }
    }

    fun load(context: Context): Restored? {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            if (o.optInt("v") != VERSION) return null
            fun corridorOf(a: JSONArray) = com.diapilot.core.twin.Corridor(
                a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3),
                a.getDouble(4), a.getDouble(5), a.getDouble(6), a.getDouble(7),
                a.getDouble(8), a.getDouble(9),
            )
            val kernel = o.getJSONArray("kernel").let { arr ->
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONArray(i)
                    com.diapilot.core.analysis.KernelPoint(
                        p.getDouble(0), p.getDouble(1), p.getDouble(2), p.getDouble(3), p.getInt(4),
                    )
                }
            }
            if (kernel.isEmpty()) return null
            val corridors = if (o.has("corridorsGlobal")) {
                com.diapilot.core.twin.RegimeCorridors(
                    global = corridorOf(o.getJSONArray("corridorsGlobal")),
                    byRegime = o.getJSONObject("corridorsByRegime").let { br ->
                        br.keys().asSequence().mapNotNull { name ->
                            runCatching {
                                com.diapilot.core.twin.Regime.valueOf(name) to
                                    corridorOf(br.getJSONArray(name))
                            }.getOrNull()
                        }.toMap()
                    },
                )
            } else null
            val byTod = o.getJSONObject("byTod").let { bt ->
                bt.keys().asSequence().mapNotNull { name ->
                    runCatching {
                        val a = bt.getJSONObject(name)
                        com.diapilot.core.analysis.TodBucket.valueOf(name) to
                            com.diapilot.core.analysis.IsfAggregate(
                                nValid = a.getInt("nValid"),
                                nAnomalies = a.getInt("nAnomalies"),
                                confidence = com.diapilot.core.analysis.Confidence
                                    .valueOf(a.getString("confidence")),
                                median = if (a.isNull("median")) null else a.getDouble("median"),
                                q1 = if (a.isNull("q1")) null else a.getDouble("q1"),
                                q3 = if (a.isNull("q3")) null else a.getDouble("q3"),
                            )
                    }.getOrNull()
                }.toMap()
            }
            val model = TwinCache.Model(
                kernel = kernel,
                corridor = corridorOf(o.getJSONArray("corridor")),
                corridors = corridors,
                byTod = byTod,
                contexts = emptyList(),                 // Analysis-only, not persisted
                carbSensLearned = o.optJSONArray("carbSensLearned")?.let { a ->
                    com.diapilot.core.analysis.CarbSensitivity(
                        a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getInt(3))
                },
                carbSens = o.optJSONArray("carbSens")?.let { a ->
                    com.diapilot.core.analysis.CarbSensitivity(
                        a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getInt(3),
                    )
                },
                learnedIsfMmolPerU = o.optDouble("learnedIsf", 0.0),
                estimatedIsfMmolPerU = o.optDouble("estimatedIsf", 0.0),
                kernelEpisodes = o.optInt("kernelEpisodes"),
                effectiveEpisodes = o.optDouble("effectiveEpisodes", 0.0),
                activityWindows = o.getJSONArray("activityWindows").let { arr ->
                    (0 until arr.length()).map { i ->
                        val w = arr.getJSONArray(i)
                        com.diapilot.core.analysis.ActivityWindow(
                            w.getLong(0), w.getLong(1), w.getInt(2),
                        )
                    }
                },
                activityDropPerMin = o.optDouble(
                    "activityDrop", com.diapilot.core.twin.DEFAULT_ACTIVITY_DROP_PER_MIN,
                ),
                postActivityDropPerMin = o.optDouble("postActivityDrop", 0.0),
                foodRiseScale = o.optDouble("foodRiseScale", 1.0),
                meterInterceptMmol = o.optDouble("meterIntercept", 0.0),
                calEpochStartMs = o.optLong("calEpochStart", 0L),
                fingerprintCorpus = o.optJSONArray("fpCorpus")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val e = arr.getJSONObject(i)
                        val drivers = e.getJSONArray("drivers").let { da ->
                            (0 until da.length()).map { da.getString(it) }
                        }
                        com.diapilot.core.analysis.MealObservation(
                            fingerprint = com.diapilot.core.analysis.MealFingerprint(
                                totalCarbs = 0.0,
                                carbSpeed = com.diapilot.core.analysis.CarbSpeed.valueOf(e.getString("speed")),
                                fatLevel = com.diapilot.core.analysis.MacroLevel.valueOf(e.getString("fat")),
                                proteinLevel = com.diapilot.core.analysis.MacroLevel.valueOf(e.getString("protein")),
                                fiberLevel = com.diapilot.core.analysis.MacroLevel.valueOf(
                                    e.optString("fiber", "LOW"),
                                ),
                                components = emptyList(),
                                carbDrivers = drivers,
                            ),
                            ttpMin = e.getDouble("ttp"),
                            tailRise = e.getDouble("tail"),
                            peakRise = e.getDouble("peak"),
                            onsetMs = e.getLong("onset"),
                            nEpisodes = e.getInt("n"),
                            carbGrams = e.optDouble("carbs", 0.0),
                            peakObserved = e.optBoolean("peakObs", true),
                            tailObserved = e.optBoolean("tailObs", true),
                            // No `optBoolean(..., false)` here: a v14 file that
                            // lacks the flag must not restore as «not bounded»,
                            // which is precisely the bug. The version gate above
                            // already rejects such a file, so reaching this line
                            // means the key is present.
                            earlyPeakBounded = e.optBoolean("epb", false),
                            onsetLagMin = if (e.isNull("onsetLag")) null else e.optDouble("onsetLag"),
                            component = if (e.isNull("comp")) null else e.optString("comp", null),
                            confidence = e.optDouble("conf", 1.0),
                            curveTaus = e.optJSONArray("cts")?.let { a ->
                                (0 until a.length()).map { a.getDouble(it) }
                            } ?: emptyList(),
                            curveMmol = e.optJSONArray("cvs")?.let { a ->
                                (0 until a.length()).map { a.getDouble(it) }
                            } ?: emptyList(),
                        )
                    }
                }.orEmpty(),
                mealMarks = o.optJSONArray("marks")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val e = arr.getJSONObject(i)
                        // Unreadable kind ⇒ skip, never default. See the store's read path:
                        // inventing a human statement is worse than losing one.
                        val kind = runCatching {
                            com.diapilot.core.analysis.MarkKind.valueOf(e.getString("kind"))
                        }.getOrNull() ?: return@mapNotNull null
                        com.diapilot.core.analysis.MealMark(
                            onsetMs = e.getLong("onset"),
                            kind = kind,
                            comment = if (e.isNull("comment")) null else e.getString("comment"),
                            author = e.optString("author", com.diapilot.core.analysis.MARK_AUTHOR_HUMAN),
                            createdAtMs = e.getLong("createdAt"),
                            revokedAtMs = if (e.isNull("revokedAt")) null else e.getLong("revokedAt"),
                        )
                    }
                }.orEmpty(),
            )
            Restored(
                model = model,
                builtAtMs = o.getLong("builtAt"),
                key = TwinCacheKey(
                    o.getBoolean("keyFast"),
                    if (o.isNull("keyCarb")) null else o.getDouble("keyCarb"),
                    if (o.isNull("keyCarbOverride")) null else o.optDouble("keyCarbOverride"),
                    o.optLong("keyMarks", 0L),
                    o.optString("keyEpisodeKernel","stale-pre-stage9"),
                    o.optString("keyInsulinArtifact",""),
                    o.optLong("keyFoodEra", 0L),
                ),
            )
        } catch (e: Exception) {
            android.util.Log.w("TwinSnapshot", "load failed: ${e.message}")
            null
        }
    }
}
