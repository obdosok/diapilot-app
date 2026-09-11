package com.diapilot.core.api

import com.diapilot.core.collector.CarbEvidenceV1
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The change journal behind `GET /api/v1/events`.
 *
 * WHY A JOURNAL AND NOT A QUERY OVER THE TABLES. A consumer pages by `seq` and
 * expects `seq` to be ARRIVAL order, so that «everything after 1052» is a
 * complete statement about what it has not yet seen. DiaPilot's readings
 * BACK-FILL — one OOP2 broadcast replays ~15 minutes of history, and an NFC scan
 * fills hours — so a `seq` derived from the measurement time would go BACKWARDS
 * on perfectly normal data and the consumer would silently skip those points.
 * The same is true of an edited bolus: the fact's time does not move, but the
 * fact changed. Only an append-only journal can express either.
 *
 * Everything here is pure Kotlin: persistence is the [EventJournalStore] port,
 * so the whole contract (after-exclusivity, revisions, deletions, paging) is
 * testable off-device.
 */
class EventJournal(private val store: EventJournalStore) {

    /** Stable per-installation identity, created once and persisted. */
    fun sourceId(): String = store.sourceId()

    /**
     * Fold the CURRENT truth for `[fromMs, toMs]` into the journal, appending
     * one entry per fact that is new, changed or gone.
     *
     * [facts] must be COMPLETE for that window: any fact already journaled with
     * an `occurred_at` inside it and absent from [facts] is recorded as a
     * deletion. Reconciling a narrow window with a wide fact list (or the
     * reverse) is how a working journal invents mass deletions — the window is
     * a parameter precisely so the caller has to state its scope.
     *
     * @param nowMs the moment we OBSERVED this truth — it becomes `received_at`.
     * @return how many journal entries were appended.
     */
    @Synchronized
    fun reconcile(fromMs: Long, toMs: Long, facts: List<Fact>, nowMs: Long): Int {
        val heads = store.headsInWindow(fromMs, toMs)
        val pending = ArrayList<PendingEntry>()
        val seen = HashSet<String>(facts.size * 2)

        for (f in facts.sortedBy { it.occurredAtMs }) {
            if (!seen.add(f.id)) continue
            // A fact whose occurred_at MOVED is not in this window's heads any
            // more; the id lookup finds it and bumps the revision instead of
            // minting a second identity for the same thing.
            val head = heads[f.id] ?: store.head(f.id)
            val next = when {
                head == null -> 1
                head.deleted -> head.revision + 1
                head.payload != f.payload ||
                    head.occurredAtMs != f.occurredAtMs ||
                    head.type != f.type -> head.revision + 1
                else -> continue // byte-identical: nothing happened, no entry
            }
            pending += PendingEntry(
                id = f.id, revision = next, type = f.type,
                occurredAtMs = f.occurredAtMs, receivedAtMs = nowMs,
                deleted = false, payload = f.payload,
            )
        }

        for ((id, head) in heads) {
            if (id in seen || head.deleted) continue
            pending += PendingEntry(
                id = id, revision = head.revision + 1, type = head.type,
                occurredAtMs = head.occurredAtMs, receivedAtMs = nowMs,
                // A deletion carries no medical fields — the consumer is told
                // the fact is gone, not what it used to say.
                deleted = true, payload = "",
            )
        }

        if (pending.isEmpty()) return 0
        store.append(pending)
        return pending.size
    }

    /** One page of the journal, strictly `seq > after`, at most [limit] rows. */
    fun page(after: Long, limit: Int): Page {
        val rows = store.page(after, limit)
        // Empty page ⇒ next_after stays put, and there is by definition nothing
        // beyond it. A non-empty page ends at its last seq.
        val nextAfter = rows.lastOrNull()?.seq ?: after
        return Page(
            entries = rows,
            nextAfter = nextAfter,
            hasMore = rows.isNotEmpty() && store.hasAfter(nextAfter),
        )
    }

    /**
     * True when [after] points into a stretch that retention has already
     * dropped, so the consumer cannot resume from it — the endpoint answers
     * 410 Gone rather than a silently incomplete page.
     *
     * `after == 0` always resumes: it means «I have nothing», and the oldest
     * retained entry is the honest answer to that.
     */
    fun isPruned(after: Long): Boolean = after > 0 && after < store.prunedThroughSeq()
}

/** A medical fact exactly as the app's own storage holds it right now. */
data class Fact(
    /** Stable identity of the fact — same across every correction of it. */
    val id: String,
    val type: String,
    val occurredAtMs: Long,
    /** Canonical JSON object body (no braces) — the type-specific fields. */
    val payload: String,
)

/** One immutable row of the journal, as served. */
data class JournalEntry(
    val seq: Long,
    val id: String,
    val revision: Int,
    val type: String,
    val occurredAtMs: Long,
    val receivedAtMs: Long,
    val deleted: Boolean,
    val payload: String,
)

/** A journal row before the store has assigned it a [JournalEntry.seq]. */
data class PendingEntry(
    val id: String,
    val revision: Int,
    val type: String,
    val occurredAtMs: Long,
    val receivedAtMs: Long,
    val deleted: Boolean,
    val payload: String,
)

data class Page(
    val entries: List<JournalEntry>,
    val nextAfter: Long,
    val hasMore: Boolean,
)

