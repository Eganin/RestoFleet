package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.FlushResult
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.BrokerSession
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.usecase.ApplyDesiredShadow
import ru.restofleet.agent.domain.usecase.FlushOutbox
import ru.restofleet.agent.domain.usecase.HandleIncomingCommand
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

internal class MemoryOutbox : Outbox {
    private val records = LinkedHashMap<Long, OutgoingMessage>()
    private var next = 1L
    val messages get() = records.values.toList()

    override suspend fun enqueue(m: OutgoingMessage) {
        records[next++] = m
    }

    /** Как `RoomOutbox`: сначала приоритет, внутри него порядок постановки. */
    override suspend fun peek(limit: Int) =
        records.entries.sortedBy { it.value.kind.priority }.take(limit).map { it.key to it.value }

    override suspend fun peek(limit: Int, kind: MessageKind) =
        records.entries.filter { it.value.kind == kind }.take(limit).map { it.key to it.value }

    override suspend fun remove(ids: List<Long>) {
        ids.forEach { records.remove(it) }
    }

    override suspend fun size() = records.size
}

/** Сессия-двойник: потоки ведут себя как у реальной (ack после возврата потребителя) и пишут события в [trace]. */
internal class ScriptedSession(private val trace: MutableList<String>) : BrokerSession {
    override val state = MutableStateFlow(SessionState.CONNECTED)
    val commandChannel = Channel<IncomingCommand>(Channel.UNLIMITED)
    val desiredChannel = Channel<DesiredShadow>(Channel.UNLIMITED)
    var connects = 0
    var disconnects = 0

    override val commands: Flow<IncomingCommand> = flow {
        for (c in commandChannel) {
            emit(c)
            trace += "ack:${c.cmdId}"
        }
    }
    override val desired: Flow<DesiredShadow> = flow {
        for (d in desiredChannel) {
            emit(d)
            trace += "ack:v${d.version}"
        }
    }

    override suspend fun connect(): Outcome<Unit, DomainError> {
        connects++
        return Outcome.Success(Unit)
    }

    override suspend fun disconnect() {
        disconnects++
        trace += "disconnect"
    }
}

class SessionSupervisorTest {
    private val trace = mutableListOf<String>()
    private val session = ScriptedSession(trace)
    private val outbox = MemoryOutbox()
    private val epoch = MutableStateFlow(0)
    private val handle = mockk<HandleIncomingCommand>()
    private val applyShadow = mockk<ApplyDesiredShadow>()
    private val published = mutableListOf<String>()
    private val publisher = object : MessagePublisher {
        override suspend fun publish(message: OutgoingMessage): Outcome<Unit, DomainError> {
            trace += "publish:${message.payload.decodeToString()}"
            published += message.payload.decodeToString()
            return Outcome.Success(Unit)
        }
    }
    private val flush = FlushOutbox(publisher, outbox)

    private fun cmd(id: String) = IncomingCommand(id, Command.Ping, "ping", null, null, null)

    private fun msg(text: String) = OutgoingMessage(MessageKind.EVENT, text.toByteArray(), 1, createdAt = 1)

    private fun result(text: String) = OutgoingMessage(MessageKind.COMMAND_RESULT, text.toByteArray(), 1, createdAt = 1)

    private fun TestScope.supervisor(log: MutableList<String> = mutableListOf()) = SessionSupervisor(
        scope = backgroundScope,
        session = session,
        connectedEpoch = epoch,
        handleCommand = handle,
        applyDesired = applyShadow,
        flush = flush,
        outbox = outbox,
        log = { log += it },
    )

    @Test
    fun startConnectsTheSession() = runTest {
        coEvery { handle(any()) } returns Unit
        coEvery { applyShadow(any()) } returns Unit

        supervisor().start()
        runCurrent()

        assertThat(session.connects).isEqualTo(1)
    }

    @Test
    fun startTwiceStartsOnce() = runTest {
        val s = supervisor()

        s.start()
        s.start()
        runCurrent()

        assertThat(session.connects).isEqualTo(1)
    }

    @Test
    fun commandsAreHandledInOrderAndAckedAfterEachHandler() = runTest {
        coEvery { handle(any()) } coAnswers {
            trace += "handle:${firstArg<IncomingCommand>().cmdId}"
            delay(50)
        }
        supervisor().start()
        runCurrent()

        session.commandChannel.trySend(cmd("c1"))
        session.commandChannel.trySend(cmd("c2"))
        advanceTimeBy(500)
        runCurrent()

        assertThat(trace.filter { it.startsWith("handle") || it.startsWith("ack") })
            .containsExactly("handle:c1", "ack:c1", "handle:c2", "ack:c2").inOrder()
    }

    @Test
    fun rebootResultIsFlushedBeforeAck() = runTest {
        // Как у HandleIncomingCommand для Reboot: итог succeeded (COMMAND_RESULT) кладётся только в outbox
        coEvery { handle(any()) } coAnswers { outbox.enqueue(result("reboot-succeeded")) }
        supervisor().start()
        runCurrent()

        session.commandChannel.trySend(cmd("c-reboot"))
        runCurrent()

        assertThat(trace.filter { it.startsWith("publish") || it.startsWith("ack") })
            .containsExactly("publish:reboot-succeeded", "ack:c-reboot").inOrder()
        assertThat(outbox.size()).isEqualTo(0)
    }

