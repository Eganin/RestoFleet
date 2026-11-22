package ru.restofleet.console.feature.auth.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer
import ru.restofleet.console.feature.auth.ui.viewmodel.model.LoginSession

/** Ввод сбрасывает прошлую ошибку; во время отправки поля не меняются. */
class LoginReducer : Reducer<LoginAction, LoginSession> {
    override fun reduce(action: LoginAction, state: LoginSession): LoginSession = when (action) {
        is LoginAction.ServerChanged -> editable(state) { it.copy(server = action.value, error = null) }
        is LoginAction.LoginChanged -> editable(state) { it.copy(login = action.value, error = null) }
        is LoginAction.PasswordChanged -> editable(state) { it.copy(password = action.value, error = null) }
        LoginAction.SubmitStarted -> state.copy(submitting = true, error = null)
        LoginAction.SubmitSucceeded -> state.copy(submitting = false, password = "", error = null)
        is LoginAction.SubmitFailed -> state.copy(submitting = false, error = action.error)
    }

    private fun editable(state: LoginSession, change: (LoginSession) -> LoginSession): LoginSession =
        if (state.submitting) state else change(state)
}
