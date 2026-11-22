package ru.restofleet.server.domain.command

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.command.usecase.ExpireCommands
import ru.restofleet.server.domain.command.usecase.GetCommand
import ru.restofleet.server.domain.command.usecase.HandleCommandResult
import ru.restofleet.server.domain.command.usecase.SendCommand
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

class CommandUseCasesTest {
    private val w = World()
    private val send = SendCommand(w.devices, w.commands, w.publisher, w.tx, w.ids, w.clock)
    private val handle = HandleCommandResult(w.commands, w.tx, w.clock)
    private val expire = ExpireCommands(w.commands, w.tx, w.clock)
    private val get = GetCommand(w.commands, w.tx)
    private val id = deviceId("d-1")

    private fun report(status: ReportedCommandStatus, cmdId: String = "c-1", device: String = "d-1") =
        CommandReport(deviceId(device), cmdId, status, 5, result = mapOf("k" to "v"), reason = "r")

    @Test
    fun sendStoresQueuedAndPublishesAfterCommit() = runTest {
        w.device(status = DeviceStatus.OFFLINE)
        val params = ShadowDocument(mapOf("text" to ShadowValue.Str("hi")))
        val command = (send(CommandRequest(id, "show_message", params, ttlSec = 60)) as Outcome.Success).value
        assertThat(command.cmdId).isEqualTo("c-id-1")
        assertThat(command.status).isEqualTo(CommandStatus.QUEUED)
        assertThat(command.expiresAt).isEqualTo(w.clock.now + 60_000)
        assertThat(command.expirySecondsAt(w.clock.now)).isEqualTo(60)
        assertThat(w.db.state.commands.getValue(command.cmdId)).isEqualTo(command)
        assertThat(w.publisher.commands).containsExactly(command)
    }

    @Test
    fun sendValidates() = runTest {
        w.device()
        assertThat(send(CommandRequest(id, "Bad-Type")))
            .isEqualTo(Outcome.Failure(ServerError.Validation("type", "invalid")))
        assertThat(send(CommandRequest(id, "ping", ttlSec = 0)))
            .isEqualTo(Outcome.Failure(ServerError.Validation("ttlSec", "out_of_range")))
        assertThat(send(CommandRequest(id, "ping", ttlSec = 86_401)))
            .isEqualTo(Outcome.Failure(ServerError.Validation("ttlSec", "out_of_range")))
        assertThat(send(CommandRequest(deviceId("d-x"), "ping")))
            .isEqualTo(Outcome.Failure(ServerError.NotFound("device", "d-x")))
        assertThat(w.db.state.commands).isEmpty()
    }

    @Test
    fun sendToDecommissionedRejected() = runTest {
        w.device(status = DeviceStatus.DECOMMISSIONED)
        assertThat(send(CommandRequest(id, "ping"))).isEqualTo(Outcome.Failure(ServerError.DeviceDecommissioned("d-1")))
    }

    @Test
    fun publishFailureFinishesCommandAsBrokerUnavailable() = runTest {
        w.device()
        w.publisher.fail = true
        assertThat(send(CommandRequest(id, "ping")))
            .isEqualTo(Outcome.Failure(ServerError.BrokerUnavailable("publish")))
        val stored = w.db.state.commands.values.single()
        assertThat(stored.status).isEqualTo(CommandStatus.FAILED)
        assertThat(stored.reason).isEqualTo(CommandReasons.BROKER_UNAVAILABLE)
        assertThat(stored.finishedAt).isEqualTo(w.clock.now)
    }

    @Test
    fun receivedThenSucceeded() = runTest {
        w.command()
        w.clock.now += 100
        assertThat(handle(report(ReportedCommandStatus.RECEIVED))).isEqualTo(IngestOutcome.APPLIED)
        val delivered = w.db.state.commands.getValue("c-1")
        assertThat(delivered.status).isEqualTo(CommandStatus.DELIVERED)
        assertThat(delivered.deliveredAt).isEqualTo(w.clock.now)
        assertThat(handle(report(ReportedCommandStatus.RECEIVED))).isEqualTo(IngestOutcome.IGNORED_STALE)
        w.clock.now += 100
        assertThat(handle(report(ReportedCommandStatus.SUCCEEDED))).isEqualTo(IngestOutcome.APPLIED)
        val done = w.db.state.commands.getValue("c-1")
        assertThat(done.status).isEqualTo(CommandStatus.SUCCEEDED)
        assertThat(done.deliveredAt).isEqualTo(delivered.deliveredAt)
        assertThat(done.finishedAt).isEqualTo(w.clock.now)
        assertThat(done.result).containsExactly("k", "v")
        // Повтор итога и поздний received — без изменений
        assertThat(handle(report(ReportedCommandStatus.FAILED))).isEqualTo(IngestOutcome.IGNORED_STALE)
        assertThat(handle(report(ReportedCommandStatus.RECEIVED))).isEqualTo(IngestOutcome.IGNORED_STALE)
        assertThat(w.db.state.commands.getValue("c-1")).isEqualTo(done)
    }

