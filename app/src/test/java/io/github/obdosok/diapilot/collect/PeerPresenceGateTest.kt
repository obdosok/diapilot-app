package io.github.obdosok.diapilot.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.diag.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The two "is the peer even installed" gates that stand in front of the
 * unauthenticated inputs: the xDrip web-service poll (a port any app can bind
 * once xDrip is stopped) and the OOP2 broadcast receivers (exported, because
 * the sender is another app).
 *
 * The broadcast receiver's own gate is covered in [XdripBroadcastGateTest];
 * this file covers the poll's decision and the OOP2 presence check, plus the
 * one property a log line in a 15-minute worker must have: it is written once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerPresenceGateTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun install(packageName: String) = Shadows.shadowOf(context.packageManager).installPackage(
        android.content.pm.PackageInfo().apply { this.packageName = packageName },
    )

    @Before fun setUp() {
        XdripApp.reset()
        Oop2App.reset()
        TreatmentsPollWorker.resetSkipLog()
        DiagLog.clear()
    }

    @After fun cleanUp() {
        XdripApp.reset()
        Oop2App.reset()
        TreatmentsPollWorker.resetSkipLog()
        DiagLog.clear()
    }

    private fun skipLines() = DiagLog.lines(System.currentTimeMillis())
        .filter { it.message.contains("web-service poll skipped") }

    @Test fun `with no xDrip installed the web-service poll is not allowed`() {
        assertFalse(TreatmentsPollWorker.xdripPollAllowed(context))
    }

    @Test fun `with xDrip installed the web-service poll is allowed`() {
        install(XdripApp.PACKAGE)
        assertTrue(TreatmentsPollWorker.xdripPollAllowed(context))
    }

    @Test fun `the skip is logged once, not on every run`() {
        repeat(5) { assertFalse(TreatmentsPollWorker.xdripPollAllowed(context)) }
        assertEquals(1, skipLines().size)
    }

    @Test fun `after an install the resumption is logged and a later skip is announced again`() {
        assertFalse(TreatmentsPollWorker.xdripPollAllowed(context))
        install(XdripApp.PACKAGE)
        XdripApp.reset() // the negative answer is cached for a minute; the test cannot wait it out
        assertTrue(TreatmentsPollWorker.xdripPollAllowed(context))
        assertEquals(1, DiagLog.lines(System.currentTimeMillis()).count { it.message.contains("poll resumed") })
        // Uninstalled again: the skip line is fresh news and is written once more.
        Shadows.shadowOf(context.packageManager).removePackage(XdripApp.PACKAGE)
        XdripApp.reset()
        assertFalse(TreatmentsPollWorker.xdripPollAllowed(context))
        assertEquals(2, skipLines().size)
    }

    @Test fun `OOP2 reads as absent until its package is installed`() {
        assertFalse(Oop2App.installed(context))
        install(Oop2App.PACKAGE)
        Oop2App.reset()
        assertTrue(Oop2App.installed(context))
    }

    @Test fun `the OOP2 package id is the one the manifest declares in queries`() {
        // The installed-check answers "absent" for any package the manifest
        // does not list under <queries> from API 30 on, so the two must agree.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<package android:name=\"${Oop2App.PACKAGE}\""))
        assertTrue(manifest.contains("<package android:name=\"${XdripApp.PACKAGE}\""))
    }
}
