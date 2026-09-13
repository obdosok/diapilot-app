package com.diapilot.core.libre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreFramTest {

    /** Synthetic decrypted FRAM: known rings, valid CRCs. */
    private fun fram(
        sensorTime: Int = 5000,
        trendIdx: Int = 3,
        histIdx: Int = 5,
        status: Int = 0x03,
    ): ByteArray {
        val d = ByteArray(FRAM_SIZE)
        d[4] = status.toByte()
        d[26] = trendIdx.toByte()
        d[27] = histIdx.toByte()
        d[316] = (sensorTime and 0xFF).toByte()
        d[317] = ((sensorTime shr 8) and 0xFF).toByte()
        // Trend record k carries raw = 1000 + k; history record k raw = 2000 + k.
        for (k in 0 until 16) {
            val o = 28 + k * 6
            d[o] = ((1000 + k) and 0xFF).toByte()
            d[o + 1] = (((1000 + k) shr 8) and 0x1F).toByte()
        }
        for (k in 0 until 32) {
            val o = 124 + k * 6
            d[o] = ((2000 + k) and 0xFF).toByte()
            d[o + 1] = (((2000 + k) shr 8) and 0x1F).toByte()
        }
        stampCrc16(d, 0, 24); stampCrc16(d, 24, 296); stampCrc16(d, 320, 24)
        return d
    }

    @Test
    fun crcRoundTrip() {
        val d = fram()
        assertTrue(verifyFramCrc(d))
        d[100] = (d[100] + 1).toByte()   // corrupt one byte in the big region
        assertFalse(verifyFramCrc(d))
    }

    @Test
    fun parseUnrollsTheRings() {
        val capture = 1_000_000_000L
        val p = parseFram(fram(sensorTime = 5000, trendIdx = 3, histIdx = 5), capture)
        assertNotNull(p)
        assertEquals(LibreStatus.READY, p!!.status)
        assertEquals(5000, p.sensorTimeMin)
        assertEquals(capture - 5000L * 60_000, p.sensorStartMs)
        // Newest trend record sits just before the ring index: slot 2.
        assertEquals(16, p.trend.size)
        val newest = p.trend.last()
        assertEquals(5000, newest.sensorTimeMin)
        assertEquals(1002, newest.raw)
        // Oldest of the 16 minutes: index goes back 15 slots (wrap to slot 3).
        assertEquals(5000 - 15, p.trend.first().sensorTimeMin)
        assertEquals(1003, p.trend.first().raw)
        // History: newest = slot 4, times step by 15 min.
        assertEquals(32, p.history.size)
        assertEquals(2004, p.history.last().raw)
        assertEquals(
            15,
            p.history[31].sensorTimeMin - p.history[30].sensorTimeMin,
        )
        // Timestamps are consistent with sensor start.
        assertEquals(p.sensorStartMs + newest.sensorTimeMin * 60_000L, newest.tsMs)
    }

    @Test
    fun bgAttachesInOrder() {
        val p = parseFram(fram(), 1_000_000_000L)!!
        val withBg = attachBg(p.trend, IntArray(16) { 100 + it })
        assertEquals(100.0, withBg.first().bgMgdl!!, 1e-9)
        assertEquals(115.0, withBg.last().bgMgdl!!, 1e-9)
        // Size mismatch → salvage the newest overlap (aligned at the end),
        // not drop the whole batch: last 3 attach, the older ones stay null.
        val partial = attachBg(p.trend, IntArray(3) { 200 + it })
        assertEquals(3, partial.count { it.bgMgdl != null })
        assertEquals(13, partial.count { it.bgMgdl == null })
        assertEquals(200.0, partial[13].bgMgdl!!, 1e-9)
        assertEquals(202.0, partial.last().bgMgdl!!, 1e-9)
        assertTrue(partial.take(13).all { it.bgMgdl == null })
    }

    @Test
    fun statusesAndSanity() {
        assertEquals(LibreStatus.STARTING, parseFram(fram(status = 0x02), 0L)!!.status)
        assertFalse(parseFram(fram(status = 0x04), 0L)!!.status.usable)
        // Absurd sensor time → junk.
        assertEquals(null, parseFram(fram(sensorTime = 60_000), 0L))
    }

    @Test
    fun serialDecodeShape() {
        // The known fake-DE sensor UID from xDrip sources.
        val uid = byteArrayOf(
            0xd6.toByte(), 0xf1.toByte(), 0x0f, 0x01, 0x00, 0xa4.toByte(), 0x07, 0xe0.toByte(),
        )
        val serial = decodeLibreSerial(uid)
        org.junit.Assert.assertNotNull(serial)
        assertEquals(11, serial!!.length)
        assertTrue(serial.startsWith("0"))
        // Deterministic: same UID, same serial.
        assertEquals(serial, decodeLibreSerial(uid.copyOf()))
    }

    @Test
    fun serialDecodeRefusesAnIdThatIsNotEightBytes() {
        // Reader mode dispatches NFC-A/B tags too: 4-, 7- and 10-byte ids,
        // none of them a Libre. Used to throw out of the scan thread.
        for (n in listOf(0, 1, 4, 7, 9, 10)) {
            org.junit.Assert.assertNull("$n bytes", decodeLibreSerial(ByteArray(n) { 0x5A }))
        }
        org.junit.Assert.assertNotNull(decodeLibreSerial(ByteArray(LIBRE_UID_BYTES) { 0x5A }))
    }
}
