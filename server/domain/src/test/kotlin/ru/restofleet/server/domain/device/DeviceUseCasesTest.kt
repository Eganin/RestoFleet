package ru.restofleet.server.domain.device

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.usecase.DecommissionDevice
import ru.restofleet.server.domain.device.usecase.GetDevice
import ru.restofleet.server.domain.device.usecase.ListDevices
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.presence.SessionCloseReason
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.Outcome

class DeviceUseCasesTest {
    private val w = World()
    private val list = ListDevices(w.devices, w.tx)
    private val get = GetDevice(w.devices, w.telemetry, w.sessions, w.tx)
    private val decommission = DecommissionDevice(
        w.devices, w.credentials, w.sessions, w.broker, w.publisher, w.cache, w.tx, w.clock,
    )

    @Test
    fun listFiltersAndPages() = runTest {
        val a = w.device("d-a", createdAt = 1, status = DeviceStatus.ONLINE)
        val b = w.device("d-b", createdAt = 2)
        val c = w.device("d-c", restaurantId = "r-2", createdAt = 3)
        assertThat(list(DeviceFilter())).containsExactly(a, b, c).inOrder()
        assertThat(list(DeviceFilter(restaurantId = RestaurantId("r-2")))).containsExactly(c)
        assertThat(list(DeviceFilter(status = DeviceStatus.ONLINE))).containsExactly(a)
        assertThat(list(DeviceFilter(role = DeviceRole.HALL))).isEmpty()
        assertThat(list(DeviceFilter(), PageRequest.of(limit = 1, offset = 1))).containsExactly(b)
    }

    @Test
    fun pageRequestIsClamped() {
        assertThat(PageRequest.of(limit = 0, offset = -5)).isEqualTo(PageRequest.of(1, 0))
        assertThat(PageRequest.of(limit = 5_000).limit).isEqualTo(PageRequest.MAX_LIMIT)
        assertThat(PageRequest.of().limit).isEqualTo(PageRequest.DEFAULT_LIMIT)
        assertThat(PageRequest.of().hashCode()).isEqualTo(PageRequest.of(100, 0).hashCode())
        assertThat(PageRequest.of().toString()).contains("limit=100")
    }

    @Test
    fun getReturnsLatestTelemetryAndSession() = runTest {
        val d = w.device()
        val old = snapshot(ts = 1)
        val fresh = snapshot(ts = 2)
        val session = DeviceSession(d.id, "k-1", 5, null, null)
        w.seed { it.copy(telemetry = listOf(fresh, old), sessions = listOf(session)) }
        assertThat(get(d.id)).isEqualTo(Outcome.Success(DeviceDetails(d, fresh, session)))
        assertThat(get(deviceId("d-x"))).isEqualTo(Outcome.Failure(ServerError.NotFound("device", "d-x")))
    }

    @Test
    fun decommissionDisablesRevokesClosesAndClears() = runTest {
        val d = w.device(status = DeviceStatus.ONLINE)
        w.credential("d-1", "pw")
        w.cache.put(d.id, "sha(pw)")
        w.seed { it.copy(sessions = listOf(DeviceSession(d.id, "k-1", 5, null, null))) }

        val result = (decommission(d.id) as Outcome.Success).value
        assertThat(result.retainedCleared).isTrue()
        assertThat(result.device.status).isEqualTo(DeviceStatus.DECOMMISSIONED)
        assertThat(w.broker.disabled).containsExactly(d.id)
        assertThat(w.db.state.devices.getValue(d.id).decommissionedAt).isEqualTo(w.clock.now)
        assertThat(w.db.state.credentials.getValue(d.id).revokedAt).isEqualTo(w.clock.now)
        assertThat(w.db.state.sessions.single().reason).isEqualTo(SessionCloseReason.DECOMMISSIONED)
        assertThat(w.publisher.cleared).containsExactly(d.id)
        assertThat(w.cache.entries).isEmpty()
    }

    @Test
    fun brokerFailureKeepsDeviceActive() = runTest {
        val d = w.device(status = DeviceStatus.ONLINE)
        w.credential("d-1", "pw")
        w.broker.failing += "disable_client"
        assertThat(decommission(d.id)).isEqualTo(Outcome.Failure(ServerError.BrokerUnavailable("disable_client")))
        assertThat(w.db.state.devices.getValue(d.id).status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(w.db.state.credentials.getValue(d.id).revokedAt).isNull()
        assertThat(w.publisher.cleared).isEmpty()
    }

    @Test
    fun repeatIsIdempotentAndFinishesCleanup() = runTest {
        val d = w.device()
        w.publisher.fail = true
        assertThat((decommission(d.id) as Outcome.Success).value.retainedCleared).isFalse()
        val decommissionedAt = w.db.state.devices.getValue(d.id).decommissionedAt
        w.publisher.fail = false
        w.clock.now += 1_000
        val again = (decommission(d.id) as Outcome.Success).value
        assertThat(again.retainedCleared).isTrue()
        assertThat(w.db.state.devices.getValue(d.id).decommissionedAt).isEqualTo(decommissionedAt)
        assertThat(w.broker.disabled).hasSize(2)
    }

    @Test
    fun unknownDevice() = runTest {
        assertThat(decommission(deviceId("d-x"))).isEqualTo(Outcome.Failure(ServerError.NotFound("device", "d-x")))
    }

    @Test
    fun enumCodes() {
        assertThat(DeviceRole.fromCode("hall")).isEqualTo(DeviceRole.HALL)
        assertThat(DeviceRole.fromCode("x")).isNull()
        assertThat(DeviceStatus.fromCode("online")).isEqualTo(DeviceStatus.ONLINE)
        assertThat(DeviceStatus.fromCode(null)).isNull()
        assertThat(DeviceTransport.fromCode("poll")).isEqualTo(DeviceTransport.POLL)
        assertThat(DeviceTransport.fromCode("x")).isNull()
    }

    private fun snapshot(ts: Long) =
        TelemetrySnapshot(deviceId("d-1"), ts, ts, 50, true, 30.0, "NONE", -60, 100, 200, "pkg", "{}")
}
