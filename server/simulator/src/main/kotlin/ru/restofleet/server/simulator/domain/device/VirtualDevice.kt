package ru.restofleet.server.simulator.domain.device

import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DesiredUpdate
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.ReplyStatus
import ru.restofleet.server.simulator.domain.model.ReportedShadow
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.profile.DayClock
import ru.restofleet.server.simulator.domain.profile.DeviceProfile
import ru.restofleet.server.simulator.domain.profile.ProfileState
import ru.restofleet.server.simulator.domain.profile.Thermal
import ru.restofleet.shared.probes.ProbeErrorClass
import ru.restofleet.shared.probes.ProbeSchedule
import ru.restofleet.shared.probes.ProbeStateMachine
import ru.restofleet.shared.shadow.VersionDecision
import ru.restofleet.shared.shadow.validateDesired
import kotlin.math.roundToInt
import kotlin.random.Random

/** Снимок одного тика: телеметрия и пересечения порогов. */
data class Tick(val sample: TelemetrySample, val events: List<SimEvent>)

/** Что делать с принятой командой после `received`. */
sealed interface CommandAction {
    /** Итог известен сразу. */
    data class Done(val reply: CommandReply) : CommandAction

    /** `run_probe`: выполнить пробу цели [target] и ответить через [VirtualDevice.probeReply]. */
    data class RunProbe(val target: ProbeTarget) : CommandAction

    /** `collect_diagnostics`: внеочередная телеметрия и все цели, ответ — [VirtualDevice.diagnosticsReply]. */
    data class Diagnostics(val targets: List<ProbeTarget>) : CommandAction
}

/** Принятая команда: ответ `received` (P1) и действие. */
data class Acceptance(val received: CommandReply, val action: CommandAction)

/**
 * Поведение виртуального устройства — то же, что у агента (FR-SM-02), без ввода-вывода: тики телеметрии по профилю
 * (FR-SM-03), пороги, тень (`VersionDecision`, `validateDesired` из `shared:shadow`), команды (идемпотентно по
 * `cmdId`, допуск часов 5 мин — реш. 206), автоматы целей проверок (`ProbeStateMachine` из `shared:probes`,
 * расписание `ProbeSchedule.forStand()`). Не потокобезопасно: вызывающий сериализует обращения.
 */
