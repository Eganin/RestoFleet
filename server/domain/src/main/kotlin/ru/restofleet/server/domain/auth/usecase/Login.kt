package ru.restofleet.server.domain.auth.usecase

import ru.restofleet.server.domain.auth.SessionIssuer
import ru.restofleet.server.domain.auth.SessionTokens
import ru.restofleet.server.domain.auth.UserRepository
import ru.restofleet.server.domain.common.PasswordHasher
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/**
 * `POST /auth/login` (FR-BE-52, реш. 216): bcrypt-проверка, access JWT + refresh. Неизвестный логин проверяется против
 * фиктивного хэша — время ответа не выдаёт, существует ли логин. Ошибка всегда [ServerError.Unauthorized].
 */
class Login(
    private val users: UserRepository,
    private val sessions: SessionIssuer,
    private val hasher: PasswordHasher,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    private val dummyHash: String by lazy { hasher.hash(DUMMY_PASSWORD) }

    suspend operator fun invoke(login: String, password: String): Outcome<SessionTokens, ServerError> =
        tx.transactional {
            val user = users.findByLogin(login.trim())
            val ok = hasher.verify(password, user?.passwordHash ?: dummyHash) && user != null
            if (user == null || !ok) {
                Outcome.Failure(ServerError.Unauthorized)
            } else {
                Outcome.Success(sessions.issue(user, clock.nowMillis()))
            }
        }

    private companion object {
        const val DUMMY_PASSWORD = "restofleet-timing-equalizer"
    }
}
