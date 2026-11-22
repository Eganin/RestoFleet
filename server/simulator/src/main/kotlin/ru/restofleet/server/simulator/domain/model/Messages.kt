package ru.restofleet.server.simulator.domain.model

/**
 * Снимок телеметрии виртуального устройства (ТЗ 7.2, `telemetry`). Транспорт превращает его в DTO протокола;
 * [thermalStatus] — имя `THERMAL_STATUS_*` (`NONE`…`SHUTDOWN`).
 */
data class TelemetrySample(
    val ts: Long,
    val seq: Long,
    val batteryLevel: Int,
    val charging: Boolean,
    val plugged: String,
    val batteryTempC: Double,
    val thermalStatus: String,
    val rssi: Int,
    val freeMb: Long,
    val availMb: Long,
    val uptimeSec: Long,
    val foregroundApp: String?,
)

/** События устройства, которые порождает симулятор (FR-AG-34, 40): форматы — фикстуры `shared:protocol`. */
sealed interface SimEvent {
    val ts: Long

    /** Пересечение порога: [metric] `battery_low` | `temp_high` | `thermal`, [state] `enter` | `exit`. */
    data class Threshold(
        override val ts: Long,
        val metric: String,
        val state: String,
        val value: Double,
        val limit: Double,
    ) : SimEvent

    /** Смена состояния цели проверки: [state] `up` | `down`. */
    data class ProbeState(
        override val ts: Long,
        val target: String,
        val state: String,
        val error: String?,
        val failures: Int,
    ) : SimEvent
}

/** Итог одной проверки доступности (ТЗ 7.2, `probe.results[]`). */
data class ProbeResult(
    val id: String,
    val ok: Boolean,
    val status: Int?,
    val error: String?,
    val totalMs: Long,
)

/** Отчёт `probe`: итоги целей одного прогона. */
data class ProbeReport(val ts: Long, val results: List<ProbeResult>)

/**
 * Команда, дошедшая до устройства (MQTT `cmd/req` или ответ `GET /poll/commands`). [receivedAtNanos] — момент получения
 * по монотонным часам процесса: его ставит транспорт сразу при разборе, чтобы задержка не включала очередь корутин.
 */
data class IncomingCommand(
    val cmdId: String,
    val type: String,
    val params: Map<String, String>,
    val expiresAt: Long?,
    val receivedAtNanos: Long,
    val correlationData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is IncomingCommand && other.cmdId == cmdId &&
        other.type == type && other.params == params && other.expiresAt == expiresAt &&
        other.receivedAtNanos == receivedAtNanos &&
        (other.correlationData?.contentEquals(correlationData) ?: (correlationData == null))

    override fun hashCode(): Int = cmdId.hashCode()
}

/** Статус ответа на команду (`cmd/res`, P1): `received`, затем итог. */
enum class ReplyStatus(val code: String) {
    RECEIVED("received"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    UNSUPPORTED("unsupported"),
}

/** Ответ устройства на команду; [result] — строковые значения (реш. 221). */
data class CommandReply(
    val cmdId: String,
    val status: ReplyStatus,
    val ts: Long,
    val result: Map<String, String>? = null,
    val reason: String? = null,
    val correlationData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = other is CommandReply && other.cmdId == cmdId &&
        other.status == status && other.ts == ts && other.result == result && other.reason == reason

    override fun hashCode(): Int = 31 * cmdId.hashCode() + status.hashCode()
}

/** Сведения для `status online` (R6, реш. 210). */
data class StatusInfo(
    val role: DeviceRole,
    val agentVersion: String,
    val bootId: String,
)
