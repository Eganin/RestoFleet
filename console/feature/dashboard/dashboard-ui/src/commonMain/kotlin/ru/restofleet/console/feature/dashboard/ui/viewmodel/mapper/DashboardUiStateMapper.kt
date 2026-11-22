package ru.restofleet.console.feature.dashboard.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.core.common.RuleTexts
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.dashboard.domain.Dashboard
import ru.restofleet.console.feature.dashboard.ui.viewmodel.CounterUi
import ru.restofleet.console.feature.dashboard.ui.viewmodel.DashboardUiState
import ru.restofleet.console.feature.dashboard.ui.viewmodel.ProblemUi
import ru.restofleet.console.feature.dashboard.ui.viewmodel.RecentUi
import ru.restofleet.console.feature.dashboard.ui.viewmodel.model.DashboardSession

class DashboardUiStateMapper {
    fun map(session: DashboardSession): DashboardUiState {
        val data = session.data
        return DashboardUiState(
            loading = session.loading && data == null,
            errorText = session.error?.let(::errorText),
            counters = data?.let(::counters).orEmpty(),
            problems = data?.let(::problems).orEmpty(),
            recent = data?.let(::recent).orEmpty(),
            empty = data != null && data.summary.devicesTotal == 0,
        )
    }

    private fun counters(d: Dashboard): List<CounterUi> {
        val s = d.summary
        val percent = if (s.devicesTotal == 0) 0 else s.devicesOnline * PERCENT / s.devicesTotal
        return listOf(
            CounterUi("Онлайн", "${s.devicesOnline}", "из ${s.devicesTotal} · $percent %", onlineTone(percent)),
            CounterUi(
                "Офлайн", "${s.devicesOffline}",
                if (s.offlineLastHour > 0) "+${s.offlineLastHour} за час" else null,
                if (s.devicesOffline > 0) StatusTone.Warn else StatusTone.Off,
            ),
            CounterUi(
                "Тревоги", "${s.alertsOpen}",
                if (s.alertsCritical > 0) "${s.alertsCritical} критичные" else null,
                if (s.alertsCritical > 0) StatusTone.Crit else if (s.alertsOpen > 0) StatusTone.Warn else StatusTone.Ok,
            ),
            CounterUi(
                "Ресторанов с проблемами", "${s.restaurantsWithProblems}", "из ${s.restaurantsTotal}",
                if (s.restaurantsWithProblems > 0) StatusTone.Warn else StatusTone.Ok,
            ),
        )
    }

    private fun onlineTone(percent: Int): StatusTone = when {
        percent >= ONLINE_OK -> StatusTone.Ok
        percent >= ONLINE_WARN -> StatusTone.Warn
        else -> StatusTone.Crit
    }

    private fun problems(d: Dashboard): List<ProblemUi> = d.summary.problems.map { p ->
        val text = RuleTexts.line(p.ruleId, p.target, p.details, d.loadedAt)
        ProblemUi(
            restaurantId = p.restaurantId,
            restaurantName = p.name,
            line = "$text · ${p.count} из ${p.devicesTotal} устройств",
            ruleId = p.ruleId,
            tone = StatusTone.Crit,
            deviceId = p.deviceId,
        )
    }

    private fun recent(d: Dashboard): List<RecentUi> = d.recent.map { a ->
        RecentUi(
            deviceId = a.deviceId,
            title = RuleTexts.line(a.ruleId, a.target, a.details, d.loadedAt),
            where = listOfNotNull(a.deviceName, a.restaurantName).joinToString(" · "),
            ago = RelativeTime.ago(d.loadedAt, a.openedAt),
            ruleId = a.ruleId,
            tone = if (a.critical) StatusTone.Crit else StatusTone.Warn,
        )
    }

    private fun errorText(error: ConsoleError): String = when (error) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.Unauthorized -> "Сессия истекла, войдите заново"
        ConsoleError.Forbidden -> "Недостаточно прав"
        else -> "Не удалось загрузить сводку"
    }

    private companion object {
        const val PERCENT = 100
        const val ONLINE_OK = 90
        const val ONLINE_WARN = 70
    }
}
