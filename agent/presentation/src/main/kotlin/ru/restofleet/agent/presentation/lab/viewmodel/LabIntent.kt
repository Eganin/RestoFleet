package ru.restofleet.agent.presentation.lab.viewmodel

import ru.restofleet.agent.presentation.lab.viewmodel.model.LabCommand
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.console.core.mvi.contract.MviIntent

sealed interface LabIntent : MviIntent {
    sealed interface User : LabIntent {
        data object RunAll : User
        data class RunOne(val id: CheckId) : User
        data class SelectFilter(val filter: LabFilter) : User
        data object Export : User
        data class SendToMqtt(val id: CheckId) : User
        data class OpenSettings(val target: SettingsTarget) : User
        data class EditCommandPackage(val text: String) : User
        data class EditCommandUrl(val text: String) : User
        data class RunCommand(val command: LabCommand) : User
    }
}
