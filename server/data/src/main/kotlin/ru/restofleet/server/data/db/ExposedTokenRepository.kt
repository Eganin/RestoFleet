package ru.restofleet.server.data.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.provisioning.ProvisioningToken
import ru.restofleet.server.domain.provisioning.TokenRepository
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** `provisioning_tokens`: погашение — один `UPDATE … RETURNING` (из двух одновременных попыток побеждает одна). */
internal class ExposedTokenRepository : TokenRepository {
    override suspend fun insertAll(tokens: List<ProvisioningToken>) {
        ProvisioningTokensTable.batchInsert(tokens, shouldReturnGeneratedValues = false) { t ->
            this[ProvisioningTokensTable.id] = t.id.toUuid()
            this[ProvisioningTokensTable.tokenHash] = t.tokenHash
            this[ProvisioningTokensTable.restaurantId] = t.restaurantId.value.toUuid()
            this[ProvisioningTokensTable.role] = t.role.code
            this[ProvisioningTokensTable.name] = t.name
            this[ProvisioningTokensTable.createdAt] = t.createdAt.toTimestamp()
            this[ProvisioningTokensTable.expiresAt] = t.expiresAt.toTimestamp()
            this[ProvisioningTokensTable.usedAt] = t.usedAt?.toTimestamp()
            this[ProvisioningTokensTable.deviceId] = t.deviceId?.value
        }
    }

    override suspend fun redeem(tokenHash: String, now: Long): ProvisioningToken? = ProvisioningTokensTable
        .updateReturning(
            returning = ProvisioningTokensTable.columns,
            where = {
                (ProvisioningTokensTable.tokenHash eq tokenHash) and
                    ProvisioningTokensTable.usedAt.isNull() and
                    (ProvisioningTokensTable.expiresAt greater now.toTimestamp())
            },
        ) { it[usedAt] = now.toTimestamp() }
        .singleOrNull()
        ?.toToken()

    override suspend fun findByHash(tokenHash: String): ProvisioningToken? = ProvisioningTokensTable.selectAll()
        .where { ProvisioningTokensTable.tokenHash eq tokenHash }
        .singleOrNull()
        ?.toToken()

    override suspend fun attachDevice(tokenId: String, deviceId: DeviceId) {
        ProvisioningTokensTable.update({ ProvisioningTokensTable.id eq tokenId.toUuid() }) {
            it[ProvisioningTokensTable.deviceId] = deviceId.value
        }
    }

    private fun ResultRow.toToken() = ProvisioningToken(
        id = this[ProvisioningTokensTable.id].toString(),
        tokenHash = this[ProvisioningTokensTable.tokenHash],
        restaurantId = RestaurantId(this[ProvisioningTokensTable.restaurantId].toString()),
        role = DeviceRole.fromCode(this[ProvisioningTokensTable.role]) ?: error("unknown role in database"),
        name = this[ProvisioningTokensTable.name],
        createdAt = this[ProvisioningTokensTable.createdAt].toMillis(),
        expiresAt = this[ProvisioningTokensTable.expiresAt].toMillis(),
        usedAt = this[ProvisioningTokensTable.usedAt]?.toMillis(),
        deviceId = this[ProvisioningTokensTable.deviceId]?.let { DeviceId.parse(it) },
    )
}
