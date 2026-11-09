package ru.restofleet.agent.domain.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.port.BrokerSession
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.agent.domain.port.ProcessedCommands
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Общий журнал вызовов: порядок событий между фейками проверяется по нему. */
class CallLog {
    val entries = mutableListOf<String>()

    operator fun plusAssign(entry: String) {
        entries += entry
    }
}

class FakeBrokerSession(initial: SessionState = SessionState.CONNECTED) : BrokerSession {
    override val state = MutableStateFlow(initial)
    override val commands: Flow<IncomingCommand> = emptyFlow()
    override val desired: Flow<DesiredShadow> = emptyFlow()

    override suspend fun connect(): Outcome<Unit, DomainError> = Outcome.Success(Unit)

    override suspend fun disconnect() = Unit
}

class FakeMessagePublisher(private val log: CallLog = CallLog()) : MessagePublisher {
    val published = mutableListOf<OutgoingMessage>()
    var result: suspend (OutgoingMessage) -> Outcome<Unit, DomainError> = { Outcome.Success(Unit) }

    /** Вызовы, в том числе неудачные: сообщение попадает в [published] только при успехе. */
    var calls = 0

    override suspend fun publish(message: OutgoingMessage): Outcome<Unit, DomainError> {
        calls++
        log += "publish:${String(message.payload)}"
        val outcome = result(message)
        if (outcome is Outcome.Success) published += message
        return outcome
    }
}

class FakeOutbox(private val log: CallLog = CallLog()) : Outbox {
    private val records = LinkedHashMap<Long, OutgoingMessage>()
    private var nextId = 1L

    /** Все аргументы `remove`, по вызовам. */
    val removeCalls = mutableListOf<List<Long>>()
    val enqueued = mutableListOf<OutgoingMessage>()

    val messages: List<OutgoingMessage> get() = records.values.toList()

    fun ids(): List<Long> = records.keys.toList()

    override suspend fun enqueue(m: OutgoingMessage) {
        log += "enqueue:${String(m.payload)}"
        enqueued += m
        records[nextId++] = m
    }

    /** Как `RoomOutbox`: сначала приоритет (`MessageKind.priority`, 0 — первым), внутри него порядок постановки. */
    override suspend fun peek(limit: Int): List<Pair<Long, OutgoingMessage>> =
        records.entries.sortedBy { it.value.kind.priority }.take(limit).map { it.key to it.value }

    override suspend fun peek(limit: Int, kind: MessageKind): List<Pair<Long, OutgoingMessage>> =
        records.entries.filter { it.value.kind == kind }.take(limit).map { it.key to it.value }

    override suspend fun remove(ids: List<Long>) {
        removeCalls += ids
        ids.forEach { records.remove(it) }
    }

    override suspend fun size(): Int = records.size
}

class FakeProcessedCommands(private val log: CallLog = CallLog()) : ProcessedCommands {
    val ids = mutableSetOf<String>()

    override suspend fun isProcessed(cmdId: String): Boolean = cmdId in ids

    override suspend fun markProcessed(cmdId: String) {
        log += "mark:$cmdId"
        ids += cmdId
    }
}

class FakeShadowStore : ShadowStore {
    var version: Long? = null
    val saved = mutableListOf<DesiredShadow>()

    override suspend fun appliedVersion(): Long? = version

    override suspend fun save(shadow: DesiredShadow) {
        saved += shadow
        version = shadow.version
    }

    override suspend fun load(): DesiredShadow? = saved.lastOrNull()

    override fun observe(): kotlinx.coroutines.flow.Flow<DesiredShadow?> = kotlinx.coroutines.flow.emptyFlow()
}

/** Пишет читаемые маркеры вместо JSON: тесты 3.4 проверяют поведение use case, а не байты. */
class FakePayloadEncoder : PayloadEncoder {
    /** Если задан и вернул не null — `commandResult` бросает это исключение. */
    var commandResultFailure: (String) -> Throwable? = { null }

    override fun telemetry(s: TelemetrySnapshot): ByteArray = "telemetry:${s.seq}".toByteArray()

    override fun probes(r: List<ProbeResult>): ByteArray = "probes:${r.size}".toByteArray()

    override fun commandResult(cmdId: String, status: String, outcome: CommandOutcome?, ts: Long): ByteArray {
        commandResultFailure(status)?.let { throw it }
        return "res:$cmdId:$status:$outcome:$ts".toByteArray()
    }

    override fun reported(s: DesiredShadow, appliedAt: Long, errors: List<String>): ByteArray =
        ("reported:v${s.version}:i${s.telemetryIntervalSec}:p${s.probes.joinToString(",") { it.id }}:" +
            "e$errors:at$appliedAt").toByteArray()

    override fun event(e: AgentEvent): ByteArray = "event:${e.ts}".toByteArray()
}
