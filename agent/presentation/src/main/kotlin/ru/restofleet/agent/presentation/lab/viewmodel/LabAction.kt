package ru.restofleet.agent.presentation.lab.viewmodel

import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.console.core.mvi.contract.MviAction

sealed interface LabAction : MviAction {
    data class DeviceLoaded(val device: DeviceInfo) : LabAction

    /** [isAll] — запущены все проверки («Запустить всё»), а не одна строка. */
    data class RunStarted(val ids: Set<CheckId>, val isAll: Boolean = false) : LabAction

    /** Запуск [ids] завершился без результата (сбой use case или отмена): строки перестают «крутиться». */
    data class RunFinished(val ids: Set<CheckId>) : LabAction
    data class ResultsArrived(val results: List<CheckResult>) : LabAction
    data class FilterSelected(val filter: LabFilter) : LabAction
    data class ExportingChanged(val isExporting: Boolean) : LabAction
    data class CommandPackageChanged(val text: String) : LabAction
    data class CommandUrlChanged(val text: String) : LabAction
}
