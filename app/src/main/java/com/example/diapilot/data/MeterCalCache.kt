package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.collector.CollectorStore

/**
 * Cached meter-correction lens. Fitting it reads ~14 days of main readings
 * plus 3 days of the minute stream — cheap once, expensive when the widget,
 * the companion push and the watch each redo it every minute. The lens
 * changes only when a fingerstick is logged or slowly as its time-decay
 * moves, so a short TTL loses nothing.
 *
 * A new meter entry must apply instantly (the user just calibrated!) —
 * call [invalidate] from the meter-write path.
 */
object MeterCalCache {
    private const val TTL_MS = 5L * 60_000

    @Volatile private var cached: com.diapilot.core.analysis.MeterCalibration? = null
    @Volatile private var builtAtMs = 0L
    @Volatile private var cachedSensorKey: String? = null

    fun invalidate() {
        builtAtMs = 0L
        cachedSensorKey = null
    }

    @Synchronized
    fun get(store: CollectorStore, context: Context): com.diapilot.core.analysis.MeterCalibration? {
        val now = System.currentTimeMillis()
        val serial = Settings.libreSensorSerial(context)
        val sensorKey = serial?.takeIf { it.isNotBlank() } ?: "legacy"
        if (cachedSensorKey == sensorKey && now - builtAtMs < TTL_MS) return cached
        val sqlite = store as? SqliteCollectorStore
        val persisted = sqlite?.meterCalibration(sensorKey)
        val configuredStart = Settings.libreSensorStartMs(context)
        val eraStart = maxOf(FoodEraSettings.current().startMs, configuredStart.takeIf { it > 0 }
            ?: persisted?.sensorStartMs
            ?: (now - 14L * 24 * 3_600_000))
        val meterRows = store.meterReadings(eraStart, now)
        val sourceHash = meterRows.fold(1) { h, row ->
            31 * h + listOf(row.tsMs, kotlin.math.round(row.mmol * 1000).toLong()).hashCode()
        }
        if (persisted != null &&
            persisted.sourceCount == meterRows.size &&
            persisted.sourceLatestTsMs == (meterRows.maxOfOrNull { it.tsMs } ?: 0L) &&
            persisted.sourceHash == sourceHash
        ) {
            // v29 introduced the append-only causal table. This also repairs a
            // v28 database whose latest cache row predates the upgrade.
            sqlite?.ensureMeterCalibrationVersion(persisted)
            cached = persisted.toCalibration()
            cachedSensorKey = sensorKey
            builtAtMs = now
            return cached
        }
        val minuteCal = MinuteCalCache.get(store, context)
        val calWindowMs = (now - eraStart).coerceIn(3_600_000L, 14L * 24 * 3_600_000)
        cached = com.diapilot.core.analysis.meterCalibration(
            sensor = store.readings(now - calWindowMs, now),
            meter = meterRows.map { com.diapilot.core.collector.GlucosePoint(it.tsMs, it.mmol) },
            nowMs = now,
            windowMs = calWindowMs,
            rateSource = if (minuteCal != null) {
                store.minuteReadings(now - 3L * 24 * 3_600_000, now)
                    .map { com.diapilot.core.collector.GlucosePoint(it.tsMs, minuteCal.apply(it.mmol)) }
            } else store.readings(now - calWindowMs, now),
            validFromMs = eraStart,
        )
        cached?.let { cal ->
            sqlite?.upsertMeterCalibration(
                MeterCalibrationRecord(
                    sensorKey = sensorKey,
                    sensorStartMs = eraStart,
                    slope = cal.slope,
                    interceptMmol = cal.interceptMmol,
                    nChecks = cal.nChecks,
                    transientOffsetMmol = cal.transient?.offsetMmol,
                    transientCheckTsMs = cal.transient?.checkTsMs,
                    transientHalfLifeMs = cal.transient?.halfLifeMs,
                    sourceCount = meterRows.size,
                    sourceLatestTsMs = meterRows.maxOfOrNull { it.tsMs } ?: 0L,
                    sourceHash = sourceHash,
                    fittedAtMs = now,
                ),
            )
        } ?: sqlite?.deleteMeterCalibration(sensorKey)
        cachedSensorKey = sensorKey
        builtAtMs = now
        return cached
    }

    /**
     * Lenses for historical chart rendering, causal in wall-clock time.
     *
     * A calibration learned at 10:00 starts at 10:00. It may improve training
     * retrospectively, but it cannot change what the chart says happened at
     * 03:00. New versions close the previous interval instead of replacing it.
     */
    fun timeline(
        store: CollectorStore,
        current: com.diapilot.core.analysis.MeterCalibration? = null,
    ): List<com.diapilot.core.analysis.MeterCalibration> {
        val sqlite = store as? SqliteCollectorStore
        val rows = sqlite?.meterCalibrationVersions().orEmpty()
        if (rows.isEmpty()) {
            // Test doubles / a database that has not produced a version yet.
            return current?.let(::listOf).orEmpty()
        }
        val sensorStarts = sqlite?.meterCalibrations().orEmpty()
            .sortedBy { it.sensorStartMs }
        val persisted = rows.map { row ->
            val nextFit = rows.asSequence()
                .filter { it.sensorKey == row.sensorKey && it.fittedAtMs > row.fittedAtMs }
                .minOfOrNull { it.fittedAtMs }
            val nextSensor = sensorStarts.firstOrNull { it.sensorStartMs > row.sensorStartMs }
                ?.sensorStartMs
            val until = listOfNotNull(nextFit, nextSensor)
                .minOrNull()?.minus(1) ?: Long.MAX_VALUE
            row.toCalibration(
                validFromMs = maxOf(row.sensorStartMs, row.fittedAtMs),
                validUntilMs = until,
            )
        }
        return persisted.sortedBy { it.validFromMs }
    }

    /**
     * Best retrospective lens per completed sensor, for model fitting only.
     * This is intentionally different from [timeline]: training may use all
     * trustworthy checks to estimate physiology, while the user-facing chart
     * remains causal and never rewrites an earlier observation.
     */
    fun trainingTimeline(
        store: CollectorStore,
    ): List<com.diapilot.core.analysis.MeterCalibration> {
        val rows = (store as? SqliteCollectorStore)?.meterCalibrations()
            .orEmpty().filter { it.fittedAtMs >= FoodEraSettings.current().startMs }.sortedBy { it.sensorStartMs }
        return rows.mapIndexed { index, row ->
            row.toCalibration(
                validFromMs = maxOf(row.sensorStartMs,FoodEraSettings.current().startMs),
                validUntilMs = rows.getOrNull(index + 1)?.sensorStartMs?.minus(1)
                    ?: Long.MAX_VALUE,
            )
        }
    }

    private fun MeterCalibrationRecord.toCalibration(
        validFromMs: Long = sensorStartMs,
        validUntilMs: Long = Long.MAX_VALUE,
    ) = com.diapilot.core.analysis.MeterCalibration(
        slope = slope,
        interceptMmol = interceptMmol,
        nChecks = nChecks,
        transient = transientOffsetMmol?.let { offset ->
            com.diapilot.core.analysis.MeterCorrection(
                offsetMmol = offset,
                checkTsMs = transientCheckTsMs ?: sensorStartMs,
                halfLifeMs = transientHalfLifeMs ?: 6L * 3_600_000,
            )
        },
        validFromMs = validFromMs,
        validUntilMs = validUntilMs,
    )
}