    // I3: после долгого офлайна команды не ждут конца досылки (1000 записей при 20/с — 50 с)
    @Test
    fun commandResultsDuringALongDrainGoOutWithinTheNextBatchAndCommandsKeepFlowing() = runTest {
        repeat(1_000) { outbox.enqueue(OutgoingMessage(MessageKind.TELEMETRY, "t$it".toByteArray(), 1, createdAt = 1)) }
        // Как PublishOrBuffer при непустом outbox: received и succeeded ложатся в очередь
        coEvery { handle(any()) } coAnswers {
            val id = firstArg<IncomingCommand>().cmdId
            trace += "handle:$id"
            outbox.enqueue(result("received:$id"))
            outbox.enqueue(result("succeeded:$id"))
        }
        supervisor().start()
        runCurrent()
        epoch.value = 1
        advanceTimeBy(1_000)
        runCurrent()

        session.commandChannel.trySend(cmd("c1"))
        session.commandChannel.trySend(cmd("c2"))
        // 6 с от начала досылки: срок команды (expiresAt = now + 300 с) далеко
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(trace).containsAtLeast(
            "handle:c1", "publish:received:c1", "publish:succeeded:c1", "ack:c1",
            "handle:c2", "publish:received:c2", "publish:succeeded:c2", "ack:c2",
        ).inOrder()
        assertThat(published.size).isLessThan(200)

        advanceTimeBy(60_000)
        runCurrent()
        assertThat(published).hasSize(1_004)
        assertThat(outbox.size()).isEqualTo(0)
    }

    @Test
    fun flushIsSkippedAfterCommandWhenOutboxIsEmpty() = runTest {
        coEvery { handle(any()) } returns Unit
        supervisor().start()
        runCurrent()

        session.commandChannel.trySend(cmd("c1"))
        runCurrent()

        assertThat(published).isEmpty()
    }

    @Test
    fun noFlushAfterCommandWhileDisconnected() = runTest {
        session.state.value = SessionState.CONNECTING
        coEvery { handle(any()) } coAnswers { outbox.enqueue(msg("pending")) }
        supervisor().start()
        runCurrent()

        session.commandChannel.trySend(cmd("c1"))
        runCurrent()

        assertThat(published).isEmpty()
        assertThat(trace).contains("ack:c1")
    }

    @Test
    fun handlerFailureDoesNotStopLaterCommandsAndStillAcks() = runTest {
        val log = mutableListOf<String>()
        coEvery { handle(match { it.cmdId == "bad" }) } throws IllegalStateException("boom")
        coEvery { handle(match { it.cmdId == "ok" }) } coAnswers { trace += "handled:ok" }
        supervisor(log).start()
        runCurrent()

        session.commandChannel.trySend(cmd("bad"))
        session.commandChannel.trySend(cmd("ok"))
        runCurrent()

        assertThat(trace).contains("handled:ok")
        assertThat(log.joinToString()).contains("IllegalStateException")
        assertThat(log.joinToString()).doesNotContain("boom")
    }

    @Test
    fun desiredIsAppliedAndFailureIsIsolated() = runTest {
        coEvery { applyShadow(match { it.version == 1L }) } throws IllegalStateException("store")
        coEvery { applyShadow(match { it.version == 2L }) } coAnswers { trace += "applied:2" }
        supervisor().start()
        runCurrent()

        session.desiredChannel.trySend(DesiredShadow(1, 30, emptyList(), emptyList()))
        session.desiredChannel.trySend(DesiredShadow(2, 30, emptyList(), emptyList()))
        runCurrent()

        assertThat(trace).contains("applied:2")
    }

    @Test
    fun everyNewConnectionFlushesTheOutbox() = runTest {
        outbox.enqueue(msg("a"))
        outbox.enqueue(msg("b"))
        supervisor().start()
        runCurrent()
        assertThat(published).isEmpty()

        epoch.value = 1
        advanceTimeBy(100) // FlushOutbox: не быстрее 20 сообщений в секунду
        runCurrent()
        assertThat(published).containsExactly("a", "b").inOrder()

        outbox.enqueue(msg("c"))
        epoch.value = 2
        advanceTimeBy(100)
        runCurrent()
        assertThat(published).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun flushFailureAfterConnectionIsIsolated() = runTest {
        val broken = mockk<FlushOutbox>()
        coEvery { broken() } throws IllegalStateException("db")
        val log = mutableListOf<String>()
        SessionSupervisor(backgroundScope, session, epoch, handle, applyShadow, broken, outbox, log = { log += it })
            .start()
        runCurrent()

        epoch.value = 1
        runCurrent()
        epoch.value = 2
        runCurrent()

        coVerify(exactly = 2) { broken() }
    }

    @Test
    fun periodicFlushRunsEveryMinuteOnlyWhileConnectedAndNotEmpty() = runTest {
        supervisor().start()
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()
        assertThat(published).isEmpty()

        outbox.enqueue(msg("late"))
        session.state.value = SessionState.CONNECTING
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(published).isEmpty()

        session.state.value = SessionState.CONNECTED
        advanceTimeBy(59_999)
        runCurrent()
        assertThat(published).isEmpty()
        advanceTimeBy(1)
        runCurrent()
        assertThat(published).containsExactly("late")
    }

    @Test
    fun stopDisconnectsAndHaltsAllLoops() = runTest {
        coEvery { handle(any()) } coAnswers { trace += "handled" }
        val s = supervisor()
        s.start()
        runCurrent()

        s.stop()
        session.commandChannel.trySend(cmd("c-after"))
        outbox.enqueue(msg("after"))
        epoch.value = 5
        advanceTimeBy(120_000)
        runCurrent()

        assertThat(session.disconnects).isEqualTo(1)
        assertThat(trace).doesNotContain("handled")
        assertThat(published).isEmpty()
    }

    @Test
    fun canBeStartedAgainAfterStop() = runTest {
        val s = supervisor()
        s.start()
        runCurrent()
        s.stop()

        s.start()
        runCurrent()

        assertThat(session.connects).isEqualTo(2)
    }
}
