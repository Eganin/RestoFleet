package ru.restofleet.server.domain.alert.usecase

import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/** Длина комментария подтверждения и закрытия (реш. 413). */
const val ALERT_COMMENT_MAX = 500

/**
 * Общий ход ручного действия над тревогой: проверка комментария, строка под блокировкой, переход состояния,
 * публикация после commit.
 */
internal suspend fun changeAlert(
    alerts: AlertRepository,
    tx: TransactionRunner,
    bus: EventBus,
    id: String,
    transition: (Alert) -> Outcome<Alert, ServerError>,
): Outcome<AlertView, ServerError> {
    val result = tx.transactional<AlertView, ServerError> {
        val alert = alerts.findForUpdate(id) ?: return@transactional Outcome.Failure(ServerError.NotFound("alert", id))
        when (val next = transition(alert)) {
            is Outcome.Failure -> next
            is Outcome.Success -> {
                alerts.update(next.value)
                Outcome.Success(alerts.views(listOf(id)).single())
            }
        }
    }
    if (result is Outcome.Success) bus.publish(RealtimeEvent.AlertChanged(result.value))
    return result
}

/** `null` — комментария нет (допустимо), иначе — обрезанный по краям текст или ошибка валидации. */
internal fun checkComment(comment: String?, required: Boolean): Outcome<String?, ServerError> {
    val text = comment?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        text == null && required -> Outcome.Failure(ServerError.Validation("comment", "required"))
        text != null && text.length > ALERT_COMMENT_MAX ->
            Outcome.Failure(ServerError.Validation("comment", "too_long"))
        else -> Outcome.Success(text)
    }
}

/** `PATCH /alerts/{id}` `acknowledge`: только открытую; комментарий необязателен. Повтор — 409. */
class AcknowledgeAlert(
    private val alerts: AlertRepository,
    private val tx: TransactionRunner,
    private val bus: EventBus,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String, by: String, comment: String?): Outcome<AlertView, ServerError> {
        val text = when (val checked = checkComment(comment, required = false)) {
            is Outcome.Failure -> return checked
            is Outcome.Success -> checked.value
        }
        return changeAlert(alerts, tx, bus, id) { alert ->
            when (alert.state) {
                AlertState.OPEN -> Outcome.Success(
                    alert.copy(state = AlertState.ACKNOWLEDGED, ackBy = by, ackAt = clock.nowMillis(), comment = text),
                )
                AlertState.ACKNOWLEDGED -> Outcome.Failure(ServerError.Conflict("already_acknowledged"))
                AlertState.RESOLVED -> Outcome.Failure(ServerError.Conflict("already_resolved"))
            }
        }
    }
}

/**
 * `PATCH /alerts/{id}` `resolve`: открытую или подтверждённую, комментарий обязателен (1…500). Повтор — 409. Если
 * условие сохраняется, новая тревога появится только при новом входе в него (реш. 413).
 */
class ResolveAlert(
    private val alerts: AlertRepository,
    private val tx: TransactionRunner,
    private val bus: EventBus,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String, by: String, comment: String?): Outcome<AlertView, ServerError> {
        val text = when (val checked = checkComment(comment, required = true)) {
            is Outcome.Failure -> return checked
            is Outcome.Success -> checked.value
        }
        return changeAlert(alerts, tx, bus, id) { alert ->
            if (alert.state == AlertState.RESOLVED) {
                Outcome.Failure(ServerError.Conflict("already_resolved"))
            } else {
                Outcome.Success(
                    alert.copy(
                        state = AlertState.RESOLVED, resolvedAt = clock.nowMillis(), resolvedBy = by, comment = text,
                        auto = false,
                    ),
                )
            }
        }
    }
}
