package ru.restofleet.server.domain.presence

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.server.domain.presence.usecase.HandleStatus

class HandleStatusTest {
    private val w = World()
    private val handle = HandleStatus(w.devices, w.sessions, w.shadows, w.publisher, w.tx, w.clock)
    private val id = deviceId("d-1")

    private fun online(connId: String, info: DeviceInfo? = null) = StatusReport(
        id, PresenceState.ONLINE, 1, connId, announcement = DeviceAnnouncement("2.0.0", "device_owner", info),
    )

    private fun offline(connId: String, reason: String? = "lwt") =
        StatusReport(id, PresenceState.OFFLINE, 2, connId, reason)

    @Test
    fun onlineOpensSessionAndMarksDevice() = runTest {
        w.device()
        val info = DeviceInfo("Tab", "Samsung", "8.0", 26)
        assertThat(handle(online("k-1", info))).isEqualTo(IngestOutcome.APPLIED)
        val d = w.db.state.devices.getValue(id)
        assertThat(d.status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(d.transport).isEqualTo(DeviceTransport.MQTT)
        assertThat(d.lastSeenAt).isEqualTo(w.clock.now)
        assertThat(d.agentVersion).isEqualTo("2.0.0")
        assertThat(d.mode).isEqualTo("device_owner")
        assertThat(d.info).isEqualTo(info)
        assertThat(w.db.state.sessions).containsExactly(DeviceSession(id, "k-1", w.clock.now, null, null))
    }

    @Test
    fun announcementWithoutInfoKeepsStoredInfo() = runTest {
        w.device()
        handle(online("k-1"))
        assertThat(w.db.state.devices.getValue(id).info).isEqualTo(World.INFO)
    }

    @Test
    fun offlineWithSameConnIdClosesSession() = runTest {
        w.device()
        handle(online("k-1"))
        w.clock.now += 50_000
        assertThat(handle(offline("k-1"))).isEqualTo(IngestOutcome.APPLIED)
        assertThat(w.db.state.devices.getValue(id).status).isEqualTo(DeviceStatus.OFFLINE)
        val session = w.db.state.sessions.single()
        assertThat(session.disconnectedAt).isEqualTo(w.clock.now)
        assertThat(session.reason).isEqualTo("lwt")
    }

    @Test
    fun lateLwtOfOldConnectionIsIgnored() = runTest {
        w.device()
        handle(online("k-1"))
        w.clock.now += 10
        handle(online("k-2"))
        assertThat(handle(offline("k-1"))).isEqualTo(IngestOutcome.IGNORED_STALE)
        assertThat(w.db.state.devices.getValue(id).status).isEqualTo(DeviceStatus.ONLINE)
        val sessions = w.db.state.sessions.associateBy { it.connId }
        assertThat(sessions.getValue("k-1").reason).isEqualTo(SessionCloseReason.REPLACED)
        assertThat(sessions.getValue("k-2").isOpen).isTrue()
    }

    @Test
    fun redeliveredOnlineIsIdempotentAndClosedSessionIsNotReopened() = runTest {
        w.device()
        handle(online("k-1"))
        assertThat(handle(online("k-1"))).isEqualTo(IngestOutcome.APPLIED)
        assertThat(w.db.state.sessions).hasSize(1)
        handle(offline("k-1", reason = null))
        assertThat(w.db.state.sessions.single().reason).isEqualTo("unknown")
        assertThat(handle(online("k-1"))).isEqualTo(IngestOutcome.IGNORED_STALE)
        assertThat(w.db.state.devices.getValue(id).status).isEqualTo(DeviceStatus.OFFLINE)
    }

    @Test
    fun offlineWithoutAnySessionMarksOffline() = runTest {
        w.device(status = DeviceStatus.ONLINE)
        assertThat(handle(offline("k-9", "stop"))).isEqualTo(IngestOutcome.APPLIED)
        assertThat(w.db.state.devices.getValue(id).status).isEqualTo(DeviceStatus.OFFLINE)
        assertThat(w.db.state.sessions).isEmpty()
    }

    @Test
    fun unknownOrDecommissionedDropped() = runTest {
        assertThat(handle(online("k-1"))).isEqualTo(IngestOutcome.DROPPED_UNKNOWN_DEVICE)
        w.device(status = DeviceStatus.DECOMMISSIONED)
        assertThat(handle(online("k-1"))).isEqualTo(IngestOutcome.DROPPED_UNKNOWN_DEVICE)
        assertThat(w.db.state.sessions).isEmpty()
    }

    @Test
    fun onlineRepublishesPendingDesiredAfterCommit() = runTest {
        w.device()
        w.shadow("d-1", version = 3, reportedVersion = 2)
        handle(online("k-1"))
        assertThat(w.publisher.desired.single().second.version).isEqualTo(3)
    }

    @Test
    fun onlineDoesNotRepublishAppliedDesired() = runTest {
        w.device()
        w.shadow("d-1", version = 3, reportedVersion = 3)
        handle(online("k-1"))
        assertThat(w.publisher.desired).isEmpty()
    }
}