    @Test
    fun finalWithoutReceivedSetsDeliveredAt() = runTest {
        w.command()
        handle(report(ReportedCommandStatus.UNSUPPORTED))
        val c = w.db.state.commands.getValue("c-1")
        assertThat(c.status).isEqualTo(CommandStatus.UNSUPPORTED)
        assertThat(c.deliveredAt).isEqualTo(w.clock.now)
        assertThat(c.reason).isEqualTo("r")
    }

    @Test
    fun lateFinalBeatsServerSideFinals() = runTest {
        w.command("c-1", status = CommandStatus.EXPIRED, reason = CommandReasons.EXPIRED)
        w.command("c-2", status = CommandStatus.FAILED, reason = CommandReasons.BROKER_UNAVAILABLE)
        w.command("c-3", status = CommandStatus.FAILED, reason = "timeout")
        assertThat(handle(report(ReportedCommandStatus.SUCCEEDED, "c-1"))).isEqualTo(IngestOutcome.APPLIED)
        assertThat(handle(report(ReportedCommandStatus.FAILED, "c-2"))).isEqualTo(IngestOutcome.APPLIED)
        assertThat(handle(report(ReportedCommandStatus.SUCCEEDED, "c-3"))).isEqualTo(IngestOutcome.IGNORED_STALE)
        assertThat(handle(report(ReportedCommandStatus.RECEIVED, "c-1"))).isEqualTo(IngestOutcome.IGNORED_STALE)
        assertThat(w.db.state.commands.getValue("c-1").status).isEqualTo(CommandStatus.SUCCEEDED)
        assertThat(w.db.state.commands.getValue("c-2").reason).isEqualTo("r")
    }

    @Test
    fun foreignOrUnknownCommandDropped() = runTest {
        w.command()
        assertThat(handle(report(ReportedCommandStatus.SUCCEEDED, device = "d-2")))
            .isEqualTo(IngestOutcome.DROPPED_UNKNOWN_DEVICE)
        assertThat(handle(report(ReportedCommandStatus.SUCCEEDED, cmdId = "c-x")))
            .isEqualTo(IngestOutcome.DROPPED_UNKNOWN_DEVICE)
        assertThat(w.db.state.commands.getValue("c-1").status).isEqualTo(CommandStatus.QUEUED)
    }

    @Test
    fun expireDueOnly() = runTest {
        w.command("c-1", expiresAt = w.clock.now - 1)
        w.command("c-2", status = CommandStatus.DELIVERED, expiresAt = w.clock.now - 1)
        w.command("c-3", expiresAt = w.clock.now + 1)
        w.command("c-4", status = CommandStatus.SUCCEEDED, expiresAt = w.clock.now - 1)
        assertThat(expire()).isEqualTo(2)
        val s = w.db.state.commands
        assertThat(s.getValue("c-1").status).isEqualTo(CommandStatus.EXPIRED)
        assertThat(s.getValue("c-1").reason).isEqualTo(CommandReasons.EXPIRED)
        assertThat(s.getValue("c-2").status).isEqualTo(CommandStatus.EXPIRED)
        assertThat(s.getValue("c-3").status).isEqualTo(CommandStatus.QUEUED)
        assertThat(s.getValue("c-4").status).isEqualTo(CommandStatus.SUCCEEDED)
    }

    @Test
    fun getCommand() = runTest {
        val c = w.command()
        assertThat(get("c-1")).isEqualTo(Outcome.Success(c))
        assertThat(get("c-x")).isEqualTo(Outcome.Failure(ServerError.NotFound("command", "c-x")))
    }

    @Test
    fun expirySecondsRoundsUpAndIsAtLeastOne() {
        val c = w.command(expiresAt = 10_500)
        assertThat(c.expirySecondsAt(10_000)).isEqualTo(1)
        assertThat(c.expirySecondsAt(8_000)).isEqualTo(3)
        assertThat(c.expirySecondsAt(20_000)).isEqualTo(1)
    }

    @Test
    fun statusCodes() {
        assertThat(CommandStatus.fromCode("expired")).isEqualTo(CommandStatus.EXPIRED)
        assertThat(CommandStatus.fromCode("x")).isNull()
        assertThat(CommandStatus.entries.filter { it.isFinal }).hasSize(4)
    }
}
