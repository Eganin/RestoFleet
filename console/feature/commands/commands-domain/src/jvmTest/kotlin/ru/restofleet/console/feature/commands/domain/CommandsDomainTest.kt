package ru.restofleet.console.feature.commands.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class CommandsDomainTest {
    private fun record(id: String = "c1", state: CommandState = CommandState.Queued) =
        CommandRecord(id, "ping", state, 0L, null, null, null, emptyMap())

    private class Repo : CommandsRepository {
        var mode: Outcome<String?, ConsoleError> = Outcome.Success("normal")
        var history: Outcome<List<CommandRecord>, ConsoleError> = Outcome.Success(emptyList())
        val sent = mutableListOf<Triple<String, CommandType, Map<String, String>>>()
        val statuses = ArrayDeque<Outcome<CommandRecord, ConsoleError>>()
        var statusCalls = 0
        override suspend fun mode(deviceId: String) = mode
        override suspend fun send(deviceId: String, type: CommandType, params: Map<String, String>): Outcome<CommandRecord, ConsoleError> {
            sent += Triple(deviceId, type, params)
            return Outcome.Success(CommandRecord("c1", type.wire, CommandState.Queued, 0L, null, null, null, emptyMap()))
        }
        override suspend fun history(deviceId: String, limit: Int) = history
        override suspend fun status(cmdId: String): Outcome<CommandRecord, ConsoleError> {
            statusCalls++
            return statuses.removeFirstOrNull() ?: Outcome.Failure(ConsoleError.Network)
        }
    }

    @Test
    fun `availability table by mode`() {
        val available = AvailableCommands()
        fun disabled(mode: String?) = available(mode).associate { it.type to it.disabled }

        val normal = disabled("normal")
        assertThat(normal[CommandType.Ping]).isNull()
        assertThat(normal[CommandType.ShowMessage]).isNull()
        assertThat(normal[CommandType.CollectDiagnostics]).isNull()
        assertThat(normal[CommandType.LaunchApp]).isNull()
        assertThat(normal[CommandType.Reboot]).isEqualTo(Disabled.NeedsDeviceOwner)
        assertThat(normal[CommandType.LockTask]).isEqualTo(Disabled.NeedsDeviceOwner)
        assertThat(disabled(null)[CommandType.Reboot]).isEqualTo(Disabled.NeedsDeviceOwner)

        val owner = disabled("device_owner")
        assertThat(owner[CommandType.Reboot]).isNull()
        assertThat(owner[CommandType.LockTask]).isEqualTo(Disabled.NotInThisStage)
        assertThat(owner[CommandType.InstallUpdate]).isEqualTo(Disabled.NotInThisStage)
        assertThat(available("normal").first { it.type == CommandType.Reboot }.enabled).isFalse()
        assertThat(available("normal").first { it.type == CommandType.Ping }.enabled).isTrue()
    }

    @Test
    fun `wire names round trip and unknown status is not final`() {
        CommandType.entries.forEach { assertThat(CommandType.fromWire(it.wire)).isEqualTo(it) }
        assertThat(CommandType.fromWire("nope")).isNull()
        assertThat(CommandState.fromWire("succeeded").final).isTrue()
        assertThat(CommandState.fromWire("expired").final).isTrue()
        assertThat(CommandState.fromWire("delivered").final).isFalse()
        assertThat(CommandState.fromWire("???")).isEqualTo(CommandState.Queued)
    }

    @Test
    fun `overview combines mode, specs and history and stops at the first failure`() = runTest {
        val repo = Repo().apply { history = Outcome.Success(listOf(record())) }
        val ok = (LoadCommandsOverview(repo, AvailableCommands())("d") as Outcome.Success).value
        assertThat(ok.mode).isEqualTo("normal")
        assertThat(ok.history).hasSize(1)
        assertThat(ok.specs).hasSize(CommandType.entries.size)

        repo.mode = Outcome.Failure(ConsoleError.NotFound)
        assertThat(LoadCommandsOverview(repo, AvailableCommands())("d")).isEqualTo(Outcome.Failure(ConsoleError.NotFound))
        repo.mode = Outcome.Success(null)
        repo.history = Outcome.Failure(ConsoleError.Network)
        assertThat(LoadCommandsOverview(repo, AvailableCommands())("d")).isEqualTo(Outcome.Failure(ConsoleError.Network))
        assertThat(RefreshHistory(repo)("d")).isEqualTo(Outcome.Failure(ConsoleError.Network))
    }

    @Test
    fun `send validates required params, trims, drops empty and unknown ones`() = runTest {
        val repo = Repo()
        val send = SendCommand(repo)

        assertThat(send("d", CommandType.ShowMessage, emptyMap()))
            .isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("text", "empty")))
        assertThat(send("d", CommandType.ShowMessage, mapOf("text" to "   ")))
            .isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("text", "empty")))
        assertThat(repo.sent).isEmpty()

        send("d", CommandType.ShowMessage, mapOf("text" to "  Привет ", "extra" to "x"))
        send("d", CommandType.Ping, mapOf("junk" to "1"))

        assertThat(repo.sent.map { it.third }).containsExactly(mapOf("text" to "Привет"), emptyMap<String, String>()).inOrder()
    }

    private fun TestScope.clock() = Clock { currentTime }

    @Test
    fun `tracking polls every 2 seconds until a final state`() = runTest {
        val repo = Repo().apply {
            statuses += Outcome.Success(record(state = CommandState.Queued))
            statuses += Outcome.Success(record(state = CommandState.Delivered))
            statuses += Outcome.Success(record(state = CommandState.Succeeded))
        }

        TrackCommand(repo, clock())("c1").test {
            assertThat(awaitItem().state).isEqualTo(CommandState.Queued)
            assertThat(awaitItem().state).isEqualTo(CommandState.Delivered)
            assertThat(awaitItem().state).isEqualTo(CommandState.Succeeded)
            awaitComplete()
        }
        assertThat(currentTime).isEqualTo(2 * TrackCommand.INTERVAL_MS)
        assertThat(repo.statusCalls).isEqualTo(3)
    }

    @Test
    fun `tracking gives up after 60 seconds and survives failed polls`() = runTest {
        val repo = Repo()

        TrackCommand(repo, clock())("c1").test { awaitComplete() }

        assertThat(currentTime).isAtLeast(TrackCommand.TIMEOUT_MS)
        assertThat(repo.statusCalls).isEqualTo(31)
    }
}
