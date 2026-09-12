package io.github.obdosok.diapilot.nfc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import io.github.obdosok.diapilot.diag.DiagLog
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FRAM decryption via the OOP2 companion app's broadcast API — the same
 * contract xDrip uses (LIBRE_DATA request → OOP2_DECODE_FARM_RESULT reply,
 * pid echo in ROW_ID). We already depend on OOP2 for the per-minute BLE
 * stream; this reuses it for NFC reads. Constants from xDrip Intents.java —
 * ported from xDrip+ (https://github.com/NightscoutFoundation/xDrip), GPL-3.0.
 */
object LibreOop2Bridge {

    private const val TAG = "LibreOop2"
    private const val OOP2_PACKAGE = "com.hg4.oopalgorithm.oopalgorithm2"

    private const val ACTION_LIBRE_DATA = "com.eveningoutpost.dexdrip.LIBRE_DATA"
    private const val ACTION_FARM_RESULT = "com.eveningoutpost.dexdrip.OOP2_DECODE_FARM_RESULT"
    private const val EX_DATA = "com.eveningoutpost.dexdrip.Extras.DATA_BUFFER"
    private const val EX_UID = "com.eveningoutpost.dexdrip.Extras.LIBRE_PATCH_UID_BUFFER"
    private const val EX_INFO = "com.eveningoutpost.dexdrip.Extras.LIBRE_PATCH_INFO_BUFFER"
    private const val EX_TS = "com.eveningoutpost.dexdrip.Extras.TIMESTAMP"
    private const val EX_SN = "com.eveningoutpost.dexdrip.Extras.LIBRE_SN"
    private const val EX_PID = "com.eveningoutpost.dexdrip.Extras.LIBRE_RAW_ID"
    private const val EX_TAG_ID = "TagId"

    data class Decoded(
        val fram: ByteArray,        // decrypted 344 bytes
        val captureMs: Long,
        val trendBg: IntArray?,     // OOP-algorithm glucose, mg/dl (OOP2 scale)
        val historyBg: IntArray?,
    )

    data class UnlockData(
        val nfcUnlock: ByteArray,       // append to {0x02,0xA1,mfr} over NFC
        val btUnlock: ByteArray,        // write to f001 after connecting
        val unlockArray: List<ByteArray>,  // precomputed per-connectionIndex
        val deviceName: String,
        val connectionIndex: Int,
    )

    private const val ACTION_BT_ENABLE = "com.eveningoutpost.dexdrip.BLUETOOTH_ENABLE"
    private const val ACTION_BT_ENABLE_RESULT =
        "com.eveningoutpost.dexdrip.OOP2_BLUETOOTH_ENABLE_RESULT"

    /**
     * Ask OOP2 for the streaming credentials: the NFC enable payload and the
     * per-connection BLE login buffers (an array of [count], so reconnects
     * don't need OOP2 again). Blocking; null on timeout.
     */
    fun enableStreaming(
        context: Context,
        uid: ByteArray,
        patchInfo: ByteArray,
        connectionIndex: Int,
        count: Int = 2000,
        timeoutS: Long = 12,
    ): UnlockData? {
        val latch = CountDownLatch(1)
        var result: UnlockData? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    val json = intent.extras?.getString("json") ?: return
                    val o = JSONObject(json)
                    if (o.optInt("ROW_ID", -1) != android.os.Process.myPid()) return
                    val arr = o.optJSONArray("BtUnlockBufferArray")
                    result = UnlockData(
                        nfcUnlock = Base64.decode(o.getString("NfcUnlockBuffer"), Base64.NO_WRAP),
                        btUnlock = Base64.decode(o.getString("BtUnlockBuffer"), Base64.NO_WRAP),
                        unlockArray = arr?.let { a ->
                            (0 until a.length()).map {
                                Base64.decode(a.getString(it), Base64.NO_WRAP)
                            }
                        } ?: emptyList(),
                        deviceName = o.optString("DeviceName", "ABBOTT"),
                        connectionIndex = o.optInt("ConnectionIndex", connectionIndex),
                    )
                    latch.countDown()
                } catch (e: Exception) {
                    DiagLog.w(TAG, "bad ENABLE result: ${e.message}")
                }
            }
        }
        val filter = IntentFilter(ACTION_BT_ENABLE_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        try {
            val intent = Intent(ACTION_BT_ENABLE).apply {
                putExtra(EX_UID, uid)
                putExtra(EX_INFO, patchInfo)
                putExtra("EnableTime", 42)
                putExtra("ConnectionIndex", connectionIndex)
                putExtra("BtUnlockBufferCount", count)
                putExtra(EX_PID, android.os.Process.myPid())
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage(OOP2_PACKAGE)
            }
            context.sendBroadcast(intent)
            latch.await(timeoutS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            DiagLog.w(TAG, "enableStreaming roundtrip failed: ${e.message}")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return result
    }

    /**
     * Send the encrypted FRAM to OOP2 and wait for the decode broadcast.
     * Blocking (call off the main thread); null on timeout.
     */
    fun decode(
        context: Context,
        scan: LibreNfcScanner.RawScan,
        serial: String,
        captureMs: Long,
        timeoutS: Long = 12,
    ): Decoded? {
        val latch = CountDownLatch(1)
        var result: Decoded? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    val json = intent.extras?.getString("json") ?: return
                    val o = JSONObject(json)
                    if (o.optInt("ROW_ID", -1) != android.os.Process.myPid()) return
                    val fram = Base64.decode(o.getString("DecodedBuffer"), Base64.NO_WRAP)
                    fun ints(key: String): IntArray? = o.optJSONArray(key)?.let { arr ->
                        IntArray(arr.length()) { arr.getInt(it) }
                    }
                    result = Decoded(
                        fram = fram,
                        captureMs = o.optLong(EX_TS, captureMs),
                        trendBg = ints("TrendBg"),
                        historyBg = ints("HistoricBg"),
                    )
                    latch.countDown()
                } catch (e: Exception) {
                    DiagLog.w(TAG, "bad FARM result: ${e.message}")
                }
            }
        }
        val filter = IntentFilter(ACTION_FARM_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        try {
            val intent = Intent(ACTION_LIBRE_DATA).apply {
                putExtra(EX_DATA, scan.fram)
                putExtra(EX_TS, captureMs)
                putExtra(EX_SN, serial)
                putExtra(EX_TAG_ID, serial)
                putExtra(EX_PID, android.os.Process.myPid())
                putExtra(EX_UID, scan.uid)
                putExtra(EX_INFO, scan.patchInfo)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage(OOP2_PACKAGE)
            }
            context.sendBroadcast(intent)
            latch.await(timeoutS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            DiagLog.w(TAG, "decode roundtrip failed: ${e.message}")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return result
    }
}
