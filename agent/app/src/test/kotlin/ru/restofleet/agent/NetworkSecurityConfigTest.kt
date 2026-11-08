package ru.restofleet.agent

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Доверие к CA стенда только в debug. Сгенерированный `xml/network_security_config` разбирается как есть; ожидание
 * «CA есть» приходит из сборки (`standCaPresent`: есть ли `deploy/certs/out/ca.crt`, либо путь из `-PstandCaPath`).
 * Release-часть (нет атрибута и ресурсов) проверяют `verifyReleaseNoDebugSurface` и `aapt2 dump` APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NetworkSecurityConfigTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val caExpected = System.getProperty("standCaPresent").toBoolean()

    private data class Config(
        val baseCleartext: String?,
        val baseAnchors: List<String>,
        val overrideAnchors: List<String>?,
    )

    private val configId get() = context.resources.getIdentifier("network_security_config", "xml", context.packageName)

    private fun parse(): Config {
        val xml = context.resources.getXml(configId)
        var baseCleartext: String? = null
        val baseAnchors = mutableListOf<String>()
        var overrides: MutableList<String>? = null
        var section = ""
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG) {
                when (xml.name) {
                    "base-config" -> {
                        section = "base"
                        baseCleartext = xml.getAttributeValue(null, "cleartextTrafficPermitted")
                    }
                    "debug-overrides" -> {
                        section = "debug"
                        overrides = mutableListOf()
                    }
                    "certificates" -> (if (section == "base") baseAnchors else overrides)!!
                        .add(xml.getAttributeValue(null, "src"))
                }
            }
            xml.next()
        }
        return Config(baseCleartext, baseAnchors, overrides)
    }

    @Test
    fun debugManifestDeclaresTheConfig() {
        val res = ApplicationInfo::class.java.getField("networkSecurityConfigRes").getInt(context.applicationInfo)

        assertThat(res).isEqualTo(configId)
        assertThat(res).isNotEqualTo(0)
    }

    @Test
    fun baseConfigForbidsCleartextAndKeepsOnlySystemAnchors() {
        val config = parse()

        assertThat(config.baseCleartext).isEqualTo("false")
        assertThat(config.baseAnchors).containsExactly("system")
    }

    @Test
    fun standCaIsTrustedOnlyInDebugOverridesAndOnlyWhenTheFileExists() {
        val config = parse()
        val rawId = context.resources.getIdentifier("stand_ca", "raw", context.packageName)

        assertThat(config.overrideAnchors).isNotNull()
        if (caExpected) {
            assertThat(config.overrideAnchors!!.single()).startsWith("@")
            assertThat(rawId).isNotEqualTo(0)
            val pem = context.resources.openRawResource(rawId).bufferedReader().readText()
            assertThat(pem).startsWith("-----BEGIN CERTIFICATE-----")
            assertThat(pem).doesNotContain("PRIVATE KEY")
        } else {
            assertThat(config.overrideAnchors).isEmpty()
            assertThat(rawId).isEqualTo(0)
        }
    }
}
