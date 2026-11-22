package ru.restofleet.console.feature.incidents.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class LoadIncidents(private val repository: IncidentsRepository) {
    suspend operator fun invoke(): Outcome<IncidentLists, ConsoleError> = repository.lists()
}

class LoadIncidentDetails(private val repository: IncidentsRepository) {
    suspend operator fun invoke(id: String): Outcome<IncidentDetails, ConsoleError> = repository.details(id)
}

/** Подтверждение инцидента с необязательным комментарием (≤ 500); повторное подтверждение сервер отвергает (409). */
class AcknowledgeIncident(private val repository: IncidentsRepository) {
    suspend operator fun invoke(id: String, comment: String): Outcome<Incident, ConsoleError> {
        val text = comment.trim()
        return if (text.length > COMMENT_MAX) {
            Outcome.Failure(ConsoleError.InvalidInput(FIELD_COMMENT, "too_long"))
        } else {
            repository.acknowledge(id, text.ifEmpty { null })
        }
    }

    companion object {
        const val FIELD_COMMENT = "comment"
        const val COMMENT_MAX = 500
    }
}

class ObserveIncidentsEvents(private val repository: IncidentsRepository) {
    operator fun invoke(): Flow<IncidentsEvent> = repository.events()
}
