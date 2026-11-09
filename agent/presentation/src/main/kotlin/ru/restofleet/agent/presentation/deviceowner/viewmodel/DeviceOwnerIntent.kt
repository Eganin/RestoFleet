package ru.restofleet.agent.presentation.deviceowner.viewmodel

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.console.core.mvi.contract.MviIntent

sealed interface DeviceOwnerIntent : MviIntent {
    sealed interface User : DeviceOwnerIntent {
        /** Перечитать режим и значения D1, R10, R13 (экран открыт, роль могли назначить командой `dpm`). */
        data object Refresh : User
        data object RunAll : User
        data class RunOne(val id: CheckId) : User

        /** D5 «через 30 с»: пауза, чтобы успеть нажать Home и запустить цель из фона. */
        data object RunLaunchFromBackground : User
        data class EditApkUrl(val text: String) : User
        data class EditApkSha256(val text: String) : User
        data class EditTargetPackage(val text: String) : User
        data class EditTargetUrl(val text: String) : User
        data object FetchChecksum : User

        /** Ответ на диалог перезагрузки; без запроса диалога игнорируется. */
        data class ConfirmReboot(val confirmed: Boolean) : User
    }
}
