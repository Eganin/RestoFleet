package ru.restofleet.console.feature.alerts.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.core.common.RuleTexts
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.alerts.domain.AlertItem
import ru.restofleet.console.feature.alerts.domain.AlertState
import ru.restofleet.console.feature.alerts.domain.ResolveAlert
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertRowUi
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsSession
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsTab
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsUiState

class AlertsUiStateMapper {
    fun map(s: AlertsSession): AlertsUiState {
        val lists = s.lists
        val now = lists?.loadedAt ?: 0L
        val current = when (s.tab) {
            AlertsTab.Open -> lists?.open
            AlertsTab.Acknowledged -> lists?.acknowledged
            AlertsTab.Resolved -> lists?.resolved
        }.orEmpty()
        return AlertsUiState(
            tabs = AlertsTab.entries.map { tab ->
                val count = when (tab) {
                    AlertsTab.Open -> lists?.open?.size
                    AlertsTab.Acknowledged -> lists?.acknowledged?.size
                    AlertsTab.Resolved -> null
                }
                if (count != null && count > 0) "${tab.title} · $count" else tab.title
            },
            selectedTab = s.tab.ordinal,
            rows = current.map { row(it, now) },
            loading = s.loading && lists == null,
            errorText = s.error?.let { errorText(it) },
            emptyText = if (lists != null && current.isEmpty()) emptyText(s.tab) else null,
            dialogTitle = s.resolving?.let { RuleTexts.title(it.ruleId, it.target) },
            comment = s.comment,
            commentError = s.commentError?.let(::commentText),
            acting = s.acting,
            actionErrorText = s.actionError?.let { errorText(it) },
        )
    }

    private fun row(a: AlertItem, now: Long): AlertRowUi {
        val where = listOfNotNull(a.deviceName ?: a.deviceId, a.restaurantName).joinToString(" · ")
        val detail = RuleTexts.detail(a.ruleId, a.details, now)
        val age = RelativeTime.duration(now - a.openedAt)
        return AlertRowUi(
            id = a.id,
            deviceId = a.deviceId,
            title = RuleTexts.title(a.ruleId, a.target),
            line = listOf(where, detail, age).filter { it.isNotBlank() }.joinToString(" · "),
            ruleId = a.ruleId,
            tone = if (a.critical) StatusTone.Crit else StatusTone.Warn,
            auto = a.state == AlertState.Resolved && a.auto,
            canAcknowledge = a.state == AlertState.Open,
            canResolve = a.state != AlertState.Resolved,
        )
    }

    private fun emptyText(tab: AlertsTab): String = when (tab) {
        AlertsTab.Open -> "Открытых тревог нет"
        AlertsTab.Acknowledged -> "Подтверждённых тревог нет"
        AlertsTab.Resolved -> "Закрытых тревог нет"
    }

    private fun errorText(e: ConsoleError): String = when (e) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.Unauthorized -> "Сессия истекла, войдите заново"
        is ConsoleError.Conflict -> "Тревога уже изменена, список обновлён"
        ConsoleError.NotFound -> "Тревога не найдена"
        else -> "Не удалось выполнить"
    }

    private fun commentText(reason: String): String =
        if (reason == "too_long") "Комментарий длиннее ${ResolveAlert.COMMENT_MAX} символов" else "Введите комментарий"
}
