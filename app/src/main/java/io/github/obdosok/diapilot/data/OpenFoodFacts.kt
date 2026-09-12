package io.github.obdosok.diapilot.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Barcode → label facts, via Open Food Facts.
 *
 * This feeds the "Label+weight" evidence path and NOTHING
 * else: what comes back is the label's per-100g numbers (carbs, macros,
 * calories) — a measured quantity, not physiology. The user still supplies the actual weight, the
 * total is recomputed by the existing arithmetic, and submit stays blocked
 * until it validates. Nothing is stored on scan; the fields are prefilled
 * VISIBLE and editable, and only the user's own confirmation writes.
 *
 * Why OFF is treated as a label source rather than an estimate: the numbers
 * are transcriptions of the package label, which is exactly what the user would
 * type by hand — with the same rounding class the evidence row already
 * declares (`recomputed_label_rounding`). Crowd data can be stale, which is
 * why the values are shown for the user's confirmation instead of applied silently.
 */
object OpenFoodFacts {

    data class Product(
        val barcode: String,
        val name: String?,
        val brand: String?,
        val carbsPer100g: Double?,
        val proteinPer100g: Double?,
        val fatPer100g: Double?,
        val kcalPer100g: Double?,
        /** The label's own serving size in grams, when the package states one. */
        val servingG: Double?,
    ) {
        /** Usable only when it can drive the label arithmetic. */
        val usable: Boolean get() = carbsPer100g != null
    }

    /** Pure parse of the OFF v2 payload — testable without HTTP. */
    fun parse(barcode: String, json: JSONObject): Product? {
        if (json.optInt("status", 1) == 0) return null
        val p = json.optJSONObject("product") ?: return null
        val n = p.optJSONObject("nutriments")
        fun d(key: String): Double? = n?.optDouble(key)?.takeIf { !it.isNaN() && it >= 0.0 }
        // «serving_quantity» is grams when unit is g/ml; anything else is not
        // a weight and must not silently become one.
        val servingUnit = p.optString("serving_quantity_unit").lowercase()
        val serving = p.optDouble("serving_quantity").takeIf { !it.isNaN() && it > 0.0 }
            ?.takeIf { servingUnit.isEmpty() || servingUnit == "g" || servingUnit == "ml" }
        return Product(
            barcode = barcode,
            name = p.optString("product_name").trim().takeIf { it.isNotEmpty() },
            brand = p.optString("brands").trim().takeIf { it.isNotEmpty() }?.substringBefore(','),
            carbsPer100g = d("carbohydrates_100g"),
            proteinPer100g = d("proteins_100g"),
            fatPer100g = d("fat_100g"),
            kcalPer100g = d("energy-kcal_100g"),
            servingG = serving,
        )
    }

    /** Macros of the actually-eaten portion — the label speaks per 100 g, the
     *  note records the whole porcia. */
    fun portion(per100: Double?, weightG: Double): Double? =
        per100?.let { it * weightG / 100.0 }

    /**
     * What may be interpolated into the request path: EAN-8 through GTIN-14.
     *
     * The code scanner is configured for EAN/UPC formats, so in practice
     * nothing else arrives — but "in practice" is a property of today's
     * callers, not of this function, and the value goes into a URL unencoded.
     * Checking the shape is cheaper than proving every caller.
     */
    internal val BARCODE = Regex("""^\d{8,14}$""")

    /** Only what [BARCODE] is to the path: a shape check before interpolation,
     *  never a claim that every caller already passes something safe. */
    private val LANG_TAG = Regex("""^[a-z]{2}$""")

    /**
     * Blocking HTTP lookup — invoke on Dispatchers.IO. Null = not found,
     * network failure, or a string that is not a barcode; the manual path
     * stays available either way.
     *
     * [lang] asks OFF for the product name and brand IN that language when it
     * has one — the app's own UI language ("en"/"ru"; see [io.github.obdosok.diapilot.i18n.uiLanguage]),
     * not the phone's, so an English-language install gets English label text
     * instead of whatever the crowd entered first. Falls back to English on
     * anything that isn't a two-letter tag, same as an unrecognised system
     * language already does in [io.github.obdosok.diapilot.i18n.AppLanguage].
     */
    fun lookup(barcode: String, lang: String = "en"): Product? {
        if (!BARCODE.matches(barcode)) return null
        val lc = lang.lowercase().takeIf { LANG_TAG.matches(it) } ?: "en"
        return try {
            val conn = URL(
                "https://world.openfoodfacts.org/api/v2/product/$barcode.json" +
                    "?fields=product_name,brands,nutriments,serving_quantity,serving_quantity_unit" +
                    "&lc=$lc",
            ).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "DiaPilot/1.3 (personal T1D companion)")
                if (conn.responseCode != 200) null
                else parse(barcode, JSONObject(conn.inputStream.bufferedReader().readText()))
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
