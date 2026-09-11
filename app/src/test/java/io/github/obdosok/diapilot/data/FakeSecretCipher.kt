package io.github.obdosok.diapilot.data

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

/**
 * Stand-in for [KeystoreSecretCipher], which needs AndroidKeyStore and so cannot
 * run under Robolectric. Keeps the contract the store relies on: output is
 * `iv(12) + ciphertext + tag(16)`, the IV differs per call, and [decrypt] throws
 * on a wrong key, a wrong AAD or any modified byte. Not cryptography.
 */
class FakeSecretCipher(private var keyId: Int = 1) : SecretCipher {
    /** Makes the next operations behave as if the keystore were unavailable. */
    var unavailable = false
    var encryptCalls = 0
    var decryptCalls = 0
    private var counter = 0

    /** Simulates a keystore key that was deleted and later regenerated. */
    fun replaceKey() {
        keyId++
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        encryptCalls++
        if (unavailable) throw GeneralSecurityException("keystore unavailable")
        counter++
        val iv = ByteArray(IV) { (counter shr (8 * (it % 4))).toByte() }
        val body = xor(plain, iv)
        return iv + body + tag(iv, body, aad)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        decryptCalls++
        if (unavailable) throw GeneralSecurityException("keystore unavailable")
        if (blob.size < IV + TAG) throw GeneralSecurityException("too short")
        val iv = blob.copyOfRange(0, IV)
        val body = blob.copyOfRange(IV, blob.size - TAG)
        val tag = blob.copyOfRange(blob.size - TAG, blob.size)
        if (!MessageDigest.isEqual(tag, tag(iv, body, aad))) throw AEADBadTagException("tag mismatch")
        return xor(body, iv)
    }

    private fun xor(data: ByteArray, iv: ByteArray): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor iv[it % IV].toInt() xor (0x5A + keyId)).toByte() }

    private fun tag(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(byteArrayOf(keyId.toByte()))
        md.update(iv)
        md.update(aad)
        md.update(body)
        return md.digest().copyOf(TAG)
    }

    private companion object {
        const val IV = 12
        const val TAG = 16
    }
}
