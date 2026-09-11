package io.github.obdosok.diapilot.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import io.github.obdosok.diapilot.data.SecretStore.Secret
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.Base64

/**
 * [SecretStore] against [FakeSecretCipher]: the storage format, the one-time
 * migration from the plaintext preferences, and the rule that an unreadable
 * secret reads as absent instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)

    private val apiKey = "sk-ant-test-0123456789abcdef"
    private val token = "companion-test-token-42"

    private lateinit var cipher: FakeSecretCipher
    private val originalFactory = Secrets.cipherFactory

    @Before fun setUp() {
        cipher = FakeSecretCipher()
        Secrets.cipherFactory = { cipher }
        Secrets.reset()
        ShadowLog.clear()
    }

    @After fun tearDown() {
        Secrets.cipherFactory = originalFactory
        Secrets.reset()
    }

    private fun newStore() = SecretStore(prefs, cipher)

    @Test fun `an absent secret reads as null without touching the cipher`() {
        val store = newStore()
        assertNull(store.get(Secret.ANTHROPIC_API_KEY))
        assertNull(store.get(Secret.COMPANION_TOKEN))
        assertEquals(0, cipher.decryptCalls)
        assertEquals(0, cipher.encryptCalls)
    }

    @Test fun `a saved secret round-trips and is stored as base64 of iv plus ciphertext`() {
        assertTrue(newStore().set(Secret.ANTHROPIC_API_KEY, "  $apiKey \n"))

        val stored = prefs.getString(Secret.ANTHROPIC_API_KEY.prefKey, null)
        assertNotNull(stored)
        assertFalse(stored!!.contains(apiKey))
        val blob = Base64.getDecoder().decode(stored)
        assertTrue(blob.size > 12 + apiKey.length)
        assertFalse(prefs.contains(Secret.ANTHROPIC_API_KEY.legacyPrefKey))
        assertFalse(prefs.all.values.any { it.toString().contains(apiKey) })

        // A fresh store has no cache: this is the decryption path.
        val fresh = newStore()
        assertEquals(apiKey, fresh.get(Secret.ANTHROPIC_API_KEY))
        assertEquals(1, cipher.decryptCalls)
    }

    @Test fun `the same value encrypts differently each time`() {
        val store = newStore()
        store.set(Secret.COMPANION_TOKEN, token)
        val first = prefs.getString(Secret.COMPANION_TOKEN.prefKey, null)
        store.set(Secret.COMPANION_TOKEN, token)
        val second = prefs.getString(Secret.COMPANION_TOKEN.prefKey, null)
        assertNotNull(first)
        assertFalse(first == second)
        assertEquals(token, newStore().get(Secret.COMPANION_TOKEN))
    }

    @Test fun `the two secrets are independent`() {
        val store = newStore()
        store.set(Secret.ANTHROPIC_API_KEY, apiKey)
        store.set(Secret.COMPANION_TOKEN, token)
        store.set(Secret.COMPANION_TOKEN, null)

        val fresh = newStore()
        assertEquals(apiKey, fresh.get(Secret.ANTHROPIC_API_KEY))
        assertNull(fresh.get(Secret.COMPANION_TOKEN))
    }

    @Test fun `blank or null clears both the encrypted and the legacy entry`() {
        prefs.edit().putString(Secret.COMPANION_TOKEN.legacyPrefKey, token).commit()
        val store = newStore()
        store.set(Secret.COMPANION_TOKEN, token)
        assertTrue(store.set(Secret.COMPANION_TOKEN, "   "))

        assertNull(store.get(Secret.COMPANION_TOKEN))
        assertFalse(prefs.contains(Secret.COMPANION_TOKEN.prefKey))
        assertFalse(prefs.contains(Secret.COMPANION_TOKEN.legacyPrefKey))
        assertNull(newStore().get(Secret.COMPANION_TOKEN))
    }

    @Test fun `a plaintext value from an older version is migrated on first read`() {
        prefs.edit()
            .putString("anthropic_api_key", apiKey)
            .putString("companion_token", token)
            .commit()

        val store = newStore()
        assertEquals(apiKey, store.get(Secret.ANTHROPIC_API_KEY))
        assertEquals(token, store.get(Secret.COMPANION_TOKEN))

        assertFalse(prefs.contains("anthropic_api_key"))
        assertFalse(prefs.contains("companion_token"))
        assertNotNull(prefs.getString(Secret.ANTHROPIC_API_KEY.prefKey, null))
        assertNotNull(prefs.getString(Secret.COMPANION_TOKEN.prefKey, null))
        assertFalse(prefs.all.values.any { it.toString().contains(apiKey) || it.toString().contains(token) })

        val fresh = newStore()
        assertEquals(apiKey, fresh.get(Secret.ANTHROPIC_API_KEY))
        assertEquals(token, fresh.get(Secret.COMPANION_TOKEN))
    }

    @Test fun `a blank legacy value migrates to absent and is removed`() {
        prefs.edit().putString("anthropic_api_key", "  ").commit()
        assertNull(newStore().get(Secret.ANTHROPIC_API_KEY))
        assertFalse(prefs.contains("anthropic_api_key"))
        assertFalse(prefs.contains(Secret.ANTHROPIC_API_KEY.prefKey))
    }

    @Test fun `without encryption the migration keeps the plaintext and retries later`() {
        prefs.edit().putString("companion_token", token).commit()
        cipher.unavailable = true

        assertEquals(token, newStore().get(Secret.COMPANION_TOKEN))
        assertEquals(token, prefs.getString("companion_token", null))
        assertFalse(prefs.contains(Secret.COMPANION_TOKEN.prefKey))

        cipher.unavailable = false
        assertEquals(token, newStore().get(Secret.COMPANION_TOKEN))
        assertFalse(prefs.contains("companion_token"))
        assertNotNull(prefs.getString(Secret.COMPANION_TOKEN.prefKey, null))
    }

    @Test fun `without encryption a new secret is refused, never stored in plaintext`() {
        val store = newStore()
        store.set(Secret.ANTHROPIC_API_KEY, apiKey)
        cipher.unavailable = true

        assertFalse(store.set(Secret.ANTHROPIC_API_KEY, "sk-ant-other"))
        assertFalse(prefs.all.values.any { it.toString().contains("sk-ant-other") })

        cipher.unavailable = false
        assertEquals(apiKey, store.get(Secret.ANTHROPIC_API_KEY))
    }

    @Test fun `a corrupted value reads as absent and can be entered again`() {
        prefs.edit().putString(Secret.ANTHROPIC_API_KEY.prefKey, "%%% not base64 %%%").commit()
        assertNull(newStore().get(Secret.ANTHROPIC_API_KEY))

        val good = run {
            newStore().set(Secret.COMPANION_TOKEN, token)
            prefs.getString(Secret.COMPANION_TOKEN.prefKey, null)!!
        }
        val tampered = Base64.getDecoder().decode(good).also { it[14] = (it[14].toInt() xor 1).toByte() }
        prefs.edit().putString(Secret.COMPANION_TOKEN.prefKey, Base64.getEncoder().encodeToString(tampered)).commit()
        assertNull(newStore().get(Secret.COMPANION_TOKEN))

        prefs.edit().putString(Secret.COMPANION_TOKEN.prefKey, Base64.getEncoder().encodeToString(ByteArray(3))).commit()
        assertNull(newStore().get(Secret.COMPANION_TOKEN))

        val store = newStore()
        assertTrue(store.set(Secret.ANTHROPIC_API_KEY, apiKey))
        assertEquals(apiKey, newStore().get(Secret.ANTHROPIC_API_KEY))
    }

    @Test fun `a lost keystore key reads as absent and re-entry works`() {
        newStore().set(Secret.ANTHROPIC_API_KEY, apiKey)
        cipher.replaceKey()

        val store = newStore()
        assertNull(store.get(Secret.ANTHROPIC_API_KEY))
        // The unreadable blob stays until it is overwritten.
        assertNotNull(prefs.getString(Secret.ANTHROPIC_API_KEY.prefKey, null))

        assertTrue(store.set(Secret.ANTHROPIC_API_KEY, apiKey))
        assertEquals(apiKey, newStore().get(Secret.ANTHROPIC_API_KEY))
    }

    @Test fun `an unreadable value with a leftover plaintext entry falls back to migrating it`() {
        newStore().set(Secret.COMPANION_TOKEN, "old")
        cipher.replaceKey()
        prefs.edit().putString("companion_token", token).commit()

        assertEquals(token, newStore().get(Secret.COMPANION_TOKEN))
        assertFalse(prefs.contains("companion_token"))
        assertEquals(token, newStore().get(Secret.COMPANION_TOKEN))
    }

    @Test fun `a keystore failure on read reads as absent`() {
        newStore().set(Secret.COMPANION_TOKEN, token)
        cipher.unavailable = true
        assertNull(newStore().get(Secret.COMPANION_TOKEN))
    }

    @Test fun `a value moved into another secret's slot does not decrypt`() {
        newStore().set(Secret.ANTHROPIC_API_KEY, apiKey)
        val blob = prefs.getString(Secret.ANTHROPIC_API_KEY.prefKey, null)
        prefs.edit().putString(Secret.COMPANION_TOKEN.prefKey, blob).commit()
        assertNull(newStore().get(Secret.COMPANION_TOKEN))
    }

    @Test fun `repeated reads are served from memory`() {
        newStore().set(Secret.ANTHROPIC_API_KEY, apiKey)
        val store = newStore()
        repeat(5) { assertEquals(apiKey, store.get(Secret.ANTHROPIC_API_KEY)) }
        assertEquals(1, cipher.decryptCalls)
    }

    @Test fun `no log line carries a secret, whatever fails`() {
        prefs.edit().putString("anthropic_api_key", apiKey).commit()
        cipher.unavailable = true
        newStore().get(Secret.ANTHROPIC_API_KEY)
        newStore().set(Secret.COMPANION_TOKEN, token)
        cipher.unavailable = false
        newStore().get(Secret.ANTHROPIC_API_KEY)
        newStore().set(Secret.COMPANION_TOKEN, token)
        cipher.replaceKey()
        newStore().get(Secret.ANTHROPIC_API_KEY)
        newStore().get(Secret.COMPANION_TOKEN)

        val logs = ShadowLog.getLogs()
        assertTrue(logs.isNotEmpty())
        logs.forEach { item ->
            val text = "${item.msg} ${item.throwable?.message}"
            assertFalse(text, text.contains(apiKey) || text.contains(token))
        }
    }

    @Test fun `the app accessors go through the encrypted store and migrate`() {
        prefs.edit().putString("anthropic_api_key", apiKey).commit()

        assertEquals(apiKey, AskClaude.apiKey(context))
        assertFalse(prefs.contains("anthropic_api_key"))

        assertTrue(Settings.setCompanionToken(context, " $token "))
        assertEquals(token, Settings.companionToken(context))
        assertFalse(prefs.contains("companion_token"))

        Secrets.reset()
        assertEquals(apiKey, AskClaude.apiKey(context))
        assertEquals(token, Settings.companionToken(context))
        assertFalse(prefs.all.values.any { it.toString().contains(apiKey) || it.toString().contains(token) })

        assertTrue(AskClaude.saveApiKey(context, ""))
        assertNull(AskClaude.apiKey(context))
    }
}
