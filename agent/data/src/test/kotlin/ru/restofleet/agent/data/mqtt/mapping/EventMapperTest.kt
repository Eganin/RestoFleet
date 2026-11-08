package ru.restofleet.agent.data.mqtt.mapping

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.AgentEvent.Lifecycle.Phase
import ru.restofleet.agent.domain.model.AgentEvent.PackageChanged.Change
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.AgentLifecycleEventDto
import ru.restofleet.shared.protocol.dto.CapabilityCheckEventDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.PackageChangedEventDto
import ru.restofleet.shared.protocol.dto.ProbeStateEventDto

class EventMapperTest {
    private fun check(fixture: String, event: AgentEvent) =
        assertMatchesFixture(fixture, EventDto.serializer(), EventMapper.toDto(event))

    @Test
    fun probeDownMatchesFixture() {
        check(
            "event-probe-state",
            AgentEvent.ProbeStateChanged(1793865700000, "api", up = false, error = ProbeError.TIMEOUT, failures = 3),
        )
    }

    @Test
    fun probeUpHasNoError() {
        val dto = EventMapper.toDto(AgentEvent.ProbeStateChanged(1, "api", up = true, error = null, failures = 0))

        assertThat(dto).isEqualTo(ProbeStateEventDto(ts = 1, target = "api", state = "up", error = null, failures = 0))
    }

    @Test
    fun networkChangedMatchesFixture() {
        check(
            "event-net-changed",
            AgentEvent.NetworkChanged(1793865710000, NetworkState(NetworkType.CELLULAR, null, true)),
        )
    }

    @Test
    fun lifecycleMatchesFixture() {
        check("event-agent-lifecycle", AgentEvent.Lifecycle(1793865590000, Phase.BOOT))
    }

    @Test
    fun everyLifecyclePhaseIsLowercase() {
        val phases = Phase.entries.map {
            (EventMapper.toDto(AgentEvent.Lifecycle(1, it)) as AgentLifecycleEventDto).phase
        }

        assertThat(phases).containsExactly("boot", "start", "stop").inOrder()
    }

    @Test
    fun packageChangedMatchesFixture() {
        check(
            "event-package-changed",
            AgentEvent.PackageChanged(1793865730000, "ru.restofleet.dummy.waiter", Change.REPLACED, 13),
        )
    }

    @Test
    fun removedPackageOmitsVersionCodeAndEveryChangeIsLowercase() {
        val dtos = Change.entries.map {
            EventMapper.toDto(AgentEvent.PackageChanged(1, "p", it, versionCode = null)) as PackageChangedEventDto
        }

        assertThat(dtos.map { it.change }).containsExactly("added", "replaced", "removed").inOrder()
        assertThat(dtos.map { it.versionCode }).containsExactly(null, null, null)
        assertThat(RfJson.encodeToString(EventDto.serializer(), dtos.last())).doesNotContain("versionCode")
    }

    @Test
    fun capabilityCheckMatchesFixture() {
        val result = CheckResult(
            CheckId.R3,
            CheckStatus.LIMITED,
            mapOf("status" to "NONE", "headroom" to "NaN"),
            "эмулятор отдаёт заглушку",
            checkedAt = 5,
        )

        check("event-capability-check", AgentEvent.CapabilityChecked(1793865720000, result))
    }

    @Test
    fun eventTimestampIsUsedNotCheckTime() {
        val result = CheckResult(CheckId.R1, CheckStatus.OK, emptyMap(), null, checkedAt = 5)

        val dto = EventMapper.toDto(AgentEvent.CapabilityChecked(ts = 99, result = result)) as CapabilityCheckEventDto

        assertThat(dto.ts).isEqualTo(99)
        assertThat(dto.note).isNull()
    }

    @Test
    fun maskedSerialStaysMaskedAndNothingElseLeaks() {
        val result = CheckResult(CheckId.R13, CheckStatus.OK, mapOf("serial" to "****4F2A"), null, checkedAt = 5)

        val json = RfJson.encodeToString(
            EventDto.serializer(),
            EventMapper.toDto(AgentEvent.CapabilityChecked(1, result)),
        )

        assertThat(json).contains("****4F2A")
        assertThat(json).doesNotContain("RF8N")
    }
}
