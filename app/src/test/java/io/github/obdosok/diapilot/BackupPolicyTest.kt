package io.github.obdosok.diapilot

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The system must never copy this app's data off the device: the preferences
 * hold the Anthropic API key and the companion token, the database is medical
 * data, and the app has its own explicit backup. Pins the merged manifest
 * (library manifests could otherwise re-enable backup) and the rule files
 * that cover device-to-device transfer on API 31+, where allowBackup alone no
 * longer does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPolicyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val allDomains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    /** The merged manifest AGP hands to Robolectric (includeAndroidResources). */
    private fun mergedManifest(): File {
        val props = Properties()
        val stream = javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")
        assertNotNull("test_config.properties missing: unitTests.isIncludeAndroidResources must stay on", stream)
        stream!!.use { props.load(it) }
        val path = props.getProperty("android_merged_manifest")
        assertNotNull("no android_merged_manifest in test_config.properties", path)
        return File(path!!).also { assertTrue("merged manifest not found at $it", it.isFile) }
    }

    @Test fun `the merged manifest disables system backup`() {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest())
        val apps = doc.getElementsByTagName("application")
        assertEquals(1, apps.length)
        val app = apps.item(0) as org.w3c.dom.Element
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(androidNs, "fullBackupContent"))
    }

    @Test fun `the installed package reports backup as not allowed`() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    /** Domain -> paths excluded, per section; includes collected separately. */
    private fun rules(resId: Int): Pair<Map<String, Map<String, Set<String>>>, List<String>> {
        val excludes = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        val includes = mutableListOf<String>()
        val parser = context.resources.getXml(resId)
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer", "full-backup-content" -> section = parser.name
                "include" -> includes += "$section:${parser.getAttributeValue(null, "domain")}"
                "exclude" -> excludes.getOrPut(section) { mutableMapOf() }
                    .getOrPut(parser.getAttributeValue(null, "domain")) { mutableSetOf() }
                    .add(parser.getAttributeValue(null, "path"))
            }
        }
        return excludes to includes
    }

    @Test fun `API 31+ rules exclude everything from cloud backup and device transfer`() {
        val (excludes, includes) = rules(R.xml.data_extraction_rules)
        assertEquals(emptyList<String>(), includes)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val bySection = excludes[section].orEmpty()
            assertEquals(section, allDomains, bySection.keys)
            bySection.forEach { (domain, paths) -> assertEquals("$section/$domain", setOf("."), paths) }
        }
    }

    @Test fun `pre-31 rules exclude everything too`() {
        val (excludes, includes) = rules(R.xml.backup_rules)
        assertEquals(emptyList<String>(), includes)
        val all = excludes["full-backup-content"].orEmpty()
        assertEquals(allDomains, all.keys)
        all.forEach { (domain, paths) -> assertEquals(domain, setOf("."), paths) }
    }
}
