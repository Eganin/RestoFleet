package ru.restofleet.console.feature.devices.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.designsystem.StatusTone

@Immutable
data class DeviceRowUi(
    val id: String,
    val title: String,
    val metrics: String,
    val statusText: String,
    val tone: StatusTone,
    val ruleId: String?,
    val ruleTone: StatusTone,
)

@Immutable
data class FilterOptionUi(val value: String?, val label: String, val selected: Boolean)

@Immutable
data class DevicesUiState(
    val title: String,
    val query: String,
    val problemsOnly: Boolean,
    val count: Int,
    val rows: List<DeviceRowUi>,
    val restaurants: List<FilterOptionUi>,
    val roles: List<FilterOptionUi>,
    val statuses: List<FilterOptionUi>,
    val versions: List<FilterOptionUi>,
    val loading: Boolean,
    val loadingMore: Boolean,
    val canLoadMore: Boolean,
    val errorText: String?,
    val emptyText: String?,
)
