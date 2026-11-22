package ru.restofleet.server.simulator.domain.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.restofleet.server.simulator.domain.device.VirtualDevice
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.CommandRecord
import ru.restofleet.server.simulator.domain.model.DetectionRecord
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.LossTest
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.TimelineRow
import ru.restofleet.server.simulator.domain.model.TransportMode
import ru.restofleet.server.simulator.domain.port.ControlApi
import ru.restofleet.server.simulator.domain.port.DeviceConnector
import ru.restofleet.server.simulator.domain.port.DeviceLink
import ru.restofleet.server.simulator.domain.port.Ticker
import ru.restofleet.server.simulator.domain.port.TrafficMeter
import ru.restofleet.server.simulator.domain.profile.DayClock
import ru.restofleet.server.simulator.domain.profile.profileOf
import ru.restofleet.shared.kernel.Clock
import java.time.ZoneId
import java.util.Collections
import kotlin.random.Random

private data class SentCommand(
    val cmdId: String,
    val deviceId: String,
    val sentAtMillis: Long,
    val sentAtNanos: Long,
)

/**
 * Состояние и шаги одного прогона [RunExperiment]: устройства и их связи, команды измерителя, временной ряд, замер
 * пропажи, остановка. Отдельный класс: в use case остаётся только последовательность фаз.
 */
