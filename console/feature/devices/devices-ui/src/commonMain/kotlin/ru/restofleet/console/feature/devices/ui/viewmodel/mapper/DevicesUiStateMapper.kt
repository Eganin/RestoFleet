package ru.restofleet.console.feature.devices.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.core.common.RuleTexts
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.devices.domain.DeviceItem
import ru.restofleet.console.feature.devices.ui.viewmodel.DeviceRowUi
import ru.restofleet.console.feature.devices.ui.viewmodel.DevicesUiState
import ru.restofleet.console.feature.devices.ui.viewmodel.FilterOptionUi
import ru.restofleet.console.feature.devices.ui.viewmodel.model.DevicesSession

class DevicesUiStateMapper {
    fun map(s: DevicesSession): DevicesUiState {
        val f = s.filter
        val filtered = f.query.isNotBlank() || f.restaurantId != null || f.role != null || f.status != null || f.version != null
        return DevicesUiState(
            title = s.title ?: "Устройства",
            query = f.query,
            problemsOnly = f.problemsOnly,
            count = s.items.size,
            rows = s.items.map { row(it, s.loadedAt) },
            restaurants = options(f.restaurantId, s.restaurants.map { it.id to it.label }),
            roles = options(f.role, ROLES),
            statuses = options(f.status, STATUSES),
            versions = options(f.version, s.items.mapNotNull { it.agentVersion }.distinct().sorted().map { it to it }),
            loading = s.loading,
            loadingMore = s.loadingMore,
            canLoadMore = s.hasMore && !s.loadingMore,
            errorText = s.error?.let(::errorText),
            emptyText = when {
                s.loading || s.error != null || s.items.isNotEmpty() -> null
                f.problemsOnly && !filtered -> "Проблем нет — все устройства в порядке"
                else -> "Ничего не найдено"
            },
        )
    }

    private fun row(d: DeviceItem, now: Long): DeviceRowUi {
        val alert = d.alerts.firstOrNull()
        val online = d.status == "online"
        return DeviceRowUi(
            id = d.id,
            title = listOfNotNull(d.name ?: d.id, d.restaurantName).joinToString(" · "),
            metrics = if (online) onlineMetrics(d, alert, now) else offlineMetrics(d, now),
            statusText = STATUS_TEXT[d.status] ?: d.status,
            tone = when {
                alert?.critical == true -> StatusTone.Crit
                alert != null -> StatusTone.Warn
                online -> StatusTone.Ok
                else -> StatusTone.Off
            },
            ruleId = alert?.ruleId,
            ruleTone = if (alert?.critical == true) StatusTone.Crit else StatusTone.Warn,
        )
    }

    private fun onlineMetrics(d: DeviceItem, alert: ru.restofleet.console.feature.devices.domain.DeviceAlert?, now: Long): String {
        val parts = mutableListOf<String>()
        d.batteryLevel?.let { parts += "$it %" + if (d.charging == true) " ⚡" else "" }
        d.tempC?.let { parts += "$it °C" }
        d.thermal?.takeIf { it != "NONE" }?.let { parts += it }
        // Заряд и температура уже показаны из телеметрии; для остальных правил подробность тревоги добавляется
        if (alert != null && alert.ruleId !in SHOWN_BY_TELEMETRY) {
            RuleTexts.detail(alert.ruleId, alert.details, now).takeIf { it.isNotBlank() }?.let { parts += it }
        }
        return parts.joinToString(" · ")
    }

    private fun offlineMetrics(d: DeviceItem, now: Long): String {
        val since = d.lastSeenAt?.let { "офлайн ${RelativeTime.duration(now - it)}" } ?: "ещё не выходило на связь"
        return listOfNotNull(since, d.lastSessionReason).joinToString(" · ")
    }

    private fun options(selected: String?, values: List<Pair<String, String>>): List<FilterOptionUi> =
        listOf(FilterOptionUi(null, "Все", selected == null)) +
            values.map { (v, label) -> FilterOptionUi(v, label, v == selected) }

    private fun errorText(e: ConsoleError): String = when (e) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.Unauthorized -> "Сессия истекла, войдите заново"
        else -> "Не удалось загрузить устройства"
    }

    private companion object {
        val SHOWN_BY_TELEMETRY = setOf("R-02", "R-03")
        val ROLES = listOf("kitchen" to "Кухня", "hall" to "Зал", "assembly" to "Сборка")
        val STATUSES = listOf("online" to "Онлайн", "offline" to "Офлайн", "provisioned" to "Не подключено")
        val STATUS_TEXT = mapOf("online" to "online", "offline" to "офлайн", "provisioned" to "не подключено")
    }
}
