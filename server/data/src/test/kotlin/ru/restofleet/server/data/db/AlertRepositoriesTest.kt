package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.Test
import ru.restofleet.server.data.realtime.SharedFlowEventBus
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertFilter
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.AlertSubject
import ru.restofleet.server.domain.alert.Condition
import ru.restofleet.server.domain.alert.ConditionKey
import ru.restofleet.server.domain.alert.Facets
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.ingest.ProbeResult
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** `alerts`, `alert_conditions`, `probe_states` и запросы консоли к устройствам (ПМ2 2.3, 2.4). */
internal class AlertRepositoriesTest : PostgresTest() {
    private val alerts = ExposedAlertRepository()
    private val conditions = ExposedAlertConditionRepository()
    private val probeStates = ExposedProbeStateRepository()
    private val telemetry = ExposedTelemetryRepository()
    private val sessions = ExposedSessionRepository()
    private val commands = ExposedCommandRepository()

    private fun alert(device: Device, rule: RuleId, openedAt: Long, target: String? = null) = Alert(
        id = Samples.uuid(), subject = AlertSubject(rule, device.id, target), restaurantId = device.restaurantId,
        state = AlertState.OPEN, openedAt = openedAt, details = mapOf("k" to "v"),
    )

    @Test
    fun `alert round trip with view join, filters, order and counts`() {
        val d = givenDevice()
        val a1 = alert(d, RuleId.R05, T0 + 1, target = "api")
        val a2 = alert(d, RuleId.R03, T0 + 2)
        val suppressed = alert(d, RuleId.R05, T0 + 3, target = "kds").copy(parentIncidentId = Samples.uuid())
        tx { listOf(a1, a2, suppressed).forEach { alerts.insert(it) } }

        assertThat(tx { alerts.active(listOf(d.id)) }.toSet()).containsExactly(a1, a2, suppressed)
        val listed = tx { alerts.list(AlertFilter(), PageRequest.of()) }
        assertThat(listed.map { it.alert }).containsExactly(a2, a1).inOrder()
        assertThat(listed.first().deviceName).isEqualTo("Кухня-1")
        assertThat(listed.first().restaurantCode).isEqualTo(tx { restaurants.findById(d.restaurantId) }!!.code)
        assertThat(tx { alerts.list(AlertFilter(includeSuppressed = true), PageRequest.of()) }).hasSize(3)
        assertThat(tx { alerts.list(AlertFilter(rule = RuleId.R05), PageRequest.of()) }.map { it.alert })
            .containsExactly(a1)
        assertThat(tx { alerts.list(AlertFilter(deviceId = d.id, state = AlertState.OPEN), PageRequest.of(limit = 1)) })
            .hasSize(1)
        assertThat(tx { alerts.list(AlertFilter(restaurantId = RestaurantId("bad")), PageRequest.of()) }).isEmpty()
        assertThat(tx { alerts.list(AlertFilter(restaurantId = d.restaurantId, activeOnly = true), PageRequest.of()) })
            .hasSize(2)
        assertThat(tx { alerts.activeCounts() }.active).isEqualTo(2)
        assertThat(tx { alerts.activeCounts() }.critical).isEqualTo(2)
        assertThat(tx { alerts.views(listOf(a2.id, "nope", a1.id.uppercase())) }.map { it.alert.id })
            .containsExactly(a2.id, a1.id).inOrder()
        assertThat(tx { alerts.views(emptyList()) }).isEmpty()
        assertThat(tx { alerts.active(emptyList()) }).isEmpty()
    }

