package ru.restofleet.server.domain.device

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.AlertSubject
import ru.restofleet.server.domain.alert.Condition
import ru.restofleet.server.domain.alert.ConditionKey
import ru.restofleet.server.domain.alert.Facets
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.command.usecase.ListDeviceCommands
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.usecase.DecommissionDevice
import ru.restofleet.server.domain.device.usecase.GetDevice
import ru.restofleet.server.domain.device.usecase.ListDevices
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.Outcome

/** Устройства для консоли (ПМ2 2.4, реш. 424, 437): поиск, версия, «Проблемы», строка списка, история команд. */
class DeviceConsoleTest {
    private val w = World()
    private val list = ListDevices(w.devices, w.restaurants, w.telemetry, w.sessions, w.alerts, w.tx)
    private val get = GetDevice(w.devices, w.telemetry, w.sessions, w.restaurants, w.alerts, w.tx)
    private val commands = ListDeviceCommands(w.devices, w.commands, w.tx)

    private fun named(id: String, name: String, version: String = "1.0.0", restaurantId: String = "r-1") {
        val d = w.device(id, restaurantId = restaurantId, status = DeviceStatus.ONLINE)
        w.seed { it.copy(devices = it.devices + (d.id to d.copy(name = name, agentVersion = version))) }
    }

    private fun alert(id: String, device: String, rule: RuleId, openedAt: Long, parent: String? = null) =
        Alert(id, AlertSubject(rule, deviceId(device)), RestaurantId("r-1"), AlertState.OPEN, openedAt,
            parentIncidentId = parent)

    init {
        w.restaurant("r-1", "MSK-01")
        w.restaurant("r-2", "SPB-07")
        named("d-1", "Кухня 2")
        named("d-2", "Зал 3", version = "0.9.0")
        named("d-3", "Сборка 1", restaurantId = "r-2")
    }

    @Test
    fun searchByNameRestaurantAndModelIgnoringCase() = runTest {
        assertThat(list(DeviceFilter(query = "кухня")).map { it.device.id.value }).containsExactly("d-1")
        assertThat(list(DeviceFilter(query = "spb")).map { it.device.id.value }).containsExactly("d-3")
        assertThat(list(DeviceFilter(query = "pixel"))).hasSize(3)
        assertThat(list(DeviceFilter(query = "нет такого"))).isEmpty()
    }

    @Test
    fun versionAndProblemsFilters() = runTest {
        assertThat(list(DeviceFilter(agentVersion = "0.9.0")).map { it.device.id.value }).containsExactly("d-2")
        w.seed {
            it.copy(
                alerts = mapOf(
                    "a1" to alert("a1", "d-1", RuleId.R03, 5),
                    "a2" to alert("a2", "d-2", RuleId.R05, 5, parent = "i-1"),
                    "a3" to alert("a3", "d-3", RuleId.R01, 5).copy(state = AlertState.RESOLVED),
                ),
            )
        }
        assertThat(list(DeviceFilter(problemsOnly = true)).map { it.device.id.value }).containsExactly("d-1")
    }

    @Test
    fun rowCarriesRestaurantTelemetrySessionReasonAndAlertsInOrder() = runTest {
        val snapshot = TelemetrySnapshot(deviceId("d-1"), 50, 1, 34, true, 46.8, "SEVERE", -71, 4800, 1200, null, "{}")
        w.seed {
            it.copy(
                telemetry = listOf(snapshot, snapshot.copy(ts = 40)),
                sessions = listOf(DeviceSession(deviceId("d-1"), "c-1", 1, 2, "lwt")),
                alerts = mapOf(
                    "a1" to alert("a1", "d-1", RuleId.R01, 5),
                    "a2" to alert("a2", "d-1", RuleId.R03, 3),
                    "a3" to alert("a3", "d-1", RuleId.R06, 9),
                    "a4" to alert("a4", "d-1", RuleId.R05, 9, parent = "i-1"),
                ),
            )
        }
        val row = list(DeviceFilter(query = "Кухня")).single()
        assertThat(row.restaurant?.code).isEqualTo("MSK-01")
        assertThat(row.lastTelemetry).isEqualTo(snapshot)
        assertThat(row.lastSessionReason).isEqualTo("lwt")
        assertThat(row.alerts.map { it.id }).containsExactly("a2", "a3", "a1").inOrder()

        w.seed { it.copy(sessions = it.sessions + DeviceSession(deviceId("d-1"), "c-2", 3, null, null)) }
        assertThat(list(DeviceFilter(query = "Кухня")).single().lastSessionReason).isNull()
        assertThat(list(DeviceFilter(), PageRequest.of(offset = 10))).isEmpty()
    }

    @Test
    fun cardCarriesRestaurantAndActiveAlerts() = runTest {
        w.seed { it.copy(alerts = mapOf("a1" to alert("a1", "d-1", RuleId.R03, 5))) }
        val details = (get(deviceId("d-1")) as Outcome.Success).value
        assertThat(details.restaurant?.name).isEqualTo("Ресторан MSK-01")
        assertThat(details.alerts.map { it.id }).containsExactly("a1")
    }

    @Test
    fun commandHistoryNewestFirstAndUnknownDevice() = runTest {
        w.command("c-1", "d-1", createdAt = 1)
        w.command("c-2", "d-1", createdAt = 3, status = CommandStatus.SUCCEEDED)
        w.command("c-3", "d-2", createdAt = 2)

        val history = (commands(deviceId("d-1")) as Outcome.Success).value
        assertThat(history.map { it.cmdId }).containsExactly("c-2", "c-1").inOrder()
        assertThat((commands(deviceId("d-1"), PageRequest.of(limit = 1)) as Outcome.Success).value.map { it.cmdId })
            .containsExactly("c-2")
        assertThat(commands(deviceId("d-9"))).isEqualTo(Outcome.Failure(ServerError.NotFound("device", "d-9")))
    }

    @Test
    fun decommissionResolvesAlertsAndDropsConditions() = runTest {
        val key = ConditionKey(RuleId.R01, deviceId("d-1"), null, Facets.OFFLINE)
        w.seed {
            it.copy(
                alerts = mapOf("a1" to alert("a1", "d-1", RuleId.R01, 5)),
                conditions = mapOf(key to Condition(key, 5, emptyMap())),
            )
        }
        val decommission = DecommissionDevice(
            w.devices, w.credentials, w.sessions, w.commands, w.alerts, w.conditions, w.broker, w.publisher, w.cache,
            w.tx, w.clock,
        )
        decommission(deviceId("d-1"))

        val alert = w.db.state.alerts.getValue("a1")
        assertThat(alert.state).isEqualTo(AlertState.RESOLVED)
        assertThat(alert.auto).isTrue()
        assertThat(w.db.state.conditions).isEmpty()
    }
}
