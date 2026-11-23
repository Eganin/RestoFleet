package ru.restofleet.server.domain.auth.usecase

import ru.restofleet.server.domain.auth.AuthSettings
import ru.restofleet.server.domain.auth.RefreshToken
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
 * `POST /auth/refresh` (реш. 216, 417, 531): refresh одноразовый — погашается и заменяется новой парой того же
 * семейства (ротация, не дольше потолка сессии).
 *
 * Повтор погашенного токена:
 * - в окне [AuthSettings.reuseGraceMillis] после его ротации, пока преемник не использован, — потерянный ответ:
 *   преемник гасится, выдаётся новая пара из того же токена (RV-4);
 * - иначе, если в семействе есть живой токен, — признак кражи: отзываются все refresh-токены пользователя
 *   (реш. 239). Вор, повторивший токен в окне, обнаруживается так же — когда владелец предъявит погашенного преемника;
 * - токен сессии, закрытой выходом, живого преемника не имеет — его повтор просто 401 (реш. 451).
 */
@Suppress("LongParameterList")
class RefreshSession(
    private val refreshTokens: RefreshTokenRepository,
    private val users: UserRepository,
    private val sessions: SessionIssuer,
    private val digest: SecretDigest,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val settings: AuthSettings,
) {
    suspend operator fun invoke(refreshToken: String): Outcome<SessionTokens, ServerError> {
        if (refreshToken.isBlank() || refreshToken.length > MAX_TOKEN) return Outcome.Failure(ServerError.Unauthorized)
        val hash = digest.sha256(refreshToken)
        val rotated = tx.transactional<SessionTokens, ServerError> {
            val now = clock.nowMillis()
            val consumed = refreshTokens.consume(hash, now)
            if (consumed != null) issueFrom(consumed, now) else retryWithinGrace(hash, now)
        }
        if (rotated is Outcome.Failure) {
            // Отдельной транзакцией: отзыв не должен откатиться вместе с неудачной ротацией
            tx.inTransaction {
                val now = clock.nowMillis()
                refreshTokens.findByHash(hash)
                    ?.takeIf { it.revokedAt != null && refreshTokens.hasActive(it.familyId, now) }
                    ?.takeIf { forgivenSuccessor(it, now) == null }
                    ?.let { refreshTokens.revokeAll(it.userId, now) }
            }
        }
        return rotated
    }

    private suspend fun retryWithinGrace(hash: String, now: Long): Outcome<SessionTokens, ServerError> {
        val previous = refreshTokens.findByHash(hash)
        val successor = previous?.let { forgivenSuccessor(it, now) }
        // Параллельный повтор того же токена гасит преемника первым — этот получает 401, но не «кражу»
        return if (previous != null && successor != null && refreshTokens.revoke(successor.id, now)) {
            issueFrom(previous, now)
        } else {
            Outcome.Failure(ServerError.Unauthorized)
        }
    }

    /** Неиспользованный преемник [token], если тот погашен ротацией не дольше окна назад; иначе `null`. */
    private suspend fun forgivenSuccessor(token: RefreshToken, now: Long): RefreshToken? {
        val revokedAt = token.revokedAt
        val withinGrace = revokedAt != null && now - revokedAt <= settings.reuseGraceMillis
        return if (withinGrace) refreshTokens.activeSuccessor(token.id, now) else null
    }

    private suspend fun issueFrom(token: RefreshToken, now: Long): Outcome<SessionTokens, ServerError> {
        val user = users.findById(token.userId) ?: return Outcome.Failure(ServerError.Unauthorized)
        return Outcome.Success(sessions.rotate(user, token, now))
    }

    private companion object {
        const val MAX_TOKEN = 256
    }
}
