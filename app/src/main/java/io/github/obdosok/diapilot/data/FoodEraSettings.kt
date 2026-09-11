package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.api.FoodEra
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import java.time.LocalDate
import java.time.ZoneId

/**
 * The stored food-era start: the first local day whose history the app may
 * learn from (see [FoodEra] for what the boundary means).
 *
 * WHERE THE VALUE COMES FROM:
 *  - On a fresh install it is the FIRST-RUN date, in the device's system zone
 *    at that moment. Both are written once, on the first read, so the boundary
 *    does not drift with the clock or with later time-zone changes.
 *  - The user can move it in Settings ([setByUser]). That is the only way it
 *    changes after the first run.
 *
 * NOTHING HERE DELETES DATA. The era only decides what learning reads; older
 * rows stay stored and visible in History. Deleting them is [PreEraPurge], and
 * it is offered only after the user has explicitly chosen an era start
 * ([explicitStartMs] is non-null only then) — never because of a default.
 *
 * `:core` never reads this object: callers pass the [FoodEra] value into core
 * functions. App code without a Context in scope reads [current], which
 * [DiaPilotApplication] loads before any collector, worker or screen runs.
 */
object FoodEraSettings {
    private const val KEY_DATE = "food_era_start_date"
    private const val KEY_ZONE = "food_era_zone"
    private const val KEY_USER_SET = "food_era_user_set"

    @Volatile
    private var cached: FoodEra? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)

    /**
     * Loads the stored era, creating the first-run default when none is stored,
     * and makes it the process-wide [current] value. Always re-reads storage.
     */
    fun init(context: Context, nowMs: Long = System.currentTimeMillis()): FoodEra {
        val p = prefs(context)
        val stored = runCatching {
            FoodEra.parse(p.getString(KEY_DATE, null)!!, p.getString(KEY_ZONE, null)!!)
        }.getOrNull()
        val era = stored ?: FoodEra.startingOnDayOf(nowMs, ZoneId.systemDefault()).also {
            p.edit()
                .putString(KEY_DATE, it.startDate.toString())
                .putString(KEY_ZONE, it.zone.id)
                .putBoolean(KEY_USER_SET, false)
                .apply()
        }
        cached = era
        return era
    }

    /** The configured era, loading it on first use. */
    fun era(context: Context): FoodEra = cached ?: init(context)

    /**
     * The configured era for code that has no Context in scope.
     *
     * Before [init] has run (only possible in a plain JVM unit test; the
     * Application loads it first on a device) this returns an era starting
     * today: the conservative choice, because it lets learning read nothing
     * old rather than everything. It is not stored and deletes nothing.
     */
    fun current(): FoodEra =
        cached ?: FoodEra.startingOnDayOf(System.currentTimeMillis(), ZoneId.systemDefault())

    /** `true` once the user has chosen the era start in Settings. */
    fun isUserSet(context: Context): Boolean = prefs(context).getBoolean(KEY_USER_SET, false)

    /**
     * The purge boundary, or null when the user has not explicitly chosen an
     * era start. A first-run default never makes older history deletable.
     */
    fun explicitStartMs(context: Context): Long? =
        if (isUserSet(context)) era(context).startMs else null

    /**
     * The user moved the era start. Stored in the device's current zone.
     *
     * A start in the future is refused: it would exclude every fact from
     * learning and make the whole history purgeable.
     *
     * Everything learned was derived under the old boundary, so the twin is
     * discarded (in memory and the disk snapshot's claim to be current) and
     * rebuilt on the next read.
     */
    fun setByUser(
        context: Context,
        startDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): FoodEra {
        val era = FoodEra(startDate, zone)
        require(era.startMs <= nowMs) { "food era cannot start in the future" }
        prefs(context).edit()
            .putString(KEY_DATE, era.startDate.toString())
            .putString(KEY_ZONE, era.zone.id)
            .putBoolean(KEY_USER_SET, true)
            .commit()
        cached = era
        TwinCache.onFoodEraChanged()
        return era
    }
}
