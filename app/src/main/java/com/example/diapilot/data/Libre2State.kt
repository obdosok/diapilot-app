package com.example.diapilot.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted Libre 2 BLE streaming context — the DiaPilot analog of xDrip's
 * Libre2SensorData: patch identity, the advertised MAC (returned by the
 * NFC enable-streaming command), the connection counter (the crypto nonce —
 * one unlock buffer per connection) and the OOP2-precomputed unlock array.
 */
object Libre2State {
    private const val KEY = "libre2_ble_state"

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            com.example.diapilot.collect.TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE,
        )

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
        prefs(context).edit().putString(KEY, o.toString()).apply()
    }

    fun load(context: Context): State? = try {
        val raw = prefs(context).getString(KEY, null) ?: return null
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



    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()
}