@Suppress("LongParameterList") // порты прогона передаются из use case как есть
internal class ExperimentRun(
    private val scenario: Scenario,
    private val started: Long,
    private val connector: DeviceConnector,
    private val control: ControlApi,
    private val runner: DeviceRunner,
    private val metrics: RunMetrics,
    private val receipts: CommandReceipts,
    private val traffic: TrafficMeter,
    private val clock: Clock,
    private val ticker: Ticker,
) {
    @Volatile var phase: String = PHASE_RAMP

    @Volatile var sendFailures: Int = 0

    val timeline: MutableList<TimelineRow> = Collections.synchronizedList(mutableListOf())
    private val links = LinkedHashMap<String, DeviceLink>()
    private val jobs = HashMap<String, Job>()
    private val sent = mutableListOf<SentCommand>()
    private val rampRandom = Random(scenario.seed)
    private val commandRandom = Random(scenario.seed + 1)
    private val dayClock = DayClock(ZoneId.of(scenario.timezone), scenario.timeScale, started)
    private val timing = DeviceTiming(
        pollIntervalSec = scenario.pollIntervalSec.takeIf { scenario.mode == TransportMode.REST },
        jitter = scenario.jitter,
    )

    suspend fun start(scope: CoroutineScope, index: Int, credentials: DeviceCredentials, victim: Boolean) {
        val slotMs = MS_IN_SEC.toDouble() / scenario.rampPerSec
        val due = started + (index * slotMs + rampRandom.nextDouble() * slotMs).toLong()
        (due - clock.nowMillis()).takeIf { it > 0 }?.let { delay(it) }
        val link = connector.link(credentials, victim)
        val seed = scenario.seed + index
        val device = VirtualDevice(
            profile = profileOf(credentials.role),
            dayClock = dayClock,
            random = Random(seed),
            startMillis = clock.nowMillis(),
            initialConfig = DeviceConfig(telemetryIntervalSec = scenario.telemetryIntervalSec),
        )
        val status = StatusInfo(credentials.role, AGENT_VERSION, "boot-$started-$index")
        links[credentials.deviceId] = link
        jobs[credentials.deviceId] = scope.launch { runner.run(device, link, status, timing, seed) }
    }

    suspend fun sampleTimeline() {
        while (currentCoroutineContext().isActive) {
            recordTimeline()
            delay(TIMELINE_STEP_MS)
        }
    }

    fun recordTimeline() {
        timeline += TimelineRow(clock.nowMillis(), phase, metrics.snapshot(), traffic.snapshot())
    }

    /** Команды с фиксированным темпом: `k`-я — в `windowStart + k·период`, последняя — до конца окна. */
    suspend fun sendCommands(
        load: CommandLoad,
        targets: List<DeviceCredentials>,
        windowStart: Long,
        windowEnd: Long,
    ) {
        val periodMs = MS_IN_MINUTE.toDouble() / load.perMinute
        var k = 0L
        val due = { windowStart + (k * periodMs).toLong() }
        while (currentCoroutineContext().isActive && targets.isNotEmpty() && due() < windowEnd) {
            val target = targets[commandRandom.nextInt(targets.size)].deviceId
            val sentNanos = ticker.nanos()
            val sentMillis = clock.nowMillis()
            val cmdId = control.sendCommand(target, load)
            if (cmdId == null) {
                sendFailures++
            } else {
                synchronized(sent) { sent += SentCommand(cmdId, target, sentMillis, sentNanos) }
            }
            k++
            (windowStart + (k * periodMs).toLong() - clock.nowMillis()).takeIf { it > 0 }?.let { delay(it) }
        }
    }

    suspend fun collectCommands(): List<CommandRecord> {
        val commands = synchronized(sent) { sent.toList() }
        val graceMs = (scenario.pollIntervalSec?.takeIf { scenario.mode == TransportMode.REST } ?: 0) * MS_IN_SEC +
            GRACE_EXTRA_MS
        val deadline = clock.nowMillis() + graceMs
        while (commands.any { receipts.receivedAt(it.cmdId) == null } && clock.nowMillis() < deadline) {
            delay(GRACE_STEP_MS)
        }
        delay(RESULT_SETTLE_MS)
        return commands.map { command ->
            val received = receipts.receivedAt(command.cmdId)
            CommandRecord(
                cmdId = command.cmdId,
                deviceId = command.deviceId,
                sentAtMillis = command.sentAtMillis,
                latencyMs = received?.let { (it - command.sentAtNanos) / NANOS_IN_MS },
                finalStatus = control.commandStatus(command.cmdId),
            )
        }
    }

    suspend fun measureLoss(loss: LossTest, victims: Set<String>): List<DetectionRecord> {
        val lostAt = LinkedHashMap<String, Long>()
        victims.forEach { id ->
            jobs[id]?.cancelAndJoin()
            // Момент пропажи — до vanish(): связь замолкает первым же действием транспорта
            lostAt[id] = clock.nowMillis()
            links[id]?.vanish(loss.mode)
        }
        val lastStatus = HashMap<String, String?>()
        val offlineAfter = HashMap<String, Long>()
        val deadline = clock.nowMillis() + loss.timeoutSec * MS_IN_SEC
        while (offlineAfter.size < victims.size && clock.nowMillis() < deadline) {
            delay(LOSS_POLL_MS)
            victims.filterNot { it in offlineAfter }.forEach { id ->
                val status = control.deviceStatus(id)
                lastStatus[id] = status
                if (status == OFFLINE) offlineAfter[id] = clock.nowMillis() - lostAt.getValue(id)
            }
        }
        victims.forEach { id ->
            links.remove(id)?.release()
            jobs.remove(id)
        }
        return lostAt.map { (id, at) -> DetectionRecord(id, at, offlineAfter[id], lastStatus[id]) }
    }

    suspend fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.values.forEach { it.join() }
        val gate = Semaphore(STOP_PARALLELISM)
        coroutineScope { links.values.map { link -> async { gate.withPermit { link.close() } } }.awaitAll() }
    }

    companion object {
        const val PHASE_RAMP = "ramp"
        const val PHASE_WARMUP = "warmup"
        const val PHASE_WINDOW = "window"
        const val PHASE_GRACE = "grace"
        const val PHASE_STOP = "stop"
        const val AGENT_VERSION = "simulator-1.0"
        private const val OFFLINE = "offline"
        private const val MS_IN_SEC = 1_000L
        private const val MS_IN_MINUTE = 60_000L
        private const val NANOS_IN_MS = 1_000_000.0
        private const val TIMELINE_STEP_MS = 5_000L
        private const val GRACE_EXTRA_MS = 10_000L
        private const val GRACE_STEP_MS = 500L
        private const val RESULT_SETTLE_MS = 2_000L
        private const val LOSS_POLL_MS = 1_000L
        private const val STOP_PARALLELISM = 64
    }
}
