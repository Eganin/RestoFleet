package ru.restofleet.agent.presentation.setup.viewmodel

import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep
import ru.restofleet.console.core.mvi.contract.MviAction

sealed interface SetupAction : MviAction {
    data class PermissionsLoaded(val granted: Set<Permission>) : SetupAction
    data class StepChanged(val step: SetupStep) : SetupAction
    data class BaseUrlChanged(val text: String) : SetupAction
    data class CodeChanged(val text: String) : SetupAction
    data object SubmitStarted : SetupAction
    data class SubmitFailed(val error: ProvisionError) : SetupAction
    data class Provisioned(val registration: Registration) : SetupAction
}
