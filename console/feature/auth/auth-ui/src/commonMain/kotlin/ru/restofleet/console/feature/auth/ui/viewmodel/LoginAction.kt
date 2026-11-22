package ru.restofleet.console.feature.auth.ui.viewmodel

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviAction

sealed interface LoginAction : MviAction {
    data class ServerChanged(val value: String) : LoginAction
    data class LoginChanged(val value: String) : LoginAction
    data class PasswordChanged(val value: String) : LoginAction
    data object SubmitStarted : LoginAction
    data object SubmitSucceeded : LoginAction
    data class SubmitFailed(val error: ConsoleError) : LoginAction
}
