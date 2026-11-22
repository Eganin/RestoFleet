package ru.restofleet.console.feature.auth.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

/** Вход. Пустые поля отсекаются до запроса; адрес проверяет репозиторий (только https). */
class Login(private val repository: AuthRepository) {
    suspend operator fun invoke(server: String, login: String, password: String): Outcome<AuthSession, ConsoleError> =
        when {
            server.isBlank() -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_SERVER, "empty"))
            login.isBlank() -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_LOGIN, "empty"))
            password.isEmpty() -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_PASSWORD, "empty"))
            else -> repository.login(server.trim(), login.trim(), password)
        }

    companion object {
        const val FIELD_SERVER = "server"
        const val FIELD_LOGIN = "login"
        const val FIELD_PASSWORD = "password"
    }
}

class Logout(private val repository: AuthRepository) {
    suspend operator fun invoke() = repository.logout()
}

class RestoreSession(private val repository: AuthRepository) {
    suspend operator fun invoke(): AuthSession? = repository.restore()
}

class ObserveSession(private val repository: AuthRepository) {
    operator fun invoke(): Flow<AuthSession?> = repository.session
}
