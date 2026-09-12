package io.github.obdosok.diapilot.diag

/**
 * THE VALUE-FREE DIAGNOSTICS FILE — text, built from a [DiagSnapshot], with no
 * Android and no clock of its own.
 *
 * It is a separate export from `data/DiagnosticsExport`, which writes
 * `diag.json` for the maintainer's own bench and carries the food corpus with
 * every number in it. This one is the file an early tester is asked to send
 * after a week, so the two have opposite rules and are deliberately not one
 * function with a flag.
 *
 * THE FILE'S OWN HEADER SAYS WHAT WAS REMOVED AND WHAT WAS KEPT, because a
 * tester deciding whether to send it cannot be expected to read this class,
 * and because a recipient has to know what the file does NOT prove.
 *
 * LANGUAGE. The body is English. It is read by whoever debugs the collection
 * chain, next to `logcat` output that is English whatever the phone's locale,
 * and the app's own log lines inside it are not translated either. The
 * user-visible surface around it — the button, the share sheet, the failure
 * message — goes through `strings.xml` in both languages as usual.
 */
object DiagnosticsBundle {

    /** `diapilot-diagnostics-20260912-1403.txt` — sortable, and says what it is. */
    fun fileName(stamp: String): String = "diapilot-diagnostics-$stamp.txt"

    fun build(snapshot: DiagSnapshot, stampLocal: String): String =
        header(snapshot, stampLocal) + "\n" + guard(body(snapshot))

    // ——— header: the promise, in the file that makes it ———

    /**
     * NOT PASSED THROUGH THE GUARD, and the reason is the guard's own rule: a
     * version like 1.3.0 is a fractional number, and a version is the one
     * number in this file that has to survive intact. Nothing else here is a
     * measurement — every field is a literal, a version or the export's clock.
     */
    internal fun header(s: DiagSnapshot, stampLocal: String): String = buildString {
        appendLine("DiaPilot diagnostics — no values")
        appendLine("================================")
        appendLine("generated: $stampLocal (the phone's own clock)")
        appendLine("app:       ${s.appLabel}")
        appendLine("android:   ${s.androidLabel}")
        appendLine("units:     ${if (s.mgdl) "mg/dL" else "mmol/L"}")
        appendLine()
        appendLine("WHAT WAS REMOVED")
        appendLine("  Every glucose value, insulin dose, carbohydrate amount and note text.")
        appendLine("  Where one stood, this file keeps its unit and shape only:")
        appendLine("  bg ${Redact.MARK} mmol/L, bolus ${Redact.MARK} U, note <redacted, 34 chars>.")
        appendLine("  Also removed: the sensor serial and the Bluetooth address — they name")
        appendLine("  the device, not a measurement, and no collection problem needs them.")
        appendLine()
        appendLine("WHAT WAS KEPT")
        appendLine("  Statuses, source names, counts, ages in minutes, timings, error classes,")
        appendLine("  the model's readiness and the identities of the artifacts it ran on, and")
        appendLine("  the app's own log lines from the last 24 h with their numbers stripped.")
        appendLine("  Times are relative (14 min ago): enough to see a stream stop, and it")
        appendLine("  does not describe the tester's day.")
        appendLine()
        appendLine("WHAT THIS FILE THEREFORE CANNOT SHOW")
        appendLine("  Whether a number was right. It shows whether data arrived, from where,")
        appendLine("  how often, and what the app said while it did.")
    }

    // ——— body: everything under the guard ———

