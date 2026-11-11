package ru.restofleet.server.data.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import ru.restofleet.server.domain.auth.RefreshToken
import ru.restofleet.server.domain.auth.RefreshTokenRepository
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.auth.UserRepository

/** `users`. */
internal class ExposedUserRepository : UserRepository {
    override suspend fun insert(user: User) {
        UsersTable.insert {
            it[id] = user.id.toUuid()
            it[login] = user.login
            it[passwordHash] = user.passwordHash
            it[role] = user.role
            it[createdAt] = user.createdAt.toTimestamp()
        }
    }

    override suspend fun findByLogin(login: String): User? =
        UsersTable.selectAll().where { UsersTable.login eq login }.singleOrNull()?.toUser()

    override suspend fun findById(id: String): User? {
        val uuid = id.toUuidOrNull() ?: return null
        return UsersTable.selectAll().where { UsersTable.id eq uuid }.singleOrNull()?.toUser()
    }

    override suspend fun updatePassword(id: String, passwordHash: String) {
        UsersTable.update({ UsersTable.id eq id.toUuid() }) { it[UsersTable.passwordHash] = passwordHash }
    }

    private fun ResultRow.toUser() = User(
        id = this[UsersTable.id].toString(),
        login = this[UsersTable.login],
        passwordHash = this[UsersTable.passwordHash],
        role = this[UsersTable.role],
        createdAt = this[UsersTable.createdAt].toMillis(),
    )
}

/** `refresh_tokens`: погашение при ротации — один `UPDATE … RETURNING`. */
internal class ExposedRefreshTokenRepository : RefreshTokenRepository {
    override suspend fun insert(token: RefreshToken) {
        RefreshTokensTable.insert {
            it[id] = token.id.toUuid()
            it[userId] = token.userId.toUuid()
            it[tokenHash] = token.tokenHash
            it[createdAt] = token.createdAt.toTimestamp()
            it[expiresAt] = token.expiresAt.toTimestamp()
            it[revokedAt] = token.revokedAt?.toTimestamp()
        }
    }

    override suspend fun consume(tokenHash: String, now: Long): RefreshToken? = RefreshTokensTable
        .updateReturning(
            returning = RefreshTokensTable.columns,
            where = {
                (RefreshTokensTable.tokenHash eq tokenHash) and
                    RefreshTokensTable.revokedAt.isNull() and
                    (RefreshTokensTable.expiresAt greater now.toTimestamp())
            },
        ) { it[revokedAt] = now.toTimestamp() }
        .singleOrNull()
        ?.toToken()

    override suspend fun findByHash(tokenHash: String): RefreshToken? = RefreshTokensTable.selectAll()
        .where { RefreshTokensTable.tokenHash eq tokenHash }
        .singleOrNull()
        ?.toToken()

    override suspend fun revokeAll(userId: String, now: Long): Int {
        val uuid = userId.toUuidOrNull() ?: return 0
        val active = (RefreshTokensTable.userId eq uuid) and RefreshTokensTable.revokedAt.isNull()
        return RefreshTokensTable.update({ active }) { it[revokedAt] = now.toTimestamp() }
    }

    private fun ResultRow.toToken() = RefreshToken(
        id = this[RefreshTokensTable.id].toString(),
        userId = this[RefreshTokensTable.userId].toString(),
        tokenHash = this[RefreshTokensTable.tokenHash],
        createdAt = this[RefreshTokensTable.createdAt].toMillis(),
        expiresAt = this[RefreshTokensTable.expiresAt].toMillis(),
        revokedAt = this[RefreshTokensTable.revokedAt]?.toMillis(),
    )
}
