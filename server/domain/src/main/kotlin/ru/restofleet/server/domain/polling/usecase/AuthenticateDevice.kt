package ru.restofleet.server.domain.polling.usecase

import ru.restofleet.server.domain.common.PasswordHasher
import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.constantTimeEquals
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.CredentialRepository
import ru.restofleet.server.domain.device.DeviceCredentialCache
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/**
 * HTTP Basic устройства для REST-polling (P9, реш. 213): bcrypt-проверка по `device_credentials`, успешная проверка
 * кэшируется как SHA-256 пароля (TTL — у кэша). Попадание в кэш не ходит в БД и bcrypt; промах или другой пароль —
 * полная проверка. Отозванная учётка и выведенное устройство — [ServerError.Unauthorized] (без уточнения причины).
 * После записи в кэш статус перечитывается (гонка с выводом из эксплуатации, R-8).
 */
class AuthenticateDevice(
    private val devices: DeviceRepository,
    private val credentials: CredentialRepository,
    private val cache: DeviceCredentialCache,
    private val hasher: PasswordHasher,
    private val digest: SecretDigest,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(username: String, password: String): Outcome<DeviceId, ServerError> {
        val id = DeviceId.parse(username)?.takeIf { password.isNotEmpty() && password.length <= MAX_PASSWORD }
            ?: return Outcome.Failure(ServerError.Unauthorized)
        val passwordDigest = digest.sha256(password)
        val cached = cache.get(id)?.let { constantTimeEquals(it, passwordDigest) } == true
        return if (cached || verify(id, password, passwordDigest)) {
            Outcome.Success(id)
        } else {
            Outcome.Failure(ServerError.Unauthorized)
        }
    }

    private suspend fun verify(id: DeviceId, password: String, passwordDigest: String): Boolean {
        val hash = activeHash(id)
        if (hash == null || !hasher.verify(password, hash)) return false
        cache.put(id, passwordDigest)
        // R-8 (реш. 307): вывод из эксплуатации мог зафиксироваться и сбросить кэш, пока шёл bcrypt, — раньше нашей
        // записи. Перепроверка после put: либо видим вывод и убираем запись, либо его сброс кэша придёт после put
        val stillActive = activeHash(id) == hash
        if (!stillActive) cache.invalidate(id)
        return stillActive
    }

    private suspend fun activeHash(id: DeviceId): String? = tx.inTransaction {
        devices.findById(id)?.takeUnless { it.isDecommissioned }?.let { credentials.findActiveHash(id) }
    }

    private companion object {
        /** bcrypt учитывает первые 72 байта; длиннее — заведомо не наш пароль (наши — 43 символа). */
        const val MAX_PASSWORD = 128
    }
}