    internal fun body(s: DiagSnapshot): String = buildString {
        val now = s.generatedAtMs
        appendLine()
        appendLine("--- COLLECTION ---")
        val st = s.state
        appendLine("ble status:          ${free(st.bleStatus.ifBlank { "off" })}")
        appendLine("ble last packet:     ${Redact.minutesAgo(now, st.bleLastPacketMs)}")
        appendLine("service started:     ${Redact.minutesAgo(now, st.serviceStartedMs)}")
        appendLine("watch server:        ${if (st.watchServerUp) "listening" else "off"}")
        appendLine("xdrip broadcast:     ${Redact.minutesAgo(now, st.lastXdripBroadcastMs)}")
        appendLine(
            "last main reading:   ${Redact.minutesAgo(now, st.lastMainReadingMs)}" +
                ", source ${free(st.lastMainReadingSource ?: "none")}",
        )
        appendLine("last minute reading: ${Redact.minutesAgo(now, st.lastMinuteReadingMs)}")
        appendLine("minute calibration:  ${st.minuteCalibrationN?.let { "$it pairs" } ?: "none"}")
        appendLine("sensor serial:       ${Redact.identifier(st.sensorSerial)}")
        appendLine("sensor started:      ${Redact.daysAgo(now, st.sensorStartMs)}")

        appendLine()
        appendLine("--- SOURCES ---")
        if (s.sources.isEmpty()) {
            appendLine("(no source has reported in this window)")
        } else {
            s.sources.forEach { src ->
                appendLine(
                    "${free(src.name)}: ${if (src.enabled) "on" else "off"}" +
                        ", last ${Redact.minutesAgo(now, src.lastEventMs)}" +
                        ", ${src.eventsIn24h?.let { "$it events / 24 h" } ?: "count unknown"}" +
                        ", last value ${if (src.lastValueMmol == null) "none" else Redact.glucose(s.mgdl)}" +
                        (src.note?.let { ", ${free(it)}" } ?: ""),
                )
            }
        }
        // WP-B1's per-source status screen is landing in parallel. Its rows
        // become `SourceStatus` entries above and need no change here.
        appendLine("(source rows are what the stored readings and DiagState can say today)")

        appendLine()
        appendLine("--- JOURNAL (last 24 h) ---")
        val j = s.journal
        appendLine("readings:            ${j.readings24h}")
        appendLine(
            "boluses:             ${j.boluses24h}" +
                ", last ${Redact.minutesAgo(now, j.lastBolusMs)}" +
                ", ${if (j.lastBolusUnits == null) "none recorded" else Redact.insulin()}",
        )
        appendLine(
            "notes:               ${j.notes24h}" +
                ", last ${Redact.minutesAgo(now, j.lastNoteMs)}" +
                ", kind ${free(j.lastNoteKind ?: "none")}" +
                ", note ${Redact.note(j.lastNoteText)}" +
                ", carbs ${if (j.lastNoteCarbs == null) "none" else Redact.carbs()}",
        )

        appendLine()
        appendLine("--- MODEL ---")
        val m = s.model
        appendLine("edition:             ${free(m.edition)}")
        appendLine(
            "model in memory:     ${if (m.inMemory) "yes" else "no"}" +
                if (m.fromDiskSnapshot) " (restored from the disk snapshot)" else "",
        )
        appendLine("built:               ${Redact.minutesAgo(now, m.builtAtMs)}")
        appendLine(
            "readings in era:     ${m.readingsInEra?.toString() ?: "unknown"}" +
                " (the build gate is ${m.readingsGate})",
        )
        appendLine(
            "kernel episodes:     ${m.kernelEpisodes}" +
                ", effective about ${kotlin.math.round(m.effectiveEpisodes).toInt()}",
        )
        appendLine(
            "carb sensitivity:    " + when (m.carbSensMmolPerG) {
                null -> "not learned"
                else -> "learned ${Redact.perUnit("mmol/g")}" +
                    (m.carbSensN?.let { " from $it meals" } ?: "") +
                    (if (m.carbSensOverridden) ", the user's override is in force" else "")
            },
        )
        appendLine(
            "estimated ISF:       " +
                if (m.estimatedIsfMmolPerU > 0) Redact.perUnit("mmol/U") else "not estimated",
        )
        appendLine("ISF source:          ${free(m.isfSource)}")
        appendLine("insulin profile:     ${free(m.insulinProfile)}")
        appendLine("physio artifact:     ${free(m.physioArtifactId ?: "none")}")
        appendLine("hybrid model sha256: ${free(m.hybridModelSha ?: "not installed")}")
        appendLine("hybrid algo version: ${free(m.hybridAlgoVersion)}")

        appendLine()
        appendLine(
            "--- LOG (last 24 h, oldest first; ${s.logLines.size} lines" +
                (if (s.logDropped > 0) ", ${s.logDropped} dropped at the cap" else "") + ") ---",
        )
        if (s.logLines.isEmpty()) {
            appendLine("(the ring is empty — a freshly started process has not logged yet)")
        } else {
            s.logLines.forEach { l ->
                appendLine("${ageColumn(now, l.atMs)} ${l.level} ${free(l.tag)}: ${free(l.message)}")
            }
        }
    }

    /**
     * A relative timestamp column, right-aligned so the log reads as a
     * sequence: -1438m is the oldest line the window can hold, -0m the newest.
     */
    private fun ageColumn(nowMs: Long, atMs: Long): String =
        "-${(nowMs - atMs) / 60_000}m".padStart(7)

    /**
     * Text that came from somewhere else — a status string, a source name, a
     * log message written at a call site that knows nothing about this file.
     * Scrubbed unconditionally rather than checked first, because [Redact.scrub]
     * also removes what [Redact.medicalNumbers] does not look for: a Bluetooth
     * address has no unit next to it and no decimal point in it.
     */
    private fun free(text: String): String = Redact.scrub(text)

    /**
     * THE LAST THING THAT RUNS. Any body line still holding a number that
     * could be a medical value is scrubbed again and, if that fails, replaced
     * whole. A helper missed somewhere above therefore costs a line of
     * diagnostics, never a value.
     */
    internal fun guard(body: String): String =
        body.lineSequence().joinToString("\n") { line ->
            if (Redact.medicalNumbers(line).isEmpty()) return@joinToString line
            val scrubbed = Redact.scrub(line)
            if (Redact.medicalNumbers(scrubbed).isEmpty()) scrubbed else Redact.WITHHELD
        }
}
