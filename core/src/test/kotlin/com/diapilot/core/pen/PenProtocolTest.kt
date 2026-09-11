package com.diapilot.core.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real pen conversation bytes captured by the OpenNov project (xDrip+) —
 * the port must speak bit-for-bit the same dialect.
 */
class PenProtocolTest {

    private val NOW = 1_783_600_000_000L

    private fun parse(hex: String, context: PenContext = PenContext(NOW)): PenMessage =
        requireNotNull(PenMessage.parse(context, hexToBytes(hex)))

    // --- link layer -------------------------------------------------------

    @Test
    fun phdFrameEncodesAndParsesLikeTheSpecimen() {
        val inner = byteArrayOf(14, 15, 16, 17, 18, 19, 20)
        val encoded = PhdFrame.encode(inner, seq = 7)
        assertArrayEquals(hexToBytes("D10308504844870E0F1011121314"), encoded)
        val parsed = requireNotNull(PhdFrame.parse(encoded))
        assertEquals(7, parsed.seq)
        assertArrayEquals(inner, parsed.inner)
    }

    // --- association ------------------------------------------------------

    private val A_REQUEST =
        "e2 00 00 32 80 00 00 00 00 01 00 2a 50 79 00 26 " +
            "80 00 00 00 80 00 80 00 00 00 00 00 00 00 00 80 " +
            "00 00 00 08 00 14 65 00 45 07 b9 51 40 0a 00 01 " +
            "01 00 00 00 00 00"

    private val A_RESPONSE =
        "e3 00 00 2c 00 03 50 79 00 26 80 00 00 00 80 00 " +
            "80 00 00 00 00 00 00 00 80 00 00 00 00 08 00 14 " +
            "65 00 45 07 b9 51 00 00 00 00 00 00 00 00 00 00"

    @Test
    fun associationRequestParsesAndResponseMatchesSpecimen() {
        val msg = parse(A_REQUEST)
        val ar = requireNotNull(msg.context.aRequest)
        assertTrue(ar.valid())
        assertEquals(2147483648L, ar.apoep!!.version)
        assertEquals(32768, ar.apoep!!.encoding)
        assertArrayEquals(hexToBytes(A_RESPONSE), msg.aResponse())
    }

    // --- device information -----------------------------------------------

    private val D_INFO =
        "e7 00 00 d3 00 d1 00 00 02 03 00 cb 00 00 00 08 " +
            "00 c5 09 84 00 0a 00 08 00 14 65 00 45 07 d7 21 " +
            "09 8f 00 04 00 01 c6 13 0a 45 00 10 20 00 1f 00 " +
            "ff ff ff ff 00 00 1f 40 00 00 00 00 09 2d 00 4b " +
            "00 04 00 47 00 01 00 01 00 06 45 47 44 4f 32 55 " +
            "00 02 00 01 00 20 44 32 30 31 33 30 32 36 33 32 " +
            "30 30 30 30 30 20 44 32 30 31 33 30 32 36 33 32 " +
            "30 30 30 30 30 20 00 03 00 01 00 01 00 00 04 00 " +
            "01 00 08 30 31 2e 30 38 2e 30 30 0a 5a 00 08 00 " +
            "01 00 04 10 48 00 01 09 28 00 1c 00 10 4e 6f 76 " +
            "6f 20 4e 6f 72 64 69 73 6b 20 41 2f 53 00 08 4e " +
            "6f 76 6f 50 65 6e 00 0a 44 00 02 40 0a 0a 4b 00 " +
            "16 00 02 00 12 02 01 00 08 04 00 00 01 00 02 a0 " +
            "48 02 02 00 02 00 00"

    @Test
    fun deviceInfoYieldsSerialAndModel() {
        val msg = parse(D_INFO)
        assertEquals(0, msg.invokeId)
        assertEquals("EGDO2U", msg.context.specification?.serial)
        assertEquals("Novo Nordisk A/S NovoPen", msg.context.model?.model)
    }

    // --- configuration ------------------------------------------------------

