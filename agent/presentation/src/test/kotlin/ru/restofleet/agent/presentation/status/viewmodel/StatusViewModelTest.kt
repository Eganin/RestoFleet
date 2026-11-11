package ru.restofleet.agent.presentation.status.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.model.ConnectionInfo
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.ConnectionMonitor
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.agent.domain.probe.ProbeStates
import ru.restofleet.agent.domain.usecase.GetAgentStatus
import ru.restofleet.agent.domain.usecase.GetRegistration
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.status.api.IStatusRouter
import ru.restofleet.agent.presentation.status.viewmodel.StatusIntent.User
import ru.restofleet.shared.kernel.DeviceId
import java.io.IOException

/** Экран статуса (FR-AG-05): снимок раз в 3 с и по кнопке, сбой источника не ломает экран. */
class StatusViewModelTest {
    private val id = DeviceId.parse("d-3")!!

    private inner class Fixture(
        scope: TestScope,
        labAvailable: Boolean = true,
        var credentials: suspend () -> BrokerSettings? = {
            BrokerSettings("10.0.2.2", 8883, id, "pw", DeviceRole.KITCHEN, "r-2")
        },
        var connection: ConnectionInfo = ConnectionInfo(SessionState.CONNECTED, "k-1", null, "10.0.2.2:8883"),
        var gate: CompletableDeferred<Unit>? = null,
    ) {
        val navigation = mutableListOf<String>()
        var reads = 0
        val probes = ProbeStates()
        var outboxSize = 2
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val store = object : CredentialStore {
            override suspend fun save(s: BrokerSettings) = Unit

            override suspend fun load(): BrokerSettings? {
                reads++
                gate?.await()
                return credentials()
            }
        }
        private val outbox = object : Outbox {
            override suspend fun enqueue(m: OutgoingMessage) = Unit
            override suspend fun peek(limit: Int) = emptyList<Pair<Long, OutgoingMessage>>()
            override suspend fun peek(limit: Int, kind: MessageKind) = emptyList<Pair<Long, OutgoingMessage>>()
            override suspend fun remove(ids: List<Long>) = Unit
            override suspend fun size(): Int = outboxSize
        }
        private val shadow = object : ShadowStore {
            override suspend fun appliedVersion(): Long = 1
            override suspend fun save(shadow: DesiredShadow) = Unit
            override suspend fun load() = DesiredShadow(1, 30, emptyList(), listOf(target("api"), target("kds")))
            override fun observe(): Flow<DesiredShadow?> = flowOf(null)
        }
        val vm = StatusViewModel(
            getStatus = GetAgentStatus(
                GetRegistration(store),
                ConnectionMonitor { connection },
                { Capabilities(AgentMode.NORMAL, 36, setOf(Permission.USAGE_ACCESS)) },
                { DeviceInfo(36, "emu", true, AgentMode.DEVICE_OWNER, "0.2.0") },
                outbox,
                probes,
                shadow,
            ),
            router = object : IStatusRouter {
                override fun openSettings(target: SettingsTarget) {
                    navigation += "settings:$target"
                }

                override fun openLab() {
                    navigation += "lab"
                }
            },
            labAvailable = labAvailable,
            io = dispatcher,
            scope = scope,
        )

        fun ui() = vm.uiState.value
    }

    private fun target(id: String) = ProbeTarget(id, "https://$id.example/", 1_000, failThreshold = 1)

    private fun result(id: String, ok: Boolean) = ProbeResult(
        id, ok, if (ok) 200 else null, if (ok) null else ProbeError.TIMEOUT, 41, ProbePhases(null, null, null, null), 1,
    )

    @Test
    fun loadingUntilTheFirstSnapshot() = runTest {
        val f = Fixture(this)

        assertThat(f.ui().isLoading).isTrue()
        assertThat(f.ui().connection).isEqualTo(StringSpec(R.string.status_disconnected))
        assertThat(f.ui().connectionBadge).isEqualTo(Badge.NA)
        runCurrent()
        assertThat(f.ui().isLoading).isFalse()
        f.scope.cancel()
    }

    @Test
    fun snapshotShowsConnectionRegistrationDeviceAndPermissions() = runTest {
        val f = Fixture(this)
        runCurrent()
        val ui = f.ui()

        assertThat(ui.connection).isEqualTo(StringSpec(R.string.status_connected))
        assertThat(ui.connectionBadge).isEqualTo(Badge.OK)
        assertThat(ui.connectionDetails.map { it.value.args }).containsExactly(listOf("k-1"), listOf("10.0.2.2:8883"))
        assertThat(ui.registration.map { it.value }).containsExactly(
            StringSpec(R.string.value_raw, listOf("d-3")),
            StringSpec(R.string.role_kitchen),
            StringSpec(R.string.value_raw, listOf("r-2")),
        ).inOrder()
        assertThat(ui.registrationNote).isNull()
        assertThat(ui.device.map { it.value }).containsExactly(
            StringSpec(R.string.status_mode_device_owner),
            StringSpec(R.string.value_raw, listOf("2")),
            StringSpec(R.string.value_raw, listOf("0.2.0")),
        ).inOrder()
        assertThat(ui.permissions.map { it.badge }).containsExactly(Badge.WARN, Badge.OK, Badge.WARN).inOrder()
        f.scope.cancel()
    }

