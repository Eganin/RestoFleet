package ru.restofleet.server.domain.auth.usecase

import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.auth.UserRepository
import ru.restofleet.server.domain.common.PasswordHasher
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome

/**
 * Dev-seed пользователей (`seed --users`, план 2.4): создаёт пользователя или меняет пароль существующему. Роль в К
 * одна — `admin`. Пароль не короче [MIN_PASSWORD] символов.
 */
class UpsertUser(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend operator fun invoke(login: String, password: String): Outcome<User, ServerError> {
        val name = login.trim()
        validate(name, password)?.let { return Outcome.Failure(it) }
        val hash = hasher.hash(password)
        return tx.transactional {
            val existing = users.findByLogin(name)
            if (existing != null) {
                users.updatePassword(existing.id, hash)
                Outcome.Success(existing.copy(passwordHash = hash))
            } else {
                val user = User(ids.newId(), name, hash, User.ROLE_ADMIN, clock.nowMillis())
                users.insert(user)
                Outcome.Success(user)
            }
        }
    }

    private fun validate(login: String, password: String): ServerError.Validation? = when {
        !LOGIN.matches(login) -> ServerError.Validation("login", "invalid_format")
        password.length !in MIN_PASSWORD..MAX_PASSWORD -> ServerError.Validation("password", "length")
        else -> null
    }

    private companion object {
        val LOGIN = Regex("^[A-Za-z0-9._-]{3,64}$")
        const val MIN_PASSWORD = 12
        const val MAX_PASSWORD = 72
    }
}
