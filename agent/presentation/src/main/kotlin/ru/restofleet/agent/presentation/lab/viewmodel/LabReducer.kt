package ru.restofleet.agent.presentation.lab.viewmodel

import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.DeviceLoaded
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.ExportingChanged
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.FilterSelected
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.ResultsArrived
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.RunFinished
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.RunStarted
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabSession
import ru.restofleet.console.core.mvi.Reducer

/** Чистая функция `(действие, сессия) -> сессия`; если ничего не меняется — возвращает тот же экземпляр. */
class LabReducer : Reducer<LabAction, LabSession> {
    override fun reduce(action: LabAction, state: LabSession): LabSession = when (action) {
        is DeviceLoaded -> state.copy(device = action.device)
        is RunStarted -> state.copy(
            running = state.running + action.ids,
            isRunningAll = state.isRunningAll || action.isAll,
        )
        is RunFinished -> {
            val running = state.running - action.ids
            state.copy(running = running, isRunningAll = state.isRunningAll && running.isNotEmpty())
        }
        is ResultsArrived -> {
            val running = state.running - action.results.map { it.id }.toSet()
            state.copy(
                results = state.results + action.results.associateBy { it.id },
                running = running,
                isRunningAll = state.isRunningAll && running.isNotEmpty(),
            )
        }
        is FilterSelected -> if (action.filter == state.filter) state else state.copy(filter = action.filter)
        is ExportingChanged -> state.copy(isExporting = action.isExporting)
        is LabAction.CommandPackageChanged -> state.copy(commandPackage = action.text)
        is LabAction.CommandUrlChanged -> state.copy(commandUrl = action.text)
    }
}
