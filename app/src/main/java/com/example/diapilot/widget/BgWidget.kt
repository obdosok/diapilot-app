package com.example.diapilot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.widget.RemoteViews
import com.example.diapilot.MainActivity
import com.example.diapilot.R
import com.example.diapilot.i18n.localized

/**
 * Home-screen widget: the current (calibrated) glucose, trend arrow, 5-min
 * delta, data age and a 3-hour mini chart with the twin's forecast branch.
 * Repainted on every fresh minute of data by the collector service; the
 * 30-min system period is just a safety net.
 */
class BgWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Thread { BgWidget.updateAll(context) }.start()
    }
}

object BgWidget {
    private const val TAG = "BgWidget"
    private const val CHART_W = 640
    private const val CHART_H = 200

    // The chart (forecast + corridor render) is the expensive half of a
    // widget refresh; the text half is cheap. Reuse the bitmap for up to
    // 5 minutes — the minute heartbeat then repaints only value/arrow/age.
    // (Keyed on age, not anchor: minute promotion moves the anchor every
    // minute, and a 1-min-newer anchor barely moves the forecast branch.)
    @Volatile private var cachedChart: Bitmap? = null
    @Volatile private var cachedChartAtMs = 0L
    @Volatile private var cachedChartKey = 0

    /** Repaint every placed widget. Safe to call from any thread. */
    fun updateAll(context: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, BgWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { mgr.updateAppWidget(it, views) }
        } catch (e: Exception) {
            Log.w(TAG, "update failed: ${e.message}")
        }
    }

    private fun build(context: Context): RemoteViews {
        // Widget text in the app language — below API 33 the provider's own
        // context still carries the system language.
        val text = context.localized()
        val views = RemoteViews(context.packageName, R.layout.widget_bg)
        val store = com.example.diapilot.data.Stores.get(context)
        val now = System.currentTimeMillis()
        val mgdl = com.example.diapilot.data.Units.isMgdl(context)
        val rangeLo = com.example.diapilot.data.Settings.rangeLoMmol(context)
        val rangeHi = com.example.diapilot.data.Settings.rangeHiMmol(context)

        // Same lenses as everywhere: minute promotion + meter correction
        // (cached — refitting from 14 days of rows every minute was waste).
        val minuteCal = com.example.diapilot.data.MinuteCalCache.get(store, context)
        val meterCal = com.example.diapilot.data.MeterCalCache.get(store, context)
        fun lens(ts: Long, mmol: Double): Double =
            meterCal?.correctedAt(ts, mmol) ?: mmol

        val sharedAnchor = com.example.diapilot.data.forecastAnchor(store, context, now)
        val lastTs = sharedAnchor?.reading?.tsMs ?: 0L
        val lastMmol = sharedAnchor?.reading?.mmol ?: Double.NaN

        // Trend off the meter-calibrated 5-min MAIN grid — SAME source as the
        // phone header, so the widget arrow never disagrees. The grid is the
        // frozen real-time record; the 1-min oop2 stream retroactively revises
        // its recent history and was seen to overshoot during a rapid drop.
        // Minute stream only as a sparse-grid fallback.
        val delta = run {
            val gridPts = store.sensorReadings(now - 16L * 60_000, now)
                .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, lens(it.tsMs, it.mmol)) }
            com.diapilot.core.twin.gridTrendReadout(gridPts, now)?.delta5Mmol
                ?: if (minuteCal != null) {
                    val pts = store.minuteReadings(now - 15L * 60_000, now)
                        .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
                    com.diapilot.core.twin.trendReadout(pts, now)?.delta5Mmol
                } else null
        }

        if (lastMmol.isNaN()) {
            views.setTextViewText(R.id.widget_value, "—")
        } else {
            views.setTextViewText(
                R.id.widget_value, com.diapilot.core.analysis.fmtBg(lastMmol, mgdl),
            )
            views.setTextColor(
                R.id.widget_value,
                when {
                    lastMmol < rangeLo -> 0xFFEF5350.toInt()
                    lastMmol > rangeHi -> 0xFFFFB74D.toInt()
                    else -> 0xFF81C784.toInt()
                },
            )
        }
        // The shared core convention — the widget must never disagree with
        // the app/watch about the arrow.
        views.setTextViewText(
            R.id.widget_arrow,
            if (delta == null) "" else com.diapilot.core.trendGlyph(com.diapilot.core.trendName(delta)),
        )
        views.setTextViewText(
            R.id.widget_delta,
            delta?.let { com.diapilot.core.analysis.fmtBgDelta(it, mgdl) } ?: "",
        )
        // Minute stream: a fresh age is noise; only a disconnect (>= 3 min)
        // is worth pixels.
        val ageMin = if (lastTs > 0) (now - lastTs) / 60_000 else -1
        views.setTextViewText(
            R.id.widget_age,
            when {
                ageMin < 0 -> text.getString(R.string.bg_widget_no_data)
                ageMin >= com.diapilot.core.PersonalParams.DEFAULT.ageNoiseMin ->
                    text.getString(R.string.bg_widget_age_min, ageMin)
                else -> ""
            },
        )

        // Insulin line: IOB (hidden once spent) + the last dose with age —
        // the pair that tells how active the insulin on board still is.
        val iob = com.example.diapilot.data.HybridRuntimeMetrics
            .surfaceIobUnits(store, context, now) ?: 0.0
        val lastBolus = store.boluses(
            now - com.diapilot.core.PersonalParams.DEFAULT.lastDoseShowMin * 60_000, now,
        ).filter { it.purpose != com.diapilot.core.api.DiaForFacts.PURPOSE_AIR }.lastOrNull()
        val insulinLine = buildString {
            if (iob >= 0.2) append("IOB %.1f".format(java.util.Locale.ENGLISH, iob))
            lastBolus?.let { b ->
                if (isNotEmpty()) append("  ·  ")
                val min = (now - b.tsMs) / 60_000
                val ago = if (min < 60) text.getString(R.string.bg_widget_ago_min, min)
                else text.getString(R.string.bg_widget_ago_hm, min / 60, min % 60)
                append(
                    text.getString(
                        R.string.bg_widget_last_dose,
                        "%.1f".format(java.util.Locale.ENGLISH, b.units), ago,
                    ),
                )
            }
        }
        views.setTextViewText(R.id.widget_insulin, insulinLine)

        val tir = com.example.diapilot.data.tir24h(store, context, now, rangeLo, rangeHi)
        views.setTextViewText(
            R.id.widget_tir,
            tir?.let { "TIR ${it.inRange}%  ·  ↓${it.low}%  ↑${it.high}%" } ?: "TIR —",
        )

        val latestFoodTs = store.annotations(now - 12L * 3_600_000, now)
            .filter { it.kind == "food" }.maxOfOrNull { it.tsMs } ?: 0L
        val latestBolusTs = store.boluses(now - 8L * 3_600_000, now)
            .maxOfOrNull { it.tsMs } ?: 0L
        val chartKey = listOf(lastTs, latestFoodTs, latestBolusTs).hashCode()
        val chart = cachedChart?.takeIf {
            cachedChartKey == chartKey && now - cachedChartAtMs < 5 * 60_000
        }
            ?: renderChart(context, store, now, minuteCal, ::lens, rangeLo, rangeHi).also {
                cachedChart = it
                cachedChartAtMs = now
                cachedChartKey = chartKey
            }
        views.setImageViewBitmap(R.id.widget_chart, chart)

        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    private fun renderChart(
        context: Context,
        store: com.diapilot.core.collector.CollectorStore,
        now: Long,
        minuteCal: com.diapilot.core.analysis.MinuteCalibration?,
        lens: (Long, Double) -> Double,
        rangeLo: Double,
        rangeHi: Double,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(CHART_W, CHART_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val from = now - 3L * 3_600_000
        val horizon = now + 60L * 60_000

        // Curve: the SMOOTHED 5-min main grid (meter-calibrated) — same line
        // the watch draws, not the jittery per-minute OOP2 stream. The grid is
        // also the frozen record the trend/delta are computed from, so the
        // widget's chart, arrow and number all agree.
        val pts = com.example.diapilot.data.displayHistory(store, context, from, now)
            .map { it.tsMs to it.mmol }
            .sortedBy { it.first }

        // Forecast branch from the cached twin.
        val prediction = try {
            val model = com.example.diapilot.data.TwinCache.getForForecast(store, context)
            val anchor = com.example.diapilot.data.forecastAnchor(store, context, now)
            if (model != null && anchor != null) {
                // The shared engine — the widget shows the SAME forecast.
                com.example.diapilot.data.Forecaster.forecast(
                    store, model, now,
                    anchorTsMs = anchor.reading.tsMs,
                    anchorMmol = anchor.reading.mmol,
                    minutePoints = anchor.minutePoints,
                    horizonMin = 60.0,
                    recordAs = "widget",
                )?.points ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val allVals = pts.map { it.second } + prediction.map { it.mmol }
        val maxBg = maxOf(12.0, (allVals.maxOrNull() ?: 10.0) + 1)
        val minBg = 2.5
        fun x(ts: Long) = (ts - from).toFloat() / (horizon - from).toFloat() * CHART_W
        fun y(v: Double) = (CHART_H * (1 - (v - minBg) / (maxBg - minBg))).toFloat()

        // Range band + guides.
        val band = Paint().apply { color = Color.argb(26, 129, 199, 132) }
        c.drawRect(0f, y(rangeHi), CHART_W.toFloat(), y(rangeLo), band)
        val guide = Paint().apply {
            color = Color.argb(70, 255, 255, 255); strokeWidth = 1f
        }
        c.drawLine(0f, y(rangeLo), CHART_W.toFloat(), y(rangeLo), guide)
        c.drawLine(0f, y(rangeHi), CHART_W.toFloat(), y(rangeHi), guide)

        // "Now" marker.
        c.drawLine(x(now), 0f, x(now), CHART_H.toFloat(), guide)

        // The curve.
        val line = Paint().apply {
            color = Color.WHITE; strokeWidth = 4f; isAntiAlias = true
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        var prev: Pair<Long, Double>? = null
        for (p in pts) {
            if (prev == null || p.first - prev.first > 12 * 60_000) {
                path.moveTo(x(p.first), y(p.second))
            } else {
                path.lineTo(x(p.first), y(p.second))
            }
            prev = p
        }
        c.drawPath(path, line)

        // Forecast: dashed, with a faint corridor.
        if (prediction.size > 1) {
            val corridor = Paint().apply {
                color = Color.argb(30, 255, 255, 255); style = Paint.Style.FILL
            }
            val cp = Path()
            prediction.forEachIndexed { i, p ->
                if (i == 0) cp.moveTo(x(p.tsMs), y(p.hi)) else cp.lineTo(x(p.tsMs), y(p.hi))
            }
            prediction.reversed().forEach { p -> cp.lineTo(x(p.tsMs), y(p.lo)) }
            cp.close()
            c.drawPath(cp, corridor)
            val dash = Paint(line).apply {
                strokeWidth = 3f
                color = Color.argb(200, 255, 255, 255)
                pathEffect = DashPathEffect(floatArrayOf(8f, 7f), 0f)
            }
            val pp = Path()
            prediction.forEachIndexed { i, p ->
                if (i == 0) pp.moveTo(x(p.tsMs), y(p.mmol)) else pp.lineTo(x(p.tsMs), y(p.mmol))
            }
            c.drawPath(pp, dash)
        }
        return bmp
    }
}
