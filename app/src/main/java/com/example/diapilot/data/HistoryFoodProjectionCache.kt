package com.example.diapilot.data

import com.diapilot.core.collector.Annotation
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Persistent cache for the deterministic half of a History food card.
 *
 * Observational attribution is intentionally overlaid on every read because
 * it can arrive later from Stage10.  The key owns every input that can change
 * the forward food curve, so a note edit or model upgrade is a cache miss,
 * never a stale answer disguised as a current one.
 */
object HistoryFoodProjectionCache {
    // v2: the key and the payload both carry the meal. See [key] and [toJson].
    private const val CONTRACT = "history-food-projection-v2"

    /**
     * How far a neighbour can still be part of the same meal.
     *
     * The engine splits a segment on a gap over 120 min, but a member's tail
     * runs far longer, so a dish six hours later can still change what the
     * shared pipe did to this one. Over-invalidating is cheap here — a miss
     * costs one recomputation — while under-invalidating is precisely the
     * defect this constant exists to close.
     */
    private const val CLUSTER_CONTEXT_MS = 6L * 60L * 60_000L
    private const val TABLE = "history_food_projection_cache_v1"

    private fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * THE KEY OWNS THE MEAL, not just the dish.
     *
     * Review finding: adding a second dish beside an already-cached one did
     * not invalidate the first dish's card, although its APPLIED curve had
     * changed — the two now share one gastric pipe. A card that describes a
     * dish inside a meal must miss when the meal changes.
     */
    private fun key(modelIdentity: String, note: Annotation, cluster: List<Annotation>): String = hash(
        listOf(
            CONTRACT, modelIdentity, note.id, note.tsMs, note.content,
            note.estCarbs, note.analysis,
            cluster.filter { it.id != note.id }
                .sortedBy { it.tsMs }
                .joinToString(";") { "${it.tsMs}:${it.estCarbs}:${it.analysis?.hashCode() ?: 0}" },
        ).joinToString("|"),
    )

    /** Everything that could share a meal with [note]. */
    private fun clusterContext(note: Annotation, all: List<Annotation>): List<Annotation> =
        all.filter { kotlin.math.abs(it.tsMs - note.tsMs) <= CLUSTER_CONTEXT_MS }