/**
 * Persistence port. The implementation must guarantee:
 *  - [sourceId] is created once and survives process death;
 *  - [append] assigns strictly increasing seq, never reusing one, in list order;
 *  - [page] returns rows with `seq > after`, ascending, at most `limit`.
 */
interface EventJournalStore {
    fun sourceId(): String
    fun append(rows: List<PendingEntry>)
    fun page(afterSeq: Long, limit: Int): List<JournalEntry>
    fun hasAfter(seq: Long): Boolean
    /** Newest entry per fact id whose `occurred_at` falls inside the window. */
    fun headsInWindow(fromMs: Long, toMs: Long): Map<String, JournalEntry>
    /** Newest entry for one fact id, wherever it sits in time. */
    fun head(id: String): JournalEntry?
    /** Highest seq retention has dropped; 0 when nothing was ever pruned. */
    fun prunedThroughSeq(): Long
}

// --- wire formatting --------------------------------------------------------

private val RFC3339: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** RFC 3339, UTC, always milliseconds — the single time format on this wire. */
fun rfc3339Utc(ms: Long): String = RFC3339.format(Instant.ofEpochMilli(ms))

/** Glucose units the wire accepts. */
enum class GlucoseUnit(val wire: String) {
    MMOL_L("mmol/L"),
    MGDL("mg/dL"),
}

/** Insulin kinds the wire accepts. `UNKNOWN` exists but DiaPilot never emits it. */
enum class InsulinKind(val wire: String) {
    BOLUS("bolus"),
    BASAL("basal"),
    PRIME("prime"),
    UNKNOWN("unknown"),
}

enum class DeliveryStatus(val wire: String) {
    DELIVERED("delivered"),
    PROGRAMMED("programmed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
}

/**
 * The type-specific halves of an event body.
 *
 * Field ORDER is fixed, because the payload string doubles as the change
 * detector in [EventJournal.reconcile]: two renderings of the same fact must be
 * byte-identical or every reconcile pass would mint a new revision.
 */
object EventPayloads {

    const val TYPE_GLUCOSE = "glucose"
    const val TYPE_INSULIN = "insulin"
    const val TYPE_MEAL = "meal"
    const val TYPE_SENSOR_RAW = "sensor_raw"

    /**
     * @param source which series the point came from. NOT in the minimal spec —
     *   added because a history can contain stretches where two sensors
     *   wrote on different scales at once, and a consumer that cannot tell them
     *   apart will compute a step change that never happened.
     */
    fun glucose(value: Double, unit: GlucoseUnit, source: String?): String = buildString {
        append("\"value\":").append(jsonNumber(value))
        append(",\"unit\":").append(jsonString(unit.wire))
        if (source != null) append(",\"source\":").append(jsonString(source))
    }

    fun insulin(
        units: Double,
        kind: InsulinKind,
        product: String?,
        deliveryStatus: DeliveryStatus,
    ): String = buildString {
        append("\"units\":").append(jsonNumber(units))
        append(",\"kind\":").append(jsonString(kind.wire))
        if (product != null) append(",\"product\":").append(jsonString(product))
        append(",\"delivery_status\":").append(jsonString(deliveryStatus.wire))
    }

    /**
     * What the user logged for one meal: their own words, the grams as
     * recorded, where the grams came from, and the LLM's written analysis.
     *
     * [carbsG] and [analysis] are nullable and OMITTED when absent rather than
     * sent as `null` — an absent analysis and an empty one are different things.
     *
     * [analysis] goes out VERBATIM. It is the app's own semi-structured text
     * ([TITLE_LINE_PREFIX] / [COMPONENT_LINE_PREFIX] / META / [GI_LINE_PREFIX] / [CARBS_LINE_PREFIX] lines), not JSON,
     * and its shape changes as the prompt does. Parsing it here into a fixed
     * schema would publish a contract we do not actually keep.
     */
    fun meal(
        text: String,
        carbsG: Double?,
        carbsSource: String?,
        analysis: String?,
        carbEvidence: CarbEvidenceV1? = null,
    ): String = buildString {
        append("\"text\":").append(jsonString(text))
        if (carbsG != null) append(",\"carbs_g\":").append(jsonNumber(carbsG))
        if (carbsSource != null) append(",\"carbs_source\":").append(jsonString(carbsSource))
        if (analysis != null) append(",\"analysis\":").append(jsonString(analysis))
        if (carbEvidence != null) append(",\"carb_evidence\":").append(carbEvidence.canonicalJson())
    }

    /** Parse the structured evidence embedded in an exported meal payload. */
    fun carbEvidenceFromMealPayload(payloadJson: String): CarbEvidenceV1? {
        val body = org.json.JSONObject(payloadJson)
        return if (body.has("carb_evidence") && !body.isNull("carb_evidence")) {
            CarbEvidenceV1.fromJsonObject(body.getJSONObject("carb_evidence"))
        } else null
    }
}

/**
 * A double as JSON, at full stored precision.
 *
 * The glucose the app computed is passed through WITHOUT extra rounding, so a
 * value derived as mg/dL ÷ 18.0182 keeps its digits. Non-finite values would
 * emit `NaN` and produce invalid JSON, so callers must filter them out first —
 * this throws rather than shipping a body no parser can read.
 */
internal fun jsonNumber(v: Double): String {
    require(v.isFinite()) { "non-finite value must not reach the wire" }
    // Whole numbers read better as 4 than 4.0, and both round-trip identically.
    return if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

internal fun jsonString(s: String): String = buildString(s.length + 2) {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}
