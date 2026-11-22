package ru.restofleet.console.feature.auth.data

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.console.core.network.apiCallUnit
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.LoginRequestDto
import ru.restofleet.shared.protocol.rest.console.LogoutRequestDto
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

/** REST входа и выхода. Клиент — без токенов; сервер приходит абсолютным URL (его вводят на входе). */
class AuthApi(private val client: HttpClient) {
    suspend fun login(server: String, login: String, password: String): Outcome<TokenResponseDto, ConsoleError> =
        apiCall { client.post(server + ConsolePaths.LOGIN) { setBody(LoginRequestDto(login, password)) } }

    suspend fun logout(server: String, refreshToken: String): Outcome<Unit, ConsoleError> =
        apiCallUnit { client.post(server + ConsolePaths.LOGOUT) { setBody(LogoutRequestDto(refreshToken)) } }
}
