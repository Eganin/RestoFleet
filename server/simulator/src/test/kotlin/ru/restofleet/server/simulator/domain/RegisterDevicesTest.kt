package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.RoleMix
import ru.restofleet.server.simulator.domain.port.ProvisionOutcome
import ru.restofleet.server.simulator.domain.port.ProvisioningApi
import ru.restofleet.server.simulator.domain.usecase.RegisterDevices
import ru.restofleet.server.simulator.domain.model.RegistrationRequest
import ru.restofleet.server.simulator.support.FakeControl
import ru.restofleet.server.simulator.support.FakeSink
import ru.restofleet.server.simulator.support.FakeStore
import ru.restofleet.server.simulator.support.creds
import kotlin.test.assertFailsWith

/** FR-SM-08: регистрация через `tokens:batch` → `/provision`, кэш учёток, дорегистрация до N. */
class RegisterDevicesTest {
    private val control = FakeControl(restaurantIds = listOf("r-1", "r-2"))
    private val sink = FakeSink()

    /** Погашение кода: учётка с ролью и рестораном из кода (как бэкенд, реш. 210). */
    private val provisioning = ProvisioningApi { code ->
        val (_, restaurant, role) = code.split('-').let { Triple(it[0], "${it[1]}-${it[2]}", it[3]) }
        ProvisionOutcome.Registered(DeviceCredentials("d-$code", "pw", restaurant, DeviceRole.fromCode(role)!!))
    }

    private fun useCase(store: FakeStore, api: ProvisioningApi = provisioning) =
        RegisterDevices(control, api, store, sink) { 0L }

    @Test
    fun registersMissingDevicesInPlanOrder() = runTest {
        val store = FakeStore()
        val report = useCase(store)(RegistrationRequest(devices = 8, parallelism = 3))

        assertThat(report.registered).isEqualTo(8)
        assertThat(report.failed).isEqualTo(0)
        assertThat(store.items.map { it.restaurantId to it.role.code }).containsExactly(
            "r-1" to "kitchen", "r-1" to "kitchen", "r-1" to "hall", "r-1" to "hall", "r-1" to "hall",
            "r-1" to "assembly", "r-2" to "kitchen", "r-2" to "kitchen",
        ).inOrder()
        assertThat(control.issued).containsExactly(
            Triple("r-1", DeviceRole.KITCHEN, 2), Triple("r-1", DeviceRole.HALL, 3),
            Triple("r-1", DeviceRole.ASSEMBLY, 1),
            Triple("r-2", DeviceRole.KITCHEN, 2),
        )
        assertThat(sink.registrations).containsExactly(report)
    }

    @Test
    fun cachedDevicesAreReusedAndOnlyTheRestIsRegistered() = runTest {
        val store = FakeStore(List(6) { creds(it) })
        val report = useCase(store)(RegistrationRequest(devices = 7, mix = RoleMix()))
        assertThat(report.cached).isEqualTo(6)
        assertThat(report.registered).isEqualTo(1)
        assertThat(store.items.last().restaurantId).isEqualTo("r-2")
        assertThat(control.issued).containsExactly(Triple("r-2", DeviceRole.KITCHEN, 1))

        val again = useCase(store)(RegistrationRequest(devices = 7))
        assertThat(again.registered).isEqualTo(0)
        assertThat(control.issued).hasSize(1)
    }

    @Test
    fun transientFailuresAreRetriedAndRejectionsCounted() = runTest {
        val attempts = HashMap<String, Int>()
        val flaky = ProvisioningApi { code ->
            val n = attempts.merge(code, 1, Int::plus)!!
            when {
                code.endsWith("-0") && n < 3 -> ProvisionOutcome.Retry("http_429")
                code.endsWith("-1") -> ProvisionOutcome.Rejected("token_used")
                code.endsWith("-2") -> ProvisionOutcome.Retry("http_503")
                else -> provisioning.provision(code)
            }
        }
        val store = FakeStore()
        val report = useCase(store, flaky)(RegistrationRequest(devices = 3, maxAttempts = 3, retryDelayMs = 10))
        assertThat(report.registered).isEqualTo(1)
        assertThat(report.failures).containsExactly("token_used", 1, "http_503", 1)
        assertThat(attempts.values.sorted()).containsExactly(1, 3, 3)
    }

    @Test
    fun registrationLimitsAreValidated() {
        assertFailsWith<IllegalArgumentException> { RegistrationRequest(devices = 0) }
        assertFailsWith<IllegalArgumentException> { RegistrationRequest(devices = 1, parallelism = 0) }
    }
}
