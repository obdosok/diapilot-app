package io.github.obdosok.diapilot.data

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.util.Base64

/**
 * The loopback server's per-installation token.
 *
 * Generated once, on the first request that needs it (or the first time the
 * Settings screen shows it), and kept in [SecretStore] — encrypted at rest like
 * the other two credentials, and therefore not readable from a copy of the
 * preferences file.
 *
 * Per INSTALLATION rather than per user or per client: it exists to tell "the
 * app the user configured" apart from "any other app on this phone", and on
 * Android the listener of a loopback socket learns nothing else about who
 * connected. Reinstalling the app makes a new one, which is the correct
 * outcome — the old clients were configured against a database that is gone.
 *
 * 160 bits from [SecureRandom], base64url without padding: enough that guessing
 * is not a strategy, and short enough to be copied into a client's settings by
 * hand.
 *
 * When the token cannot be stored ([SecretStore.set] returns false because
 * encryption is unavailable) this returns null and the guarded endpoints answer
 * 401. Closed, never open by default.
 */
object WatchApiToken {

    private const val TAG = "WatchApiToken"
    private const val BYTES = 20

    private val lock = Any()

    /** The stored token, or null when none has been generated yet. */
    fun current(context: Context): String? =
        Secrets.store(context).get(SecretStore.Secret.WATCH_API_TOKEN)

    /**
     * The stored token, generating one on the first call. Serialized: two
     * concurrent requests must not each mint a token and leave one of them
     * comparing against the other's.
     */
    fun getOrCreate(context: Context): String? = synchronized(lock) {
        current(context)?.let { return it }
        val fresh = generate()
        if (!Secrets.store(context).set(SecretStore.Secret.WATCH_API_TOKEN, fresh)) {
            // Never the value, not even at debug level.
            Log.w(TAG, "token could not be stored; the guarded endpoints stay closed")
            return null
        }
        return fresh
    }

    private fun generate(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(BYTES).also { SecureRandom().nextBytes(it) })

    /**
     * What the Settings screen shows: the first and last four characters, the
     * middle replaced by a fixed-length ellipsis. Enough for the user to tell
     * whether a client is configured against this token, and not enough to
     * reconstruct it from a screenshot or a shoulder.
     */
    fun masked(token: String?): String {
        if (token.isNullOrEmpty()) return ""
        if (token.length <= 10) return "•".repeat(token.length)
        return token.take(4) + "•".repeat(8) + token.takeLast(4)
    }
}
