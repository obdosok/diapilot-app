/**
 * NovoPen 6 / Echo Plus NFC protocol — byte-level helpers.
 *
 * The protocol implementation in this package is a Kotlin port of the
 * OpenNov module from xDrip+ (https://github.com/NightscoutFoundation/xDrip,
 * author JamOrHam), GPL-3.0, reverse engineered there; ported with
 * structural changes but no clean-room pretense.
 */
package com.diapilot.core.pen

import java.nio.ByteBuffer

internal fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
internal fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
internal fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL

internal fun ByteBuffer.putU8(v: Int) { put((v and 0xFF).toByte()) }
internal fun ByteBuffer.putU16(v: Int) { putShort((v and 0xFFFF).toShort()) }
internal fun ByteBuffer.putU32(v: Long) { putInt((v and 0xFFFFFFFFL).toInt()) }

/** Length-prefixed (u16) byte block. */
internal fun ByteBuffer.indexedBytes(): ByteArray {
    val len = u16()
    val bytes = ByteArray(len)
    get(bytes)
    return bytes
}

internal fun ByteBuffer.putIndexedBytes(bytes: ByteArray?) {
    putU16(bytes?.size ?: 0)
    if (bytes != null) put(bytes)
}

internal fun ByteBuffer.indexedString(): String =
    String(indexedBytes()).replace("\u0000", "")

internal fun ivalue(bytes: ByteArray): Long = when (bytes.size) {
    4 -> ByteBuffer.wrap(bytes).u32()
    2 -> ByteBuffer.wrap(bytes).u16().toLong()
    else -> -1L
}

/** "e7 00 0d ..." (whitespace-tolerant) → bytes; used heavily by tests. */
fun hexToBytes(hex: String): ByteArray {
    val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