    @Test
    fun `update changes lifecycle fields and a second active alert of the subject is ignored`() {
        val d = givenDevice()
        val a = alert(d, RuleId.R01, T0)
        tx { alerts.insert(a) }
        tx { alerts.insert(a.copy(id = Samples.uuid())) }
        assertThat(tx { alerts.active(listOf(d.id)) }).containsExactly(a)

        val resolved = a.copy(
            state = AlertState.RESOLVED, ackBy = "support", ackAt = T0 + 1, resolvedAt = T0 + 2, resolvedBy = "support",
            comment = "готово", auto = false, details = mapOf("reason" to "lwt"),
        )
        tx { alerts.update(resolved) }
        assertThat(tx { alerts.findForUpdate(a.id) }).isEqualTo(resolved)
        assertThat(tx { alerts.findForUpdate("not-a-uuid") }).isNull()
        assertThat(tx { alerts.active(listOf(d.id)) }).isEmpty()
        val again = a.copy(id = Samples.uuid(), openedAt = T0 + 5)
        tx { alerts.insert(again) }
        assertThat(tx { alerts.active(listOf(d.id)) }).containsExactly(again)
    }

    @Test
    fun `conditions are upserted and deleted, empty target round trips as null`() {
        val d = givenDevice()
        val offline = Condition(ConditionKey(RuleId.R01, d.id, null, Facets.OFFLINE), T0, mapOf("reason" to "lwt"))
        val down = Condition(ConditionKey(RuleId.R05, d.id, "api", Facets.TARGET_DOWN), T0, emptyMap())
        tx { conditions.upsert(offline); conditions.upsert(down) }
        tx { conditions.upsert(offline.copy(details = mapOf("reason" to "stop"))) }

        assertThat(tx { conditions.active(listOf(d.id)) }.toSet())
            .containsExactly(offline.copy(details = mapOf("reason" to "stop")), down)
        tx { conditions.delete(down.key) }
        assertThat(tx { conditions.active(listOf(d.id)) }.map { it.key }).containsExactly(offline.key)
        assertThat(tx { conditions.active(emptyList()) }).isEmpty()
    }

    @Test
    fun `probe states keep since on repeated state and take the latest result time`() {
        val d = givenDevice()
        fun result(target: String) = ProbeResult(target, true, 204, null, 10, null, null, null, null)
        tx { probeStates.recordResults(listOf(ProbeReport(d.id, T0 + 10, listOf(result("api"), result("ref"))))) }
        tx { probeStates.recordState(d.id, "api", down = true, at = T0 + 20) }
        tx { probeStates.recordState(d.id, "api", down = true, at = T0 + 30) }
        tx { probeStates.recordResults(listOf(ProbeReport(d.id, T0 + 5, listOf(result("api"))))) }
        tx { probeStates.recordState(d.id, "kds", down = false, at = T0 + 40) }
        tx { probeStates.recordResults(emptyList()) }

        val rows = tx { readProbeStates() }
        assertThat(rows).containsExactly(
            "api" to Triple("down", T0 + 20, T0 + 10), "ref" to Triple("up", T0 + 10, T0 + 10),
            "kds" to Triple("up", T0 + 40, null),
        )
        tx { probeStates.recordState(d.id, "api", down = false, at = T0 + 50) }
        assertThat(tx { readProbeStates() }.toMap().getValue("api")).isEqualTo(Triple("up", T0 + 50, T0 + 10))
    }

    @Test
    fun `device search escapes wildcards, filters by version and problems, counts`() {
        val r = givenRestaurant("MSK-77")
        val a = givenDevice(r) { Samples.device(it).copy(name = "Кухня 100%", status = DeviceStatus.ONLINE) }
        val b = givenDevice(r) { Samples.device(it).copy(name = "Зал_1", agentVersion = "1.0.0") }
        val other = givenDevice()
        tx { alerts.insert(alert(a, RuleId.R03, T0)) }
        tx { alerts.insert(alert(b, RuleId.R05, T0, "api").copy(parentIncidentId = Samples.uuid())) }

        fun ids(filter: DeviceFilter) = tx { devices.list(filter, PageRequest.of()) }.map { it.id }.toSet()
        assertThat(ids(DeviceFilter(query = "100%"))).containsExactly(a.id)
        assertThat(ids(DeviceFilter(query = "%"))).containsExactly(a.id)
        assertThat(ids(DeviceFilter(query = "л_"))).containsExactly(b.id)
        assertThat(ids(DeviceFilter(query = "msk-77"))).containsExactly(a.id, b.id)
        assertThat(ids(DeviceFilter(query = "GPHONE"))).containsExactly(a.id, b.id, other.id)
        assertThat(ids(DeviceFilter(agentVersion = "1.0.0"))).containsExactly(b.id)
        assertThat(ids(DeviceFilter(problemsOnly = true))).containsExactly(a.id)

        tx { devices.markOffline(b.id, T0) }
        val counts = tx { devices.counts(offlineSince = 0) }
        assertThat(counts.total).isEqualTo(3)
        assertThat(counts.online).isEqualTo(1)
        assertThat(counts.offline).isEqualTo(1)
        assertThat(counts.offlineRecently).isEqualTo(0)
        assertThat(counts.byRestaurant[r.id]).isEqualTo(2)
        assertThat(tx { devices.findByIds(listOf(a.id, other.id)) }.map { it.id }).containsExactly(a.id, other.id)
        assertThat(tx { devices.findByIds(emptyList()) }).isEmpty()
    }

