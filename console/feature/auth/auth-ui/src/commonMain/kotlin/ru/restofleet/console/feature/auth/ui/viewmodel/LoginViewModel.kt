package ru.restofleet.console.feature.auth.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.auth.domain.Login
import ru.restofleet.console.feature.auth.ui.api.IAuthRouter
import ru.restofleet.console.feature.auth.ui.viewmodel.mapper.LoginUiStateMapper
import ru.restofleet.console.feature.auth.ui.viewmodel.model.LoginSession
import ru.restofleet.shared.kernel.Outcome

class LoginViewModel(
    private val login: Login,
    private val router: IAuthRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<LoginSession, LoginUiState, LoginIntent, LoginAction, LoginEffect>(
    store = MviStore(LoginSession(), LoginReducer(), LoginUiStateMapper()::map),
    scope = scope,
) {
    override fun onIntent(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.User.ServerChanged -> reduce(LoginAction.ServerChanged(intent.value))
            is LoginIntent.User.LoginChanged -> reduce(LoginAction.LoginChanged(intent.value))
            is LoginIntent.User.PasswordChanged -> reduce(LoginAction.PasswordChanged(intent.value))
            LoginIntent.User.Submit -> submit()
        }
    }

    private fun submit() {
        val s = state
        if (s.submitting) return
        emitEffect(LoginEffect.HideKeyboard)
        reduce(LoginAction.SubmitStarted)
        scope.launch {
            when (val result = login(s.server, s.login, s.password)) {
                is Outcome.Success -> {
                    reduce(LoginAction.SubmitSucceeded)
                    router.onLoggedIn()
                }
                is Outcome.Failure -> reduce(LoginAction.SubmitFailed(result.error))
            }
        }
    }
}
