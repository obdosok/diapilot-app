package io.github.obdosok.diapilot.i18n

import android.content.Context
import com.diapilot.core.analysis.StatusActing
import com.diapilot.core.analysis.StatusForecast
import com.diapilot.core.analysis.StatusSummary
import com.diapilot.core.analysis.WatchHint
import com.diapilot.core.analysis.fmtBg
import com.diapilot.core.analysis.fmtOneDecimal
import io.github.obdosok.diapilot.R

/**
 * Renders :core's header summary ([StatusSummary]) and the watch hint
 * ([WatchHint]) in the UI language. Works from Compose (pass
 * `LocalContext.current`) and from services/workers alike.
 */
object StatusText {
    /** Joins the independent fragments of the first line; not a sentence. */
    const val SEPARATOR = " · "

    /** 0–2 lines: what is acting now, then where the forecast goes. */
    fun lines(context: Context, s: StatusSummary): List<String> {
        val res = context.localized()
        val out = mutableListOf<String>()
        if (s.acting.isNotEmpty()) out += s.acting.joinToString(SEPARATOR) { acting(res, it) }
        s.forecast?.let { out += forecast(res, it) }
        return out
    }

    /** "40m" / "1h05m", in the UI language. */
    fun age(context: Context, min: Long): String {
        val res = context.localized()
        return if (min < 60) res.getString(R.string.status_age_min, min)
        else res.getString(R.string.status_age_hm, min / 60, min % 60)
    }

    fun watchHint(context: Context, hint: WatchHint): String {
        val res = context.localized()
        return when (hint) {
            is WatchHint.HypoPlan -> res.getString(R.string.watch_hint_hypo_plan, hint.protocol)
            WatchHint.HypoCheck -> res.getString(R.string.watch_hint_hypo_check)
            is WatchHint.AboveTarget -> res.getString(R.string.watch_hint_above_target, fmtBg(hint.settleMmol, hint.mgdl))
        }
    }

    private fun grams(g: Double): String = String.format(java.util.Locale.ROOT, "%.0f", g)

    private fun acting(res: Context, a: StatusActing): String = when (a) {
        is StatusActing.Food -> when {
            a.cobGrams != null ->
                res.getString(R.string.status_food_cob, a.label, age(res, a.ageMin), grams(a.cobGrams!!))
            a.eatenGrams != null ->
                res.getString(R.string.status_food_eaten, a.label, age(res, a.ageMin), grams(a.eatenGrams!!))
            else -> res.getString(R.string.status_food, a.label, age(res, a.ageMin))
        }
        is StatusActing.Iob -> a.lastBolusAgeMin
            ?.let { res.getString(R.string.status_iob_injected, fmtOneDecimal(a.units), age(res, it)) }
            ?: res.getString(R.string.status_iob, fmtOneDecimal(a.units))
        is StatusActing.LastBolus ->
            res.getString(R.string.status_last_bolus, fmtOneDecimal(a.units), age(res, a.ageMin))
    }

    private fun forecast(res: Context, f: StatusForecast): String {
        val lo = f.loMmol
        val hi = f.hiMmol
        val v = if (lo != null && hi != null) {
            res.getString(R.string.status_value_band, fmtBg(f.mmol, f.mgdl), fmtBg(lo, f.mgdl), fmtBg(hi, f.mgdl))
        } else res.getString(R.string.status_value, fmtBg(f.mmol, f.mgdl))
        val settle = f.settleMin ?: 0L
        return when (f.kind) {
            StatusForecast.Kind.IN_AN_HOUR -> res.getString(R.string.status_forecast_in_an_hour, v)
            StatusForecast.Kind.STILL_MOVING -> res.getString(R.string.status_forecast_still_moving, age(res, settle), v)
            StatusForecast.Kind.FLAT -> res.getString(R.string.status_forecast_flat, v)
            StatusForecast.Kind.SETTLES -> res.getString(R.string.status_forecast_settles, v, age(res, settle))
        }
    }
}
