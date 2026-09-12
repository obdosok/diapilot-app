package io.github.obdosok.diapilot.diag

/**
 * EVERYTHING THE EXPORT IS BUILT FROM, as plain data with no Android in it.
 *
 * The values arrive here RAW — a glucose reading is still a number, a note is
 * still its text — and [DiagnosticsBundle] is what removes them. That is
 * deliberate and it is the only arrangement the promise can be tested: a test
 * seeds this with distinctive numbers, builds the file, and asserts those
 * digits appear nowhere in it. A snapshot that arrived pre-redacted would
 * make the same test pass with the redaction deleted.
 */
data class DiagSnapshot(
    val generatedAtMs: Long,
    /** Header only, verbatim: applicationId, edition, versionCode/versionName. */
    val appLabel: String,
    /** Header only, verbatim: Android release and SDK level. */
    val androidLabel: String,
    /** Which unit the app is set to — the unit is KEPT where the value is not. */
    val mgdl: Boolean,
    val state: CollectionState,
    val sources: List<SourceStatus>,
    val journal: JournalState,
    val model: ModelState,
    val logLines: List<DiagLog.Line>,
    val logDropped: Int,
)

/** What [io.github.obdosok.diapilot.collect.DiagState] and the store can say about the chain. */
data class CollectionState(
    val bleStatus: String,
    val bleLastPacketMs: Long,
    val serviceStartedMs: Long,
    val watchServerUp: Boolean,
    val lastXdripBroadcastMs: Long,
    val sensorSerial: String?,
    val sensorStartMs: Long,
    val lastMainReadingMs: Long,
    val lastMainReadingSource: String?,
    val lastMinuteReadingMs: Long,
    val minuteCalibrationN: Int?,
)

/**
 * One input's state. WP-B1 is adding a per-source status screen in parallel;
 * when its type lands, this is where it plugs in — build one [SourceStatus]
 * per row it knows about and the file gains the rest of the chain for free.
 * Until then these are derived from what the store and `DiagState` already
 * hold: the source names stamped on the readings themselves.
 *
 * [lastValueMmol] is carried and never printed as a number: the file says a
 * value arrived and in which unit, which is the whole question when a source
 * goes quiet.
 */
data class SourceStatus(
    val name: String,
    val enabled: Boolean,
    val lastEventMs: Long,
    val lastValueMmol: Double?,
    val eventsIn24h: Int?,
    /** Free text from the source itself (a refusal reason, a permission state). */
    val note: String? = null,
)

/**
 * WHETHER THE USER IS ENTERING ANYTHING AT ALL — counts over the last day plus
 * the newest entry of each kind, which is the question behind "the app shows
 * nothing" as often as the collection chain is.
 *
 * Every value here is carried raw and printed as a unit: a dose as
 * `<redacted> U`, a carbohydrate amount as `<redacted> g`, the note as its
 * length. The LENGTH of a note is a real diagnostic — an empty note and a
 * 200-character one fail the food parser in different ways — and it cannot be
 * read back.
 */
data class JournalState(
    val readings24h: Int,
    val boluses24h: Int,
    val lastBolusMs: Long,
    val lastBolusUnits: Double?,
    val notes24h: Int,
    val lastNoteMs: Long,
    val lastNoteKind: String?,
    val lastNoteText: String?,
    val lastNoteCarbs: Double?,
)

/**
 * The model's readiness and provenance AS THE CODE CAN DESCRIBE IT TODAY: what
 * the twin cache holds without being asked to build, which gates it is past,
 * and the identities of the artifacts the forecast ran on. A first-run package
 * will introduce a `ModelReadiness` type; this is not it, and nothing here
 * invents one.
 */
data class ModelState(
    val edition: String,
    /** A model in memory, i.e. this process has already built or restored one. */
    val inMemory: Boolean,
    val builtAtMs: Long,
    val fromDiskSnapshot: Boolean,
    val kernelEpisodes: Int,
    val effectiveEpisodes: Double,
    /** Sensor readings in the food era against the build gate, so "not yet" has a reason. */
    val readingsInEra: Int?,
    val readingsGate: Int,
    val carbSensMmolPerG: Double?,
    val carbSensN: Int?,
    val carbSensOverridden: Boolean,
    val estimatedIsfMmolPerU: Double,
    val isfSource: String,
    val insulinProfile: String,
    val physioArtifactId: String?,
    val hybridModelSha: String?,
    val hybridAlgoVersion: String,
)
