package ru.restofleet.server.domain.command

import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.shadow.ShadowDocument

/** Статусы команды на бэкенде (FR-BE-41): `queued → delivered → succeeded / failed / unsupported / expired`. */
enum class CommandStatus(val code: String, val isFinal: Boolean) {
    QUEUED("queued", isFinal = false),
    DELIVERED("delivered", isFinal = false),
    SUCCEEDED("succeeded", isFinal = true),
    FAILED("failed", isFinal = true),
    UNSUPPORTED("unsupported", isFinal = true),
    EXPIRED("expired", isFinal = true),
    ;

    companion object {
        fun fromCode(code: String?): CommandStatus? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Команда устройству (FR-BE-40, 41). [params] — JSON-объект параметров (дерево `shared:shadow`, без сериализации);
 * [result] — объект со строковыми значениями из `cmd/res` (реш. 221).
 */
data class Command(
    val cmdId: String,
    val deviceId: DeviceId,
    val type: String,
    val params: ShadowDocument,
    val status: CommandStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val deliveredAt: Long?,
    val finishedAt: Long?,
    val result: Map<String, String>?,
    val reason: String?,
) {
    /** Оставшийся срок жизни для `Message Expiry Interval`, секунды (не меньше 1). */
    fun expirySecondsAt(now: Long): Long = ((expiresAt - now + MS_PER_SEC - 1) / MS_PER_SEC).coerceAtLeast(1)

    private companion object {
        const val MS_PER_SEC = 1_000L
    }
}

/** Заявка на команду (`POST /devices/{id}/commands`). [ttlSec] — `Message Expiry Interval` и `expiresAt` (P2). */
data class CommandRequest(
    val deviceId: DeviceId,
    val type: String,
    val params: ShadowDocument = ShadowDocument.EMPTY,
    val ttlSec: Int = DEFAULT_TTL_SEC,
) {
    companion object {
        /** ТЗ 7.2: `Message Expiry Interval = 300`. */
        const val DEFAULT_TTL_SEC = 300

        /** Не дольше Session Expiry брокера (24 ч): иначе брокер всё равно потеряет команду. */
        val TTL_SEC: IntRange = 1..86_400

        val TYPE = Regex("^[a-z][a-z0-9_]{0,63}$")
    }
}

/** Статус из `cmd/res` устройства (P1: сначала `received`, затем итог). */
enum class ReportedCommandStatus { RECEIVED, SUCCEEDED, FAILED, UNSUPPORTED }

/** Сообщение `cmd/res` (MQTT) или `POST /poll/commands/result` (REST). */
data class CommandReport(
    val deviceId: DeviceId,
    val cmdId: String,
    val status: ReportedCommandStatus,
    val ts: Long,
    val result: Map<String, String>? = null,
    val reason: String? = null,
)

/** Причины, которые выставляет сам бэкенд. */
object CommandReasons {
    const val EXPIRED = "expired"
    const val BROKER_UNAVAILABLE = "broker_unavailable"

    /** Устройство выведено из эксплуатации, пока команда ждала доставки или итога (R-8, реш. 307). */
    const val DECOMMISSIONED = "decommissioned"
}

/**
 * Переходы статуса по отчёту устройства (FR-BE-14, 41; реш. 238): идемпотентно по `(cmdId, status)`.
 * - `received`: `queued → delivered`; иначе без изменений;
 * - итог (`succeeded`/`failed`/`unsupported`) принимается, пока команда не завершена устройством: из `queued`,
 *   `delivered`, а также поверх серверных итогов `expired` и `failed/broker_unavailable` — «поздний итог
 *   побеждает» (P2).
 *   Первый итог устройства окончательный.
 * `null` — отчёт ничего не меняет.
 */
object CommandTransitions {
    fun apply(command: Command, report: CommandReport, now: Long): Command? = when (report.status) {
        ReportedCommandStatus.RECEIVED ->
            if (command.status == CommandStatus.QUEUED) {
                command.copy(status = CommandStatus.DELIVERED, deliveredAt = now)
            } else {
                null
            }
        else -> if (acceptsFinal(command)) {
            command.copy(
                status = finalStatus(report.status),
                deliveredAt = command.deliveredAt ?: now,
                finishedAt = now,
                result = report.result,
                reason = report.reason,
            )
        } else {
            null
        }
    }

    private fun acceptsFinal(command: Command): Boolean = when (command.status) {
        CommandStatus.QUEUED, CommandStatus.DELIVERED, CommandStatus.EXPIRED -> true
        CommandStatus.FAILED -> command.reason == CommandReasons.BROKER_UNAVAILABLE
        CommandStatus.SUCCEEDED, CommandStatus.UNSUPPORTED -> false
    }

    private fun finalStatus(status: ReportedCommandStatus): CommandStatus = when (status) {
        ReportedCommandStatus.SUCCEEDED -> CommandStatus.SUCCEEDED
        ReportedCommandStatus.FAILED -> CommandStatus.FAILED
        ReportedCommandStatus.UNSUPPORTED -> CommandStatus.UNSUPPORTED
        ReportedCommandStatus.RECEIVED -> error("received is not final")
    }
}

/** Порт команд. Только в транзакции. */
interface CommandRepository {
    suspend fun insert(command: Command)

    suspend fun find(cmdId: String): Command?

    /** С блокировкой строки до конца транзакции. */
    suspend fun findForUpdate(cmdId: String): Command?

    suspend fun update(command: Command)

    /** `queued`/`delivered` с `expires_at < now` → `expired` (P2); число изменённых. */
    suspend fun expireDue(now: Long): Int

    /** REST-polling: `queued` команды устройства, ещё не истёкшие, атомарно → `delivered`; по времени создания. */
    suspend fun deliverQueued(deviceId: DeviceId, now: Long): List<Command>

    /** Незавершённые (`queued`/`delivered`) команды устройства → `failed` с [reason]; число изменённых. */
    suspend fun failUnfinished(deviceId: DeviceId, reason: String, now: Long): Int
}
