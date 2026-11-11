package ru.restofleet.server.simulator.domain.usecase

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.RunReport
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.forRun
import ru.restofleet.server.simulator.domain.port.ControlApi
import ru.restofleet.server.simulator.domain.port.DeviceConnector
import ru.restofleet.server.simulator.domain.port.ResultSink
import ru.restofleet.server.simulator.domain.port.Ticker
import ru.restofleet.server.simulator.domain.port.TrafficMeter
import ru.restofleet.server.simulator.domain.runtime.CommandReceipts
import ru.restofleet.server.simulator.domain.runtime.DeviceRunner
import ru.restofleet.server.simulator.domain.runtime.ExperimentRun
import ru.restofleet.server.simulator.domain.runtime.DeviceTiming
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.domain.runtime.minus
import ru.restofleet.shared.kernel.Clock

/**
 * Прогон сценария (FR-SM-01, 06, 07) и измеритель эксперимента ТЗ 13.2 (план 4.2):
 * 1. разгон: устройство `i` стартует в момент `i / rampPerSec` плюс случайный сдвиг внутри своего слота;
 * 2. прогрев [Scenario.warmupSec], затем окно замера [Scenario.durationSec]: снимки счётчиков и байт интерфейса на
 *    границах окна; в окне — команды измерителя с частотой k/мин случайным устройствам через REST API бэкенда.
 *    Задержка команды = получение устройством − отправка, оба момента — монотонные часы этого процесса;
 * 3. добор: ждём неполученные команды (до интервала опроса + 10 с), затем читаем их итоговый статус в бэкенде;
 * 4. пропажа: последние K устройств пропадают без DISCONNECT, `GET /devices/{id}` опрашивается раз в секунду до
 *    `offline` (MQTT — по LWT после keep-alive, REST — по отсутствию опросов, реш. 214);
 * 5. штатная остановка остальных. Временной ряд счётчиков пишется раз в 5 с на всех фазах.
 */
class RunExperiment(
    private val connector: DeviceConnector,
    private val control: ControlApi,
    private val runner: DeviceRunner,
    private val metrics: RunMetrics,
    private val receipts: CommandReceipts,
    private val traffic: TrafficMeter,
    private val sink: ResultSink,
    private val clock: Clock,
    private val ticker: Ticker,
) {
    suspend operator fun invoke(scenario: Scenario, credentials: List<DeviceCredentials>): RunReport = coroutineScope {
        val devices = credentials.forRun(scenario.devices)
        val victims = scenario.loss
            ?.let { loss -> devices.takeLast(loss.devices).map { it.deviceId }.toSet() }
            .orEmpty()
        val started = clock.nowMillis()
        val run = ExperimentRun(
            scenario, started, connector, control, runner, metrics, receipts, traffic, clock, ticker,
        )
        val sampler = launch { run.sampleTimeline() }

        devices.forEachIndexed { index, device -> run.start(this, index, device, device.deviceId in victims) }
        val rampEnd = clock.nowMillis()
        run.phase = ExperimentRun.PHASE_WARMUP
        delay(scenario.warmupSec * MS_IN_SEC)

        run.phase = ExperimentRun.PHASE_WINDOW
        val windowStart = clock.nowMillis()
        val trafficStart = traffic.snapshot()
        val countersStart = metrics.snapshot()
        val sender = scenario.commands?.let { load ->
            val windowEndDue = windowStart + scenario.durationSec * MS_IN_SEC
            launch { run.sendCommands(load, devices.filterNot { it.deviceId in victims }, windowStart, windowEndDue) }
        }
        delay(scenario.durationSec * MS_IN_SEC)
        sender?.cancelAndJoin()
        val windowEnd = clock.nowMillis()
        val trafficEnd = traffic.snapshot()
        val countersEnd = metrics.snapshot()

        run.phase = ExperimentRun.PHASE_GRACE
        val commands = run.collectCommands()
        val detections = scenario.loss?.let { loss -> run.measureLoss(loss, victims) }.orEmpty()

        run.phase = ExperimentRun.PHASE_STOP
        run.stopAll()
        connector.shutdown()
        sampler.cancelAndJoin()
        run.recordTimeline()

        val report = RunReport(
            scenario = scenario,
            startedAtMillis = started,
            rampEndMillis = rampEnd,
            windowStartMillis = windowStart,
            windowEndMillis = windowEnd,
            connectedAtWindowStart = countersStart.connected,
            windowCounters = countersEnd - countersStart,
            windowTraffic = if (trafficStart != null && trafficEnd != null) {
                trafficEnd.copy(
                    rxBytes = trafficEnd.rxBytes - trafficStart.rxBytes,
                    txBytes = trafficEnd.txBytes - trafficStart.txBytes,
                )
            } else {
                null
            },
            commands = commands,
            commandSendFailures = run.sendFailures,
            detections = detections,
            timeline = run.timeline.toList(),
        )
        sink.run(report)
        report
    }

    private companion object {
        const val MS_IN_SEC = 1_000L
    }
}
