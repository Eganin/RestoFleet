package ru.restofleet.server.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.app.config.AppConfigLoader
import ru.restofleet.server.app.config.ProbeSection
import ru.restofleet.server.app.config.RoleDesiredSection
import ru.restofleet.server.app.config.RuntimeConfig
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.DesiredRules
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue
import kotlin.test.assertFailsWith

/** Умолчания `desired` по ролям из `application.yaml` (ПМ5 5.1, реш. 425). */
class DesiredDefaultsConfigTest {
    private val runtime = RuntimeConfig.forDatabaseOnly(AppConfigLoader.load(emptyMap())) { "db" }
    private val defaults = runtime.provisioning.desired

    private fun ShadowDocument.list(key: String) = (fields.getValue(key) as ShadowValue.List).items

    private fun ShadowDocument.probeIds() =
        list("probes").map { ((it as ShadowValue.Obj).fields.getValue("id") as ShadowValue.Str).value }

    private fun ShadowDocument.apps() = list("watchedApps").map { (it as ShadowValue.Str).value }

    @Test
    fun `kitchen gets api, kds and the reference target and watches chrome`() {
        val kitchen = defaults.forRole(DeviceRole.KITCHEN)
        assertThat(kitchen.probeIds()).containsExactly("api", "kds", "ref").inOrder()
        assertThat(kitchen.apps()).containsExactly("com.android.chrome")
        val ref = kitchen.list("probes").last() as ShadowValue.Obj
        assertThat(ref.fields["reference"]).isEqualTo(ShadowValue.Bool(true))
        assertThat(ref.fields["expectedStatus"]).isEqualTo(ShadowValue.Num(204.0))
        assertThat(DesiredRules.validate(kitchen)).isNull()
    }

    @Test
    fun `hall and assembly get api and ref with placeholder apps, every document is valid`() {
        listOf(DeviceRole.HALL, DeviceRole.ASSEMBLY).forEach { role ->
            val doc = defaults.forRole(role)
            assertThat(doc.probeIds()).containsExactly("api", "ref").inOrder()
            assertThat(doc.apps().single()).startsWith("ru.restofleet.dummy.")
            assertThat(DesiredRules.validate(doc)).isNull()
        }
    }

    @Test
    fun `invalid or unknown role section stops the start`() {
        val config = AppConfigLoader.load(emptyMap())
        val badUrl = RoleDesiredSection(probes = listOf(ProbeSection(id = "api", url = "https://x/h", intervalSec = 1)))
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.forDatabaseOnly(config.copy(desired = mapOf("kitchen" to badUrl))) { "db" }
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.forDatabaseOnly(config.copy(desired = mapOf("chef" to RoleDesiredSection()))) { "db" }
        }
        val empty = RuntimeConfig.forDatabaseOnly(config.copy(desired = emptyMap())) { "db" }
        assertThat(empty.provisioning.desired.forRole(DeviceRole.HALL)).isEqualTo(DefaultDesired.document)
    }
}
