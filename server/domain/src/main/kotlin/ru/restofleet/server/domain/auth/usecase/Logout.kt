package ru.restofleet.server.domain.auth.usecase

import ru.restofleet.server.domain.auth.RefreshTokenRepository
import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.shared.kernel.Clock

/**
 * `POST /auth/logout` (реш. 417, 438): отзывает семейство refresh-токена — сессию этого входа; другие входы того же
 * пользователя живут. Неизвестный, погашенный или пустой токен — ничего не делает: результат всегда одинаков, по нему
 * нельзя узнать, был ли токен действителен.
 */
class Logout(
    private val refreshTokens: RefreshTokenRepository,
    private val digest: SecretDigest,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(refreshToken: String) {
        if (refreshToken.isBlank() || refreshToken.length > MAX_TOKEN) return
        val hash = digest.sha256(refreshToken)
        tx.inTransaction {
            refreshTokens.findByHash(hash)?.let { refreshTokens.revokeFamily(it.familyId, clock.nowMillis()) }
        }
    }

    private companion object {
        const val MAX_TOKEN = 256
    }
}
