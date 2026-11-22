package ru.restofleet.console.feature.incidents.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.incidents.domain.Incident
import ru.restofleet.console.feature.incidents.domain.IncidentKind
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentRowUi
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentsSession
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentsUiState

/** Названия и тон инцидентов — общие для списка и карточки. */
object IncidentTexts {
    fun title(i: Incident): String {
        val names = i.restaurants.joinToString(", ") { it.name }
        return when (i.kind) {
            IncidentKind.RestaurantNetwork -> "Сеть ресторана · $names"
            IncidentKind.BackendGlobal -> "Бэкенд недоступен · ${i.restaurants.size} ресторанов"
            IncidentKind.BackendLocal -> "Бэкенд недоступен · $names"
            IncidentKind.Unknown -> "Инцидент ${i.ruleId}"
        }
    }

    fun tone(i: Incident): StatusTone = when {
        i.resolved -> StatusTone.Off
        i.kind == IncidentKind.BackendGlobal -> StatusTone.Crit
        else -> StatusTone.Warn
    }
}

class IncidentsUiStateMapper {
    fun map(s: IncidentsSession): IncidentsUiState {
        val lists = s.lists
        val now = lists?.loadedAt ?: 0L
        val open = lists?.open.orEmpty().map { row(it, now) }
        val resolved = lists?.resolved.orEmpty().map { row(it, now) }
        return IncidentsUiState(
            open = open,
            resolved = resolved,
            loading = s.loading && lists == null,
            errorText = s.error?.let { if (it == ConsoleError.Network) "Нет связи с сервером" else "Не удалось загрузить инциденты" },
            emptyText = if (lists != null && open.isEmpty() && resolved.isEmpty()) "Инцидентов нет" else null,
        )
    }

    private fun row(i: Incident, now: Long): IncidentRowUi {
        val duration = RelativeTime.duration((i.resolvedAt ?: now) - i.startedAt)
        val parts = listOfNotNull(
            i.target,
            if (i.resolved) "длился $duration" else "идёт $duration",
            i.suppressedAlerts.takeIf { it > 0 }?.let { "скрыто тревог: $it" },
        )
        return IncidentRowUi(i.id, IncidentTexts.title(i), parts.joinToString(" · "), i.ruleId, IncidentTexts.tone(i))
    }
}
