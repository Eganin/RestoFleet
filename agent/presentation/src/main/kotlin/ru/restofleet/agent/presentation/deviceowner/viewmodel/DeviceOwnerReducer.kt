package ru.restofleet.agent.presentation.deviceowner.viewmodel

import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.ApkSha256Changed
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.ApkUrlChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.DeviceLoaded
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.FetchingHashChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.ResultsArrived
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.RunFinished
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.RunStarted
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.TargetPackageChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.TargetUrlChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession
import ru.restofleet.console.core.mvi.Reducer

/** Чистая функция `(действие, сессия) -> сессия`. */
class DeviceOwnerReducer : Reducer<DeviceOwnerAction, DeviceOwnerSession> {
    override fun reduce(action: DeviceOwnerAction, state: DeviceOwnerSession): DeviceOwnerSession = when (action) {
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
        is ApkUrlChanged -> state.copy(apkUrl = action.text)
        is ApkSha256Changed -> state.copy(apkSha256 = action.text)
        is TargetPackageChanged -> state.copy(targetPackage = action.text)
        is TargetUrlChanged -> state.copy(targetUrl = action.text)
        is FetchingHashChanged -> state.copy(isFetchingHash = action.isFetching)
    }
}
