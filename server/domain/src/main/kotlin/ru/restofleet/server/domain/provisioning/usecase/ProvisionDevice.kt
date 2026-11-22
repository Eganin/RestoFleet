package ru.restofleet.server.domain.provisioning.usecase

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.restofleet.server.domain.common.PasswordHasher
import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.SecretGenerator
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.device.BrokerAccounts
import ru.restofleet.server.domain.device.CredentialRepository
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.provisioning.ProvisionRequest
import ru.restofleet.server.domain.provisioning.ProvisionedDevice
import ru.restofleet.server.domain.provisioning.ProvisioningCode
import ru.restofleet.server.domain.provisioning.ProvisioningSettings
import ru.restofleet.server.domain.provisioning.ProvisioningToken
import ru.restofleet.server.domain.provisioning.TokenRepository
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome

/**
 * Обмен кода на учётку (FR-BE-02, FR-AG-10; реш. 210, 236). Одна транзакция:
 * 1. атомарное погашение кода ([TokenRepository.redeem]); неудача — [ServerError.TokenInvalid] /
 *    [ServerError.TokenUsed] / [ServerError.TokenExpired];
 * 2. устройство (`provisioned`, роль и ресторан из кода), bcrypt-хэш пароля (32 байта), тень с `desired` по умолчанию;
 * 3. клиент DynSec `clientid = deviceId` с ролью `device`.
 *
 * Отказ брокера на шаге 3 откатывает транзакцию: код не погашен, устройства нет — агент может повторить. Если брокер
 * клиента создал, а commit не прошёл, клиент DynSec удаляется (компенсация) и исключение пробрасывается. После commit
 * публикуется retained `desired` версии 1; его сбой не ломает провижининг (повтор — при подключении устройства,
 * [ru.restofleet.server.domain.presence.usecase.HandleStatus]).
 */
class ProvisionDevice(
    private val tokens: TokenRepository,
    private val devices: DeviceRepository,
    private val credentials: CredentialRepository,
    private val shadows: ShadowRepository,
    private val broker: BrokerAccounts,
    private val publisher: MessagePublisher,
    private val hasher: PasswordHasher,
    private val secrets: SecretGenerator,
    private val digest: SecretDigest,
    private val settings: ProvisioningSettings,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend operator fun invoke(request: ProvisionRequest): Outcome<ProvisionedDevice, ServerError> {
        val code = ProvisioningCode.normalize(request.code)
        if (code.isEmpty() || code.length > MAX_CODE) return Outcome.Failure(ServerError.TokenInvalid)
        val tokenHash = digest.sha256(code)
        var brokerClient: DeviceId? = null
        val outcome = try {
            tx.transactional<Pair<ProvisionedDevice, Shadow>, ServerError> {
                val now = clock.nowMillis()
                val token = tokens.redeem(tokenHash, now)
                    ?: return@transactional Outcome.Failure(rejection(tokens.findByHash(tokenHash), now))
                val password = secrets.devicePassword()
                val device = newDevice(token, request, now)
                devices.insert(device)
                credentials.insert(device.id, hasher.hash(password), now)
                tokens.attachDevice(token.id, device.id)
                val shadow = Shadow.initial(device.id, DefaultDesired.document, now)
                shadows.insert(shadow)
                when (val created = broker.createDevice(device.id, password)) {
                    is Outcome.Failure -> created
                    is Outcome.Success -> {
                        brokerClient = device.id
                        Outcome.Success(
                            ProvisionedDevice(device.id, password, token.restaurantId, token.role, settings.broker) to
                                shadow,
                        )
                    }
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Включая отмену корутины: компенсация идёт в NonCancellable, исключение пробрасывается как есть
            withContext(NonCancellable) { compensate(brokerClient) }
            throw e
        }
        return when (outcome) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> {
                val (provisioned, shadow) = outcome.value
                publisher.publishDesired(provisioned.deviceId, shadow.desiredState())
                Outcome.Success(provisioned)
            }
        }
    }

    private fun newDevice(token: ProvisioningToken, request: ProvisionRequest, now: Long): Device = Device(
        id = DeviceId.parse("$DEVICE_PREFIX${ids.newId()}") ?: error("generated device id is not a valid DeviceId"),
        restaurantId = token.restaurantId,
        role = token.role,
        name = token.name,
        status = DeviceStatus.PROVISIONED,
        transport = null,
        lastSeenAt = null,
        decommissionedAt = null,
        createdAt = now,
        info = request.deviceInfo,
        agentVersion = request.agentVersion.take(MAX_VERSION).ifBlank { null },
        mode = null,
    )

    private fun rejection(token: ProvisioningToken?, now: Long): ServerError = when {
        token == null -> ServerError.TokenInvalid
        token.usedAt != null -> ServerError.TokenUsed
        token.expiresAt <= now -> ServerError.TokenExpired
        else -> ServerError.TokenInvalid
    }

    private suspend fun compensate(created: DeviceId?) {
        if (created != null) broker.deleteDevice(created)
    }

    private companion object {
        const val DEVICE_PREFIX = "d-"
        const val MAX_CODE = 64
        const val MAX_VERSION = 64
    }
}
