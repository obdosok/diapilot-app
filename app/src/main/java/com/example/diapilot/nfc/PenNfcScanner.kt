package com.example.diapilot.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.diapilot.core.pen.Fsa
import com.diapilot.core.pen.InsulinDose
import com.diapilot.core.pen.PenContext
import com.diapilot.core.pen.PenMachine
import com.diapilot.core.pen.PhdFrame
import com.diapilot.core.pen.T4Read
import com.diapilot.core.pen.T4Reply
import com.diapilot.core.pen.T4Select
import com.diapilot.core.pen.T4Update
import java.nio.ByteBuffer

/**
 * NFC side of the NovoPen exchange: Type 4 tag select/read/update over
 * IsoDep, PHD frame ack/sequence bookkeeping, and the core PenMachine
 * driving the conversation. Protocol logic lives in core (tested); this
 * class only moves bytes.
 */
class PenNfcScanner {

    companion object {
        private const val TAG = "PenNfc"
        private const val MAX_ERRORS = 3
        private const val MAX_TRANSACTIONS = 200
    }

    data class ScanResult(
        val serial: String?,
        val model: String?,
        val doses: List<InsulinDose>,
        val completed: Boolean,
    )

    private var mleMax = -1
    private var mlcMax = -1
    private var sequence = 0

    fun scan(tag: Tag): ScanResult? {
        val isoDep = IsoDep.get(tag) ?: return null
        // Every tap is a fresh conversation — the pen starts at seq 0.
        sequence = 0
        mleMax = -1
        mlcMax = -1
        return try {
            isoDep.connect()
            isoDep.timeout = 1000
            if (!doNeededSelection(isoDep)) {
                Log.w(TAG, "Type 4 selection failed")
                return null
            }
            Log.i(TAG, "selection ok; mle=$mleMax mlc=$mlcMax")

            val context = PenContext(System.currentTimeMillis())
            val allDoses = mutableListOf<InsulinDose>()
            val machine = PenMachine(
                context,
                onDoses = { doses, _ -> allDoses.addAll(doses) },
                log = { Log.d(TAG, it) },
            )

            var fsa = Fsa.read()
            var errors = 0
            var transactions = 0
            while (fsa.doRead && errors < MAX_ERRORS && transactions < MAX_TRANSACTIONS) {
                transactions++
                val raw = readFromLinkLayer(isoDep)
                val payload = extractInnerPacket(isoDep, raw)
                if (payload != null) {
                    fsa = machine.processPayload(payload)
                    if (fsa.action == Fsa.Action.WRITE_READ) {
                        writeInnerPacket(isoDep, fsa.payload ?: ByteArray(0))
                    }
                } else {
                    errors++
                    Log.d(TAG, "null payload, errors=$errors")
                }
            }
            val completed = !fsa.doRead
            Log.i(
                TAG,
                "scan finished: completed=$completed doses=${allDoses.size} " +
                    "serial=${context.specification?.serial} tx=$transactions",
            )
            ScanResult(
                serial = context.specification?.serial,
                model = context.model?.model,
                doses = allDoses,
                completed = completed,
            )
        } catch (e: Exception) {
            Log.w(TAG, "scan failed: $e")
            null
        } finally {
            runCatching { isoDep.close() }
        }
    }

    // --- Type 4 plumbing ---------------------------------------------------

    private fun transceive(isoDep: IsoDep, bytes: ByteArray, into: T4Reply? = null): T4Reply? =
        try {
            T4Reply.parse(isoDep.transceive(bytes), into)
        } catch (e: Exception) {
            Log.d(TAG, "transceive failed: $e")
            null
        }

    private fun transceiveOkay(isoDep: IsoDep, bytes: ByteArray, msg: String): Boolean {
        val ok = transceive(isoDep, bytes)?.isOkay == true
        if (!ok) Log.d(TAG, msg)
        return ok
    }

    private fun doNeededSelection(isoDep: IsoDep): Boolean =
        transceiveOkay(isoDep, T4Select.application(), "app select failed") &&
            transceiveOkay(isoDep, T4Select.container(), "container select failed") &&
            readContainerData(isoDep) &&
            transceiveOkay(isoDep, T4Select.ndef(), "ndef select failed")

    private fun readContainerData(isoDep: IsoDep): Boolean {
        val reply = transceive(isoDep, T4Read.encode(0, 15)) ?: return false
        if (!reply.isOkay) return false
        val data = reply.bytes ?: return false
        if (data.size != 15) return false
        val b = ByteBuffer.wrap(data)
        b.short   // cclen
        b.get()   // mapping
        mleMax = b.short.toInt() and 0xFFFF
        mlcMax = b.short.toInt() and 0xFFFF
        return true
    }

    private fun readFromLinkLayer(isoDep: IsoDep): ByteArray? {
        repeat(MAX_ERRORS) {
            if (!isoDep.isConnected) return null
            isoDep.timeout = 3000
            val lengthResult = transceive(isoDep, T4Read.encode(0, 2)) ?: return null
            if (!lengthResult.isOkay) return@repeat
            val readLen = lengthResult.asInteger()
            var reply: T4Reply? = null
            for (cmd in T4Read.encodeForMtu(2, readLen, minOf(255, mleMax))) {
                var ok = false
                repeat(MAX_ERRORS) inner@{
                    if (ok) return@inner
                    reply = transceive(isoDep, cmd, reply) ?: return null
                    if (reply!!.isOkay) ok = true else Thread.sleep(50)
                }
            }
            val bytes = reply?.takeIf { it.isOkay }?.bytes
            if (bytes != null && bytes.size == readLen) return bytes
        }
        return null
    }

    private fun writeToLinkLayer(isoDep: IsoDep, bytes: ByteArray) {
        if (!isoDep.isConnected) return
        for (packet in T4Update.encodeForMtu(bytes, mlcMax)) {
            if (!transceiveOkay(isoDep, packet, "packet write failed")) break
        }
    }

    // --- PHD frame ack/sequence ---------------------------------------------

    private val emptyNdef = byteArrayOf(0xD0.toByte(), 0x00, 0x00)

    private fun extractInnerPacket(isoDep: IsoDep, raw: ByteArray?): ByteArray? {
        val frame = PhdFrame.parse(raw) ?: return null
        if (frame.seq != sequence) {
            Log.d(TAG, "sequence mismatch ${frame.seq} vs $sequence")
            return null
        }
        writeToLinkLayer(isoDep, emptyNdef)
        return frame.inner
    }

    private fun writeInnerPacket(isoDep: IsoDep, inner: ByteArray) {
        val outer = PhdFrame.encode(inner, ++sequence)
        sequence = (sequence + 1) and 0x0F
        writeToLinkLayer(isoDep, outer)
    }
}
