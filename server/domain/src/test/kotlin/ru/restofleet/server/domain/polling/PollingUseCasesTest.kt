package ru.restofleet.server.domain.polling

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.IngestResult
import ru.restofleet.server.domain.ingest.usecase.IngestBatch
import ru.restofleet.server.domain.polling.usecase.AuthenticateDevice
import ru.restofleet.server.domain.polling.usecase.MarkStalePollers
import ru.restofleet.server.domain.polling.usecase.PollCommands
import ru.restofleet.server.domain.polling.usecase.PollTelemetry
import ru.restofleet.shared.kernel.Outcome

class PollingUseCasesTest {
    private val w = World()
    private val auth = AuthenticateDevice(w.devices, w.credentials, w.cache, w.hasher, w.digest, w.tx)
    private val ingest = IngestBatch(w.devices, w.telemetry, w.tx, w.clock)
    private val pollTelemetry = PollTelemetry(w.devices, ingest, w.tx, w.clock)
    private val pollCommands = PollCommands(w.devices, w.commands, w.tx, w.clock)
    private val markStale = MarkStalePollers(w.devices, w.pollingSettings, w.tx, w.clock)
    private val id = deviceId("d-1")

    @Test
    fun authenticatesAndCachesDigest() = runTest {
        w.device()
        w.credential("d-1", "secret")
        assertThat(auth("d-1", "secret")).isEqualTo(Outcome.Success(id))
        assertThat(w.cache.entries).containsExactly(id, "sha(secret)")
        assertThat(w.hasher.verifies).isEqualTo(1)
        // Попадание в кэш: без транзакции и bcrypt
        val commits = w.tx.commits
        assertThat(auth("d-1", "secret")).isEqualTo(Outcome.Success(id))
        assertThat(w.hasher.verifies).isEqualTo(1)
        assertThat(w.tx.commits).isEqualTo(commits)
    }

    @Test
    fun wrongPasswordWithWarmCacheIsRejected() = runTest {
        w.device()
        w.credential("d-1", "secret")
        auth("d-1", "secret")
        assertThat(auth("d-1", "other")).isEqualTo(Outcome.Failure(ServerError.Unauthorized))
        assertThat(w.hasher.verifies).isEqualTo(2)
    }

    @Test
    fun rejections() = runTest {
        w.device()
        w.credential("d-1", "secret")
        w.device("d-rev")
        w.credential("d-rev", "secret", revokedAt = 1)
        w.device("d-dec", status = DeviceStatus.DECOMMISSIONED)
        w.credential("d-dec", "secret")
        val unauthorized = Outcome.Failure(ServerError.Unauthorized)
        assertThat(auth("bad/id", "secret")).isEqualTo(unauthorized)
        assertThat(auth("d-1", "")).isEqualTo(unauthorized)
        assertThat(auth("d-1", "x".repeat(129))).isEqualTo(unauthorized)
        assertThat(auth("d-unknown", "secret")).isEqualTo(unauthorized)
        assertThat(auth("d-rev", "secret")).isEqualTo(unauthorized)
        assertThat(auth("d-dec", "secret")).isEqualTo(unauthorized)
        assertThat(w.cache.entries).isEmpty()
    }

    @Test
    fun pollTelemetryMarksPresenceAndIngestsOwnData() = runTest {
        w.device()
        val foreign = DeviceEvent(deviceId("d-2"), 1, "x", "{}")
        val batch = DeviceDataBatch(events = listOf(DeviceEvent(id, 1, "x", "{}"), foreign))
        assertThat(pollTelemetry(id, batch)).isEqualTo(IngestResult(0, 0, 1, 1))
        val d = w.db.state.devices.getValue(id)
        assertThat(d.status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(d.transport).isEqualTo(DeviceTransport.POLL)
        assertThat(w.db.state.events.single().deviceId).isEqualTo(id)
    }

    @Test
    fun pollCommandsDeliversQueuedOnce() = runTest {
        w.device()
        w.command("c-2", createdAt = w.clock.now + 1)
        w.command("c-1")
        w.command("c-old", expiresAt = w.clock.now - 1)
        w.command("c-other", deviceId = "d-2")
        val first = pollCommands(id)
        assertThat(first.map { it.cmdId }).containsExactly("c-1", "c-2").inOrder()
        assertThat(first.all { it.status == CommandStatus.DELIVERED && it.deliveredAt == w.clock.now }).isTrue()
        assertThat(pollCommands(id)).isEmpty()
        assertThat(w.db.state.devices.getValue(id).transport).isEqualTo(DeviceTransport.POLL)
    }

    @Test
    fun staleOnlyForPollers() = runTest {
        w.device("d-p1", status = DeviceStatus.ONLINE, transport = DeviceTransport.POLL, lastSeenAt = 0)
        w.device("d-p2", status = DeviceStatus.ONLINE, transport = DeviceTransport.POLL, lastSeenAt = w.clock.now)
        w.device("d-m", status = DeviceStatus.ONLINE, transport = DeviceTransport.MQTT, lastSeenAt = 0)
        assertThat(markStale()).isEqualTo(1)
        assertThat(w.db.state.devices.getValue(deviceId("d-p1")).status).isEqualTo(DeviceStatus.OFFLINE)
        assertThat(w.db.state.devices.getValue(deviceId("d-p2")).status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(w.db.state.devices.getValue(deviceId("d-m")).status).isEqualTo(DeviceStatus.ONLINE)
    }
}
