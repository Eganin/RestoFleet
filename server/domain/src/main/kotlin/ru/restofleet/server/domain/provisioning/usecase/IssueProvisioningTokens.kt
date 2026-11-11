package ru.restofleet.server.domain.provisioning.usecase

import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.SecretGenerator
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.provisioning.IssueTokensRequest
import ru.restofleet.server.domain.provisioning.IssuedToken
import ru.restofleet.server.domain.provisioning.ProvisioningCode
import ru.restofleet.server.domain.provisioning.ProvisioningSettings
import ru.restofleet.server.domain.provisioning.ProvisioningToken
import ru.restofleet.server.domain.provisioning.TokenRepository
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome

/**
 * Выпуск одноразовых кодов (FR-BE-02): 1…[ProvisioningSettings.MAX_BATCH] за раз, 128 бит, TTL 24 ч; в БД — SHA-256
 * нормализованного кода. Ресторан и роль кода фиксируются (реш. 210).
 */
class IssueProvisioningTokens(
    private val tokens: TokenRepository,
    private val restaurants: RestaurantRepository,
    private val secrets: SecretGenerator,
    private val digest: SecretDigest,
    private val settings: ProvisioningSettings,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend operator fun invoke(request: IssueTokensRequest): Outcome<List<IssuedToken>, ServerError> {
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
        when {
            request.count !in 1..ProvisioningSettings.MAX_BATCH -> ServerError.Validation("count", "out_of_range")
            name != null && name.length > MAX_NAME -> ServerError.Validation("name", "too_long")
            else -> null
        }?.let { return Outcome.Failure(it) }
        return tx.transactional {
            if (restaurants.findById(request.restaurantId) == null) {
                return@transactional Outcome.Failure(ServerError.NotFound("restaurant", request.restaurantId.value))
            }
            val now = clock.nowMillis()
            val expiresAt = now + settings.tokenTtlMillis
            val issued = List(request.count) {
                val code = secrets.provisioningCode()
                IssuedToken(ids.newId(), code, request.restaurantId, request.role, name, expiresAt)
            }
            tokens.insertAll(
                issued.map {
                    ProvisioningToken(
                        id = it.id,
                        tokenHash = digest.sha256(ProvisioningCode.normalize(it.code)),
                        restaurantId = it.restaurantId,
                        role = it.role,
                        name = it.name,
                        createdAt = now,
                        expiresAt = expiresAt,
                        usedAt = null,
                        deviceId = null,
                    )
                },
            )
            Outcome.Success(issued)
        }
    }

    private companion object {
        const val MAX_NAME = 100
    }
}
