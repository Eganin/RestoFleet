package ru.restofleet.server.simulator.domain.runtime

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.server.simulator.domain.device.CommandAction
import ru.restofleet.server.simulator.domain.device.VirtualDevice
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.ProbeReport
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.port.DeviceLink
import ru.restofleet.server.simulator.domain.port.Incoming
import ru.restofleet.server.simulator.domain.port.ProbeRunner
import ru.restofleet.shared.kernel.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Первое получение каждой команды устройством (монотонные нс) — для задержки команды измерителя (план 4.2). */
class CommandReceipts {
    private val receivedAt = ConcurrentHashMap<String, Long>()

    fun received(cmdId: String, atNanos: Long) {
        receivedAt.putIfAbsent(cmdId, atNanos)
    }

    fun receivedAt(cmdId: String): Long? = receivedAt[cmdId]
}

/** Параметры циклов одного устройства: интервал опроса (только REST), разброс периодов. */
data class DeviceTiming(val pollIntervalSec: Int?, val jitter: Double)

/**
 * Жизненный цикл одного виртуального устройства поверх [DeviceLink] (FR-SM-01, 02): подключение и `status online`,
 * телеметрия с интервалом из тени (по умолчанию 30 с) и первым тиком в случайной фазе, тень → `reported`, команды →
 * `received` (MQTT) + итог, плановые пробы. REST: опрос команд с интервалом [DeviceTiming.pollIntervalSec].
 * Работает, пока его не отменят; обращения к [VirtualDevice] сериализуются замком.
 */
class DeviceRunner(
    private val clock: Clock,
    private val probes: ProbeRunner,
    private val metrics: RunMetrics,
    private val receipts: CommandReceipts,
) {
    suspend fun run(device: VirtualDevice, link: DeviceLink, status: StatusInfo, timing: DeviceTiming, seed: Long) {
        if (!link.open(status)) {
            metrics.connectFailed()
            return
        }
        metrics.connected()
        val session = Session(device, link, Mutex())
        try {
            coroutineScope {
                launch { link.incoming.collect { session.onIncoming(it) } }
                launch { session.telemetryLoop(Random(seed * SEED_MUL + 1), timing.jitter) }
                timing.pollIntervalSec?.takeIf { !link.pushesCommands }?.let { poll ->
                    launch { session.pollLoop(poll * MS_IN_SEC, Random(seed * SEED_MUL + 2), timing.jitter) }
                }
                launch { session.probeLoop() }
            }
        } finally {
            metrics.disconnected()
        }
    }

    private inner class Session(val device: VirtualDevice, val link: DeviceLink, val lock: Mutex) {
        suspend fun telemetryLoop(random: Random, jitter: Double) {
            delay(random.nextLong(lock.withLock { device.config.telemetryIntervalSec } * MS_IN_SEC))
            while (currentCoroutineContext().isActive) {
                val tick = lock.withLock { device.tick(clock.nowMillis()) }
                if (tick.events.isNotEmpty()) link.sendEvents(tick.events)
                link.sendTelemetry(tick.sample)
                val interval = lock.withLock { device.config.telemetryIntervalSec } * MS_IN_SEC
                delay(jittered(interval, jitter, random))
            }
        }

        suspend fun pollLoop(intervalMs: Long, random: Random, jitter: Double) {
            delay(random.nextLong(intervalMs))
            while (currentCoroutineContext().isActive) {
                link.poll()?.forEach { onCommand(it) }
                delay(jittered(intervalMs, jitter, random))
            }
        }

        suspend fun probeLoop() {
            while (currentCoroutineContext().isActive) {
                val due = lock.withLock { device.dueProbes(clock.nowMillis()) }
                if (due.isNotEmpty()) runProbes(due)
                val idle = lock.withLock { device.config.probes.isEmpty() }
                delay(if (idle) IDLE_PROBE_TICK_MS else PROBE_TICK_MS)
            }
        }

        suspend fun onIncoming(incoming: Incoming) {
            when (incoming) {
                is Incoming.Desired -> {
                    val reported = lock.withLock { device.applyDesired(incoming.update, clock.nowMillis()) }
                    reported?.let { link.sendReported(it) }
                }
                is Incoming.Command -> onCommand(incoming.command)
            }
        }

        suspend fun onCommand(cmd: IncomingCommand) {
            receipts.received(cmd.cmdId, cmd.receivedAtNanos)
            val accepted = lock.withLock { device.accept(cmd, clock.nowMillis()) } ?: return
            if (link.pushesCommands) link.reply(accepted.received)
            link.reply(finish(cmd, accepted.action))
        }

        private suspend fun finish(cmd: IncomingCommand, action: CommandAction): CommandReply = when (action) {
            is CommandAction.Done -> action.reply
            is CommandAction.RunProbe -> {
                val result = runProbes(listOf(action.target)).single()
                lock.withLock { device.probeReply(cmd, result, clock.nowMillis()) }
            }
            is CommandAction.Diagnostics -> {
                val tick = lock.withLock { device.tick(clock.nowMillis()) }
                if (tick.events.isNotEmpty()) link.sendEvents(tick.events)
                link.sendTelemetry(tick.sample)
                val results = if (action.targets.isEmpty()) emptyList() else runProbes(action.targets)
                lock.withLock { device.diagnosticsReply(cmd, tick.sample.seq, results, clock.nowMillis()) }
            }
        }

        private suspend fun runProbes(targets: List<ProbeTarget>): List<ProbeResult> {
            val results = targets.map { probes.run(it) }
            val now = clock.nowMillis()
            val events = lock.withLock {
                targets.zip(results).flatMap { (target, result) -> device.recordProbe(target, result, now) }
            }
            if (events.isNotEmpty()) link.sendEvents(events)
            link.sendProbe(ProbeReport(now, results))
            return results
        }
    }

    companion object {
        private const val MS_IN_SEC = 1_000L
        private const val SEED_MUL = 31L
        private const val PROBE_TICK_MS = 1_000L
        private const val IDLE_PROBE_TICK_MS = 5_000L

        /** Период с разбросом ±[jitter] (доля); без разброса — ровно [baseMs]. */
        fun jittered(baseMs: Long, jitter: Double, random: Random): Long =
            if (jitter <= 0.0) baseMs else (baseMs * (1 + random.nextDouble(-jitter, jitter))).toLong()
    }
}
