package ru.restofleet.server.simulator.support

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.ProbeReport
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.RegistrationReport
import ru.restofleet.server.simulator.domain.model.ReportedShadow
import ru.restofleet.server.simulator.domain.model.RunReport
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.model.TrafficSnapshot
import ru.restofleet.server.simulator.domain.port.ControlApi
import ru.restofleet.server.simulator.domain.port.CredentialStore
import ru.restofleet.server.simulator.domain.port.DeviceConnector
import ru.restofleet.server.simulator.domain.port.DeviceLink
import ru.restofleet.server.simulator.domain.port.Incoming
import ru.restofleet.server.simulator.domain.port.ProbeRunner
import ru.restofleet.server.simulator.domain.port.ResultSink
import ru.restofleet.server.simulator.domain.port.TrafficMeter
import java.util.Collections

fun creds(index: Int, role: DeviceRole = DeviceRole.HALL) = DeviceCredentials("d-$index", "pw-$index", "r-1", role)

/** Запоминает всё, что устройство отправило; входящие подаются через [push], опрос отдаёт [pollQueue]. */
class FakeLink(override val pushesCommands: Boolean, private val openResult: Boolean = true) : DeviceLink {
    private val channel = Channel<Incoming>(Channel.UNLIMITED)
    override val incoming: Flow<Incoming> = channel.receiveAsFlow()

    val telemetry: MutableList<TelemetrySample> = Collections.synchronizedList(mutableListOf())
    val events: MutableList<SimEvent> = Collections.synchronizedList(mutableListOf())
    val probes: MutableList<ProbeReport> = Collections.synchronizedList(mutableListOf())
    val reported: MutableList<ReportedShadow> = Collections.synchronizedList(mutableListOf())
    val replies: MutableList<CommandReply> = Collections.synchronizedList(mutableListOf())
    val pollQueue: MutableList<IncomingCommand> = Collections.synchronizedList(mutableListOf())
    var polls = 0
    var opened: StatusInfo? = null
    var closed = false
    var vanished: LossMode? = null
    var released = false

    fun push(incoming: Incoming) {
        channel.trySend(incoming)
    }

    override suspend fun open(status: StatusInfo): Boolean = openResult.also { opened = status }

    override suspend fun sendTelemetry(sample: TelemetrySample) = telemetry.add(sample)

    override suspend fun sendEvents(events: List<SimEvent>) = this.events.addAll(events)

    override suspend fun sendProbe(report: ProbeReport) = probes.add(report)

    override suspend fun sendReported(reported: ReportedShadow) = this.reported.add(reported)

    override suspend fun reply(reply: CommandReply) = replies.add(reply)

    override suspend fun poll(): List<IncomingCommand> {
        polls++
        return synchronized(pollQueue) { pollQueue.toList().also { pollQueue.clear() } }
    }

    override suspend fun close() {
        closed = true
    }

    override suspend fun vanish(mode: LossMode) {
        vanished = mode
    }

    override suspend fun release() {
        released = true
    }
}

class FakeConnector(private val pushes: Boolean) : DeviceConnector {
    val links = LinkedHashMap<String, FakeLink>()
    val victims = mutableSetOf<String>()
    var shutdown = false

    override fun link(credentials: DeviceCredentials, victim: Boolean): DeviceLink {
        if (victim) victims += credentials.deviceId
        return FakeLink(pushes).also { links[credentials.deviceId] = it }
    }

    override suspend fun shutdown() {
        shutdown = true
    }
}

class FakeProbes(private val ok: Boolean = true) : ProbeRunner {
    val runs = mutableListOf<String>()

    override suspend fun run(target: ProbeTarget): ProbeResult {
        runs += target.id
        return ProbeResult(target.id, ok, if (ok) 200 else null, if (ok) null else "timeout", 7)
    }
}

/** Оператор: [onCommand] решает, что делать с командой (например, доставить её устройству). */
class FakeControl(
    var restaurantIds: List<String> = listOf("r-2", "r-1"),
    private val onCommand: (deviceId: String, cmdId: String) -> Unit = { _, _ -> },
) : ControlApi {
    val issued = mutableListOf<Triple<String, DeviceRole, Int>>()
    val statuses = HashMap<String, String>()
    val deviceStatus = HashMap<String, () -> String?>()
    var failCommands = false

    /** Вызовы в порядке поступления: `prepare@<время>`, `command@<время>` (время — от [now]). */
    val calls = mutableListOf<String>()
    var now: () -> Long = { 0 }
    private var next = 0

    override suspend fun prepare() {
        calls += "prepare@${now()}"
    }

    override suspend fun restaurants() = restaurantIds

    override suspend fun issueCodes(restaurantId: String, role: DeviceRole, count: Int): List<String> {
        issued += Triple(restaurantId, role, count)
        return List(count) { "code-$restaurantId-${role.code}-${next++}" }
    }

    override suspend fun sendCommand(deviceId: String, load: CommandLoad): String? {
        calls += "command@${now()}"
        if (failCommands) return null
        val cmdId = "c-${next++}"
        statuses[cmdId] = "queued"
        onCommand(deviceId, cmdId)
        return cmdId
    }

    override suspend fun commandStatus(cmdId: String) = statuses[cmdId]

    override suspend fun deviceStatus(deviceId: String) = deviceStatus[deviceId]?.invoke() ?: "online"
}

class FakeStore(initial: List<DeviceCredentials> = emptyList()) : CredentialStore {
    val items = initial.toMutableList()

    override fun load() = items.toList()

    override fun append(credentials: DeviceCredentials) {
        items += credentials
    }
}

class FakeSink : ResultSink {
    val registrations = mutableListOf<RegistrationReport>()
    val runs = mutableListOf<RunReport>()

    override fun registration(report: RegistrationReport) {
        registrations += report
    }

    override fun run(report: RunReport) {
        runs += report
    }
}

/** Байты растут на [perRead] при каждом снимке. */
class FakeTraffic(private val perRead: Long = 1_000) : TrafficMeter {
    private var total = 0L

    override fun snapshot(): TrafficSnapshot {
        total += perRead
        return TrafficSnapshot(total, total * 2)
    }
}
