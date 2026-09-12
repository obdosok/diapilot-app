package io.github.obdosok.diapilot.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.github.obdosok.diapilot.collect.TreatmentsPollWorker
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for short secrets.
 *
 * [encrypt] returns `iv + ciphertext` (the GCM tag is part of the ciphertext);
 * [decrypt] takes the same layout back and THROWS on any failure — a missing
 * key, a wrong key, a tampered or truncated blob. [aad] is authenticated but not
 * stored: it binds a blob to the name it was saved under, so a value copied
 * into another secret's slot fails to decrypt instead of being read as that
 * other secret.
 *
 * An interface because AndroidKeyStore does not exist under Robolectric: the
 * store's logic is tested against a fake, the real cipher runs on devices only.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key generated inside AndroidKeyStore.
 *
 * The key is non-exportable: its material never enters this process, so a copy
 * of the preferences file (a backup, another device, a rooted dump of the app
 * directory) cannot be decrypted without this phone's keystore.
 *
 * Deliberately NO user-authentication or unlocked-device requirement: the
 * collector service pushes to the companion server while the phone is locked,
 * and a key usable only after unlock would silently stop that.
 *
 * `androidx.security:security-crypto` (EncryptedSharedPreferences) would do the
 * same job but is deprecated; this is the few lines of it that are needed.
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The keystore generates the IV itself (randomized encryption is
        // required for GCM keys); supplying one would be rejected.
        cipher.init(Cipher.ENCRYPT_MODE, keyOrCreate())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("blob too short")
        val key = existingKey() ?: throw GeneralSecurityException("no keystore key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey
    }

    /** Serialized so two first-time callers cannot each generate a key and
     *  leave one of them with a blob the surviving key cannot open. */
    private fun keyOrCreate(): SecretKey = synchronized(LOCK) {
        existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "diapilot_secret_store_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val LOCK = Any()
    }
}

/**
 * The app's two credentials, encrypted at rest in the ordinary preferences file.
 *
 * Stored form: `base64(iv + ciphertext)` under [Secret.prefKey]. The plaintext
 * names the app used before ([Secret.legacyPrefKey]) are migrated on the first
 * read: the value is encrypted, written under the new name and the plaintext
 * entry removed IN THE SAME EDIT, so there is no moment on disk with neither.
 *
 * Failure policy — the secret is a convenience the user can type again, the app
 * must keep running:
 * - a blob that cannot be decrypted (keystore key lost, data restored onto
 *   another device, corruption) reads as ABSENT; the blob is left in place and
 *   overwritten when the user enters the secret again;
 * - if encryption is unavailable during migration, the plaintext value is still
 *   returned and left where it was, and the migration is retried in the next
 *   process — the state before this class existed, never worse;
 * - if encryption is unavailable when saving, [set] returns false and stores
 *   nothing: a new secret is never written in plaintext.
 * Nothing here throws, and no log line carries a value — only the secret's name
 * and the exception class.
 *
 * Decrypted values are cached for the life of the process: several screens ask
 * for the API key during composition, and each keystore operation is a binder
 * call.
 */
class SecretStore(internal val prefs: SharedPreferences, private val cipher: SecretCipher) {

    enum class Secret(val prefKey: String, val legacyPrefKey: String) {
        ANTHROPIC_API_KEY("anthropic_api_key_enc", "anthropic_api_key"),
        COMPANION_TOKEN("companion_token_enc", "companion_token"),

        /** The loopback server's per-installation token ([WatchApiToken]). Born
         *  encrypted: the legacy name never existed on disk and is declared
         *  only because every secret is looked up under both names. */
        WATCH_API_TOKEN("watch_api_token_enc", "watch_api_token"),

        /** Optional `api-secret` for xDrip's local web service, entered by the
         *  user if their xDrip has one configured. */
        XDRIP_API_SECRET("xdrip_api_secret_enc", "xdrip_api_secret"),
    }

    private class Cached(val value: String?)

    private val cache = ConcurrentHashMap<Secret, Cached>()

    fun get(secret: Secret): String? {
        cache[secret]?.let { return it.value }
        synchronized(this) {
            cache[secret]?.let { return it.value }
            val value = load(secret)
            cache[secret] = Cached(value)
            return value
        }
    }

    /** Saves [value] (trimmed; blank or null clears the secret). False when it
     *  could not be encrypted — the previous value is then kept. */
    fun set(secret: Secret, value: String?): Boolean = synchronized(this) {
        val normalized = normalize(value)
        if (normalized == null) {
            prefs.edit().remove(secret.prefKey).remove(secret.legacyPrefKey).apply()
            cache[secret] = Cached(null)
            return true
        }
        val blob = encryptOrNull(secret, normalized)
        if (blob == null) {
            cache.remove(secret)
            return false
        }
        prefs.edit().putString(secret.prefKey, blob).remove(secret.legacyPrefKey).apply()
        cache[secret] = Cached(normalized)
        return true
    }

    private fun load(secret: Secret): String? {
        val stored = prefs.getString(secret.prefKey, null)
        if (stored != null) {
            val plain = decryptOrNull(secret, stored)
            if (plain != null) {
                if (prefs.contains(secret.legacyPrefKey)) {
                    prefs.edit().remove(secret.legacyPrefKey).apply()
                }
                return plain
            }
        }
        val legacy = normalize(prefs.getString(secret.legacyPrefKey, null))
        if (legacy == null) {
            if (prefs.contains(secret.legacyPrefKey)) {
                prefs.edit().remove(secret.legacyPrefKey).apply()
            }
            return null
        }
        val blob = encryptOrNull(secret, legacy)
        if (blob != null) {
            prefs.edit().putString(secret.prefKey, blob).remove(secret.legacyPrefKey).apply()
        }
        return legacy
    }

    private fun encryptOrNull(secret: Secret, value: String): String? = try {
        Base64.getEncoder().encodeToString(
            cipher.encrypt(value.toByteArray(Charsets.UTF_8), aad(secret)),
        )
    } catch (e: Exception) {
        Log.w(TAG, "${secret.name}: encryption unavailable (${e.javaClass.simpleName})")
        null
    }

    private fun decryptOrNull(secret: Secret, stored: String): String? = try {
        val blob = Base64.getDecoder().decode(stored)
        normalize(String(cipher.decrypt(blob, aad(secret)), Charsets.UTF_8))
    } catch (e: Exception) {
        Log.w(TAG, "${secret.name}: stored value unreadable (${e.javaClass.simpleName}), treated as absent")
        null
    }

    private fun aad(secret: Secret): ByteArray = secret.prefKey.toByteArray(Charsets.UTF_8)

    private fun normalize(value: String?): String? = value?.trim()?.ifEmpty { null }

    private companion object {
        const val TAG = "SecretStore"
    }
}

/** The process-wide [SecretStore] over the app's main preferences file. */
object Secrets {
    @Volatile private var current: SecretStore? = null

    /** Swapped by tests: AndroidKeyStore is unavailable under Robolectric. */
    @Volatile internal var cipherFactory: () -> SecretCipher = { KeystoreSecretCipher() }

    fun store(context: Context): SecretStore {
        val prefs = context.getSharedPreferences(TreatmentsPollWorker.PREFS, Context.MODE_PRIVATE)
        current?.let { if (it.prefs === prefs) return it }
        return synchronized(this) {
            current?.takeIf { it.prefs === prefs }
                ?: SecretStore(prefs, cipherFactory()).also { current = it }
        }
    }

    /** Drops the cached store (and its decrypted values); tests only. */
    internal fun reset() {
        current = null
    }
}
