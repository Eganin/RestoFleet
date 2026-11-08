package ru.restofleet.agent.presentation.deviceowner.viewmodel

import ru.restofleet.console.core.mvi.contract.MviEffect

/** Текст пользователю: тип сообщения, а не готовая строка — строки живут в ресурсах UI. */
sealed interface DeviceOwnerMessage {
    data object HashFetched : DeviceOwnerMessage
    data class HashFailed(val code: String) : DeviceOwnerMessage
    data class Error(val details: String) : DeviceOwnerMessage
}

sealed interface DeviceOwnerEffect : MviEffect {
    data class ShowMessage(val text: DeviceOwnerMessage) : DeviceOwnerEffect

    /** Показать диалог подтверждения перезагрузки; ответ — `DeviceOwnerIntent.User.ConfirmReboot`. */
    data object ConfirmReboot : DeviceOwnerEffect
}
