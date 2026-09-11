package io.github.obdosok.diapilot.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.diapilot.core.collector.CollectorStore
import com.diapilot.core.collector.HrPoint
import com.diapilot.core.collector.SleepSession
import java.time.Instant

/**
 * Pulls heart rate and sleep from Health Connect (fed by Zepp on this phone).
 * All local: Health Connect is the on-device health hub; nothing goes to any
 * cloud of ours. Idempotent by timestamp, so re-syncing overlapping windows
 * is free.
 */
object HealthConnectSync {

    private const val TAG = "HealthConnectSync"
    private const val WINDOW_MS = 7L * 24 * 3_600_000
    // Incremental sync: re-reading 7 full days of HR samples every 10–15 min
    // was a top battery burner. Each stream keeps its own watermark and reads
    // only [watermark − overlap .. now]; the 7-day window remains for the
    // first run (or after long gaps). Upserts are idempotent, overlap is free.
    private const val OVERLAP_MS = 2L * 3_600_000
    // Sleep arrives once a day — syncing it more often reads nothing new;
    // a 3-day window catches late edits of the night's session.
    private const val SLEEP_PERIOD_MS = 6L * 3_600_000
    private const val SLEEP_WINDOW_MS = 3L * 24 * 3_600_000
    private const val PREFS = "hc_sync"

    private fun sinceFor(context: Context, key: String, now: Instant): Instant {
        val wm = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        val floor = now.minusMillis(WINDOW_MS)
        val cand = if (wm > 0) Instant.ofEpochMilli(wm - OVERLAP_MS) else floor
        return if (cand.isBefore(floor)) floor else cand
    }

