package io.github.obdosok.diapilot.diag

/**
 * THE VOCABULARY OF THE VALUE-FREE EXPORT — what replaces a number, and the
 * guard that catches a number nobody replaced.
 *
 * The diagnostics export exists to be sent by an early tester to someone the
 * tester has never met (see `docs/audit.md`, S10). It must therefore answer
 * "did the collection chain work" while carrying no glucose value, no insulin
 * dose, no carbohydrate amount and no note text. Every such number is
 * replaced by its UNIT AND SHAPE — "<redacted> mmol/L", "<redacted> U",
 * "<redacted, 34 chars>" — because the unit is what a collection problem is
 * debugged with and the number is what makes the file private.
 *
 * TWO LAYERS, and the second one is why this object holds a guard as well as
 * the replacements. Layer one is [DiagnosticsBundle], which formats every
 * value it knows about through the helpers below. Layer two is [scrub] and
 * [medicalNumbers] over the finished text: the log section carries lines
 * written at ~40 call sites by code that does not know this file exists, so a
 * line that still holds a number is caught on the way out rather than trusted
 * not to exist. A helper missed at a call site is then a line with a
 * `<redacted>` in it, not a leak.
 */
object Redact {

    /** What stands where a value stood. Recognisable, and impossible to parse as a number. */
    const val MARK: String = "<redacted>"

    /** A line the guard could not clean: kept as evidence, without its content. */
    const val WITHHELD: String = "<line withheld: an unredacted number survived the scrubber>"

    /** A glucose value: the unit the app is set to, never the number. */
    fun glucose(mgdl: Boolean): String = if (mgdl) "$MARK mg/dL" else "$MARK mmol/L"

    /** An insulin dose, bolus or basal. */
    fun insulin(): String = "$MARK U"

    /** A carbohydrate amount. */
    fun carbs(): String = "$MARK g"

    /** A rate the model learned, e.g. carbohydrate sensitivity. */
    fun perUnit(unit: String): String = "$MARK $unit"

    /**
     * Free text — a note, a meal name, a label. Its LENGTH is the shape: it
     * says "the user writes long notes" or "the note is empty", which is what
     * a parsing problem is diagnosed with, and it cannot be read back.
     */
    fun note(text: String?): String =
        if (text == null) "none" else "<redacted, ${text.length} chars>"

    /**
     * An identifier that belongs to hardware rather than to a measurement — a
     * sensor serial, a BLE address. Not a medical value, but it names the
     * tester's device, and nothing about a collection problem needs it: the
     * status line says whether the link is up.
     */
    fun identifier(value: String?): String =
        if (value.isNullOrBlank()) "none" else "<redacted, ${value.length} chars>"

    /** A timestamp as an age. Relative is enough to debug a collection problem. */
    fun minutesAgo(nowMs: Long, tsMs: Long): String =
        if (tsMs <= 0) "never" else "${(nowMs - tsMs) / 60_000} min ago"

    /** An age in whole days, for the sensor's own clock. */
    fun daysAgo(nowMs: Long, tsMs: Long): String =
        if (tsMs <= 0) "never" else "${(nowMs - tsMs) / 86_400_000} d ago"

    // The units a medical number appears next to in this app's own log lines
    // and status strings. Longest alternatives first: `units?` has to win over
    // `u`, and `grams?` over `g`, or the shorter one truncates the match and
    // leaves the rest of the word behind.
    private const val UNITS = "mmol(?:/l)?|mg ?/ ?dl|mgdl|units?|grams?|iu|u|g"

    private val NUMBER_WITH_UNIT =
        Regex("""(\d[\d.,]*)\s*($UNITS)\b""", RegexOption.IGNORE_CASE)

    /**
     * Any fractional number. A glucose value, a dose and a learned rate are
     * all fractional here; nothing the export needs to KEEP is — ages,
     * counts, timings and status codes are whole numbers by construction.
     * That asymmetry is what makes a blanket rule safe.
     */
    private val DECIMAL = Regex("""\d+[.,]\d+""")

    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")

    /**
     * Rewrites one line so no medical number survives it, keeping everything a
     * collection problem is read with: the words, the counts, the ages, the
     * units.
     */
    fun scrub(line: String): String {
        var out = MAC.replace(line, "<mac redacted>")
        out = NUMBER_WITH_UNIT.replace(out) { m -> "$MARK ${m.groupValues[2]}" }
        out = DECIMAL.replace(out, MARK)
        return out
    }

    /**
     * Every number in [text] that could be a medical value — a fractional
     * number, or a number standing next to a glucose / insulin / carbohydrate
     * unit. The export runs this over its own output before writing the file,
     * and the tests run it over an export built from a seeded state; an empty
     * result is the property the file promises in its header.
     */
    fun medicalNumbers(text: String): List<String> =
        (NUMBER_WITH_UNIT.findAll(text).map { it.value.trim() } +
            DECIMAL.findAll(text).map { it.value })
            .distinct()
            .toList()
}
