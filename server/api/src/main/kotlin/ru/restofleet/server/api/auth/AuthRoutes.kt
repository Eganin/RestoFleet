package ru.restofleet.server.api.auth

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.restofleet.server.api.AuthUseCases
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.noStore
import ru.restofleet.server.domain.auth.SessionTokens

/** `POST /auth/login`. [toString] пароль не раскрывает. */
@Serializable
data class LoginRequest(val login: String, val password: String) {
    override fun toString(): String = "LoginRequest(login=$login, password=***)"
}

/** `POST /auth/refresh`: refresh одноразовый, в ответе — новая пара (ротация, реш. 216, 239). */
@Serializable
data class RefreshRequest(val refreshToken: String) {
    override fun toString(): String = "RefreshRequest(refreshToken=***)"
}

@Serializable
data class TokenResponse(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val tokenType: String = "Bearer",
) {
    override fun toString(): String =
        "TokenResponse(accessToken=***, refreshToken=***, accessExpiresAt=$accessExpiresAt)"
}

/** Вход и ротация (FR-BE-52): ошибка всегда `401 unauthorized` без уточнения причины. Login — под rate limit. */
fun Route.loginRoute(useCases: AuthUseCases) {
    post("/auth/login") {
        val request = call.receive<LoginRequest>()
        val tokens = withContext(Dispatchers.Default) { useCases.login(request.login, request.password) }.orThrow()
        call.noStore()
        call.respond(tokens.toDto())
    }
}

fun Route.refreshRoute(useCases: AuthUseCases) {
    post("/auth/refresh") {
        val tokens = useCases.refresh(call.receive<RefreshRequest>().refreshToken).orThrow()
        call.noStore()
        call.respond(tokens.toDto())
    }
}

private fun SessionTokens.toDto() = TokenResponse(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt)
