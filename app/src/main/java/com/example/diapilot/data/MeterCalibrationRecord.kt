package com.example.diapilot.data

/** Persisted, auditable calibration lens for one physical sensor. Raw CGM
 * readings stay immutable; this record is the derived view that can be
 * rebuilt from its meter checks. */
data class MeterCalibrationRecord(
    val sensorKey: String,
    val sensorStartMs: Long,
    val slope: Double,
    val interceptMmol: Double,
    val nChecks: Int,
    val transientOffsetMmol: Double?,
    val transientCheckTsMs: Long?,
    val transientHalfLifeMs: Long?,
    val sourceCount: Int,
    val sourceLatestTsMs: Long,
    val sourceHash: Int,
    val fittedAtMs: Long,
)
