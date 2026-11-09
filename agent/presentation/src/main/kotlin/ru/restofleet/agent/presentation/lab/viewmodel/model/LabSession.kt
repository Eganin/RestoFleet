package ru.restofleet.agent.presentation.lab.viewmodel.model

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.console.core.mvi.contract.MviState

/**
 * Источник истины экрана. [running] — проверки, которые выполняются сейчас; [isRunningAll] — идёт «Запустить всё»;
 * [isExporting] — идёт экспорт отчёта (он заново прогоняет все проверки). [commandsAvailable] — подключён ли запуск
 * команд C1–C4 (только debug-сборка, решение 140); по умолчанию выключен. [debugAvailable] — кнопки M2/M7 (тоже
 * только debug).
 */
data class LabSession(
    val results: Map<CheckId, CheckResult> = emptyMap(),
    val running: Set<CheckId> = emptySet(),
    val filter: LabFilter = LabFilter.ALL,
    val device: DeviceInfo? = null,
    val isRunningAll: Boolean = false,
    val isExporting: Boolean = false,
    val commandPackage: String = DEFAULT_COMMAND_PACKAGE,
    val commandUrl: String = "",
    val commandsAvailable: Boolean = false,
    val debugAvailable: Boolean = false,
) : MviState

/** Целевое приложение по умолчанию для C2/C3 — тестовый `tools:dummy-waiter` (Chrome на образе может отсутствовать). */
const val DEFAULT_COMMAND_PACKAGE = "ru.restofleet.dummy.waiter"