@Suppress("TooManyFunctions") // одна сущность — одно состояние устройства; функции — его операции
class VirtualDevice(
    private val profile: DeviceProfile,
    private val dayClock: DayClock,
    private val random: Random,
    startMillis: Long,
    initialConfig: DeviceConfig = DeviceConfig(),
    private val schedule: ProbeSchedule = ProbeSchedule.forStand(),
) {
    var config: DeviceConfig = initialConfig
        private set

    private var state: ProfileState = profile.initial(random)
    private var seq = 0L
    private var lastTickMillis = startMillis
    private val bootMillis = startMillis
    private val thresholds = ThresholdTracker()
    private val processed = LinkedHashSet<String>()
    private val machines = HashMap<String, ProbeStateMachine>()
    private val lastProbeRun = HashMap<String, Long>()

    /** Очередной снимок телеметрии на момент [now] (мс). */
    fun tick(now: Long): Tick {
        val simDtSec = dayClock.simulatedMillis(now).minus(dayClock.simulatedMillis(lastTickMillis))
            .coerceAtLeast(0L) / MS_IN_SEC.toDouble()
        lastTickMillis = maxOf(lastTickMillis, now)
        state = profile.next(state, dayClock.hour(now), simDtSec, random)
        val thermal = Thermal.code(state.tempC)
        val sample = TelemetrySample(
            ts = now,
            seq = ++seq,
            batteryLevel = state.batteryPct.roundToInt(),
            charging = state.charging,
            plugged = if (state.charging) profile.plugged else NOT_PLUGGED,
            batteryTempC = (state.tempC * TENTHS).roundToInt() / TENTHS,
            thermalStatus = Thermal.name(thermal),
            rssi = state.rssi,
            freeMb = state.freeMb,
            availMb = state.availMb,
            uptimeSec = (now - bootMillis).coerceAtLeast(0L) / MS_IN_SEC,
            foregroundApp = profile.foregroundApp,
        )
        return Tick(sample, thresholds.evaluate(sample, config.thresholds))
    }

    /** Пришёл `desired`: `null` — версия старее применённой; иначе `reported` для публикации (ТЗ 7.2). */
    fun applyDesired(update: DesiredUpdate, now: Long): ReportedShadow? =
        when (VersionDecision.of(config.version, update.version)) {
            VersionDecision.IGNORE_OLDER -> null
            VersionDecision.ECHO_EQUAL -> ReportedShadow(config, update.version, now, emptyList())
            VersionDecision.APPLY_NEWER -> {
                val checked = validateDesired(
                    update.telemetryIntervalSec, update.watchedApps, update.probes, { it.id }, { it.url },
                )
                config = DeviceConfig(
                    version = update.version,
                    telemetryIntervalSec = checked.telemetryIntervalSec ?: config.telemetryIntervalSec,
                    thresholds = update.thresholds,
                    watchedApps = checked.watchedApps,
                    probes = checked.probes,
                )
                machines.keys.retainAll(config.probes.map { it.id }.toSet())
                ReportedShadow(config, update.version, now, checked.errors)
            }
        }

    /** Команда; `null` — повтор уже обработанного `cmdId` (ответа нет, как у агента). */
    fun accept(cmd: IncomingCommand, now: Long): Acceptance? {
        if (!processed.add(cmd.cmdId)) return null
        if (processed.size > PROCESSED_LIMIT) processed.remove(processed.first())
        val received = reply(cmd, ReplyStatus.RECEIVED, now)
        return Acceptance(received, action(cmd, now))
    }

    private fun action(cmd: IncomingCommand, now: Long): CommandAction {
        val expired = cmd.expiresAt != null && now > cmd.expiresAt + CLOCK_SKEW_TOLERANCE_MS
        return when {
            expired -> done(cmd, ReplyStatus.FAILED, now, reason = REASON_EXPIRED)
            cmd.type == PING -> done(cmd, ReplyStatus.SUCCEEDED, now, mapOf("deviceTime" to now.toString()))
            cmd.type == SHOW_MESSAGE -> if (cmd.params["text"].isNullOrBlank()) {
                done(cmd, ReplyStatus.FAILED, now, reason = "invalid_params:text")
            } else {
                done(cmd, ReplyStatus.SUCCEEDED, now)
            }
            cmd.type == LAUNCH_APP -> done(cmd, ReplyStatus.SUCCEEDED, now)
            cmd.type == RUN_PROBE -> runProbe(cmd, now)
            cmd.type == COLLECT_DIAGNOSTICS -> CommandAction.Diagnostics(config.probes)
            cmd.type == BEEP -> done(cmd, ReplyStatus.UNSUPPORTED, now, reason = "not_available")
            cmd.type in DEVICE_OWNER_COMMANDS ->
                done(cmd, ReplyStatus.UNSUPPORTED, now, reason = "requires_device_owner")
            else -> done(cmd, ReplyStatus.UNSUPPORTED, now, reason = "unknown_type:${cmd.type}")
        }
    }

    private fun runProbe(cmd: IncomingCommand, now: Long): CommandAction {
        val id = cmd.params["target"]
        val target = config.probes.firstOrNull { it.id == id }
        return when {
            id.isNullOrBlank() -> done(cmd, ReplyStatus.UNSUPPORTED, now, reason = "invalid_target")
            target == null -> done(cmd, ReplyStatus.FAILED, now, reason = "unknown_target")
            else -> CommandAction.RunProbe(target)
        }
    }

    /** Цели, которым пора на плановую проверку (не чаще [ProbeSchedule]); отмечает их запуск. */
    fun dueProbes(now: Long): List<ProbeTarget> = config.probes.filter { target ->
        val due = schedule.nextRunAt(lastProbeRun[target.id], target.intervalSec, now) <= now
        if (due) lastProbeRun[target.id] = now
        due
    }

    /** Итог пробы через автомат цели; переходы — события `probe_state` (FR-AG-34). */
    fun recordProbe(target: ProbeTarget, result: ProbeResult, now: Long): List<SimEvent> {
        val machine = machines[target.id]?.takeIf { it.failThreshold == target.failThreshold.coerceAtLeast(1) }
            ?: ProbeStateMachine(target.failThreshold).also { machines[target.id] = it }
        val transition = machine.record(result.ok, ProbeErrorClass.fromCode(result.error)) ?: return emptyList()
        return listOf(
            SimEvent.ProbeState(now, target.id, transition.to.code, transition.error?.code, transition.failures),
        )
    }

    /** Итог `run_probe` (реш. 268): `succeeded` и при неудачной пробе, поля строками. */
    fun probeReply(cmd: IncomingCommand, result: ProbeResult, now: Long): CommandReply {
        val fields = buildMap {
            put("target", result.id)
            put("ok", result.ok.toString())
            result.status?.let { put("status", it.toString()) }
            result.error?.let { put("error", it) }
            put("totalMs", result.totalMs.toString())
        }
        return reply(cmd, ReplyStatus.SUCCEEDED, now, fields)
    }

    /** Итог `collect_diagnostics` (реш. 268): `seq` внеочередной телеметрии и итоги целей. */
    fun diagnosticsReply(cmd: IncomingCommand, telemetrySeq: Long, results: List<ProbeResult>, now: Long) =
        reply(
            cmd, ReplyStatus.SUCCEEDED, now,
            mapOf(
                "seq" to telemetrySeq.toString(),
                "probes" to results.joinToString(",") { it.id },
                "probesOk" to results.filter { it.ok }.joinToString(",") { it.id },
                "probesFailed" to results.filterNot { it.ok }.joinToString(",") { it.id },
                "unavailable" to "",
            ),
        )

    private fun done(
        cmd: IncomingCommand,
        status: ReplyStatus,
        now: Long,
        result: Map<String, String>? = null,
        reason: String? = null,
    ) = CommandAction.Done(reply(cmd, status, now, result, reason))

    private fun reply(
        cmd: IncomingCommand,
        status: ReplyStatus,
        now: Long,
        result: Map<String, String>? = null,
        reason: String? = null,
    ) = CommandReply(cmd.cmdId, status, now, result, reason, cmd.correlationData)

    companion object {
        const val PING = "ping"
        const val SHOW_MESSAGE = "show_message"
        const val LAUNCH_APP = "launch_app"
        const val RUN_PROBE = "run_probe"
        const val COLLECT_DIAGNOSTICS = "collect_diagnostics"
        const val BEEP = "beep"
        val DEVICE_OWNER_COMMANDS = setOf("reboot", "install_update", "lock_task")
        const val REASON_EXPIRED = "expired"

        /** Допуск на уход часов устройства вперёд (реш. 206). */
        const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60_000L

        /** Журнал обработанных `cmdId`, как у агента (реш. 130). */
        const val PROCESSED_LIMIT = 200
        private const val NOT_PLUGGED = "none"
        private const val MS_IN_SEC = 1_000L
        private const val TENTHS = 10.0
    }
}
