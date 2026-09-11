/**
 * PHD link layer: IEEE 11073 payloads wrapped in an NDEF record with type
 * "PHD" and a sequence nibble. Sits between the Type 4 tag file and the
 * association/message layer.
 *
 * Part of the NovoPen protocol port from xDrip+
 * (https://github.com/NightscoutFoundation/xDrip), GPL-3.0 — see Bytes.kt.
 */
package com.diapilot.core.pen

import java.nio.ByteBuffer

class PhdFrame private constructor() {
    var seq: Int = -1
        private set
    var inner: ByteArray = ByteArray(0)
        private set

    companion object {
        private const val MB = 1 shl 7
        private const val ME = 1 shl 6
        private const val SR = 1 shl 4
        private const val IL = 1 shl 3
        private const val WELL_KNOWN = 1
        private val TYPE_ID = "PHD".toByteArray()

        fun encode(inner: ByteArray, seq: Int): ByteArray {
            val b = ByteBuffer.allocate(inner.size + 7)
            b.putU8(MB or ME or SR or WELL_KNOWN)
            b.putU8(TYPE_ID.size)
            b.putU8(inner.size + 1)
            b.put(TYPE_ID)
            b.putU8((seq and 0x0F) or 0x80)
            if (inner.isNotEmpty()) b.put(inner)
            return b.array()
        }

        fun parse(bytes: ByteArray?): PhdFrame? {
            if (bytes == null) return null
            return try {
                val b = ByteBuffer.wrap(bytes)
                val f = PhdFrame()
                val opcode = b.u8()
                val hasId = (opcode and IL) != 0
                /* typeLen */ b.u8()
                val payloadLen = b.u8() - 1
                val idHeaderLen = if (hasId) b.u8() else 0
                val protoId = ByteArray(3)
                b.get(protoId)
                if (!protoId.contentEquals(TYPE_ID)) return null
                if (hasId) b.get(ByteArray(idHeaderLen))
                val chk = b.u8()
                f.seq = chk and 0x0F
                f.inner = ByteArray(payloadLen)
                b.get(f.inner)
                f
            } catch (e: Exception) {
                null
            }
        }
    }
}
