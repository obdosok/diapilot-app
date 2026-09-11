package io.github.obdosok.diapilot.data

import android.content.Context
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import org.json.JSONArray
import org.json.JSONObject

/**
 * F-05 layer 3: aliases that grow from CONFIRMATIONS.
 *
 * Until now the alias list was asset JSON edited by hand. After this, a
 * confirmed "is this your smoothie?" adds the wording the user actually
 * typed, so the vocabulary converges on how the user writes, not on how the
 * asset guessed they would.
 *
 * Rejections are remembered too — "no" must silence THAT question for THAT
 * wording, or the dialogue becomes a nag and gets ignored, which is how the
 * settings-tap discipline died.
 *
 * Storage is prefs JSON: tiny, append-only in practice, and deliberately
 * outside the DB — these are vocabulary, not observations.
 */
object DishAliasRuntime {

    private const val PREF_LEARNED = "dish_aliases_learned"
    private const val PREF_REJECTED = "dish_aliases_rejected"

    private fun prefs(context: Context) =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)

    // ---- pure codec, testable without Android ------------------------------

    fun encode(map: Map<String, List<String>>): String {
        val o = JSONObject()
        map.forEach { (id, list) -> o.put(id, JSONArray(list)) }
        return o.toString()
    }

    fun decode(raw: String?): Map<String, List<String>> = try {
        raw?.let { JSONObject(it) }?.let { o ->
            buildMap {
                o.keys().forEach { id ->
                    val arr = o.optJSONArray(id) ?: return@forEach
                    put(id, (0 until arr.length()).map(arr::getString))
                }
            }
        }.orEmpty()
    } catch (_: Exception) {
        emptyMap()
    }

    /** Add [wording] to [map] under [dishId] — deduplicated, order kept. */
    fun withAlias(map: Map<String, List<String>>, dishId: String, wording: String): Map<String, List<String>> {
        val w = wording.trim()
        if (w.isEmpty()) return map
        val cur = map[dishId].orEmpty()
        if (cur.any { it.equals(w, ignoreCase = true) }) return map
        return map + (dishId to cur + w)
    }

    // ---- persistence -------------------------------------------------------

    fun learned(context: Context): Map<String, List<String>> =
        decode(prefs(context).getString(PREF_LEARNED, null))

    fun addAlias(context: Context, dishId: String, wording: String) {
        prefs(context).edit()
            .putString(PREF_LEARNED, encode(withAlias(learned(context), dishId, wording)))
            .apply()
    }

    private fun rejected(context: Context): Map<String, List<String>> =
        decode(prefs(context).getString(PREF_REJECTED, null))

    fun isRejected(context: Context, dishId: String, wording: String): Boolean =
        rejected(context)[dishId].orEmpty().any { it.equals(wording.trim(), ignoreCase = true) }

    fun reject(context: Context, dishId: String, wording: String) {
        prefs(context).edit()
            .putString(PREF_REJECTED, encode(withAlias(rejected(context), dishId, wording)))
            .apply()
    }
}
