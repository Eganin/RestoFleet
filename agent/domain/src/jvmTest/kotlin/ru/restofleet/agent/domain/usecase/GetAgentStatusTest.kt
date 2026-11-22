package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.FakeShadowStore
import ru.restofleet.agent.domain.model.ConnectionInfo
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.ConnectionMonitor
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.DeviceInfoSource
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.probe.ProbeStates
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.probes.ProbeState

class GetAgentStatusTest {
    private val outbox = FakeOutbox()
    private val shadow = FakeShadowStore()
    private val probes = ProbeStates()
    private var caps: CapabilitiesSource = CapabilitiesSource {
        Capabilities(AgentMode.NORMAL, 36, setOf(Permission.POST_NOTIFICATIONS))
    }
    private val connection = ConnectionInfo(SessionState.CONNECTED, connId = "k-1", endpoint = "10.0.2.2:8883")
    private val store = object : CredentialStore {
        override suspend fun save(s: BrokerSettings) = Unit

        override suspend fun load(): BrokerSettings? = null
    }

    private val emulator = DeviceInfoSource { DeviceInfo(36, "emu", true, AgentMode.NORMAL, "0.2") }

    private fun sut(device: DeviceInfoSource = emulator) = GetAgentStatus(
        GetRegistration(store), ConnectionMonitor { connection }, { caps.current() }, device, outbox, probes, shadow,
    )

    private fun target(id: String) = ProbeTarget(id, "https://$id.example/", 1_000)

    private fun result(id: String) = ProbeResult(id, true, 200, null, 9, ProbePhases(null, null, null, null), 1)

    @Test
    fun snapshotCombinesAllSources() = runTest {
        outbox.enqueue(OutgoingMessage(MessageKind.EVENT, ByteArray(0), 1, createdAt = 1))

        val status = sut()()

        assertThat(status.registration).isEqualTo(RegistrationState.NotRegistered)
        assertThat(status.connection).isEqualTo(connection)
        assertThat(status.mode).isEqualTo(AgentMode.NORMAL)
        assertThat(status.agentVersion).isEqualTo("0.2")
        assertThat(status.granted).containsExactly(Permission.POST_NOTIFICATIONS)
        assertThat(status.outboxSize).isEqualTo(1)
    }

    @Test
    fun onlyProbesOfTheCurrentShadowAreShown() = runTest {
        probes.record(target("api"), result("api"))
        probes.record(target("old"), result("old"))
        shadow.save(DesiredShadow(1, 30, emptyList(), listOf(target("api"))))

        val shown = sut()().probes

        assertThat(shown.map { it.targetId }).containsExactly("api")
        assertThat(shown.single().state).isEqualTo(ProbeState.UP)
    }

    @Test
    fun failingSourcesLeaveTheirFieldsEmptyButTheRestIsShown() = runTest {
        caps = CapabilitiesSource { error("system service") }

        val status = sut(device = DeviceInfoSource { error("pm") })()

        assertThat(status.granted).isNull()
        assertThat(status.mode).isNull()
        assertThat(status.agentVersion).isNull()
        assertThat(status.outboxSize).isEqualTo(0)
        assertThat(status.connection.state).isEqualTo(SessionState.CONNECTED)
    }

    @Test
    fun registrationIsReadFromCredentials() = runTest {
        val id = DeviceId.parse("d-7")!!
        val withCreds = object : CredentialStore {
            override suspend fun save(s: BrokerSettings) = Unit

            override suspend fun load() = BrokerSettings("h", 8883, id, "pw")
        }
        val status = GetAgentStatus(
            GetRegistration(withCreds), ConnectionMonitor { connection }, caps,
            { DeviceInfo(36, "emu", true, AgentMode.NORMAL, "0.2") }, outbox, probes, shadow,
        )()

        assertThat((status.registration as RegistrationState.Registered).registration.deviceId).isEqualTo(id)
    }
}
