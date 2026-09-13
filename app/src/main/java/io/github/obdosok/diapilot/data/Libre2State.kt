package io.github.obdosok.diapilot.data

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted Libre 2 BLE streaming context — the DiaPilot analog of xDrip's
 * Libre2SensorData: patch identity, the advertised MAC (returned by the
 * NFC enable-streaming command), the connection counter (the crypto nonce —
 * one unlock buffer per connection) and the OOP2-precomputed unlock array.
 *
 * STORED THROUGH [SecretStore], NOT IN PLAIN PREFERENCES. The unlock array is
 * what lets a BLE client talk to this sensor, and the UID and patch info are
 * the sensor's identity; SECURITY.md says secrets stay in the keystore, and
 * these were the exception. The whole state is one blob under
 * [SecretStore.Secret.LIBRE2_BLE_STATE]: the fields are only meaningful
 * together, and one encrypted entry cannot be left half-migrated. The
 * plaintext entry an earlier build wrote is moved on the first read and
 * removed in the same edit — the store's ordinary migration.
 *
 * [save] cannot fail loudly without changing the callers, so when the
 * keystore is unavailable it logs the fact and stores nothing: a new secret
 * is never written in plaintext (the store's rule), and the cost is one NFC
 * re-scan after the next restart, not a credential on disk.
 */
object Libre2State {
    private const val TAG = "Libre2State"

    data class State(
        val uid: ByteArray,
        val patchInfo: ByteArray,
        val serial: String,
        val mac: String,
        val deviceName: String,
        val connectionIndex: Int,
        val unlockArray: List<ByteArray>,  // index i ↔ connectionIndex startIndex+i
        val unlockStartIndex: Int,
    )

    private fun secrets(context: Context) = Secrets.store(context)

    fun save(context: Context, s: State) {
        val o = JSONObject()
            .put("uid", Base64.encodeToString(s.uid, Base64.NO_WRAP))
            .put("patchInfo", Base64.encodeToString(s.patchInfo, Base64.NO_WRAP))
            .put("serial", s.serial)
            .put("mac", s.mac)
            .put("deviceName", s.deviceName)
            .put("connectionIndex", s.connectionIndex)
            .put("unlockStartIndex", s.unlockStartIndex)
            .put(
                "unlockArray",
                JSONArray().apply {
                    s.unlockArray.forEach { put(Base64.encodeToString(it, Base64.NO_WRAP)) }
                },
            )
        if (!secrets(context).set(SecretStore.Secret.LIBRE2_BLE_STATE, o.toString())) {
            Log.w(TAG, "streaming state not saved: secure storage unavailable")
        }
    }

    fun load(context: Context): State? = try {
        val raw = secrets(context).get(SecretStore.Secret.LIBRE2_BLE_STATE) ?: return null
        val o = JSONObject(raw)
        val arr = o.getJSONArray("unlockArray")
        State(
            uid = Base64.decode(o.getString("uid"), Base64.NO_WRAP),
            patchInfo = Base64.decode(o.getString("patchInfo"), Base64.NO_WRAP),
            serial = o.getString("serial"),
            mac = o.getString("mac"),
            deviceName = o.optString("deviceName", "ABBOTT"),
            connectionIndex = o.getInt("connectionIndex"),
            unlockStartIndex = o.optInt("unlockStartIndex", 1),
            unlockArray = (0 until arr.length()).map {
                Base64.decode(arr.getString(it), Base64.NO_WRAP)
            },
        )
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        secrets(context).set(SecretStore.Secret.LIBRE2_BLE_STATE, null)
    }
}
