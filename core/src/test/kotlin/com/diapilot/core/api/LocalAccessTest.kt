package com.diapilot.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of the loopback socket: which requests reach a handler at
 * all.
 *
 * The frozen watch feed must keep passing without a method check or a token —
 * a Zepp OS face reads it and cannot be redeployed — while the endpoint that
 * writes a dose and the one that publishes the whole history must not.
 */
class LocalAccessTest {

    private val token = "s3cr3t-token"

    @Test
    fun `the watch feed passes untouched`() {
        listOf("GET", "POST", "HEAD").forEach { m ->
            assertEquals(m, LocalAccess.OK, localAccess(LocalRoute.INFO_JSON, m, null, token))
        }
        // Also with no token stored at all.
        assertEquals(LocalAccess.OK, localAccess(LocalRoute.INFO_JSON, "GET", null, null))
        // A 404 stays a 404 rather than becoming a 401 that names the path.
        assertEquals(LocalAccess.OK, localAccess(LocalRoute.NOT_FOUND, "GET", null, null))
    }

    @Test
    fun `add_treatments is POST only`() {
        listOf("GET", "HEAD", "PUT", "DELETE").forEach { m ->
            assertEquals(
                m,
                LocalAccess.METHOD_NOT_ALLOWED,
                localAccess(LocalRoute.ADD_TREATMENTS, m, token, token),
            )
        }
        assertEquals(LocalAccess.OK, localAccess(LocalRoute.ADD_TREATMENTS, "POST", token, token))
        assertEquals(LocalAccess.OK, localAccess(LocalRoute.ADD_TREATMENTS, "post", token, token))
    }

    @Test
    fun `both guarded routes need the token`() {
        listOf(LocalRoute.ADD_TREATMENTS to "POST", LocalRoute.EVENTS to "GET").forEach { (r, m) ->
            assertEquals("$r no token", LocalAccess.UNAUTHORIZED, localAccess(r, m, null, token))
            assertEquals("$r empty", LocalAccess.UNAUTHORIZED, localAccess(r, m, "", token))
            assertEquals("$r wrong", LocalAccess.UNAUTHORIZED, localAccess(r, m, "$token ", token))
            assertEquals("$r prefix", LocalAccess.UNAUTHORIZED, localAccess(r, m, token.dropLast(1), token))
            assertEquals("$r right", LocalAccess.OK, localAccess(r, m, token, token))
        }
    }

    @Test
    fun `with no token stored the guarded routes are closed, not open`() {
        assertEquals(
            LocalAccess.UNAUTHORIZED,
            localAccess(LocalRoute.ADD_TREATMENTS, "POST", null, null),
        )
        assertEquals(LocalAccess.UNAUTHORIZED, localAccess(LocalRoute.EVENTS, "GET", "anything", null))
        assertEquals(LocalAccess.UNAUTHORIZED, localAccess(LocalRoute.EVENTS, "GET", "", ""))
        assertFalse(tokenMatches(null, null))
        assertFalse(tokenMatches("", ""))
    }

    @Test
    fun `a client may present the token three ways`() {
        assertEquals(token, presentedToken(mapOf(LOCAL_TOKEN_HEADER to token), emptyMap()))
        assertEquals(token, presentedToken(mapOf("authorization" to "Bearer $token"), emptyMap()))
        assertEquals(token, presentedToken(mapOf("authorization" to "bearer $token"), emptyMap()))
        assertEquals(token, presentedToken(emptyMap(), mapOf(LOCAL_TOKEN_PARAM to token)))
        // The header wins, so a token in a query string cannot downgrade one
        // already presented in a header.
        assertEquals(
            token,
            presentedToken(mapOf(LOCAL_TOKEN_HEADER to token), mapOf(LOCAL_TOKEN_PARAM to "other")),
        )
        assertNull(presentedToken(emptyMap(), emptyMap()))
        assertNull(presentedToken(mapOf("authorization" to "Basic $token"), emptyMap()))
        assertNull(presentedToken(mapOf(LOCAL_TOKEN_HEADER to "  "), emptyMap()))
    }

    @Test
    fun `a form-encoded body parses like a query string`() {
        val p = parseFormEncoded("insulin=4.5&carbs=30&note=two%20words")
        assertEquals("4.5", p["insulin"])
        assertEquals("30", p["carbs"])
        assertEquals("two words", p["note"])
        assertTrue(parseFormEncoded("").isEmpty())
        assertTrue(parseFormEncoded("nothing").isEmpty())
    }
}
