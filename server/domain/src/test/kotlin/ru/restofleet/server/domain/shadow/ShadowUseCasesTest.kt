package ru.restofleet.server.domain.shadow

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.server.domain.shadow.usecase.GetShadow
import ru.restofleet.server.domain.shadow.usecase.HandleReported
import ru.restofleet.server.domain.shadow.usecase.UpdateDesired
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue.Num
import ru.restofleet.shared.shadow.ShadowValue.Obj

class ShadowUseCasesTest {
    private val w = World()
    private val update = UpdateDesired(w.devices, w.shadows, w.publisher, w.tx, w.clock)
    private val reported = HandleReported(w.devices, w.shadows, w.tx, w.clock)
    private val get = GetShadow(w.devices, w.shadows, w.tx)
    private val id = deviceId("d-1")

    private fun interval(sec: Int) = ShadowDocument(mapOf("telemetry" to Obj(mapOf("intervalSec" to Num(sec)))))

    @Test
    fun updateBumpsVersionLocksAndPublishesAfterCommit() = runTest {
        w.device()
        w.shadow("d-1", version = 1)
        val result = (update(id, interval(60)) as Outcome.Success).value
        assertThat(result.published).isTrue()
        assertThat(result.shadow.desiredVersion).isEqualTo(2)
        assertThat(w.shadows.lockedFor).contains(id)
        val stored = w.db.state.shadows.getValue(id)
        assertThat((stored.desired["telemetry"] as Obj).fields["intervalSec"]).isEqualTo(Num(60))
        assertThat(w.publisher.desired.single()).isEqualTo(id to DesiredState(2, stored.desired))
    }

    @Test
    fun updateWithoutShadowRowStartsAtVersionOne() = runTest {
        w.device()
        val result = (update(id, interval(60)) as Outcome.Success).value
        assertThat(result.shadow.desiredVersion).isEqualTo(1)
    }

    @Test
    fun noOpPatchKeepsVersionButRepublishes() = runTest {
        w.device()
        w.shadow("d-1", version = 4)
        val result = (update(id, ShadowDocument.EMPTY) as Outcome.Success).value
        assertThat(result.shadow.desiredVersion).isEqualTo(4)
        assertThat(w.publisher.desired.single().second.version).isEqualTo(4)
    }

    @Test
    fun invalidPatchRollsBack() = runTest {
        w.device()
        w.shadow("d-1", version = 1)
        assertThat(update(id, interval(5)))
            .isEqualTo(
                Outcome.Failure(
                    ServerError.Validation("telemetryIntervalSec", "telemetryIntervalSec:out_of_range"),
                ),
            )
        assertThat(w.db.state.shadows.getValue(id).desiredVersion).isEqualTo(1)
        assertThat(w.publisher.desired).isEmpty()
    }

    @Test
    fun publishFailureKeepsSavedShadow() = runTest {
        w.device()
        w.shadow("d-1", version = 1)
        w.publisher.fail = true
        val result = (update(id, interval(60)) as Outcome.Success).value
        assertThat(result.published).isFalse()
        assertThat(w.db.state.shadows.getValue(id).desiredVersion).isEqualTo(2)
    }

    @Test
    fun updateRejectsUnknownAndDecommissioned() = runTest {
        assertThat(update(id, interval(60))).isEqualTo(Outcome.Failure(ServerError.NotFound("device", "d-1")))
        w.device(status = DeviceStatus.DECOMMISSIONED)
        assertThat(update(id, interval(60))).isEqualTo(Outcome.Failure(ServerError.DeviceDecommissioned("d-1")))
    }

    @Test
    fun reportedIsStoredAndDeltaEmpties() = runTest {
        w.device()
        val shadow = w.shadow("d-1", version = 2)
        assertThat(shadow.delta).isEqualTo(shadow.desired)
        val report = ReportedShadow(id, shadow.desired, appliedVersion = 2, appliedAt = 77, errors = listOf("e"))
        assertThat(reported(report)).isEqualTo(IngestOutcome.APPLIED)
        val stored = w.db.state.shadows.getValue(id)
        assertThat(stored.reportedVersion).isEqualTo(2)
        assertThat(stored.appliedAt).isEqualTo(77)
        assertThat(stored.reportedAt).isEqualTo(w.clock.now)
        assertThat(stored.errors).containsExactly("e")
        assertThat(stored.delta.isEmpty).isTrue()
        assertThat(stored.isPending).isFalse()
    }

    @Test
    fun olderReportIsIgnored() = runTest {
        w.device()
        w.shadow("d-1", version = 3, reportedVersion = 3)
        assertThat(reported(ReportedShadow(id, ShadowDocument.EMPTY, 2, 1, emptyList())))
            .isEqualTo(IngestOutcome.IGNORED_STALE)
        assertThat(w.db.state.shadows.getValue(id).reportedVersion).isEqualTo(3)
    }

    @Test
    fun reportWithoutShadowRowCreatesIt() = runTest {
        w.device()
        assertThat(reported(ReportedShadow(id, DefaultDesired.document, 1, 1, emptyList())))
            .isEqualTo(IngestOutcome.APPLIED)
        assertThat(w.db.state.shadows.getValue(id).desiredVersion).isEqualTo(0)
    }

    @Test
    fun reportFromUnknownDeviceDropped() = runTest {
        assertThat(reported(ReportedShadow(id, ShadowDocument.EMPTY, 1, 1, emptyList())))
            .isEqualTo(IngestOutcome.DROPPED_UNKNOWN_DEVICE)
    }

    @Test
    fun getShadowWithAndWithoutRow() = runTest {
        assertThat(get(id)).isEqualTo(Outcome.Failure(ServerError.NotFound("device", "d-1")))
        w.device()
        val empty = (get(id) as Outcome.Success).value
        assertThat(empty.desiredVersion).isEqualTo(0)
        assertThat(empty.isPending).isFalse()
        val stored = w.shadow("d-1", version = 2)
        assertThat(get(id)).isEqualTo(Outcome.Success(stored))
    }
}
