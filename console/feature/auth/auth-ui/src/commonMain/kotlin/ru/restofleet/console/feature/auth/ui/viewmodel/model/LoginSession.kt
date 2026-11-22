package ru.restofleet.console.feature.auth.ui.viewmodel.model

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviState

/** Источник истины экрана входа. Пароль в `toString` не попадает. */
data class LoginSession(
    val server: String = "",
    val login: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: ConsoleError? = null,
) : MviState {
    override fun toString(): String =
        "LoginSession(server=$server, login=$login, password=***, submitting=$submitting, error=$error)"
}
