package io.github.obdosok.diapilot.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ring itself: a day wide, capped, oldest first, and honest about what the
 * cap threw away. Driven through [DiagLog.record], which is the half with no
 * Android in it — [DiagLog.i] and friends add the `android.util.Log` write on
 * top of it, and that half needs no test of its own.
 */
class DiagLogTest {

    private val now = 1_757_000_000_000L

    @Before fun empty() = DiagLog.clear()

    @After fun leaveItEmpty() = DiagLog.clear()

    @Test fun `only the last day leaves the ring`() {
        DiagLog.record('I', "A", "yesterday plus a minute", now - DiagLog.WINDOW_MS - 60_000)
        DiagLog.record('I', "B", "exactly a day", now - DiagLog.WINDOW_MS)
        DiagLog.record('I', "C", "an hour ago", now - 3_600_000)
        assertEquals(listOf("B", "C"), DiagLog.lines(now).map { it.tag })
    }

    @Test fun `lines come out in the order they were written`() {
        repeat(5) { i -> DiagLog.record('I', "T$i", "line $i", now - (5 - i) * 60_000L) }
        assertEquals((0..4).map { "line $it" }, DiagLog.lines(now).map { it.message })
    }

    @Test fun `a reconnect storm cannot grow the ring without bound`() {
        repeat(DiagLog.MAX_LINES + 250) { DiagLog.record('W', "BLE", "reconnecting", now) }
        assertEquals(DiagLog.MAX_LINES, DiagLog.lines(now).size)
        assertEquals(250, DiagLog.dropped())
    }

    @Test fun `the level and the tag survive, because they are how the log is read`() {
        DiagLog.record('W', "XdripBgReceiver", "broadcast refused", now)
        val line = DiagLog.lines(now).single()
        assertEquals('W', line.level)
        assertEquals("XdripBgReceiver", line.tag)
        assertEquals("broadcast refused", line.message)
        assertTrue(line.atMs == now)
    }
}
