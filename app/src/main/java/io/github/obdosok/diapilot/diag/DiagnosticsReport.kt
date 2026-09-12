package io.github.obdosok.diapilot.diag

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.diapilot.core.collector.CollectorStore
import io.github.obdosok.diapilot.AppIdentity
import io.github.obdosok.diapilot.BuildConfig
import io.github.obdosok.diapilot.Edition
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.FoodEraSettings
import io.github.obdosok.diapilot.data.HybridShadowRegistry
import io.github.obdosok.diapilot.data.IsfSource
import io.github.obdosok.diapilot.data.MinuteCalCache
import io.github.obdosok.diapilot.data.PhysioRuntime
import io.github.obdosok.diapilot.data.Settings
import io.github.obdosok.diapilot.data.TwinCache
import io.github.obdosok.diapilot.data.Units
import io.github.obdosok.diapilot.i18n.localized
import java.io.File

/**
 * THE ANDROID HALF of the value-free diagnostics export: reads the phone,
 * writes the file, hands out a share intent. The text itself is
 * [DiagnosticsBundle], which has no Android in it so the redaction can be
 * tested as a property rather than as a screenshot.
 *
 * WHY IT EXISTS. The O-B gate is "5 strangers' phones report data for 7 days
 * via diagnostics export" (`docs/roadmap.md`), and the only instrument for
 * that until now was a bug report — which carries hours of glucose history
 * along with the answer (`docs/audit.md`, S10).
 */
object DiagnosticsReport {

    /** `filesDir/diagnostics/` — its own FileProvider path, not a widened `photos/`. */
    const val DIR_NAME: String = "diagnostics"

    /**
     * Files kept on disk. One per share, so a tester who taps twice and sends
     * the older file still sends a file this build wrote; more than a handful
     * is litter in the app's own sandbox.
     */
    private const val KEEP_FILES = 3

    private const val DAY_MS = 24L * 3_600_000

    /**
     * Collects, writes and returns the chooser intent — the whole button, so
     * the screen holds no part of the policy. Runs off the main thread: it
     * reads the store.
     */
    fun shareIntent(
        context: Context,
        store: CollectorStore?,
        nowMs: Long = System.currentTimeMillis(),
    ): Intent {
        val file = export(context, store, nowMs)
        return chooser(
            context,
            FileProvider.getUriForFile(context, AppIdentity.FILE_PROVIDER_AUTHORITY, file),
            file.name,
        )
    }

