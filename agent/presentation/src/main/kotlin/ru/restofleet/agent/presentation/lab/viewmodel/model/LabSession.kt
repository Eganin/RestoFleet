package ru.restofleet.agent.presentation.lab.viewmodel.model

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.console.core.mvi.contract.MviState

/**
 * Источник истины экрана. [running] — проверки, которые выполняются сейчас; [isRunningAll] — идёт «Запустить всё»;
 * [isExporting] — идёт экспорт отчёта (он заново прогоняет все проверки).
 */
data class LabSession(
    val results: Map<CheckId, CheckResult> = emptyMap(),
    val running: Set<CheckId> = emptySet(),
    val filter: LabFilter = LabFilter.ALL,
    val device: DeviceInfo? = null,
    val isRunningAll: Boolean = false,
    val isExporting: Boolean = false,
) : MviState
