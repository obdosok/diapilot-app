package io.github.obdosok.diapilot.nfc

import android.nfc.Tag
import android.nfc.tech.NfcV
import com.diapilot.core.libre.FRAM_SIZE
import io.github.obdosok.diapilot.diag.DiagLog

/**
 * Libre 2 NFC transport: read patchInfo and the (encrypted) 344-byte FRAM
 * over ISO-15693. Command sequences ported from xDrip+
 * (https://github.com/NightscoutFoundation/xDrip), GPL-3.0: NFCReaderX.
 * Decryption is NOT done here — the raw image goes to the OOP2 companion
 * app over its broadcast API (see LibreOop2Bridge).
 */
class LibreNfcScanner {

    companion object {
        private const val TAG = "LibreNfc"
    }

    data class RawScan(
        val uid: ByteArray,        // 8-byte ISO-15693 UID
        val patchInfo: ByteArray,  // 6 bytes
        val fram: ByteArray,       // 344 bytes, encrypted for Libre 2
    )

    fun scan(tag: Tag): RawScan? {
        val nfcv = NfcV.get(tag) ?: return null
        return try {
            nfcv.connect()
            val uid = tag.id
            val mfr = uid[6]

            // patchInfo: custom command 0xA1; first reply byte is a status
            // flag, the canonical payload is 6 bytes.
            val info = nfcv.transceive(byteArrayOf(0x02, 0xA1.toByte(), mfr))
            val patchInfo = info.copyOfRange(1, minOf(7, info.size))

            val fram = readFram(nfcv) ?: return null
            RawScan(uid, patchInfo, fram)
        } catch (e: Exception) {
            DiagLog.w(TAG, "scan failed: ${e.message}")
            null
        } finally {
            runCatching { nfcv.close() }
        }
    }

    /**
     * Enable BLE streaming: {0x02, 0xA1, mfr} ++ OOP2's NFC unlock payload.
     * The sensor answers with its BLE MAC (7 bytes: drop the status byte,
     * reverse the remaining six). Null on failure.
     */
    fun enableStreaming(tag: Tag, nfcUnlock: ByteArray): String? {
        val nfcv = NfcV.get(tag) ?: return null
        return try {
            nfcv.connect()
            val mfr = tag.id[6]
            val cmd = byteArrayOf(0x02, 0xA1.toByte(), mfr) + nfcUnlock
            val res = nfcv.transceive(cmd)
            if (res.size != 7) {
                DiagLog.w(TAG, "enableStreaming: unexpected reply len ${res.size}")
                return null
            }
            res.copyOfRange(1, 7).reversedArray()
                .joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            DiagLog.w(TAG, "enableStreaming failed: ${e.message}")
            null
        } finally {
            runCatching { nfcv.close() }
        }
    }

    /** 43 blocks × 8 bytes, three at a time (xDrip multiblock read). */
    private fun readFram(nfcv: NfcV): ByteArray? {
        val data = ByteArray(360)
        var i = 0
        while (i < 43) {
            val count = if (i == 42) 1 else 3
            val reply = try {
                nfcv.transceive(byteArrayOf(0x02, 0x23, i.toByte(), (count - 1).toByte()))
            } catch (e: Exception) {
                // Fall back to a single-block read for this position.
                try {
                    nfcv.transceive(byteArrayOf(0x02, 0x23, i.toByte(), 0x00))
                } catch (e2: Exception) {
                    DiagLog.w(TAG, "block $i read failed: ${e2.message}")
                    return null
                }
            }
            val payload = reply.copyOfRange(1, reply.size)
            val blocks = payload.size / 8
            for (b in 0 until blocks) {
                val at = (i + b) * 8
                if (at + 8 <= data.size) {
                    payload.copyInto(data, at, b * 8, b * 8 + 8)
                }
            }
            i += blocks.coerceAtLeast(1)
        }
        return data.copyOfRange(0, FRAM_SIZE)
    }
}
