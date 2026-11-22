package ru.restofleet.agent.presentation.root.viewmodel

import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.console.core.mvi.contract.MviAction

sealed interface RootAction : MviAction {
    data class RegistrationResolved(val state: RegistrationState) : RootAction
    data object LabOpened : RootAction
    data object LabClosed : RootAction
    data object SetupCompleted : RootAction
}
