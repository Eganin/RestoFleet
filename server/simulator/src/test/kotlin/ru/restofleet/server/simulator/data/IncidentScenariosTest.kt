package ru.restofleet.server.simulator.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.server.simulator.data.scenario.ScenarioException
import ru.restofleet.server.simulator.data.scenario.YamlScenarioLoader
import ru.restofleet.server.simulator.domain.fault.DeviceFaultType
import ru.restofleet.server.simulator.domain.synthetic.NetworkVariant
import ru.restofleet.server.simulator.domain.synthetic.OutageKind
import ru.restofleet.server.simulator.domain.synthetic.ProbeMode
import java.io.File
import kotlin.test.assertFailsWith

/**
 * Сценарии отказов и точности из каталога `tools/incidents/scenarios` (ПМ4): разбираются строгим загрузчиком, имя — имя
 * файла, S8 и точность — в режиме `synthetic` на парке 12 × 6, точность — 8 раундов по 5 мин (реш. 428).
 */
class IncidentScenariosTest {
    private val files = File(System.getProperty("incidentScenariosDir")).listFiles { f -> f.extension == "yaml" }!!
        .associateBy { it.nameWithoutExtension }

    private fun load(name: String) = YamlScenarioLoader.load(files.getValue(name).toPath()).scenario

    @Test
    fun `every scenario parses and is named after its file`() {
        assertThat(files.keys).containsExactly("s8-a", "s8-b", "s8-c", "accuracy", "sm04")
        files.forEach { (name, _) -> assertWithMessage(name).that(load(name).name).isEqualTo(name) }
    }

    @Test
    fun `s8 scenarios use the synthetic fleet and one outage each`() {
        val expected = mapOf(
            "s8-a" to OutageKind.BACKEND_GLOBAL_DOWN,
            "s8-b" to OutageKind.RESTAURANT_NETWORK_DOWN,
            "s8-c" to OutageKind.BACKEND_LOCAL_DOWN,
        )
        expected.forEach { (name, kind) ->
            val s = load(name)
            assertWithMessage(name).that(s.probeMode).isEqualTo(ProbeMode.SYNTHETIC)
            assertWithMessage(name).that(s.fleet!!.devices).isEqualTo(s.devices)
            assertWithMessage(name).that(s.outages.single().kind).isEqualTo(kind)
        }
        assertThat(load("s8-b").outages.single().variant).isEqualTo(NetworkVariant.OFFLINE)
        assertThat(load("s8-c").outages.single().restaurants).containsExactly("S8-05", "S8-06")
    }

    @Test
    fun `accuracy runs 40 minutes of five minute rounds`() {
        val s = load("accuracy")
        val episodes = s.episodes!!
        assertThat(episodes.rounds * episodes.roundSec).isEqualTo(s.durationSec)
        assertThat(s.durationSec).isEqualTo(2_400)
        assertThat(s.fleet!!.codes.first()).isEqualTo("S8-01")
    }

    @Test
    fun `sm04 covers every device fault`() {
        assertThat(load("sm04").faults.map { it.type }.toSet()).containsExactlyElementsIn(DeviceFaultType.entries)
        assertThat(load("sm04").probeMode).isEqualTo(ProbeMode.SYNTHETIC)
    }

    @Test
    fun `unknown fault, outage kind, variant or probe mode is rejected`() {
        val base = "name: x\nmode: mqtt\ndevices: 1\nendpoints: { api: http://a }\n"
        listOf(
            "faults: [{ type: melt, atSec: 1, durationSec: 1 }]",
            "probeMode: synthetic\noutages: [{ kind: meteor, atSec: 1, durationSec: 1 }]",
            "probeMode: synthetic\noutages: [{ kind: restaurant_network_down, atSec: 1, durationSec: 1, variant: x }]",
            "probeMode: psychic",
            "outages: [{ kind: backend_global_down, atSec: 1, durationSec: 1 }]",
        ).forEach { extra ->
            assertFailsWith<ScenarioException>(extra) { YamlScenarioLoader.parse(base + extra) }
        }
        val targets = "probeMode: synthetic\nsyntheticTargets: [{ id: api, intervalSec: 5 }]"
        val custom = YamlScenarioLoader.parse(base + targets)
        assertThat(custom.scenario.syntheticTargets.single().intervalSec).isEqualTo(5)
    }
}
