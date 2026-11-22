package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable

/** `POST /auth/login`. [toString] пароль не раскрывает. */
@Serializable
data class LoginRequestDto(val login: String, val password: String) {
    override fun toString(): String = "LoginRequestDto(login=$login, password=***)"
}

/** `POST /auth/refresh`: refresh одноразовый, в ответе — новая пара (ротация, реш. 216, 239, 417). */
@Serializable
data class RefreshRequestDto(val refreshToken: String) {
    override fun toString(): String = "RefreshRequestDto(refreshToken=***)"
}

/**
 * `POST /auth/logout` (реш. 417): отзывает семейство refresh-токенов сессии. JWT не нужен (access мог истечь),
 * ответ всегда `204` — по ответу нельзя узнать, был ли токен действителен.
 */
@Serializable
data class LogoutRequestDto(val refreshToken: String) {
    override fun toString(): String = "LogoutRequestDto(refreshToken=***)"
}

/**
 * Пара токенов входа и ротации. Access — JWT на 15 мин; refresh — до `min(now + 24 ч, вход + 7 сут)` (реш. 417).
 * Время — epoch ms. [toString] токены не раскрывает.
 */
@Serializable
data class TokenResponseDto(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val tokenType: String = "Bearer",
) {
    override fun toString(): String =
        "TokenResponseDto(accessToken=***, refreshToken=***, accessExpiresAt=$accessExpiresAt, " +
            "refreshExpiresAt=$refreshExpiresAt)"
}
