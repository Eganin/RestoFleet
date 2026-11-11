package ru.restofleet.server.domain.auth

import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.SecretGenerator
import ru.restofleet.shared.kernel.IdGenerator

/** Выпуск пары access + refresh для `Login` и `RefreshSession` (внутри их транзакций). */
class SessionIssuer(
    private val refreshTokens: RefreshTokenRepository,
    private val accessTokens: AccessTokenIssuer,
    private val secrets: SecretGenerator,
    private val digest: SecretDigest,
    private val settings: AuthSettings,
    private val ids: IdGenerator,
) {
    suspend fun issue(user: User, now: Long): SessionTokens {
        val access = accessTokens.issue(user)
        val refresh = secrets.refreshToken()
        val expiresAt = now + settings.refreshTtlMillis
        refreshTokens.insert(RefreshToken(ids.newId(), user.id, digest.sha256(refresh), now, expiresAt, null))
        return SessionTokens(access.token, access.expiresAt, refresh, expiresAt)
    }
}
