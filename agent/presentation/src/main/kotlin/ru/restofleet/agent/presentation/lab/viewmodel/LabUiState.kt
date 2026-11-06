package ru.restofleet.agent.presentation.lab.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget

enum class Badge { OK, WARN, FAIL, NA, PENDING }

data class TabUi(val filter: LabFilter, val label: String, val selected: Boolean)

/**
 * Строка проверки. [id] — имя `CheckId`; [canSend] — есть результат для «В MQTT»;
 * [settingsTargets] — кнопки настроек (только у L4).
 */
data class CheckRowUi(
    val id: String,
    val title: String,
    val badge: Badge,
    val summary: String,
    val isRunning: Boolean,
    val canSend: Boolean = false,
    val settingsTargets: List<SettingsTarget> = emptyList(),
)

@Immutable
data class LabUiState(
    val header: String,
    val tabs: List<TabUi>,
    val rows: List<CheckRowUi>,
    val isRunningAll: Boolean,
    val isExporting: Boolean,
)
