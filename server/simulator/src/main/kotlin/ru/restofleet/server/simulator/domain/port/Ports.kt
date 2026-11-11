package ru.restofleet.server.simulator.domain.port

import kotlinx.coroutines.flow.Flow
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DesiredUpdate
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

/** Монотонные часы процесса, нс: отправка и получение команды меряются по одним часам (план 4.2). */
fun interface Ticker {
    fun nanos(): Long
}

/** REST API бэкенда от имени оператора (`admin`, JWT): рестораны, коды, команды, статусы. */
interface ControlApi {
    /** id существующих ресторанов. */
    suspend fun restaurants(): List<String>

    /** Пакет одноразовых кодов (`POST /provisioning/tokens:batch`, 1…1000 за вызов). */
    suspend fun issueCodes(restaurantId: String, role: DeviceRole, count: Int): List<String>

    /** Команда устройству; `cmdId` или `null`, если бэкенд отказал. */
    suspend fun sendCommand(deviceId: String, load: CommandLoad): String?

    /** Статус команды в бэкенде (`queued`, `delivered`, `succeeded`, …) или `null`. */
    suspend fun commandStatus(cmdId: String): String?

    /** Статус устройства в реестре (`online`, `offline`, …) или `null`. */
    suspend fun deviceStatus(deviceId: String): String?
}

/** Итог погашения кода. */
sealed interface ProvisionOutcome {
    data class Registered(val credentials: DeviceCredentials) : ProvisionOutcome

    /** Временный отказ (429, 503, сеть): можно повторить тот же код. */
    data class Retry(val reason: String) : ProvisionOutcome

    /** Окончательный отказ: код неверен, погашен или просрочен. */
    data class Rejected(val reason: String) : ProvisionOutcome
}

/** `POST /provision` — тот же путь, что у агента (FR-SM-08). */
fun interface ProvisioningApi {
    suspend fun provision(code: String): ProvisionOutcome
}

/** Кэш учёток (файл 600 вне git): регистрация один раз, переиспользование в обоих режимах. */
interface CredentialStore {
    fun load(): List<DeviceCredentials>

    fun append(credentials: DeviceCredentials)
}

/** Входящее с брокера (только MQTT). */
sealed interface Incoming {
    data class Desired(val update: DesiredUpdate) : Incoming

    data class Command(val command: IncomingCommand) : Incoming
}

/**
 * Связь одного виртуального устройства с платформой. MQTT: постоянная сессия, команды и тень приходят в [incoming].
 * REST-polling: команды забираются [poll], события и отчёты проб уходят вместе со следующей телеметрией, тени нет.
 * `false`/`null` — отправка не удалась (транспорт уже посчитал ошибку).
 */
interface DeviceLink {
    /** Команды приходят сами (MQTT), а не по опросу. */
    val pushesCommands: Boolean

    val incoming: Flow<Incoming>

    suspend fun open(status: StatusInfo): Boolean

    suspend fun sendTelemetry(sample: TelemetrySample): Boolean

    suspend fun sendEvents(events: List<SimEvent>): Boolean

    suspend fun sendProbe(report: ProbeReport): Boolean

    suspend fun sendReported(reported: ReportedShadow): Boolean

    suspend fun reply(reply: CommandReply): Boolean

    suspend fun poll(): List<IncomingCommand>?

    /** Штатная остановка: `status offline` и DISCONNECT (MQTT). */
    suspend fun close()

    /** Пропажа без DISCONNECT (замер обнаружения): связь замолкает ([LossMode.SILENT]) или рвётся ([LossMode.CLOSE]) */
    suspend fun vanish(mode: LossMode)

    /** Освободить ресурсы пропавшего устройства после замера. */
    suspend fun release()
}

/** Транспорт устройств сценария. [victim] — устройство, которое в замере пропадёт (MQTT — через управляемое реле). */
interface DeviceConnector {
    fun link(credentials: DeviceCredentials, victim: Boolean): DeviceLink

    suspend fun shutdown()
}

/** Проверка доступности цели (`real`-режим: настоящий запрос, ТЗ 7.2). */
fun interface ProbeRunner {
    suspend fun run(target: ProbeTarget): ProbeResult
}

/** Счётчик байт сетевого интерфейса устройств; `null` — недоступен (запуск вне контейнера). */
fun interface TrafficMeter {
    fun snapshot(): TrafficSnapshot?
}

/** Запись результатов (CSV, FR-SM-07). */
interface ResultSink {
    fun registration(report: RegistrationReport)

    fun run(report: RunReport)
}
