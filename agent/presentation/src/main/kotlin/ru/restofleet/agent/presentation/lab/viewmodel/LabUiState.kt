package ru.restofleet.agent.presentation.lab.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabCommand
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.common.SettingsTarget

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

/** Кнопка секции «Команды»; [isRunning] — команда исполняется (или ждёт задержки «через 30 с»). */
data class CommandButtonUi(val command: LabCommand, val isRunning: Boolean)

/** Секция «Команды»: поля пакета и адреса и кнопки C1–C4. */
data class CommandsUi(val pkg: String, val url: String, val buttons: List<CommandButtonUi>)

@Immutable
data class LabUiState(
    val header: String,
    val tabs: List<TabUi>,
    val rows: List<CheckRowUi>,
    val isRunningAll: Boolean,
    val isExporting: Boolean,
    /** `null` — секция скрыта (она показывается на вкладке «Все»). */
    val commands: CommandsUi?,
    /** Кнопки отладки M2/M7 (только debug, вкладка «Все»). */
    val debugTools: Boolean = false,
)
