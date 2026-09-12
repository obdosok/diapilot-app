package io.github.obdosok.diapilot.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redaction helpers and the guard behind them, asserted on the two things
 * that make the export safe to send: what a replacement looks like, and what
 * the guard refuses to let past.
 */
class RedactTest {

    @Test fun `a value is replaced by its unit, never by nothing`() {
        assertEquals("<redacted> mmol/L", Redact.glucose(mgdl = false))
        assertEquals("<redacted> mg/dL", Redact.glucose(mgdl = true))
        assertEquals("<redacted> U", Redact.insulin())
        assertEquals("<redacted> g", Redact.carbs())
        assertEquals("<redacted> mmol/g", Redact.perUnit("mmol/g"))
    }

    @Test fun `free text keeps its length and loses its content`() {
        assertEquals("<redacted, 34 chars>", Redact.note("x".repeat(34)))
        assertEquals("<redacted, 0 chars>", Redact.note(""))
        assertEquals("none", Redact.note(null))
        assertFalse(Redact.note("pizza with the neighbours").contains("pizza"))
    }

    @Test fun `an identifier is a length, not a serial`() {
        assertEquals("<redacted, 8 chars>", Redact.identifier("0M001234"))
        assertEquals("none", Redact.identifier(null))
        assertEquals("none", Redact.identifier("  "))
    }

    @Test fun `a timestamp leaves as an age`() {
        val now = 1_700_000_000_000L
        assertEquals("14 min ago", Redact.minutesAgo(now, now - 14 * 60_000))
        assertEquals("never", Redact.minutesAgo(now, 0))
        assertEquals("3 d ago", Redact.daysAgo(now, now - 3 * 86_400_000L))
        assertEquals("never", Redact.daysAgo(now, 0))
    }

    @Test fun `the scrubber removes the number and keeps the unit`() {
        assertEquals("BG <redacted> mmol/L arrived", Redact.scrub("BG 5.6 mmol/L arrived"))
        assertEquals("bolus <redacted> U accepted", Redact.scrub("bolus 4.25 U accepted"))
        assertEquals("meal <redacted> g", Redact.scrub("meal 45 g"))
        assertEquals("iob <redacted> units", Redact.scrub("iob 3 units"))
        assertEquals("sgv <redacted> mg/dL", Redact.scrub("sgv 101 mg/dL"))
    }

    @Test fun `the scrubber keeps what a collection problem is read with`() {
        assertEquals(
            "connection state=2 status=0 packets=17",
            Redact.scrub("connection state=2 status=0 packets=17"),
        )
        assertEquals("build DONE in 7412 ms", Redact.scrub("build DONE in 7412 ms"))
        assertEquals("stall notified (age=32 min)", Redact.scrub("stall notified (age=32 min)"))
        assertEquals("sgv.json: 288 readings backfilled", Redact.scrub("sgv.json: 288 readings backfilled"))
    }

    @Test fun `a bluetooth address is not a measurement and still goes`() {
        assertEquals(
            "connecting to <mac redacted>, connectionIndex=3",
            Redact.scrub("connecting to C1:2D:3E:4F:5A:6B, connectionIndex=3"),
        )
    }

    @Test fun `the guard names every number that could be a medical value`() {
        assertTrue(Redact.medicalNumbers("hypo PREDICT_ALARM, anchor <redacted> mmol/L").isEmpty())
        assertTrue(Redact.medicalNumbers("service started: 42 min ago").isEmpty())
        assertEquals(listOf("5.6 mmol/L", "5.6"), Redact.medicalNumbers("bg 5.6 mmol/L"))
        assertEquals(listOf("4 U"), Redact.medicalNumbers("bolus 4 U"))
        assertEquals(listOf("45 g"), Redact.medicalNumbers("ate 45 g of rice"))
        assertEquals(listOf("1.33"), Redact.medicalNumbers("the sensor reads 1.33 low"))
    }

    @Test fun `a scrubbed line satisfies the guard`() {
        listOf(
            "BG 5.6 mmol/L @ 1700000000000 (Flat)",
            "hypo PREDICT_ALARM bg=3.9",
            "pebble: now=7.2 iob=1.85",
            "rapid fall: -0.12/min projected 3.4",
            "sustained high: 95 min, peak 14.8",
            "ate 45 g, gave 4 U",
        ).forEach { line ->
            val scrubbed = Redact.scrub(line)
            assertTrue(
                "the guard still objects to: $scrubbed",
                Redact.medicalNumbers(scrubbed).isEmpty(),
            )
        }
    }
}
