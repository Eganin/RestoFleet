package ru.restofleet.server.domain.ingest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.ingest.usecase.GetEventHistory
import ru.restofleet.server.domain.ingest.usecase.GetProbeHistory
import ru.restofleet.server.domain.ingest.usecase.GetTelemetryHistory
import ru.restofleet.server.domain.ingest.usecase.IngestBatch
import ru.restofleet.shared.kernel.Outcome

class IngestUseCasesTest {
    private val w = World()
    private val ingest = IngestBatch(w.devices, w.telemetry, w.tx, w.clock)
    private val telemetryHistory = GetTelemetryHistory(w.devices, w.telemetry, w.tx)
    private val probeHistory = GetProbeHistory(w.devices, w.telemetry, w.tx)
    private val eventHistory = GetEventHistory(w.devices, w.telemetry, w.tx)

    @Test
    fun emptyBatchDoesNotOpenTransaction() = runTest {
        assertThat(ingest(DeviceDataBatch())).isEqualTo(IngestResult(0, 0, 0, 0))
        assertThat(w.tx.commits).isEqualTo(0)
    }

    @Test
    fun storesActiveDropsUnknownAndDecommissioned() = runTest {
        w.device("d-1")
        w.device("d-old", status = DeviceStatus.DECOMMISSIONED)
        val batch = DeviceDataBatch(
            telemetry = listOf(t("d-1", 1), t("d-1", 2), t("d-x", 1), t("d-old", 1)),
            probes = listOf(probe("d-1", 1, "api", "kds"), probe("d-x", 1, "api")),
            events = listOf(event("d-1", 1), event("d-old", 2)),
        )
        assertThat(ingest(batch)).isEqualTo(IngestResult(telemetry = 2, probeResults = 2, events = 1, dropped = 4))
        assertThat(w.db.state.telemetry.map { it.deviceId.value }).containsExactly("d-1", "d-1")
        assertThat(w.db.state.devices.getValue(deviceId("d-1")).lastSeenAt).isEqualTo(w.clock.now)
        assertThat(w.db.state.devices.getValue(deviceId("d-old")).lastSeenAt).isNull()
    }

    @Test
    fun duplicateTelemetryIsNotCountedTwice() = runTest {
        w.device("d-1")
        ingest(DeviceDataBatch(telemetry = listOf(t("d-1", 1))))
        val again = ingest(DeviceDataBatch(telemetry = listOf(t("d-1", 1)), probes = listOf(probe("d-1", 1, "api"))))
        assertThat(again).isEqualTo(IngestResult(0, 1, 0, 0))
        assertThat(w.db.state.telemetry).hasSize(1)
    }

    @Test
    fun allDroppedDoesNotTouch() = runTest {
        assertThat(ingest(DeviceDataBatch(events = listOf(event("d-x", 1))))).isEqualTo(IngestResult(0, 0, 0, 1))
    }

    @Test
    fun historiesFilterNewestFirst() = runTest {
        w.device("d-1")
        ingest(
            DeviceDataBatch(
                telemetry = listOf(t("d-1", 1), t("d-1", 2), t("d-1", 3)),
                probes = listOf(probe("d-1", 1, "api", "kds"), probe("d-1", 2, "api")),
                events = listOf(event("d-1", 1), event("d-1", 2, type = "net_changed")),
            ),
        )
        val id = deviceId("d-1")
        val history = (telemetryHistory(id, TimeRange(from = 2)) as Outcome.Success).value
        assertThat(history.map { it.ts }).containsExactly(3L, 2L).inOrder()
        val firstPage = telemetryHistory(id, TimeRange(), PageRequest.of(limit = 1)) as Outcome.Success
        assertThat(firstPage.value.map { it.ts })
            .containsExactly(3L)
        val probes = (probeHistory(id, TimeRange(to = 2), target = "kds") as Outcome.Success).value
        assertThat(probes.map { it.result.target to it.ts }).containsExactly("kds" to 1L)
        assertThat((probeHistory(id, TimeRange()) as Outcome.Success).value).hasSize(3)
        val events = (eventHistory(id, TimeRange(), type = "net_changed") as Outcome.Success).value
        assertThat(events.map { it.ts }).containsExactly(2L)
        assertThat((eventHistory(id, TimeRange()) as Outcome.Success).value).hasSize(2)

        val missing = deviceId("d-x")
        val notFound = Outcome.Failure(ServerError.NotFound("device", "d-x"))
        assertThat(telemetryHistory(missing, TimeRange())).isEqualTo(notFound)
        assertThat(probeHistory(missing, TimeRange())).isEqualTo(notFound)
        assertThat(eventHistory(missing, TimeRange())).isEqualTo(notFound)
    }

    private fun t(id: String, ts: Long) =
        TelemetrySnapshot(deviceId(id), ts, ts, 80, false, 31.5, "NONE", -50, 1_000, 500, "com.android.chrome", "{}")

    private fun probe(id: String, ts: Long, vararg targets: String) = ProbeReport(
        deviceId(id),
        ts,
        targets.map { ProbeResult(it, true, 200, null, 100, 1, 2, 3, 4) },
    )

    private fun event(id: String, ts: Long, type: String = "probe_state") = DeviceEvent(deviceId(id), ts, type, "{}")
}
