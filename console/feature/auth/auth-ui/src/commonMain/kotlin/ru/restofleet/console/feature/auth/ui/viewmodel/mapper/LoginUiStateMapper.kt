package ru.restofleet.console.feature.auth.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.feature.auth.domain.Login
import ru.restofleet.console.feature.auth.ui.viewmodel.LoginUiState
import ru.restofleet.console.feature.auth.ui.viewmodel.model.LoginSession

/** Session → UiState: русские тексты ошибок строятся здесь, а не в Screen. */
class LoginUiStateMapper {
    fun map(session: LoginSession): LoginUiState {
        val error = session.error
        val field = (error as? ConsoleError.InvalidInput)?.field
        return LoginUiState(
            server = session.server,
            login = session.login,
            password = session.password,
            loading = session.submitting,
            submitEnabled = !session.submitting && session.server.isNotBlank() &&
                session.login.isNotBlank() && session.password.isNotEmpty(),
            serverError = when {
                error is ConsoleError.InvalidAddress -> addressText(error.reason)
                field == Login.FIELD_SERVER -> "Укажите адрес сервера"
                else -> null
            },
            loginError = if (field == Login.FIELD_LOGIN) "Укажите логин" else null,
            passwordError = if (field == Login.FIELD_PASSWORD) "Введите пароль" else null,
            formError = formText(error),
        )
    }

    private fun addressText(reason: String): String = when (reason) {
        "https_required" -> "Нужен адрес https://"
        "userinfo" -> "Уберите логин и пароль из адреса"
        else -> "Неверный адрес сервера"
    }

    private fun formText(error: ConsoleError?): String? = when (error) {
        ConsoleError.Unauthorized -> "Неверный логин или пароль"
        ConsoleError.Network -> "Нет связи с сервером"
        is ConsoleError.RateLimited -> "Слишком много попыток, повторите позже"
        is ConsoleError.Server -> "Ошибка сервера (${error.status})"
        ConsoleError.Forbidden -> "Доступ запрещён"
        null, is ConsoleError.InvalidAddress, is ConsoleError.InvalidInput -> null
        else -> "Не удалось войти"
    }
}
