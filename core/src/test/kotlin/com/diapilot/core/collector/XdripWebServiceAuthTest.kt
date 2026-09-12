package com.diapilot.core.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `api-secret` header for xDrip's local web service.
 *
 * The format is xDrip's, not ours: SHA-1 of the secret in hex (what its
 * `Hashing.sha1().hashBytes(secret)` compares against), so the vectors below
 * are the published SHA-1 test vectors rather than something this code
 * produced.
 */
class XdripWebServiceAuthTest {

    @Test
    fun `the header is the hex sha1 of the secret`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", xdripApiSecretHeader("abc"))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            xdripApiSecretHeader("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        // 40 lower-case hex characters, whatever the secret.
        val h = xdripApiSecretHeader("a longer pass phrase with spaces")!!
        assertEquals(40, h.length)
        assertEquals(h, h.lowercase())
        // Never the plaintext.
        assertEquals(null, h.takeIf { it.contains("phrase") })
    }

    @Test
    fun `no secret means no header`() {
        assertNull(xdripApiSecretHeader(null))
        assertNull(xdripApiSecretHeader(""))
        assertNull(xdripApiSecretHeader("   "))
        assertNull(xdripApiSecretHeader("\n\t"))
    }

    @Test
    fun `surrounding whitespace does not change the hash`() {
        // A secret pasted with a trailing newline must hash as the secret.
        assertEquals(xdripApiSecretHeader("abc"), xdripApiSecretHeader("  abc\n"))
    }

    @Test
    fun `the header name is the one xDrip reads`() {
        assertEquals("api-secret", XDRIP_API_SECRET_HEADER)
    }
}
