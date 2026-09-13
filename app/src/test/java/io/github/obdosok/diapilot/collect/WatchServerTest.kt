package io.github.obdosok.diapilot.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The loopback server's contract, driven through [WatchServer.respond] — the
 * whole decision without a socket, so no port is bound and no accept loop is
 * raced.
 *
 * What is under test is the boundary, not the payload: which requests are
 * refused, and that `/info.json` keeps answering exactly as it did (a Zepp OS
 * watch face reads it and cannot be redeployed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchServerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun server() = WatchServer(context)

    // The token lives in SecretStore, whose real cipher needs AndroidKeyStore;
    // Robolectric has none, so a fake stands in.
    private val originalCipherFactory = io.github.obdosok.diapilot.data.Secrets.cipherFactory

    @Before fun fakeCipher() {
        io.github.obdosok.diapilot.data.Secrets.cipherFactory = {
            io.github.obdosok.diapilot.data.FakeSecretCipher()
        }
        io.github.obdosok.diapilot.data.Secrets.reset()
    }

    @After fun restore() {
        io.github.obdosok.diapilot.data.Secrets.cipherFactory = originalCipherFactory
        io.github.obdosok.diapilot.data.Secrets.reset()
        // `/info.json` opens the shared store; it must not outlive the test.
        io.github.obdosok.diapilot.data.Stores.close()
    }

    private fun token() = io.github.obdosok.diapilot.data.WatchApiToken.getOrCreate(context)!!

    private fun authorized(target: String) =
        server().respond("POST", target, mapOf("x-diapilot-token" to token()))

    @Test fun `a GET on add_treatments is 405, not a write`() {
        val r = server().respond("GET", "/add_treatments?insulin=4&token=${token()}")
        assertEquals("405 Method Not Allowed", r.status)
        assertTrue(r.extraHeaders, r.extraHeaders.contains("Allow: POST"))
    }

    @Test fun `add_treatments without a token is 401`() {
        assertEquals("401 Unauthorized", server().respond("POST", "/add_treatments?insulin=4").status)
    }

    @Test fun `add_treatments with the wrong token is 401`() {
        val wrong = token().let { it.dropLast(1) + if (it.last() == 'A') 'B' else 'A' }
        assertEquals(
            "401 Unauthorized",
            server().respond("POST", "/add_treatments?insulin=4", mapOf("x-diapilot-token" to wrong)).status,
        )
    }

    @Test fun `the events journal needs the same token`() {
        assertEquals("401 Unauthorized", server().respond("GET", "/api/v1/events").status)
        assertEquals(
            "401 Unauthorized",
            server().respond("GET", "/api/v1/events", mapOf("authorization" to "Bearer nope")).status,
        )
    }

    @Test fun `a dose above the personal fuse is refused`() {
        // 100 U is the LLM path's canonical "10 misheard as 100"; this path had
        // no ceiling at all before.
        val r = authorized("/add_treatments?insulin=100")
        assertTrue(r.status, r.status.startsWith("400"))
    }

    @Test fun `a non-positive or non-finite dose is refused, not read as absent`() {
        listOf("0", "-2", "NaN", "Infinity").forEach { v ->
            val r = authorized("/add_treatments?insulin=$v")
            assertTrue("$v -> ${r.status}", r.status.startsWith("400"))
        }
    }

    @Test fun `implausible carbs are refused too`() {
        assertTrue(authorized("/add_treatments?carbs=5000").status.startsWith("400"))
    }

    @Test fun `info json needs nothing and stays a plain-text body`() {
        val r = server().respond("GET", "/info.json")
        assertTrue(r.status, r.status.startsWith("200"))
        assertTrue(r.contentType, r.contentType.startsWith("text/plain"))
        assertTrue(r.extraHeaders.isEmpty())
    }

    @Test fun `an unknown path is still a plain 404`() {
        val r = server().respond("GET", "/whatever")
        assertTrue(r.status, r.status.startsWith("404"))
    }

    // --- the parser: what it reads before any route is looked at ---

    private fun head(raw: String) = server().readHead(java.io.StringReader(raw))

    @Test fun `a well-formed request head is parsed with lower-cased header names`() {
        val h = head("POST /add_treatments?insulin=4 HTTP/1.1\r\nHost: 127.0.0.1\r\nX-DiaPilot-Token: abc\r\n\r\n")
        assertTrue(h is WatchServer.Head.Ok)
        h as WatchServer.Head.Ok
        assertEquals("POST", h.method)
        assertEquals("/add_treatments?insulin=4", h.target)
        assertEquals("abc", h.headers["x-diapilot-token"])
        assertEquals("127.0.0.1", h.headers["host"])
    }

    @Test fun `a closed connection with no request line is not a request`() {
        assertEquals(WatchServer.Head.Closed, head(""))
    }

    @Test fun `a header line over the cap is refused as 431, not read to its end`() {
        val cap = WatchServer.MAX_HEADER_LINE_CHARS
        val ok = head("GET /info.json HTTP/1.1\r\nX-Pad: ${"a".repeat(cap - "X-Pad: ".length)}\r\n\r\n")
        assertTrue(ok is WatchServer.Head.Ok)
        val over = head("GET /info.json HTTP/1.1\r\nX-Pad: ${"a".repeat(cap)}\r\n\r\n")
        assertTrue(over is WatchServer.Head.Refused)
        assertEquals("431 Request Header Fields Too Large", (over as WatchServer.Head.Refused).status)
    }

    @Test fun `a request line over the cap is refused as 414`() {
        val over = head("GET /${"a".repeat(WatchServer.MAX_HEADER_LINE_CHARS)} HTTP/1.1\r\n\r\n")
        assertTrue(over is WatchServer.Head.Refused)
        assertEquals("414 URI Too Long", (over as WatchServer.Head.Refused).status)
    }

    @Test fun `more headers than the cap are refused`() {
        fun request(n: Int) = "GET /info.json HTTP/1.1\r\n" + (1..n).joinToString("") { "X-H$it: v\r\n" } + "\r\n"
        assertTrue(head(request(WatchServer.MAX_HEADERS)) is WatchServer.Head.Ok)
        val over = head(request(WatchServer.MAX_HEADERS + 1))
        assertTrue(over is WatchServer.Head.Refused)
    }

    @Test fun `a line that never ends is refused at the cap instead of being buffered`() {
        // A reader that hands out 'a' forever: readLine() on it never returns.
        val endless = object : java.io.Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(cbuf, off, off + len, 'a')
                return len
            }
            override fun close() {}
        }
        val h = server().readHead(endless)
        assertTrue(h is WatchServer.Head.Refused)
    }

    @Test fun `the server admits a bounded number of connections at once`() {
        val s = server()
        repeat(WatchServer.MAX_CONNECTIONS) { assertTrue("slot $it", s.admit()) }
        assertTrue(!s.admit())
        s.release()
        assertTrue(s.admit())
        assertTrue(!s.admit())
    }

    @Test fun `a form-encoded body carries the parameters too`() {
        val r = server().respond(
            "POST",
            "/add_treatments",
            mapOf(
                "x-diapilot-token" to token(),
                "content-type" to "application/x-www-form-urlencoded",
            ),
            body = "insulin=100",
        )
        // Reached the guard through the body rather than the query string.
        assertTrue(r.status, r.status.startsWith("400"))
    }
}
