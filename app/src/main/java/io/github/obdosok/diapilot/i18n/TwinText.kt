package io.github.obdosok.diapilot.i18n

import android.content.Context
import com.diapilot.core.twin.HealthReason
import com.diapilot.core.twin.Regime
import com.diapilot.core.twin.TrendNuance
import io.github.obdosok.diapilot.R

/** Renders :core's twin values: trend nuance, regime, forecast health reasons. */
object TwinText {
    /** "speeding up", in the UI language. */
    fun nuance(context: Context, n: TrendNuance): String = context.localized().getString(
        when (n) {
            TrendNuance.ACCELERATING -> R.string.trend_nuance_accelerating
            TrendNuance.DECELERATING -> R.string.trend_nuance_decelerating
            TrendNuance.REVERSED -> R.string.trend_nuance_reversed
        },
    )

    fun regime(context: Context, r: Regime): String = context.localized().getString(
        when (r) {
            Regime.NIGHT -> R.string.regime_night
            Regime.ACTIVITY -> R.string.regime_activity
            Regime.POST_MEAL -> R.string.regime_post_meal
            Regime.POST_BOLUS -> R.string.regime_post_bolus
            Regime.QUIET -> R.string.regime_quiet
        },
    )

    fun healthReason(context: Context, r: HealthReason): String {
        val res = context.localized()
        return when (r) {
            is HealthReason.StaleData -> res.getString(R.string.health_reason_stale, r.ageMin)
            is HealthReason.ThinEvidence -> res.getString(R.string.health_reason_thin_evidence, r.effectiveCorrections)
            HealthReason.WideCorridor -> res.getString(R.string.health_reason_wide_band)
            HealthReason.NoMinuteStream -> res.getString(R.string.health_reason_no_minute_stream)
            is HealthReason.SensorImplausible -> res.getString(R.string.health_reason_sensor_implausible, r.detail)
            is HealthReason.FloorClamped -> res.resources.getQuantityString(
                R.plurals.health_reason_floor_clamped, r.points, r.floorMmol, r.points,
            )
        }
    }
}