    /** Collects and writes the file, and returns it. */
    fun export(
        context: Context,
        store: CollectorStore?,
        nowMs: Long = System.currentTimeMillis(),
    ): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
            .format(java.util.Date(nowMs))
        val text = DiagnosticsBundle.build(collect(context, store, nowMs), stamp)
        return write(context, DiagnosticsBundle.fileName(stamp), text)
    }

    /** Everything the file is built from, read as it stands. Nothing is triggered. */
    fun collect(context: Context, store: CollectorStore?, nowMs: Long): DiagSnapshot {
        val diag = io.github.obdosok.diapilot.collect.DiagState
        val lastMain = store?.lastReading()
        val lastMinute = store?.lastMinuteReading()
        val cal = store?.let { runCatching { MinuteCalCache.get(it, context) }.getOrNull() }
        val peek = TwinCache.peek()
        val model = peek.model
        val eraStart = FoodEraSettings.current().startMs
        val eraReadings = store?.let { runCatching { it.sensorReadings(eraStart, nowMs).size }.getOrNull() }
        return DiagSnapshot(
            generatedAtMs = nowMs,
            appLabel = "${BuildConfig.APPLICATION_ID} ${BuildConfig.FLAVOR} " +
                "${BuildConfig.VERSION_CODE} / ${BuildConfig.VERSION_NAME}",
            androidLabel = "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
            mgdl = Units.isMgdl(context),
            state = CollectionState(
                bleStatus = diag.bleStatus,
                bleLastPacketMs = diag.bleLastPacketMs,
                serviceStartedMs = diag.serviceStartedMs,
                watchServerUp = diag.watchServerUp,
                lastXdripBroadcastMs = diag.lastXdripBroadcastMs,
                sensorSerial = Settings.libreSensorSerial(context),
                sensorStartMs = Settings.libreSensorStartMs(context),
                lastMainReadingMs = lastMain?.tsMs ?: 0,
                lastMainReadingSource = lastMain?.source,
                lastMinuteReadingMs = lastMinute?.tsMs ?: 0,
                minuteCalibrationN = cal?.n,
                lastAlertTickMs = diag.lastAlertTickMs,
            ),
            sources = sources(store, nowMs),
            journal = journal(store, nowMs),
            model = ModelState(
                edition = "${BuildConfig.FLAVOR}" +
                    " (prospective ${Edition.prospective}, sensor-direct ${Edition.sensorDirect})",
                inMemory = model != null,
                builtAtMs = peek.builtAtMs,
                fromDiskSnapshot = peek.fromDisk,
                kernelEpisodes = model?.kernelEpisodes ?: 0,
                effectiveEpisodes = model?.effectiveEpisodes ?: 0.0,
                readingsInEra = eraReadings,
                readingsGate = TwinCache.MIN_READINGS_FOR_BUILD,
                carbSensMmolPerG = model?.carbSens?.mmolPerGram,
                carbSensN = model?.carbSens?.n,
                carbSensOverridden = Settings.carbSensOverrideMmolPerG(context) != null,
                estimatedIsfMmolPerU = model?.estimatedIsfMmolPerU ?: 0.0,
                isfSource = IsfSource.choice(context).name.lowercase(),
                insulinProfile = Settings.insulinProfile(context).label,
                physioArtifactId = PhysioRuntime.priorArtifactId(),
                hybridModelSha = HybridShadowRegistry.modelSha256(),
                hybridAlgoVersion = io.github.obdosok.diapilot.data.HYBRID_V11_SHADOW_ALGO_VERSION,
            ),
            logLines = DiagLog.lines(nowMs),
            logDropped = DiagLog.dropped(),
        )
    }

    /**
     * PER-SOURCE STATUS FROM WHAT THE TREE HOLDS TODAY: the source name is
     * stamped on every stored reading, so a count per name over the last day
     * plus the newest timestamp already answers "which input went quiet".
     *
     * WP-B1's data-sources screen is landing in parallel with this package and
     * brings a richer per-source status with it. This is the one place to
     * replace when it does — map each of its rows to a [SourceStatus] and the
     * file gains them without another change to the bundle.
     */
    private fun sources(store: CollectorStore?, nowMs: Long): List<SourceStatus> {
        store ?: return emptyList()
        val since = nowMs - DAY_MS
        val rows = runCatching { store.sensorReadingsWithSource(since, nowMs) }.getOrNull()
            ?: return emptyList()
        return rows.groupBy { it.second ?: "unknown" }
            .map { (name, points) ->
                val newest = points.maxByOrNull { it.first.tsMs }
                SourceStatus(
                    name = name,
                    enabled = true,
                    lastEventMs = newest?.first?.tsMs ?: 0,
                    lastValueMmol = newest?.first?.mmol,
                    eventsIn24h = points.size,
                )
            }
            .sortedByDescending { it.eventsIn24h ?: 0 }
    }

    /**
     * What the user has entered in the last day, as counts plus the newest
     * entry of each kind. The values go in raw and leave as units — see
     * [JournalState].
     */
    private fun journal(store: CollectorStore?, nowMs: Long): JournalState {
        val since = nowMs - DAY_MS
        val readings = store?.let { runCatching { it.readings(since, nowMs).size }.getOrNull() } ?: 0
        val boluses = store?.let { runCatching { it.boluses(since, nowMs) }.getOrNull() }.orEmpty()
        val notes = store?.let { runCatching { it.annotations(since, nowMs) }.getOrNull() }.orEmpty()
        val lastBolus = boluses.maxByOrNull { it.tsMs }
        val lastNote = notes.maxByOrNull { it.tsMs }
        return JournalState(
            readings24h = readings,
            boluses24h = boluses.size,
            lastBolusMs = lastBolus?.tsMs ?: 0,
            lastBolusUnits = lastBolus?.units,
            notes24h = notes.size,
            lastNoteMs = lastNote?.tsMs ?: 0,
            lastNoteKind = lastNote?.kind,
            lastNoteText = lastNote?.content,
            lastNoteCarbs = lastNote?.estCarbs,
        )
    }

    /** Writes the file into the export's own directory and prunes older ones. */
    fun write(context: Context, name: String, text: String): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(text)
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES)
            ?.forEach { it.delete() }
        return file
    }

    /**
     * The share sheet. `clipData` carries the same URI as `EXTRA_STREAM`
     * because some targets read the grant off the clip rather than the extra,
     * and a mail client that cannot read the attachment is a tester who
     * reports nothing.
     *
     * TAKES THE URI RATHER THAN THE FILE so the intent's shape can be asserted
     * without `FileProvider`: under Robolectric the provider resolves its
     * `FILE_PROVIDER_PATHS` meta-data to an empty root table, so a test that
     * called it would fail on `photos/` as well — see `DiagnosticsShareTest`,
     * which checks the declared paths against the resource instead.
     */
    fun chooser(context: Context, uri: android.net.Uri, fileName: String): Intent {
        val text = context.localized()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, text.getString(R.string.diagnostics_share_subject))
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, text.getString(R.string.diagnostics_share_chooser))
    }
}
