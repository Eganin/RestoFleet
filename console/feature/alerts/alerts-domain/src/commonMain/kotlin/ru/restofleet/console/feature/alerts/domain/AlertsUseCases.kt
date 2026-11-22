package ru.restofleet.console.feature.alerts.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class LoadAlerts(private val repository: AlertsRepository) {
    suspend operator fun invoke(): Outcome<AlertLists, ConsoleError> = repository.lists(LIMIT)

    companion object {
        const val LIMIT = 100
    }
}

/** Подтверждение: быстрое действие (свайп вправо), комментарий не требуется. */
class AcknowledgeAlert(private val repository: AlertsRepository) {
    suspend operator fun invoke(id: String): Outcome<AlertItem, ConsoleError> = repository.acknowledge(id)
}

/** Закрытие требует комментарий (макет FR-CN-08): пустой и длиннее 500 символов отсекаются до запроса. */
class ResolveAlert(private val repository: AlertsRepository) {
    suspend operator fun invoke(id: String, comment: String): Outcome<AlertItem, ConsoleError> {
        val text = comment.trim()
        return when {
            text.isEmpty() -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_COMMENT, "empty"))
            text.length > COMMENT_MAX -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_COMMENT, "too_long"))
            else -> repository.resolve(id, text)
        }
    }

    companion object {
        const val FIELD_COMMENT = "comment"
        const val COMMENT_MAX = 500
    }
}

class ObserveAlertsEvents(private val repository: AlertsRepository) {
    operator fun invoke(): Flow<AlertsEvent> = repository.events()
}
