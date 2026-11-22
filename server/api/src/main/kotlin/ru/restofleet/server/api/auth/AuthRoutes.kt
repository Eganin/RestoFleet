package ru.restofleet.server.api.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.restofleet.server.api.AuthUseCases
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.noStore
import ru.restofleet.server.domain.auth.SessionTokens
import ru.restofleet.shared.protocol.rest.console.LoginRequestDto
import ru.restofleet.shared.protocol.rest.console.LogoutRequestDto
import ru.restofleet.shared.protocol.rest.console.RefreshRequestDto
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

/** Вход и ротация (FR-BE-52): ошибка всегда `401 unauthorized` без уточнения причины. Login — под rate limit. */
fun Route.loginRoute(useCases: AuthUseCases) {
    post("/auth/login") {
        val request = call.receive<LoginRequestDto>()
        val tokens = withContext(Dispatchers.Default) { useCases.login(request.login, request.password) }.orThrow()
        call.noStore()
        call.respond(tokens.toDto())
    }
}

/** `POST /auth/logout` (реш. 417, 438): без JWT, всегда 204 — ответ не выдаёт, был ли токен действителен. */
fun Route.logoutRoute(useCases: AuthUseCases) {
    post("/auth/logout") {
        useCases.logout(call.receive<LogoutRequestDto>().refreshToken)
        call.respond(HttpStatusCode.NoContent)
    }
}

fun Route.refreshRoute(useCases: AuthUseCases) {
    post("/auth/refresh") {
        val tokens = useCases.refresh(call.receive<RefreshRequestDto>().refreshToken).orThrow()
        call.noStore()
        call.respond(tokens.toDto())
    }
}

private fun SessionTokens.toDto() = TokenResponseDto(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt)
