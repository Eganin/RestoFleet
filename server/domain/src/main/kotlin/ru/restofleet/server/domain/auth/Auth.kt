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

/**
 * Refresh-токен (реш. 216, 417): в БД только SHA-256, одноразовый (ротация). [familyId] — сессия: все токены одной
 * цепочки ротаций с одного входа; [sessionExpiresAt] — абсолютный потолок сессии (вход + 7 сут); [rotatedFrom] —
 * токен, ротацией которого выпущен этот (у первого токена входа — `null`; окно прощения повтора, реш. 531).
 */
data class RefreshToken(
    val id: String,
    val userId: String,
    val tokenHash: String,
    val createdAt: Long,
    val expiresAt: Long,
    val revokedAt: Long?,
    val familyId: String,
    val sessionExpiresAt: Long,
    val rotatedFrom: String? = null,
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

/**
 * Настройки сессий пользователей (реш. 417): refresh живёт [refreshIdleMillis] с последней ротации, но не дольше
 * [sessionMaxMillis] со входа. Access TTL (15 мин) — у [AccessTokenIssuer]. [reuseGraceMillis] — окно прощения
 * повтора только что ротированного токена, чей преемник ещё не использован (потерянный ответ, реш. 531); 0 — без окна.
 */
data class AuthSettings(
    val refreshIdleMillis: Long = DEFAULT_REFRESH_IDLE_MS,
    val sessionMaxMillis: Long = DEFAULT_SESSION_MAX_MS,
    val reuseGraceMillis: Long = DEFAULT_REUSE_GRACE_MS,
) {
    init {
        require(refreshIdleMillis in 1..sessionMaxMillis) { "refresh idle must be within the session ceiling" }
        require(reuseGraceMillis >= 0) { "reuse grace must not be negative" }
    }

    companion object {
        /** Два таймаута запроса консоли (30 с): ответ потерян по таймауту, и на повтор остаётся ещё ≈ 30 с. */
        const val DEFAULT_REUSE_GRACE_MS: Long = 60_000
        const val DEFAULT_REFRESH_IDLE_MS: Long = 24L * 60 * 60 * 1000
        const val DEFAULT_SESSION_MAX_MS: Long = 7L * 24 * 60 * 60 * 1000
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

    /** Отзыв действующих токенов сессии ([RefreshToken.familyId]) — выход. */
    suspend fun revokeFamily(familyId: String, now: Long): Int

    /** Есть ли в семействе действующий (не погашенный и не просроченный) токен. */
    suspend fun hasActive(familyId: String, now: Long): Boolean

    /** Действующий токен, выпущенный ротацией токена [tokenId] (`rotated_from`), — ещё не использованный преемник. */
    suspend fun activeSuccessor(tokenId: String, now: Long): RefreshToken?

    /** Атомарно гасит действующий токен по id (`… WHERE id = ? AND revoked_at IS NULL`); `false` — уже погашен. */
    suspend fun revoke(tokenId: String, now: Long): Boolean
}