    private fun ensure(store: SqliteCollectorStore) {
        store.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "cache_key TEXT PRIMARY KEY, note_id INTEGER NOT NULL, " +
                "payload_json TEXT NOT NULL, updated_at_ms INTEGER NOT NULL)",
        )
    }

    fun getOrCompute(
        store: SqliteCollectorStore,
        modelIdentity: String,
        notes: List<Annotation>,
        compute: (List<Annotation>) -> Map<Long, HybridFoodReadout>,
    ): Map<Long, HybridFoodReadout> {
        if (notes.isEmpty()) return emptyMap()
        ensure(store)
        val keys = notes.associateWith { key(modelIdentity, it, clusterContext(it, notes)) }
        val hits = mutableMapOf<Long, HybridFoodReadout>()
        keys.forEach { (note, cacheKey) ->
            store.readableDatabase.rawQuery(
                "SELECT payload_json FROM $TABLE WHERE cache_key=?",
                arrayOf(cacheKey),
            ).use { cursor ->
                if (cursor.moveToFirst()) runCatching {
                    fromJson(JSONObject(cursor.getString(0))).copy(
                        observed = HybridRuntimeMetrics.currentFoodObservation(note.tsMs),
                    )
                }.getOrNull()?.let { hits[note.id] = it }
            }
        }
        val missing = notes.filterNot { hits.containsKey(it.id) }
        if (missing.isEmpty()) return hits
        // COMPUTE WITH THE NEIGHBOURS, STORE ONLY THE MISSES. Handing `compute`
        // the missing rows alone was worse than a stale key: a single miss was
        // recomputed as though that dish had been eaten by itself, so the
        // cluster curve the card is supposed to show could not exist. The
        // neighbours are context, not output — they are not re-stored, so the
        // cost stays proportional to the misses.
        val context = missing.flatMap { clusterContext(it, notes) }.distinctBy { it.id }
            .sortedBy { it.tsMs }
        val computed = compute(context)
        val fresh = computed.filterKeys { id -> missing.any { it.id == id } }
        val now = System.currentTimeMillis()
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            missing.forEach { note ->
                val value = fresh[note.id] ?: return@forEach
                db.execSQL(
                    "INSERT OR REPLACE INTO $TABLE(cache_key,note_id,payload_json,updated_at_ms) VALUES(?,?,?,?)",
                    arrayOf<Any?>(keys.getValue(note), note.id, toJson(value.copy(observed = null)).toString(), now),
                )
            }
            // Cache is expendable. Bound it without ever touching source facts.
            db.execSQL(
                "DELETE FROM $TABLE WHERE cache_key NOT IN " +
                    "(SELECT cache_key FROM $TABLE ORDER BY updated_at_ms DESC LIMIT 600)",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return hits + fresh
    }

    private fun toJson(r: HybridFoodReadout) = JSONObject().apply {
        put("amplitude", r.amplitudeMmol); put("onset", r.onsetMin)
        put("peak", r.peakMin); put("duration", r.durationMin)
        put("group", r.group); put("component", r.componentBased)
        put("carbs", r.carbsG ?: JSONObject.NULL); put("label", r.modelLabel)
        put("amplitude_basis", r.amplitudeBasis); put("timing_basis", r.timingBasis)
        put("template", r.timingTemplateId ?: JSONObject.NULL)
        put("source", r.timingSource ?: JSONObject.NULL)
        put("kinetics", r.kineticsSummary ?: JSONObject.NULL)
        // WHAT THE CARD ACTUALLY PRINTS. `half` was missing entirely, so a
        // cached row silently fell back to `peakMin` — the very number the card
        // stopped showing because it jumps between humps. The
        // cluster fields were missing for the same reason: they were added to
        // the readout and not to its serialisation, so the applied curve
        // survived exactly until the first cache write.
        put("half", r.halfArrivalMin)
        put("protein", r.proteinG ?: JSONObject.NULL)
        put("model_curve", org.json.JSONArray(r.modelCurveMmol))
        put("mixture_curve", org.json.JSONArray(r.mixtureCurveMmol))
        put("fat", r.fatG ?: JSONObject.NULL)
        put("cluster_members", r.clusterMembers)
        put("cluster_half", r.clusterHalfArrivalMin ?: JSONObject.NULL)
        put("cluster_duration", r.clusterDurationMin ?: JSONObject.NULL)
        put("cluster_prior", r.clusterPriorRealised ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject) = HybridFoodReadout(
        amplitudeMmol = o.getDouble("amplitude"), onsetMin = o.getInt("onset"),
        peakMin = o.getInt("peak"), durationMin = o.getInt("duration"),
        group = o.getString("group"), componentBased = o.getBoolean("component"),
        carbsG = o.optDouble("carbs", Double.NaN).takeUnless(Double::isNaN),
        modelLabel = o.getString("label"), amplitudeBasis = o.getString("amplitude_basis"),
        timingBasis = o.getString("timing_basis"),
        timingTemplateId = if (o.isNull("template")) null else o.optString("template").takeIf { it.isNotBlank() },
        timingSource = if (o.isNull("source")) null else o.optString("source").takeIf { it.isNotBlank() },
        kineticsSummary = if (o.isNull("kinetics")) null else o.optString("kinetics").takeIf { it.isNotBlank() },
        halfArrivalMin = if (o.isNull("half")) o.getInt("peak") else o.getInt("half"),
        proteinG = if (o.isNull("protein")) null else o.getDouble("protein"),
        mixtureCurveMmol = o.optJSONArray("mixture_curve")?.let { a ->
            (0 until a.length()).map { a.getDouble(it) }
        }.orEmpty(),
        modelCurveMmol = o.optJSONArray("model_curve")?.let { a ->
            (0 until a.length()).map { a.getDouble(it) }
        } ?: emptyList(),
        fatG = if (o.isNull("fat")) null else o.getDouble("fat"),
        clusterMembers = o.optInt("cluster_members", 1),
        clusterHalfArrivalMin = if (o.isNull("cluster_half")) null else o.getInt("cluster_half"),
        clusterDurationMin = if (o.isNull("cluster_duration")) null else o.getInt("cluster_duration"),
        clusterPriorRealised = if (o.isNull("cluster_prior")) null else o.getDouble("cluster_prior"),
    )
}
