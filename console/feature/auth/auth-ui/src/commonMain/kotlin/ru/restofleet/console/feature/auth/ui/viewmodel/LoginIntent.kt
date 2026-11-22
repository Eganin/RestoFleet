package ru.restofleet.console.feature.auth.ui.viewmodel

import ru.restofleet.console.core.mvi.contract.MviIntent

sealed interface LoginIntent : MviIntent {
    sealed interface User : LoginIntent {
        data class ServerChanged(val value: String) : User
        data class LoginChanged(val value: String) : User
        data class PasswordChanged(val value: String) : User
        data object Submit : User
    }
}
