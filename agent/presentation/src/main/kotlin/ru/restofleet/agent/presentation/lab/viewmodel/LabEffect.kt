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

    /** Итог проверки ACL (M2): [reason] — код PUBACK (`0x87 not_authorized` ожидается). */
    data class ForeignTopic(val accepted: Boolean, val reason: String) : LabMessage

    /** Серия `debug_seq` отправлена (M7). */
    data class DebugEventsSent(val count: Int, val batchId: String) : LabMessage
}

sealed interface LabEffect : MviEffect {
    data class ShowMessage(val text: LabMessage) : LabEffect
    data class OpenSystemSettings(val target: SettingsTarget) : LabEffect
}
