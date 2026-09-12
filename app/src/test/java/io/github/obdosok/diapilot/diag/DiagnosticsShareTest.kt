package io.github.obdosok.diapilot.diag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.AppIdentity
import io.github.obdosok.diapilot.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * THE WAY OUT: a share sheet whose attachment the receiving app can actually
 * open, over a FileProvider path that exists only for this export.
 *
 * WHAT THIS TEST CANNOT DO, and why the seam in [DiagnosticsReport.chooser]
 * exists: `FileProvider.getUriForFile` fails under Robolectric for EVERY path
 * this app declares, `photos/` included — the provider resolves its
 * `FILE_PROVIDER_PATHS` meta-data through the application's own resources and
 * gets an empty root table back, while `context.resources.getXml` on the same
 * resource parses fine. So the declared paths are asserted against the
 * resource, and the intent is asserted on a URI of the shape the provider
 * returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsShareTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val dir get() = File(context.filesDir, DiagnosticsReport.DIR_NAME)

    @After fun cleanUp() {
        dir.deleteRecursively()
        DiagLog.clear()
    }

    private fun sendOf(chooser: Intent): Intent =
        requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)) {
            "the chooser carries no EXTRA_INTENT"
        }

    private fun streamOf(send: Intent): Uri =
        requireNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))

    private fun chooserForWrittenFile(): Intent {
        val file = DiagnosticsReport.export(context, store = null)
        val uri = Uri.parse(
            "content://${AppIdentity.FILE_PROVIDER_AUTHORITY}/diagnostics/${file.name}",
        )
        return DiagnosticsReport.chooser(context, uri, file.name)
    }

    // ——— the provider path ———

    /** Every root the provider declares, as `tag:path`. */
    private fun declaredPaths(): List<String> {
        val parser = context.resources.getXml(R.xml.file_paths)
        val roots = mutableListOf<String>()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name != "paths") {
                roots += "${parser.name}:${parser.getAttributeValue(null, "path")}"
            }
            event = parser.next()
        }
        return roots
    }

    @Test fun `the export has its own provider path and photos was not widened`() {
        assertEquals(
            listOf("files-path:photos/", "files-path:diagnostics/"),
            declaredPaths(),
        )
    }

    @Test fun `no declared root exposes the app's whole sandbox`() {
        declaredPaths().forEach { root ->
            val path = root.substringAfter(':')
            assertFalse("a root open onto everything: $root", path == "." || path == "/" || path.isEmpty())
        }
    }

    // ——— the intent ———

    @Test fun `the export is offered through a share sheet as plain text`() {
        val chooser = chooserForWrittenFile()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = sendOf(chooser)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(
            context.getString(R.string.diagnostics_share_subject),
            send.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        val uri = streamOf(send)
        assertEquals(AppIdentity.FILE_PROVIDER_AUTHORITY, uri.authority)
        assertTrue(uri.lastPathSegment!!.startsWith("diapilot-diagnostics-"))
    }

    @Test fun `the receiving app is granted read access`() {
        val send = sendOf(chooserForWrittenFile())
        assertTrue(
            "without this flag the attachment arrives empty",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        // Some targets take the URI off the clip rather than the extra.
        assertEquals(1, send.clipData?.itemCount)
        assertEquals(streamOf(send), send.clipData?.getItemAt(0)?.uri)
    }

    // ——— the file the intent points at ———

    @Test fun `the file on disk is the file the header promises`() {
        val text = DiagnosticsReport.export(context, store = null).readText()
        assertTrue(text.startsWith("DiaPilot diagnostics"))
        assertTrue(text.contains("WHAT WAS REMOVED"))
        assertTrue(text.contains("--- COLLECTION ---"))
        assertTrue(text.contains("--- JOURNAL (last 24 h) ---"))
        assertTrue(text.contains("--- LOG ("))
        assertTrue(Redact.medicalNumbers(text.substringAfter("--- COLLECTION ---")).isEmpty())
    }

    @Test fun `a line written through DiagLog reaches the file without its value`() {
        DiagLog.clear()
        DiagLog.record('I', "FakeSource", "BG 6.3 mmol/L stored for a stranger")
        val text = DiagnosticsReport.export(context, store = null).readText()
        assertTrue(text.contains("FakeSource: BG <redacted> mmol/L stored for a stranger"))
        assertFalse(text.contains("6.3"))
    }

    @Test fun `taps do not accumulate files in the sandbox`() {
        repeat(6) { i ->
            DiagnosticsReport.write(context, "diapilot-diagnostics-2026091$i-1200.txt", "x")
        }
        assertTrue("kept ${dir.listFiles()?.size} files", (dir.listFiles()?.size ?: 0) <= 3)
    }
}
