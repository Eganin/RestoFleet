package ru.restofleet.server.api

/** Настройки REST API; заполняет `server:app` из конфигурации (секрет JWT — из файла, реш. 252). */
data class ApiSettings(
    val jwt: JwtSettings,
    val rateLimit: RateLimitSettings = RateLimitSettings(),
    /** Предел тела запроса (413 сверх него); nginx стенда режет на том же уровне. */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_BODY_BYTES: Long = 1024L * 1024
    }
}

/**
 * Access JWT (реш. 216, 250): HS256, `iss`/`aud` из конфигурации, срок — 15 мин. [secret] — не короче
 * [MIN_SECRET_BYTES] байт; [toString] его не раскрывает.
 */
data class JwtSettings(
    val secret: String,
    val issuer: String = DEFAULT_ISSUER,
    val audience: String = DEFAULT_AUDIENCE,
    val realm: String = DEFAULT_REALM,
    val accessTtlMillis: Long = DEFAULT_ACCESS_TTL_MS,
) {
    init {
        require(secret.encodeToByteArray().size >= MIN_SECRET_BYTES) {
            "JWT secret must be at least $MIN_SECRET_BYTES bytes"
        }
        require(accessTtlMillis > 0) { "access TTL must be positive" }
    }

    override fun toString(): String =
        "JwtSettings(secret=***, issuer=$issuer, audience=$audience, realm=$realm, accessTtlMillis=$accessTtlMillis)"

    companion object {
        const val MIN_SECRET_BYTES = 32
        const val DEFAULT_ISSUER = "restofleet"
        const val DEFAULT_AUDIENCE = "restofleet-api"
        const val DEFAULT_REALM = "restofleet"
        const val DEFAULT_ACCESS_TTL_MS: Long = 15L * 60 * 1000
    }
}

/**
 * Rate limit (арх. разд. 12, реш. 249): корзина токенов на клиента (IP из `X-Forwarded-For` nginx или адрес
 * соединения), пополнение раз в [periodMillis]. Симулятор (КМ4) поднимает [provisionPerPeriod] конфигом стенда.
 */
data class RateLimitSettings(
    val loginPerPeriod: Int = 10,
    val provisionPerPeriod: Int = 60,
    val periodMillis: Long = 60_000,
) {
    init {
        require(loginPerPeriod > 0 && provisionPerPeriod > 0 && periodMillis > 0) { "rate limits must be positive" }
    }
}
