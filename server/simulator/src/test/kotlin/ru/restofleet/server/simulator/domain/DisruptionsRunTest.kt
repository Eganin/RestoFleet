package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.simulator.domain.fault.DeviceFault
import ru.restofleet.server.simulator.domain.fault.DeviceFaultType
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.EpisodePlan
import ru.restofleet.server.simulator.domain.model.FleetSpec
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.RoleMix
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.TransportMode
import ru.restofleet.server.simulator.domain.port.ProvisionOutcome
import ru.restofleet.server.simulator.domain.port.ProvisioningApi
import ru.restofleet.server.simulator.domain.runtime.CommandReceipts
import ru.restofleet.server.simulator.domain.runtime.DeviceRunner
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.domain.synthetic.NetworkVariant
import ru.restofleet.server.simulator.domain.synthetic.Outage
import ru.restofleet.server.simulator.domain.synthetic.OutageKind
import ru.restofleet.server.simulator.domain.synthetic.ProbeMode
import ru.restofleet.server.simulator.domain.usecase.PrepareFleet
import ru.restofleet.server.simulator.domain.usecase.RegisterDevices
import ru.restofleet.server.simulator.domain.usecase.RunExperiment
import ru.restofleet.server.simulator.support.FakeConnector
import ru.restofleet.server.simulator.support.FakeControl
import ru.restofleet.server.simulator.support.FakeProbes
import ru.restofleet.server.simulator.support.FakeSink
import ru.restofleet.server.simulator.support.FakeStore
import ru.restofleet.server.simulator.support.FakeTraffic
import ru.restofleet.server.simulator.support.FakeTruth

/** Прогон с отказами (FR-SM-04, 05): синтетические пробы, пропажа и возврат устройств, эталон, подготовка парка. */
class DisruptionsRunTest {
    private val metrics = RunMetrics()
    private val receipts = CommandReceipts()
    private val truth = FakeTruth()
    private val devices: List<DeviceCredentials> = (0 until 6).map {
        DeviceCredentials("d-$it", "pw", if (it < 3) "r-a" else "r-b", DeviceRole.KITCHEN)
    }
    private val control = FakeControl(restaurantIds = listOf("r-a", "r-b")).apply {
        codes["r-a"] = "S8-01"
        codes["r-b"] = "S8-02"
    }

    private fun TestScope.experiment(connector: FakeConnector): RunExperiment {
        val clock = { testScheduler.currentTime }
        return RunExperiment(
            connector, control, DeviceRunner(clock, FakeProbes(), metrics, receipts), metrics, receipts, FakeTraffic(),
            FakeSink(), truth, clock, { testScheduler.currentTime * 1_000_000 },
        )
    }

    private fun scenario(outages: List<Outage> = emptyList(), faults: List<DeviceFault> = emptyList()) = Scenario(
        name = "s8-test", mode = TransportMode.MQTT, devices = 6, rampPerSec = 100, warmupSec = 0, durationSec = 120,
        jitter = 0.0, probeMode = ProbeMode.SYNTHETIC, outages = outages, faults = faults,
    )

    @Test
    fun backendLocalOutageFailsOnlyApiOfThatRestaurantAndWritesTruth() = runTest {
        val connector = FakeConnector(pushes = true)
        val outage = Outage(OutageKind.BACKEND_LOCAL_DOWN, 10_000, 100_000, restaurants = setOf("S8-02"))

        experiment(connector)(scenario(outages = listOf(outage)), devices)

        val rows = truth.written.values.single()
        assertThat(rows.map { "${it.restaurant}:${it.expectedClass}" }).containsExactly("S8-02:backend_local")
        assertThat(rows.single().to - rows.single().from).isEqualTo(90_000)
        fun states(id: String) = connector.links.getValue(id).events.filterIsInstance<SimEvent.ProbeState>()
            .map { "${it.target}:${it.state}" }
        // Автомат K сообщает и начальное `up` (как агент)
        assertThat(states("d-4")).containsAtLeast("api:up", "api:down", "api:up").inOrder()
        assertThat(states("d-4")).doesNotContain("ref:down")
        assertThat(states("d-0")).doesNotContain("api:down")
        assertThat(connector.links.getValue("d-4").probes.flatMap { r -> r.results.map { it.id } }.toSet())
            .containsExactly("api", "ref")
        assertThat(connector.victims).hasSize(6)
    }

