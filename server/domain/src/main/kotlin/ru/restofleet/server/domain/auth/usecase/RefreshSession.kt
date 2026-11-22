package ru.restofleet.server.domain.auth.usecase

import ru.restofleet.server.domain.auth.RefreshTokenRepository
import ru.restofleet.server.domain.auth.SessionIssuer
import ru.restofleet.server.domain.auth.SessionTokens
import ru.restofleet.server.domain.auth.UserRepository
import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/**
 * `POST /auth/refresh` (реш. 216): refresh одноразовый — погашается и заменяется новой парой (ротация). Повторное
 * предъявление уже погашенного токена — признак кражи: отзываются все refresh-токены пользователя (реш. 239).
 */
class RefreshSession(
    private val refreshTokens: RefreshTokenRepository,
    private val users: UserRepository,
    private val sessions: SessionIssuer,
    private val digest: SecretDigest,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(refreshToken: String): Outcome<SessionTokens, ServerError> {
        if (refreshToken.isBlank() || refreshToken.length > MAX_TOKEN) return Outcome.Failure(ServerError.Unauthorized)
        val hash = digest.sha256(refreshToken)
        val rotated = tx.transactional<SessionTokens, ServerError> {
            val now = clock.nowMillis()
            val consumed = refreshTokens.consume(hash, now)
            val user = consumed?.let { users.findById(it.userId) }
            if (user == null) Outcome.Failure(ServerError.Unauthorized) else Outcome.Success(sessions.issue(user, now))
        }
        if (rotated is Outcome.Failure) {
            // Отдельной транзакцией: отзыв не должен откатиться вместе с неудачной ротацией
            tx.inTransaction {
                refreshTokens.findByHash(hash)
                    ?.takeIf { it.revokedAt != null }
                    ?.let { refreshTokens.revokeAll(it.userId, clock.nowMillis()) }
            }
        }
        return rotated
    }

    private companion object {
        const val MAX_TOKEN = 256
    }
}
