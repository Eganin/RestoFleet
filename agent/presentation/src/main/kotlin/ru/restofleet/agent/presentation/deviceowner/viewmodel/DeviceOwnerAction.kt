package ru.restofleet.agent.presentation.deviceowner.viewmodel

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.console.core.mvi.contract.MviAction

sealed interface DeviceOwnerAction : MviAction {
    data class DeviceLoaded(val device: DeviceInfo) : DeviceOwnerAction

    /** [isAll] — идёт «Запустить все проверки DO», а не одна строка. */
    data class RunStarted(val ids: Set<CheckId>, val isAll: Boolean = false) : DeviceOwnerAction

    /** Запуск [ids] завершился без результата (сбой, пропуск или отмена): строки перестают «крутиться». */
    data class RunFinished(val ids: Set<CheckId>) : DeviceOwnerAction
    data class ResultsArrived(val results: List<CheckResult>) : DeviceOwnerAction
    data class ApkUrlChanged(val text: String) : DeviceOwnerAction
    data class ApkSha256Changed(val text: String) : DeviceOwnerAction
    data class TargetPackageChanged(val text: String) : DeviceOwnerAction
    data class TargetUrlChanged(val text: String) : DeviceOwnerAction
    data class FetchingHashChanged(val isFetching: Boolean) : DeviceOwnerAction
}
