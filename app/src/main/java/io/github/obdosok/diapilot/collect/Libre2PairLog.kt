package io.github.obdosok.diapilot.collect

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.Libre2State
import io.github.obdosok.diapilot.i18n.localized
import org.json.JSONObject
import java.io.File

/**
 * Stage-4 groundwork: known-answer pairs for the future own-crypto port.
 *
 * While OOP2 is still the decryptor, every encrypted packet we send and
 * every decrypted buffer that comes back is appended here, joined by the
 * capture timestamp. When the Kotlin port of the (openly published) Libre 2
 * cipher lands, these pairs validate it OFFLINE, bit-exact, before it is
 * trusted with a single live reading — zero risk to the running pipeline.
 *
 * JSONL, capped: a week of minutes is more test vectors than any port needs.
 */
object Libre2PairLog {
    private const val TAG = "Libre2Pairs"
    private const val FILE = "libre2_pairs.jsonl"
    private const val MAX_BYTES = 2L * 1024 * 1024

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    private fun append(context: Context, line: JSONObject) {
        try {
            val f = file(context)
            if (f.length() > MAX_BYTES) return  // enough vectors collected
            f.appendText(line.toString() + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "append failed: ${e.message}")
        }
    }

    fun logEncrypted(context: Context, tsMs: Long, packet: ByteArray) {
        val state = Libre2State.load(context)
        append(
            context,
            JSONObject()
                .put("ts", tsMs)
                .put("enc", Base64.encodeToString(packet, Base64.NO_WRAP))
                .put("uid", state?.uid?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .put("patchInfo", state?.patchInfo?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .put("connIndex", state?.connectionIndex ?: -1),
        )
    }

    fun logDecoded(context: Context, tsMs: Long, decodedB64: String) {
        append(context, JSONObject().put("ts", tsMs).put("dec", decodedB64))
    }

    fun stats(context: Context): String {
        val f = file(context)
        val text = context.localized()
        return if (f.exists()) {
            text.getString(R.string.libre2_pair_log_stats_kb, f.length() / 1024.0)
        } else {
            text.getString(R.string.libre2_pair_log_stats_empty)
        }
    }
}
