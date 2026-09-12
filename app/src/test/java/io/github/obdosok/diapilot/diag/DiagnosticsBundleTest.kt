package io.github.obdosok.diapilot.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PROMISE IN THE FILE'S HEADER, ASSERTED AS A PROPERTY.
 *
 * Not a golden string: a golden file would have to be regenerated every time a
 * line is added, and the regeneration is exactly the moment a leaked value
 * would be accepted as the new expectation. So the state is seeded with
 * distinctive numbers instead, and the assertions are (1) those digits appear
 * nowhere in the export and (2) the export's body holds no number that could
 * be a glucose value, a dose or a carbohydrate amount at all.
 *
 * The seeds are deliberately unusual — 19.7, 4.25, 137, 0.0731 — so a match is
 * a leak and not a coincidence with an age in minutes or a count.
 */
class DiagnosticsBundleTest {

    private val now = 1_757_000_000_000L

    /** Every medical number the fixture holds, as it would be written out. */
    private val seeds = listOf("19.7", "12.9", "3.4", "4.25", "137", "0.0731", "porridge")

    private fun seeded(
        logLines: List<DiagLog.Line> = defaultLog(),
        sources: List<SourceStatus> = defaultSources(),
    ) = DiagSnapshot(
        generatedAtMs = now,
        appLabel = "io.github.obdosok.diapilot oss 79 / 1.3.0-f05-dish-recognition",
        androidLabel = "14 (SDK 34)",
        mgdl = false,
        state = CollectionState(
            bleStatus = "stream is up (packet 14:03:22)",
            bleLastPacketMs = now - 4 * 60_000,
            serviceStartedMs = now - 9 * 3_600_000,
            watchServerUp = true,
            lastXdripBroadcastMs = now - 6 * 60_000,
            sensorSerial = "0M0012345678",
            sensorStartMs = now - 11 * 86_400_000L,
            lastMainReadingMs = now - 3 * 60_000,
            lastMainReadingSource = "xdrip_broadcast",
            lastMinuteReadingMs = now - 60_000,
            minuteCalibrationN = 42,
        ),
        sources = sources,
        journal = JournalState(
            readings24h = 271,
            boluses24h = 6,
            lastBolusMs = now - 88 * 60_000,
            lastBolusUnits = 4.25,
            notes24h = 4,
            lastNoteMs = now - 90 * 60_000,
            lastNoteKind = "food",
            lastNoteText = "porridge and a small apple",
            lastNoteCarbs = 137.0,
        ),
        model = ModelState(
            edition = "oss (prospective true, sensor-direct true)",
            inMemory = true,
            builtAtMs = now - 95 * 60_000,
            fromDiskSnapshot = true,
            kernelEpisodes = 31,
            effectiveEpisodes = 27.4,
            readingsInEra = 8123,
            readingsGate = 500,
            carbSensMmolPerG = 0.0731,
            carbSensN = 19,
            carbSensOverridden = true,
            estimatedIsfMmolPerU = 3.4,
            isfSource = "manual",
            insulinProfile = "NovoRapid",
            physioArtifactId = "physio-v1:abc0123456789def:cts-7:state-0f1e2d3c",
            hybridModelSha = "abc0123456789def0123456789abcdef0123456789abcdef0123456789abcdef",
            hybridAlgoVersion = "forecast-v11-kotlin-shadow-13-macro-queue-single-arm",
        ),
        logLines = logLines,
        logDropped = 12,
    )

    /**
     * The log as the call sites leave it, INCLUDING lines that still carry a
     * value. Two of these are shapes the S10 fix removed at the source; they
     * stay in the fixture on purpose, because the guard is what protects the
     * ~40 call sites nobody has audited since.
     */
    private fun defaultLog() = listOf(
        DiagLog.Line(now - 700 * 60_000, 'I', "CollectorService", "Collector service started"),
        DiagLog.Line(now - 320 * 60_000, 'W', "LibreBleClient", "connecting to C1:2D:3E:4F:5A:6B, auto=true"),
        DiagLog.Line(now - 120 * 60_000, 'I', "XdripBgReceiver", "BG 19.7 mmol/L @ 1757000000000 (Flat)"),
        DiagLog.Line(now - 44 * 60_000, 'I', "HypoAlertNotifier", "hypo PREDICT_ALARM bg=3.4"),
        DiagLog.Line(now - 12 * 60_000, 'W', "TreatmentsPollWorker", "pebble: now=12.9 iob=4.25"),
        DiagLog.Line(now - 2 * 60_000, 'I', "PenDoseSaver", "saved: new=1 dup=0 of 24 valid"),
    )

