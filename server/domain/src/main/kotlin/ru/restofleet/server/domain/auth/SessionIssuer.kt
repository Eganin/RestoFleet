package ru.restofleet.server.domain.auth

import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.SecretGenerator
import ru.restofleet.shared.kernel.IdGenerator

/**
 * Выпуск пары access + refresh для `Login` и `RefreshSession` (внутри их транзакций, реш. 417): вход начинает
 * семейство с потолком `now + sessionMax`, ротация наследует семейство и потолок; срок refresh —
 * `min(now + refreshIdle, потолок)`.
 */
class SessionIssuer(
    private val refreshTokens: RefreshTokenRepository,
    private val accessTokens: AccessTokenIssuer,
    private val secrets: SecretGenerator,
    private val digest: SecretDigest,
    private val settings: AuthSettings,
    private val ids: IdGenerator,
) {
    /** Вход: новое семейство. */
    suspend fun issue(user: User, now: Long): SessionTokens =
        issue(user, now, familyId = ids.newId(), sessionExpiresAt = now + settings.sessionMaxMillis)

    /** Ротация [previous]: то же семейство и тот же потолок. */
    suspend fun rotate(user: User, previous: RefreshToken, now: Long): SessionTokens =
        issue(user, now, previous.familyId, previous.sessionExpiresAt)

    private suspend fun issue(user: User, now: Long, familyId: String, sessionExpiresAt: Long): SessionTokens {
        val access = accessTokens.issue(user)
        val refresh = secrets.refreshToken()
        val expiresAt = minOf(now + settings.refreshIdleMillis, sessionExpiresAt)
        val token = RefreshToken(
            ids.newId(), user.id, digest.sha256(refresh), now, expiresAt, null, familyId, sessionExpiresAt,
        )
        refreshTokens.insert(token)
        return SessionTokens(access.token, access.expiresAt, refresh, expiresAt)
    }
}
