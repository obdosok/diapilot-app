/**
 * NFC Forum Type 4 tag commands (ISO 7816 APDUs) used by the pen:
 * application/container/NDEF selection, chunked binary read and update.
 * Pure encode/parse — the actual transceive lives in the app layer.
 *
 * Part of the NovoPen protocol port from xDrip+
 * (https://github.com/NightscoutFoundation/xDrip), GPL-3.0 — see Bytes.kt.
 */
package com.diapilot.core.pen

import java.nio.ByteBuffer

object T4Select {
    private val NDEF_TAG_APPLICATION = hexToBytes("D2760000850101")
    private val CAPABILITY_CONTAINER = hexToBytes("E103")
    private val NDEF = hexToBytes("E104")

    private fun encode(p1: Int, p2: Int, data: ByteArray, le: Int): ByteArray {
        val hasLe = le != -1
        val b = ByteBuffer.allocate(5 + data.size + if (hasLe) 1 else 0)
        b.putU8(0x00)        // CLA
        b.putU8(0xA4)        // INS select
        b.putU8(p1)
        b.putU8(p2)
        b.putU8(data.size)
        b.put(data)
        if (hasLe) b.putU8(le)
        return b.array()
    }

    fun application(): ByteArray = encode(p1 = 0x04, p2 = 0x00, data = NDEF_TAG_APPLICATION, le = 0)
    fun container(): ByteArray = encode(p1 = 0x00, p2 = 0x0C, data = CAPABILITY_CONTAINER, le = -1)
    fun ndef(): ByteArray = encode(p1 = 0x00, p2 = 0x0C, data = NDEF, le = -1)
}

object T4Read {
    fun encode(offset: Int, length: Int): ByteArray {
        val b = ByteBuffer.allocate(5)
        b.putU8(0x00)        // CLA
        b.putU8(0xB0)        // INS read binary
        b.putU16(offset)
        b.putU8(length)
        return b.array()
    }

    fun encodeForMtu(offset: Int, length: Int, mtu: Int): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var off = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, mtu)
            list.add(encode(off, chunk))
            off += chunk
            remaining -= chunk
        }
        return list
    }
}

object T4Update {
    private fun encode(offset: Int, bytes: ByteArray, firstFragment: Boolean, lastFragment: Boolean): ByteArray {
        val isFragment = offset > 0
        val hasDlen = offset == 0 || firstFragment
        val len = if (lastFragment) 7 else bytes.size + if (hasDlen) 7 else 5
        val b = ByteBuffer.allocate(len)
        b.putU8(0x00)        // CLA
        b.putU8(0xD6)        // INS update binary
        b.putU16(if (isFragment) offset + 2 else 0)
        b.putU8(if (lastFragment) 2 else bytes.size + if (hasDlen) 2 else 0)
        if (hasDlen) b.putU16(if (firstFragment) 0 else bytes.size)
        if (!lastFragment) b.put(bytes)
        return b.array()
    }

    fun encodeForMtu(bytes: ByteArray, mtu: Int): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var offset = 0
        val b = ByteBuffer.wrap(bytes)
        while (b.remaining() > 0) {
            val chunkSize = minOf(b.remaining(), mtu - 7)
            val chunk = ByteArray(chunkSize)
            b.get(chunk)
            list.add(encode(offset, chunk, firstFragment = offset == 0 && b.remaining() > 0, lastFragment = false))
            offset += chunkSize
        }
        when {
            list.size > 1 -> list.add(encode(0, bytes, firstFragment = false, lastFragment = true))
            list.isEmpty() -> list.add(encode(0, ByteArray(0), firstFragment = false, lastFragment = false))
        }
        return list
    }
}

class T4Reply private constructor() {
    var bytes: ByteArray? = null
    private var lastShort: Int = -1

    val isOkay: Boolean get() = lastShort == 0x9000

    fun asInteger(): Int {
        val bts = bytes ?: return -1
        val b = ByteBuffer.wrap(bts)
        return when (bts.size) {
            2 -> b.u16()
            4 -> b.int
            else -> -1
        }
    }

    companion object {
        /** Parses a reply; when [into] is given, payload bytes accumulate (chunked reads). */
        fun parse(raw: ByteArray?, into: T4Reply? = null): T4Reply? {
            if (raw == null) return null
            val b = ByteBuffer.wrap(raw)
            val r = into ?: T4Reply()
            val blen = b.remaining() - 2
            if (blen < 0) return null
            if (blen > 0) {
                val prev = r.bytes
                if (prev != null) {
                    r.bytes = prev.copyOf(prev.size + blen)
                    b.get(r.bytes!!, prev.size, blen)
                } else {
                    r.bytes = ByteArray(blen)
                    b.get(r.bytes!!, 0, blen)
                }
            }
            r.lastShort = b.u16()
            return r
        }
    }
}