    private val C_REPORT =
        "e7 00 00 c4 00 c2 00 00 01 01 00 bc 00 00 00 00 " +
            "00 00 0d 1c 00 b2 40 0a 00 04 00 ac 00 3d 01 00 " +
            "00 08 00 38 0a 4d 00 02 08 00 09 43 00 02 00 00 " +
            "09 41 00 04 00 00 03 20 09 53 00 02 00 00 0a 57 " +
            "00 04 00 02 50 4d 09 51 00 02 00 01 0a 63 00 04 " +
            "00 00 00 00 09 44 00 04 00 00 00 02 00 06 00 02 " +
            "00 04 00 20 09 2f 00 04 00 82 34 01 0a 46 00 02 " +
            "f0 40 09 96 00 02 15 60 0a 55 00 08 00 01 00 04 " +
            "0a 56 00 04 00 05 00 03 00 03 00 1a 09 2f 00 04 " +
            "00 82 34 02 0a 46 00 02 f0 40 0a 55 00 08 00 01 " +
            "00 04 0a 66 00 02 00 06 00 04 00 03 00 1a 09 2f " +
            "00 04 00 82 f0 00 0a 46 00 02 f0 40 0a 55 00 08 " +
            "00 01 00 04 0a 66 00 02"

    private val C_ACCEPT =
        "e7 00 00 16 00 14 00 00 02 01 00 0e 00 00 00 00 " +
            "00 00 0d 1c 00 04 40 0a 00 00"

    @Test
    fun configurationReportAcceptedWithSpecimenReply() {
        val msg = parse(C_REPORT)
        val config = requireNotNull(msg.context.configuration)
        assertTrue(config.isAsExpected)
        assertArrayEquals(hexToBytes(C_ACCEPT), msg.acceptConfig())
    }

    // --- segments ------------------------------------------------------------

    private val S_INFO =
        "e7 00 00 7c 00 7a 00 01 02 07 00 74 01 00 0c 0d " +
            "00 6e 00 01 00 6a 00 10 00 06 00 64 09 22 00 02 " +
            "00 10 0a 4e 00 36 40 00 00 03 00 30 00 06 00 82 " +
            "34 01 00 02 00 01 00 04 0a 56 00 04 00 05 00 82 " +
            "34 02 00 03 00 01 00 04 0a 66 00 02 00 06 00 82 " +
            "f0 00 00 04 00 01 00 04 0a 66 00 02 09 53 00 02 " +
            "00 00 0a 58 00 0a 00 08 44 6f 73 65 20 4c 6f 67 " +
            "09 7b 00 04 00 00 00 03 0a 64 00 04 00 02 71 00"

    @Test
    fun segmentInfoParsesAsTypical() {
        val msg = parse(S_INFO)
        val sil = requireNotNull(msg.context.segmentInfoList)
        assertEquals(1, sil.items.size)
        assertEquals(16, sil.items[0].instnum)
        assertTrue(sil.isTypical)
    }

    @Test
    fun segmentDataYieldsDoses() {
        val msg = parse(
            "e7 00 00 44 00 42 80 01 01 01 00 3c 01 00 00 01 " +
                "c6 13 0d 21 00 32 00 10 00 00 00 00 00 00 00 03 " +
                "c0 00 00 24 00 01 c5 d1 ff 00 00 1e 08 00 00 00 " +
                "00 00 01 4b ff 00 00 14 08 00 00 00 00 00 00 00 " +
                "00 7f ff ff 04 00 00 08",
        )
        val er = requireNotNull(msg.context.eventReport)
        assertEquals(3, er.doses.size)
        val first = er.doses[0]
        assertEquals(3.0, first.units, 1e-9)
        assertTrue(first.isValid)
        // report clock 0x01c613 (116243s), dose clock 0x01c5d1 (116177s):
        // the dose happened 66 seconds before the scan.
        assertEquals(NOW - 66_000L, first.absoluteTime)
        // third entry has garbage units/flags and must be invalid
        assertTrue(!er.doses[2].isValid)
    }

    @Test
    fun ackFramesParseWithoutCrashing() {
        parse(
            "e7 00 00 1e 00 1c 80 01 02 01 00 16 01 00 ff ff " +
                "ff ff 0d 21 00 0c 00 10 00 00 00 00 00 00 00 02 00 80",
        )
        assertNotNull(
            parse(
                "e7 00 00 1e 00 1c 80 01 02 01 00 16 01 00 ff ff" +
                    " ff ff 0d 21 00 0c 00 10 00 00 00 00 00 00 00 12 00 80",
            ),
        )
    }
}