    @Test
    fun disconnectedSessionShowsTheReason() = runTest {
        val f = Fixture(this, connection = ConnectionInfo(SessionState.DISCONNECTED, errorCode = "auth_failed"))
        runCurrent()

        assertThat(f.ui().connectionBadge).isEqualTo(Badge.FAIL)
        assertThat(f.ui().connectionDetails.single().value)
            .isEqualTo(StringSpec(R.string.status_error, listOf("auth_failed")))
        f.connection = ConnectionInfo(SessionState.CONNECTING)
        f.vm.onIntent(User.Refresh)
        runCurrent()
        assertThat(f.ui().connection).isEqualTo(StringSpec(R.string.status_connecting))
        assertThat(f.ui().connectionBadge).isEqualTo(Badge.PENDING)
        f.scope.cancel()
    }

    @Test
    fun probesShowUpDownAndDetails() = runTest {
        val f = Fixture(this)
        f.probes.record(target("api"), result("api", ok = true))
        f.probes.record(target("kds"), result("kds", ok = false))
        runCurrent()

        val rows = f.ui().probes
        assertThat(rows.map { it.id to it.badge }).containsExactly("api" to Badge.OK, "kds" to Badge.FAIL).inOrder()
        assertThat(rows[0].detail).isEqualTo(StringSpec(R.string.status_probe_detail_ok, listOf("200", "41")))
        assertThat(rows[1].detail).isEqualTo(StringSpec(R.string.status_probe_detail_fail, listOf("timeout", "1")))
        f.scope.cancel()
    }

    @Test
    fun probeWithoutStateYetIsUnknown() = runTest {
        val f = Fixture(this)
        f.probes.record(ProbeTarget("api", "https://a/", 1, failThreshold = 3), result("api", ok = false))
        runCurrent()

        assertThat(f.ui().probes.single().badge).isEqualTo(Badge.NA)
        assertThat(f.ui().probes.single().state).isEqualTo(StringSpec(R.string.status_probe_unknown))
        f.scope.cancel()
    }

    @Test
    fun snapshotIsRefreshedPeriodicallyOnlyWhileShown() = runTest {
        val f = Fixture(this)
        runCurrent()
        advanceTimeBy(10_000)
        assertThat(f.reads).isEqualTo(1)

        f.vm.onIntent(StatusIntent.External.Shown)
        f.vm.onIntent(StatusIntent.External.Shown)
        runCurrent()
        assertThat(f.reads).isEqualTo(2)
        advanceTimeBy(3_001)
        assertThat(f.reads).isEqualTo(3)

        f.vm.onIntent(StatusIntent.External.Hidden)
        advanceTimeBy(10_000)
        assertThat(f.reads).isEqualTo(3)
        f.scope.cancel()
    }

    @Test
    fun snapshotIsRefreshedOnRequestWithoutOverlap() = runTest {
        val f = Fixture(this)
        runCurrent()
        assertThat(f.reads).isEqualTo(1)
        f.vm.onIntent(User.Refresh)
        runCurrent()
        assertThat(f.reads).isEqualTo(2)

        f.outboxSize = 7
        f.vm.onIntent(User.Refresh)
        runCurrent()
        assertThat(f.reads).isEqualTo(3)
        assertThat(f.ui().device[1].value).isEqualTo(StringSpec(R.string.value_raw, listOf("7")))

        val gate = CompletableDeferred<Unit>()
        f.gate = gate
        f.vm.onIntent(User.Refresh)
        runCurrent()
        assertThat(f.ui().isRefreshing).isTrue()
        f.vm.onIntent(User.Refresh)
        runCurrent()
        assertThat(f.reads).isEqualTo(4)
        gate.complete(Unit)
        runCurrent()
        assertThat(f.ui().isRefreshing).isFalse()
        f.scope.cancel()
    }

    @Test
    fun missingOrUnavailableCredentialsAreExplained() = runTest {
        val f = Fixture(this, credentials = { null })
        runCurrent()
        assertThat(f.ui().registrationNote).isEqualTo(StringSpec(R.string.status_not_registered))
        assertThat(f.ui().registration).isEmpty()

        f.credentials = { throw IOException("keystore") }
        f.vm.onIntent(User.Refresh)
        runCurrent()
        assertThat(f.ui().registrationNote).isEqualTo(StringSpec(R.string.status_registration_unavailable))
        f.scope.cancel()
    }

    @Test
    fun navigationGoesThroughTheRouterAndLabOnlyWhenAvailable() = runTest {
        val debug = Fixture(this)
        val release = Fixture(this, labAvailable = false)

        debug.vm.onIntent(User.OpenSettings(SettingsTarget.BATTERY_OPTIMIZATION))
        debug.vm.onIntent(User.OpenLab)
        release.vm.onIntent(User.OpenLab)

        assertThat(debug.navigation).containsExactly("settings:BATTERY_OPTIMIZATION", "lab").inOrder()
        assertThat(release.navigation).isEmpty()
        debug.scope.cancel()
        release.scope.cancel()
    }
}
