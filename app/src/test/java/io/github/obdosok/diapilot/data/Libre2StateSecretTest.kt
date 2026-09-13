package io.github.obdosok.diapilot.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Libre2State] keeps the sensor's streaming credentials in the secret store:
 * the plaintext entry an earlier build wrote is moved on the first read and
 * deleted, a saved state comes back intact, and nothing on disk carries the
 * unlock array or the UID in the clear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Libre2StateSecretTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
    private val originalFactory = Secrets.cipherFactory
    private lateinit var cipher: FakeSecretCipher

    private val legacyKey = "libre2_ble_state"
    private val encKey = SecretStore.Secret.LIBRE2_BLE_STATE.prefKey

    private val uid = byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
    private val patchInfo = byteArrayOf(0x9D.toByte(), 0x08, 0x30, 0x01, 0x00, 0x00)
    private val unlock = listOf(ByteArray(8) { it.toByte() }, ByteArray(8) { (it + 8).toByte() })

    @Before fun setUp() {
        cipher = FakeSecretCipher()
        Secrets.cipherFactory = { cipher }
        Secrets.reset()
        prefs.edit().clear().apply()
    }

    @After fun tearDown() {
        prefs.edit().clear().apply()
        Secrets.cipherFactory = originalFactory
        Secrets.reset()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** The JSON the previous build wrote straight into the preferences. */
    private fun legacyJson(): String = JSONObject()
        .put("uid", b64(uid)).put("patchInfo", b64(patchInfo))
        .put("serial", "0M0001ABCDE").put("mac", "AA:BB:CC:DD:EE:FF").put("deviceName", "ABBOTT")
        .put("connectionIndex", 3).put("unlockStartIndex", 1)
        .put("unlockArray", JSONArray().apply { unlock.forEach { put(b64(it)) } })
        .toString()

    private fun nothingPlainOnDisk() {
        assertFalse("plaintext entry must be gone", prefs.contains(legacyKey))
        val onDisk = prefs.all.values.joinToString()
        assertFalse("unlock array in the clear", onDisk.contains(b64(unlock[0])))
        assertFalse("sensor UID in the clear", onDisk.contains(b64(uid)))
        assertFalse("serial in the clear", onDisk.contains("0M0001ABCDE"))
    }

    @Test fun `a state saved by an earlier build is read once from plaintext, then moved into the secret store`() {
        prefs.edit().putString(legacyKey, legacyJson()).apply()

        val state = Libre2State.load(context)
        assertNotNull(state)
        assertArrayEquals(uid, state!!.uid)
        assertArrayEquals(patchInfo, state.patchInfo)
        assertEquals("0M0001ABCDE", state.serial)
        assertEquals("AA:BB:CC:DD:EE:FF", state.mac)
        assertEquals(3, state.connectionIndex)
        assertEquals(2, state.unlockArray.size)
        assertArrayEquals(unlock[1], state.unlockArray[1])

        assertTrue(prefs.contains(encKey))
        nothingPlainOnDisk()

        // A fresh process reads the encrypted entry, not a cache.
        Secrets.reset()
        assertEquals("0M0001ABCDE", Libre2State.load(context)?.serial)
        assertTrue(cipher.decryptCalls >= 1)
    }

    @Test fun `a saved state round-trips and never touches the plaintext key`() {
        Libre2State.save(
            context,
            Libre2State.State(uid, patchInfo, "0M0001ABCDE", "AA:BB:CC:DD:EE:FF", "ABBOTT", 7, unlock, 5),
        )
        assertTrue(prefs.contains(encKey))
        nothingPlainOnDisk()

        Secrets.reset()
        val back = Libre2State.load(context)!!
        assertEquals(7, back.connectionIndex)
        assertEquals(5, back.unlockStartIndex)
        assertArrayEquals(unlock[0], back.unlockArray[0])
    }

    @Test fun `clear removes the state and load then answers null`() {
        Libre2State.save(
            context,
            Libre2State.State(uid, patchInfo, "s", "m", "ABBOTT", 1, unlock, 1),
        )
        Libre2State.clear(context)
        assertNull(Libre2State.load(context))
        assertFalse(prefs.contains(encKey))
        assertFalse(prefs.contains(legacyKey))
    }

    @Test fun `with the keystore unavailable a new state is not written in plaintext`() {
        cipher.unavailable = true
        Libre2State.save(
            context,
            Libre2State.State(uid, patchInfo, "s", "m", "ABBOTT", 1, unlock, 1),
        )
        assertFalse(prefs.contains(encKey))
        assertFalse(prefs.contains(legacyKey))
        assertNull(Libre2State.load(context))
    }

    @Test fun `with the keystore unavailable the legacy state is still readable and left for the next attempt`() {
        prefs.edit().putString(legacyKey, legacyJson()).apply()
        cipher.unavailable = true
        assertEquals("0M0001ABCDE", Libre2State.load(context)?.serial)
        assertTrue("migration retried later, never worse than before", prefs.contains(legacyKey))
        assertFalse(prefs.contains(encKey))
    }
}
