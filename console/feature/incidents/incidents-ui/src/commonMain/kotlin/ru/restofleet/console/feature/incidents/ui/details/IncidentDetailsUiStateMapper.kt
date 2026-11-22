package ru.restofleet.console.feature.incidents.ui.details

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.incidents.domain.AcknowledgeIncident
import ru.restofleet.console.feature.incidents.ui.viewmodel.mapper.IncidentTexts

class IncidentDetailsUiStateMapper(private val explanation: IncidentExplanationMapper = IncidentExplanationMapper()) {
    fun map(s: IncidentDetailsSession): IncidentDetailsUiState {
        val d = s.details
        val i = d?.incident
        val ackText = i?.ackBy?.let { "Подтверждено: $it" + (i.comment?.let { c -> " — $c" }.orEmpty()) }
        val tooLong = (s.ackError as? ConsoleError.InvalidInput)?.message == "too_long"
        return IncidentDetailsUiState(
            loading = s.loading && d == null,
            errorText = s.error?.let { errorText(it) },
            title = i?.let(IncidentTexts::title).orEmpty(),
            statusText = i?.let { status(it.resolved, it.resolveReason, it.resolvedAt, it.startedAt) }.orEmpty(),
            statusTone = i?.let(IncidentTexts::tone) ?: StatusTone.Off,
            ruleId = i?.ruleId.orEmpty(),
            restaurants = i?.restaurants?.joinToString(", ") { "${it.name} (${it.code})" }.orEmpty(),
            explanation = d?.let(explanation::explain).orEmpty(),
            errorsLine = d?.let(explanation::errors)?.let { "Ошибки проверок: $it" },
            ackInfo = ackText,
            canAcknowledge = i != null && !i.resolved && i.ackBy == null,
            comment = s.comment,
            commentError = if (tooLong) "Комментарий длиннее ${AcknowledgeIncident.COMMENT_MAX} символов" else null,
            acknowledging = s.acknowledging,
            ackErrorText = s.ackError?.takeIf { !tooLong }?.let { errorText(it) },
        )
    }

    private fun status(resolved: Boolean, reason: String?, resolvedAt: Long?, startedAt: Long): String {
        if (!resolved) return "Открыт"
        val parts = listOfNotNull(
            reason?.let(::reasonText),
            resolvedAt?.let { "длился ${RelativeTime.duration(it - startedAt)}" },
        )
        return if (parts.isEmpty()) "Закрыт" else "Закрыт · " + parts.joinToString(" · ")
    }

    private fun reasonText(reason: String): String = when (reason) {
        "cleared" -> "условие исчезло"
        "superseded" -> "заменён другим инцидентом"
        else -> reason
    }

    private fun errorText(e: ConsoleError): String = when (e) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.NotFound -> "Инцидент не найден"
        is ConsoleError.Conflict -> "Инцидент уже подтверждён или закрыт"
        else -> "Не удалось выполнить"
    }
}
