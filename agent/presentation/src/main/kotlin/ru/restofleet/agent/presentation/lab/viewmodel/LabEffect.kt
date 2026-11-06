package ru.restofleet.agent.presentation.lab.viewmodel

import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.console.core.mvi.contract.MviEffect

/** Текст пользователю: тип сообщения, а не готовая строка — строки живут в ресурсах UI. */
sealed interface LabMessage {
    data class Exported(val path: String) : LabMessage
    data class Published(val id: CheckId) : LabMessage
    data class Error(val details: String) : LabMessage
    data object MqttNotConfigured : LabMessage
    data object NoResult : LabMessage
}

sealed interface LabEffect : MviEffect {
    data class ShowMessage(val text: LabMessage) : LabEffect
    data class OpenSystemSettings(val target: SettingsTarget) : LabEffect
}
