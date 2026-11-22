package ru.restofleet.console.feature.auth.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.ServerAddress
import ru.restofleet.console.core.network.ServerEndpoint
import ru.restofleet.console.core.network.SessionManager
import ru.restofleet.console.core.network.SessionState
import ru.restofleet.console.feature.auth.domain.AuthRepository
import ru.restofleet.console.feature.auth.domain.AuthSession
import ru.restofleet.shared.kernel.Outcome

class AuthRepositoryImpl(
    private val api: AuthApi,
    private val sessions: SessionManager,
    private val endpoint: ServerEndpoint,
) : AuthRepository {
    override val session: Flow<AuthSession?> = sessions.state.map { it.toDomain() }

    override suspend fun restore(): AuthSession? = sessions.restore().toDomain()

    override suspend fun login(server: String, login: String, password: String): Outcome<AuthSession, ConsoleError> =
        when (val address = ServerAddress.normalize(server)) {
            is Outcome.Failure -> address
            is Outcome.Success -> when (val result = api.login(address.value, login, password)) {
                is Outcome.Failure -> result
                is Outcome.Success -> {
                    sessions.establish(address.value, login, result.value)
                    Outcome.Success(AuthSession(address.value, login))
                }
            }
        }

    override suspend fun logout() {
        val refresh = sessions.currentRefreshToken()
        val server = endpoint.baseUrl
        // Выход на сервере — по возможности: сетевая ошибка не должна оставлять пользователя в приложении
        if (refresh != null && server != null) api.logout(server, refresh)
        sessions.end()
    }

    private fun SessionState.toDomain(): AuthSession? = when (this) {
        SessionState.LoggedOut -> null
        is SessionState.LoggedIn -> AuthSession(server, login)
    }
}
