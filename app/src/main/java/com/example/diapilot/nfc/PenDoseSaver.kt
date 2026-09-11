package com.example.diapilot.nfc

import android.util.Log
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.InsulinEvent
import com.diapilot.core.pen.InsulinDose

/**
 * Persists a pen scan with three dedup layers:
 *  1. rescans — every dose has a stable uuid (serial + pen-clock + units),
 *     remembered in the store;
 *  2. cross-source — the same shot may already be here via the xDrip
 *     treatments sync (same units within ±3 min);
 *  3. priming — a small dose immediately followed by a bigger one is the
 *     air shot, not insulin in the body.
 */
object PenDoseSaver {

    private const val TAG = "PenNfc"
    private const val PRIMING_MAX_UNITS = 2.0
    private const val PRIMING_WINDOW_MS = 2L * 60_000
    private const val CROSS_SOURCE_WINDOW_MS = 3L * 60_000

    data class Result(
        val newDoses: Int,
        val priming: Int,
        val duplicates: Int,
        val serial: String?,
    )

    fun save(store: CollectorStore, scan: PenNfcScanner.ScanResult): Result {
        val valid = scan.doses.filter { it.isValid }.sortedBy { it.absoluteTime }
        var new = 0
        var priming = 0
        var duplicates = 0

        for (dose in valid) {
            val uuid = "${scan.serial}:${dose.hash}"
            if (store.penDoseSeen(uuid)) {
                duplicates++
                continue
            }
            store.markPenDose(uuid, dose.absoluteTime)

            if (isPriming(dose, valid)) {
                priming++
                continue
            }

            val near = store.boluses(
                dose.absoluteTime - CROSS_SOURCE_WINDOW_MS,
                dose.absoluteTime + CROSS_SOURCE_WINDOW_MS,
            )
            if (near.any { kotlin.math.abs(it.units - dose.units) < 0.05 }) {
                duplicates++
                continue
            }

            store.upsertInsulin(
                InsulinEvent(dose.absoluteTime, dose.units, "bolus", "pen_nfc"),
            )
            new++
        }
        Log.i(TAG, "saved: new=$new priming=$priming dup=$duplicates of ${valid.size} valid")
        return Result(new, priming, duplicates, scan.serial)
    }

    private fun isPriming(dose: InsulinDose, all: List<InsulinDose>): Boolean =
        dose.units <= PRIMING_MAX_UNITS && all.any {
            it !== dose &&
                it.absoluteTime > dose.absoluteTime &&
                it.absoluteTime < dose.absoluteTime + PRIMING_WINDOW_MS &&
                it.units > dose.units
        }
}
