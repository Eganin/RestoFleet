package ru.restofleet.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Учётка брокера лежит в SharedPreferences: правила резервного копирования обязаны исключать домен `sharedpref`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Для каждого раздела (`full-backup-content`, `cloud-backup`, `device-transfer`) — исключённые домены. */
    private fun excludedBySection(resName: String): Map<String, Set<String>> {
        val xml = context.resources.getXml(context.resources.getIdentifier(resName, "xml", context.packageName))
        val sections = linkedMapOf<String, MutableSet<String>>()
        var section: String? = null
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG) {
                when (xml.name) {
                    "full-backup-content", "cloud-backup", "device-transfer" ->
                        section = xml.name.also { sections[it] = mutableSetOf() }
                    "exclude" -> sections.getValue(section!!) += xml.getAttributeValue(null, "domain")
                    "include" -> sections.getValue(section!!) += "include:" + xml.getAttributeValue(null, "domain")
                }
            }
            xml.next()
        }
        return sections
    }

    @Test
    fun fullBackupExcludesSharedPrefs() {
        val rules = excludedBySection("backup_rules")

        assertThat(rules.keys).containsExactly("full-backup-content")
        assertThat(rules.getValue("full-backup-content")).contains("sharedpref")
        assertThat(rules.getValue("full-backup-content").filter { it.startsWith("include:") }).isEmpty()
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeSharedPrefs() {
        val rules = excludedBySection("data_extraction_rules")

        assertThat(rules.keys).containsExactly("cloud-backup", "device-transfer")
        for ((_, excluded) in rules) {
            assertThat(excluded).contains("sharedpref")
            assertThat(excluded.filter { it.startsWith("include:") }).isEmpty()
        }
    }

    @Test
    fun manifestForbidsBackup() {
        val flags = context.applicationInfo.flags

        assertThat(flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }
}
