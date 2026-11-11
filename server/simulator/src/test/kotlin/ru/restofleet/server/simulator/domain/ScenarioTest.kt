package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.LossTest
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.TransportMode
import kotlin.test.assertFailsWith

class ScenarioTest {
    private val base = Scenario(name = "mqtt-100", mode = TransportMode.MQTT, devices = 100)

    @Test
    fun defaultsMatchTheExperimentPlan() {
        assertThat(base.telemetryIntervalSec).isEqualTo(30)
        assertThat(base.durationSec).isEqualTo(240)
        assertThat(base.mix.pattern()).hasSize(6)
        assertThat(DeviceRole.fromCode("hall")).isEqualTo(DeviceRole.HALL)
        assertThat(DeviceRole.fromCode("chef")).isNull()
    }

    @Test
    fun invalidScenariosAreRejected() {
        val invalid = listOf(
            { base.copy(name = "bad name") },
            { base.copy(devices = 0) },
            { base.copy(rampPerSec = 0) },
            { base.copy(durationSec = 0) },
            { base.copy(warmupSec = -1) },
            { base.copy(telemetryIntervalSec = 0) },
            { base.copy(mode = TransportMode.REST, pollIntervalSec = null) },
            { base.copy(jitter = 0.9) },
            { base.copy(timeScale = 0.0) },
            { base.copy(loss = LossTest(100, LossMode.SILENT, 10)) },
            { CommandLoad(perMinute = 0) },
            { CommandLoad(perMinute = 1, ttlSec = 0) },
            { LossTest(0, LossMode.CLOSE, 10) },
            { LossTest(1, LossMode.CLOSE, 0) },
        )
        invalid.forEach { build -> assertFailsWith<IllegalArgumentException> { build() } }
        assertThat(base.copy(mode = TransportMode.REST, pollIntervalSec = 5).pollIntervalSec).isEqualTo(5)
    }
}