    private fun stamp(context: Context, key: String, now: Instant) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key, now.toEpochMilli()).apply()
    }

    // HR + sleep are load-bearing (the twin uses them); steps are additive
    // history for exercise-v2 — requested together, but their absence must
    // never stall the HR/sleep sync (users granted the old pair already).
    private val REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )
    private val STEPS_PERMISSION =
        HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class)
    val PERMISSIONS = REQUIRED_PERMISSIONS + STEPS_PERMISSION

    fun clientOrNull(context: Context): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null

    suspend fun hasPermissions(context: Context): Boolean {
        val client = clientOrNull(context) ?: return false
        return try {
            client.permissionController.getGrantedPermissions()
                .containsAll(REQUIRED_PERMISSIONS)
        } catch (e: Exception) {
            Log.w(TAG, "Permission check failed: ${e.message}")
            false
        }
    }

    /**
     * Steps were added after HR/sleep — many users granted the old pair and
     * never re-consented, so the steps table silently stays empty.
     *
     * ⚠ THIS ALREADY HAPPENED IN PRACTICE. The function existed, but NOTHING
     * called it: the screen asks [hasPermissions], which deliberately checks
     * only heart rate and sleep so a missing steps grant does not block the
     * rest of the sync. The logic is correct, but the consequence is not: the
     * screen reported "permissions granted" while steps stayed dead.
     *
     * Steps in the database can stop advancing while heart rate keeps
     * updating — the sync is running, only the steps block is being skipped.
     * This is confirmed by the trail the block leaves on success:
     * `physio_activity_coverage_v1` gets rows only while steps are syncing.
     *
     * THE RECOVERY WINDOW IS HARD: `sinceFor` takes the max of "watermark
     * minus 2 hours" and "now minus [WINDOW_MS]" = 7 days. If the permission
     * is re-granted within that window the missed days are backfilled; once
     * the 7-day floor passes, they are gone for good.
     */
    suspend fun hasStepsPermission(context: Context): Boolean {
        val client = clientOrNull(context) ?: return false
        return try {
            STEPS_PERMISSION in client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            false
        }
    }

    /** Incremental HR/steps + daily-ish sleep sync. Returns true if synced. */
    suspend fun sync(context: Context, store: CollectorStore): Boolean {
        val client = clientOrNull(context) ?: return false
        if (!hasPermissions(context)) return false
        val to = Instant.now()
        var nHr = 0
        var nSleep = 0

        try {
            // Heart rate: series records, paginated, from its own watermark.
            val hrFrom = sinceFor(context, "hr", to)
            var pageToken: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(hrFrom, to),
                        pageToken = pageToken,
                    ),
                )
                for (record in response.records) {
                    for (sample in record.samples) {
                        store.upsertHeartRate(
                            HrPoint(sample.time.toEpochMilli(), sample.beatsPerMinute.toDouble()),
                        )
                        nHr++
                    }
                }
                pageToken = response.pageToken
            } while (pageToken != null)
            stamp(context, "hr", to)

            // Sleep sessions: once in SLEEP_PERIOD_MS is plenty (it lands
            // once a day); a 3-day window catches late edits.
            val sleepPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (to.toEpochMilli() - sleepPrefs.getLong("sleep_at", 0) >= SLEEP_PERIOD_MS) {
                var sleepToken: String? = null
                do {
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            recordType = SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(
                                to.minusMillis(SLEEP_WINDOW_MS), to,
                            ),
                            pageToken = sleepToken,
                        ),
                    )
                    for (record in response.records) {
                        store.upsertSleepSession(
                            SleepSession(record.startTime.toEpochMilli(), record.endTime.toEpochMilli()),
                        )
                        nSleep++
                    }
                    sleepToken = response.pageToken
                } while (sleepToken != null)
                sleepPrefs.edit().putLong("sleep_at", to.toEpochMilli()).apply()
            }

            // Steps: only when granted (added later than HR/sleep — the user
            // may not have re-consented yet); its own watermark.
            var nSteps = 0
            val granted = client.permissionController.getGrantedPermissions()
            if (STEPS_PERMISSION !in granted) {
                // THE REFUSAL IS RECORDED, NOT SWALLOWED. Silence is
                // indistinguishable from "there were no steps", and that is
                // exactly how a multi-day gap can go unnoticed. A coverage
                // row with a refusal source makes the hole COUNTABLE after
                // the fact — the same argument as for the refusal row in the
                // forecast ledger.
                Log.w(TAG, "STEPS NOT SYNCING: Health Connect permission revoked")
                (store as? SqliteCollectorStore)?.recordActivityCoverage(
                    to.toEpochMilli(), to.toEpochMilli(), to.toEpochMilli(),
                    source = "health_connect_steps_denied",
                )
            }
            if (STEPS_PERMISSION in granted) {
                val stepsFrom = sinceFor(context, "steps", to)
                var stepsToken: String? = null
                do {
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            recordType = androidx.health.connect.client.records.StepsRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(stepsFrom, to),
                            pageToken = stepsToken,
                        ),
                    )
                    for (record in response.records) {
                        store.upsertSteps(
                            com.diapilot.core.collector.StepBucket(
                                record.startTime.toEpochMilli(),
                                record.endTime.toEpochMilli(),
                                record.count,
                                // WITHOUT THIS FIELD, TWO APPS' COUNTS ADDED UP.
                                // Health Connect returns records from every
                                // app that writes them; their boundaries
                                // differ, so nothing is a duplicate and
                                // nothing gets overwritten — the sum just
                                // ends up twice the truth. See StepStream.
                                record.metadata.dataOrigin.packageName,
                            ),
                        )
                        nSteps++
                    }
                    stepsToken = response.pageToken
                } while (stepsToken != null)
                (store as? SqliteCollectorStore)?.recordActivityCoverage(stepsFrom.toEpochMilli(),to.toEpochMilli(),to.toEpochMilli())
                stamp(context, "steps", to)
            }

            Log.d(TAG, "Synced $nHr HR samples, $nSleep sleep sessions, $nSteps step buckets")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect sync failed: ${e.message}")
            return false
        }
    }
}
