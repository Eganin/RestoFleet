package ru.restofleet.console.feature.dashboard.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.designsystem.StatusTone

@Immutable
data class CounterUi(val label: String, val value: String, val caption: String?, val tone: StatusTone?)

@Immutable
data class ProblemUi(
    val restaurantId: String,
    val restaurantName: String,
    val line: String,
    val ruleId: String,
    val tone: StatusTone,
    val deviceId: String?,
)

@Immutable
data class RecentUi(
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
