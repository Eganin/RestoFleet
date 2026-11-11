package ru.restofleet.server.domain.auth

/** Пользователь консоли/Swagger (FR-BE-52; в К одна роль `admin`, реш. 216). */
data class User(
    val id: String,
    val login: String,
    val passwordHash: String,
    val role: String,
    val createdAt: Long,
) {
    override fun toString(): String = "User(id=$id, login=$login, passwordHash=***, role=$role, createdAt=$createdAt)"

    companion object {
        const val ROLE_ADMIN = "admin"
    }
}

/** Refresh-токен (реш. 216): в БД только SHA-256, одноразовый (ротация). */
data class RefreshToken(
    val id: String,
    val userId: String,
    val tokenHash: String,
    val createdAt: Long,
    val expiresAt: Long,
    val revokedAt: Long?,
)

/** Подписанный access-токен (JWT подписывает `server:api`/`server:app` — порт [AccessTokenIssuer]). */
data class AccessToken(val token: String, val expiresAt: Long) {
    override fun toString(): String = "AccessToken(token=***, expiresAt=$expiresAt)"
}

/** Пара токенов после входа или ротации. [toString] секреты не раскрывает. */
data class SessionTokens(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
) {
    override fun toString(): String =
        "SessionTokens(accessToken=***, accessExpiresAt=$accessExpiresAt, refreshToken=***, " +
            "refreshExpiresAt=$refreshExpiresAt)"
}

/** Настройки сессий пользователей. Access TTL (15 мин) — у [AccessTokenIssuer]. */
data class AuthSettings(val refreshTtlMillis: Long = DEFAULT_REFRESH_TTL_MS) {
    companion object {
        const val DEFAULT_REFRESH_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/** Порт выпуска access JWT (issuer/audience/секрет — конфигурация api/app). */
fun interface AccessTokenIssuer {
    fun issue(user: User): AccessToken
}

/** Порт пользователей. Только в транзакции. */
interface UserRepository {
    suspend fun insert(user: User)

    suspend fun findByLogin(login: String): User?

    suspend fun findById(id: String): User?

    suspend fun updatePassword(id: String, passwordHash: String)
}

/** Порт refresh-токенов. Только в транзакции. */
interface RefreshTokenRepository {
    suspend fun insert(token: RefreshToken)

    /**
     * Атомарное погашение (ротация): `UPDATE … SET revoked_at = now WHERE token_hash = ? AND revoked_at IS NULL AND
     * expires_at > now RETURNING`; `null` — токен неизвестен, просрочен или уже погашен.
     */
    suspend fun consume(tokenHash: String, now: Long): RefreshToken?

    suspend fun findByHash(tokenHash: String): RefreshToken?

    /** Отзыв всех действующих токенов пользователя (повторное использование погашенного токена). */
    suspend fun revokeAll(userId: String, now: Long): Int
}