    @Test
    fun `latest telemetry and session per device and command history newest first`() {
        val a = givenDevice()
        val b = givenDevice()
        fun point(id: DeviceId, ts: Long) =
            TelemetrySnapshot(id, ts, ts, 50, false, 30.0, null, null, 900, null, null, "{}")
        tx { telemetry.insertTelemetry(listOf(point(a.id, T0), point(a.id, T0 + 5), point(b.id, T0 + 1))) }
        tx {
            sessions.open(DeviceSession(a.id, "c-1", T0, null, null))
            sessions.close(a.id, "c-1", T0 + 1, "lwt")
            sessions.open(DeviceSession(a.id, "c-2", T0 + 2, null, null))
        }

        val latest = tx { telemetry.latestFor(listOf(a.id, b.id, Samples.deviceId())) }
        assertThat(latest.mapValues { it.value.ts }).containsExactly(a.id, T0 + 5, b.id, T0 + 1)
        assertThat(tx { sessions.latestFor(listOf(a.id, b.id)) }.mapValues { it.value.connId })
            .containsExactly(a.id, "c-2")
        assertThat(tx { telemetry.latestFor(emptyList()) }).isEmpty()
        assertThat(tx { sessions.latestFor(emptyList()) }).isEmpty()

        val c1 = Samples.command(a.id, createdAt = T0)
        val c2 = Samples.command(a.id, createdAt = T0 + 10)
        tx { commands.insert(c1); commands.insert(c2); commands.insert(Samples.command(b.id)) }
        assertThat(tx { commands.listByDevice(a.id, PageRequest.of()) }.map { it.cmdId })
            .containsExactly(c2.cmdId, c1.cmdId).inOrder()
        assertThat(tx { commands.listByDevice(a.id, PageRequest.of(limit = 1, offset = 1)) }.single().cmdId)
            .isEqualTo(c1.cmdId)
    }

    @Test
    fun `event bus delivers to subscribers and counts events dropped by a stalled subscriber`() = runBlocking {
        val bus = SharedFlowEventBus(capacity = 1)
        val event = RealtimeEvent.TelemetryReceived(
            TelemetrySnapshot(Samples.deviceId(), 1, 1, null, null, null, null, null, null, null, null, "{}"),
        )
        bus.publish(event)
        assertThat(bus.dropped).isEqualTo(0)

        val received = launch { assertThat(bus.events().first()).isEqualTo(event) }
        yield()
        bus.publish(event)
        received.join()

        val stalled = launch { bus.events().collect { awaitCancellation() } }
        yield()
        repeat(3) { bus.publish(event) }
        assertThat(bus.dropped).isAtLeast(1)
        stalled.cancel()
    }

    private fun readProbeStates(): List<Pair<String, Triple<String, Long, Long?>>> =
        ProbeStatesTable.selectAll().map {
            it[ProbeStatesTable.target] to Triple(
                it[ProbeStatesTable.state],
                it[ProbeStatesTable.since].toMillis(),
                it[ProbeStatesTable.lastResultAt]?.toMillis(),
            )
        }
}
