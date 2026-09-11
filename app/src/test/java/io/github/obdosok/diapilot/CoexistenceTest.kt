package io.github.obdosok.diapilot

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.github.obdosok.diapilot.data.BackupRestore
import io.github.obdosok.diapilot.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Side-by-side safety: this build must install next to another build of the
 * same source (a different applicationId) and must not touch anything the two
 * would share until the user switches it on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoexistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val legacyId = "com.example.diapilot"

    @Test fun `the public build has its own applicationId`() {
        assertEquals("io.github.obdosok.diapilot", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, AppIdentity.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
    }

    @Test fun `every shared name derives from the applicationId`() {
        val names = listOf(
            AppIdentity.FILE_PROVIDER_AUTHORITY,
            AppIdentity.ACTION_LABEL,
            AppIdentity.ACTION_BLE_WAKEUP,
            AppIdentity.autoBackupPrefix(),
            AppIdentity.autoBackupName(3),
            AppIdentity.manualExportName("20260101-0000"),
        )
        names.forEach { name ->
            assertTrue(name, name.startsWith(BuildConfig.APPLICATION_ID))
            assertFalse(name, name.contains(legacyId))
        }
        assertEquals(
            io.github.obdosok.diapilot.collect.LabelActionReceiver.ACTION_LABEL,
            AppIdentity.ACTION_LABEL,
        )
    }

    @Test fun `the manifest declares the derived FileProvider authority and no other copy's`() {
        val pm = context.packageManager
        assertNotNull(pm.resolveContentProvider(AppIdentity.FILE_PROVIDER_AUTHORITY, 0))
        assertNull(pm.resolveContentProvider("$legacyId.fileprovider", 0))
        @Suppress("DEPRECATION")
        val providers = pm.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue(providers.isNotEmpty())
        providers.forEach { p ->
            p.authority.split(';').forEach { authority ->
                assertTrue(authority, authority.startsWith(BuildConfig.APPLICATION_ID))
            }
        }
    }

    @Test fun `backup names of two installed copies never match each other`() {
        val ours = AppIdentity.autoBackupPrefix()
        val other = AppIdentity.autoBackupName(3, applicationId = legacyId)
        val suffixed = AppIdentity.autoBackupName(3, applicationId = BuildConfig.APPLICATION_ID + ".dev")
        assertEquals("io.github.obdosok.diapilot-auto-backup-3.sqlite", AppIdentity.autoBackupName(3))
        assertTrue(AppIdentity.autoBackupName(3).startsWith(ours))
        assertFalse(other.startsWith(ours))
        assertFalse(suffixed.startsWith(ours))
        assertFalse(AppIdentity.autoBackupName(3).startsWith(AppIdentity.autoBackupPrefix(legacyId)))
        // The prune matches with SQL LIKE: the prefix must carry no wildcard.
        assertFalse(ours.contains('%') || ours.contains('_'))
        // Manual exports are never matched by the auto-backup prune.
        assertFalse(AppIdentity.manualExportName("20260101-0000").startsWith(ours))
    }

    @Test fun `every shared resource is off until switched on`() {
        assertFalse(Settings.watchServerEnabled(context))
        assertFalse(Settings.autoBackupEnabled(context))
        assertFalse(Settings.libreNfcEnabled(context))
        assertFalse(Settings.ownBleEnabled(context))
        assertFalse(BackupRestore.autoBackupTargets(context).any)
    }

    @Test fun `the switches turn each resource on and off`() {
        Settings.setWatchServerEnabled(context, true)
        Settings.setAutoBackupEnabled(context, true)
        Settings.setLibreNfcEnabled(context, true)
        assertTrue(Settings.watchServerEnabled(context))
        assertTrue(Settings.autoBackupEnabled(context))
        assertTrue(Settings.libreNfcEnabled(context))
        assertEquals(
            BackupRestore.AutoBackupTargets(downloads = true, cloud = false, companion = false),
            BackupRestore.autoBackupTargets(context),
        )

        Settings.setAutoBackupEnabled(context, false)
        Settings.setLibreNfcEnabled(context, false)
        Settings.setWatchServerEnabled(context, false)
        assertFalse(Settings.watchServerEnabled(context))
        assertFalse(Settings.libreNfcEnabled(context))
        assertFalse(BackupRestore.autoBackupTargets(context).any)
    }

    @Test fun `a companion copy counts only with both URL and token`() {
        Settings.setCompanionUrl(context, "http://127.0.0.1:8787")
        assertFalse(BackupRestore.autoBackupTargets(context).companion)
        Settings.setCompanionToken(context, "t")
        assertTrue(BackupRestore.autoBackupTargets(context).companion)
        assertFalse(BackupRestore.autoBackupTargets(context).downloads)
    }

    @Test fun `with nothing switched on the daily backup does nothing`() {
        BackupRestore.autoBackupIfDue(context)
        val last = context.getSharedPreferences("backup", Context.MODE_PRIVATE).getLong("last_auto_ms", 0L)
        assertEquals(0L, last)
    }
}