    @Test
    fun networkOfflineVanishesRestaurantSilentlyAndRestoresIt() = runTest {
        val connector = FakeConnector(pushes = true)
        val outage =
            Outage(OutageKind.RESTAURANT_NETWORK_DOWN, 20_000, 60_000, setOf("S8-01"), variant = NetworkVariant.OFFLINE)

        experiment(connector)(scenario(outages = listOf(outage)), devices)

        (0 until 3).forEach { i ->
            val history = connector.history.getValue("d-$i")
            assertThat(history).hasSize(2)
            assertThat(history.first().vanished).isEqualTo(LossMode.SILENT)
            assertThat(history.last().opened).isNotNull()
            assertThat(history.last().closed).isTrue()
        }
        assertThat(connector.history.getValue("d-3")).hasSize(1)
    }

    @Test
    fun massReconnectClosesAndReopensTheCoveredDevices() = runTest {
        val connector = FakeConnector(pushes = true)
        val fault = DeviceFault(DeviceFaultType.MASS_RECONNECT, atSec = 30, durationSec = 10, devices = 2, offset = 4)

        experiment(connector)(scenario(faults = listOf(fault)), devices)

        assertThat(connector.history.getValue("d-4").first().vanished).isEqualTo(LossMode.CLOSE)
        assertThat(connector.history.getValue("d-5")).hasSize(2)
        assertThat(connector.history.getValue("d-0")).hasSize(1)
    }

    @Test
    fun episodesAreGeneratedFromRestaurantCodes() = runTest {
        val connector = FakeConnector(pushes = true)
        val many = (0 until 30).map { DeviceCredentials("d-$it", "pw", "r-${it / 6}", DeviceRole.HALL) }
        val control5 = FakeControl(restaurantIds = (0 until 5).map { "r-$it" })
        val clock = { testScheduler.currentTime }
        val run = RunExperiment(
            connector, control5, DeviceRunner(clock, FakeProbes(), metrics, receipts), metrics, receipts, FakeTraffic(),
            FakeSink(), truth, clock, { testScheduler.currentTime * 1_000_000 },
        )
        val scenario = Scenario(
            name = "acc", mode = TransportMode.MQTT, devices = 30, rampPerSec = 100, warmupSec = 0, durationSec = 300,
            jitter = 0.0, probeMode = ProbeMode.SYNTHETIC, episodes = EpisodePlan(rounds = 1),
        )

        run(scenario, many)

        assertThat(truth.written.values.single().map { it.restaurant }.toSet()).hasSize(5)
    }

    @Test
    fun prepareCreatesMissingFleetRestaurantsAndRegistersOnlyThere() = runTest {
        val clock = { testScheduler.currentTime }
        val store = FakeStore()
        var n = 0
        val provisioning = ProvisioningApi { code ->
            val restaurant = code.substringAfter("code-").substringBefore("-kitchen").substringBefore("-hall")
            ProvisionOutcome.Registered(DeviceCredentials("d-${n++}", "pw", restaurant, DeviceRole.HALL))
        }
        val register = RegisterDevices(control, provisioning, store, FakeSink(), clock)
        val fleet = FleetSpec("S8", restaurants = 3, perRestaurant = 2)

        val mix = RoleMix(kitchen = 1, hall = 1, assembly = 0)
        val report = PrepareFleet(control, register)(fleet, mix, parallelism = 2)

        assertThat(control.calls).containsExactly("create:S8-03")
        assertThat(report.registered).isEqualTo(6)
        assertThat(control.issued.map { it.first }.toSet()).containsExactly("r-a", "r-b", "id-S8-03")
        PrepareFleet(control, register)(fleet, mix, parallelism = 2)
        assertThat(control.calls).containsExactly("create:S8-03")
        assertThat(store.items).hasSize(6)
        assertThat(fleet.codes).containsExactly("S8-01", "S8-02", "S8-03").inOrder()
        assertThat(runCatching { FleetSpec("S8-", 1) }.isFailure).isTrue()
    }

    @Test
    fun outagesRequireSyntheticMode() {
        val error = runCatching {
            Scenario(
                name = "x", mode = TransportMode.MQTT, devices = 1,
                outages = listOf(Outage(OutageKind.BACKEND_GLOBAL_DOWN, 0, 1_000)),
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