    private fun defaultSources() = listOf(
        SourceStatus("xdrip_broadcast", true, now - 3 * 60_000, 19.7, 271),
        SourceStatus("libre_ble", true, now - 4 * 60_000, 12.9, 143, note = "slope 1.04 applied"),
        SourceStatus("meter", false, 0, null, 0),
    )

    private fun export() = DiagnosticsBundle.build(seeded(), "20260912-1403")

    // ——— the property ———

    @Test fun `no seeded value appears anywhere in the export`() {
        val text = export()
        seeds.forEach { seed ->
            assertFalse("the export still contains $seed:\n$text", text.contains(seed))
        }
    }

    @Test fun `the body holds no number that could be a medical value`() {
        val body = DiagnosticsBundle.guard(DiagnosticsBundle.body(seeded()))
        assertEquals(
            "unredacted numbers survived:\n$body",
            emptyList<String>(),
            Redact.medicalNumbers(body),
        )
    }

    @Test fun `the property holds when a log line arrives in a shape nobody foresaw`() {
        val hostile = listOf(
            DiagLog.Line(now, 'I', "Future", "corrected to 8,4 mmol/l after the lens"),
            DiagLog.Line(now, 'I', "Future", "carbs=137g protein=19g"),
            DiagLog.Line(now, 'I', "Future", "basal 4.25U at 21:00"),
        )
        val body = DiagnosticsBundle.guard(DiagnosticsBundle.body(seeded(logLines = hostile)))
        assertTrue(Redact.medicalNumbers(body).isEmpty())
        assertFalse(body.contains("137"))
        assertFalse(body.contains("4.25"))
        assertFalse(body.contains("8,4"))
    }

    @Test fun `a source that reported keeps its unit and loses its value`() {
        val body = DiagnosticsBundle.body(seeded())
        assertTrue(body.contains("xdrip_broadcast: on"))
        assertTrue(body.contains("271 events / 24 h"))
        assertTrue(body.contains("last value <redacted> mmol/L"))
        assertTrue(body.contains("meter: off, last never, 0 events / 24 h, last value none"))
    }

    @Test fun `the sensor serial does not leave the phone`() {
        val text = export()
        assertFalse(text.contains("0M0012345678"))
        assertTrue(text.contains("sensor serial:       <redacted, 12 chars>"))
    }

    @Test fun `the journal says whether anything is being entered, not what`() {
        val body = DiagnosticsBundle.body(seeded())
        assertTrue(body.contains("readings:            271"))
        assertTrue(body.contains("boluses:             6, last 88 min ago, <redacted> U"))
        assertTrue(
            body.contains(
                "notes:               4, last 90 min ago, kind food, " +
                    "note <redacted, 26 chars>, carbs <redacted> g",
            ),
        )
    }

    @Test fun `an empty ring says so instead of looking like a quiet day`() {
        val body = DiagnosticsBundle.body(seeded(logLines = emptyList()))
        assertTrue(body.contains("(the ring is empty"))
    }

    // ——— what the file keeps, because a file that keeps nothing is not a diagnostic ———

    @Test fun `the header states what was removed and what was kept`() {
        val header = DiagnosticsBundle.header(seeded(), "20260912-1403")
        assertTrue(header.contains("WHAT WAS REMOVED"))
        assertTrue(header.contains("WHAT WAS KEPT"))
        assertTrue(header.contains("bg <redacted> mmol/L"))
        // The version is the one number in the file that must survive intact.
        assertTrue(header.contains("1.3.0-f05-dish-recognition"))
    }

    @Test fun `the body still answers the question it exists for`() {
        val body = DiagnosticsBundle.body(seeded())
        assertTrue(body.contains("ble last packet:     4 min ago"))
        assertTrue(body.contains("xdrip broadcast:     6 min ago"))
        assertTrue(body.contains("watch server:        listening"))
        assertTrue(body.contains("source xdrip_broadcast"))
        assertTrue(body.contains("readings in era:     8123 (the build gate is 500)"))
        assertTrue(body.contains("kernel episodes:     31"))
        assertTrue(body.contains("insulin profile:     NovoRapid"))
        assertTrue(body.contains("forecast-v11-kotlin-shadow-13-macro-queue-single-arm"))
        // The log keeps its own narrative: the tags, the levels and the order.
        assertTrue(body.contains("CollectorService: Collector service started"))
        assertTrue(body.indexOf("CollectorService") < body.indexOf("PenDoseSaver"))
    }

    @Test fun `the file name says what the file is`() {
        assertEquals(
            "diapilot-diagnostics-20260912-1403.txt",
            DiagnosticsBundle.fileName("20260912-1403"),
        )
    }
}
