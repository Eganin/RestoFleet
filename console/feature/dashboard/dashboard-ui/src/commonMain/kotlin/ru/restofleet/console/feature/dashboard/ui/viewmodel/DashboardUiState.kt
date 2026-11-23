package ru.restofleet.console.feature.dashboard.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.designsystem.StatusTone

@Immutable
data class CounterUi(val label: String, val value: String, val caption: String?, val tone: StatusTone?)

/** [key] — уникальный ключ строки в списке (V-1). */
@Immutable
data class ProblemUi(
    val key: String,
    val restaurantId: String,
    val restaurantName: String,
    val line: String,
    val ruleId: String,
    val tone: StatusTone,
    val deviceId: String?,
)

/** [key] — уникальный ключ строки в списке: id тревоги (V-1). */
@Immutable
data class RecentUi(
    val key: String,
    val deviceId: String,
    val title: String,
    val where: String,
    val ago: String,
    val ruleId: String,
    val tone: StatusTone,
)

@Immutable
data class DashboardUiState(
    val loading: Boolean,
    val errorText: String?,
    val counters: List<CounterUi>,
    val problems: List<ProblemUi>,
    val recent: List<RecentUi>,
    val empty: Boolean,
)
