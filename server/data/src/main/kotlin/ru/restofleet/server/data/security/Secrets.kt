package ru.restofleet.server.data.security

import at.favre.lib.crypto.bcrypt.BCrypt
import ru.restofleet.server.domain.common.PasswordHasher
import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.SecretGenerator
import ru.restofleet.server.domain.device.DeviceCredentialCache
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** bcrypt (favre), cost 10 (арх. разд. 12). Битый хэш при проверке — `false`, а не исключение. */
class BcryptHasher(private val cost: Int = DEFAULT_COST) : PasswordHasher {
    override fun hash(password: String): String = BCrypt.withDefaults().hashToString(cost, password.toCharArray())

    override fun verify(password: String, hash: String): Boolean = try {
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        const val DEFAULT_COST = 10
    }
}

/**
 * Секреты из [SecureRandom]: код регистрации — 128 бит в base32 Крокфорда (26 символов без `I L O U`, удобно вводить
 * руками), пароль устройства и refresh-токен — 32 байта в base64url без `=` (43 символа).
 */
class SecureRandomSecrets(private val random: SecureRandom = SecureRandom()) : SecretGenerator {
    override fun provisioningCode(): String = crockford(bytes(CODE_BYTES))

    override fun devicePassword(): String = base64(bytes(SECRET_BYTES))

    override fun refreshToken(): String = base64(bytes(SECRET_BYTES))

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val CODE_BYTES = 16
        const val SECRET_BYTES = 32
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val BITS = 5
        const val MASK = 0x1F
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFF

        fun crockford(bytes: ByteArray): String = buildString {
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl BYTE_BITS) or (b.toInt() and BYTE_MASK)
                bits += BYTE_BITS
                while (bits >= BITS) {
                    append(ALPHABET[(buffer shr (bits - BITS)) and MASK])
                    bits -= BITS
                }
            }
            if (bits > 0) append(ALPHABET[(buffer shl (BITS - bits)) and MASK])
        }
    }
}

/** SHA-256 в hex (64 символа — столбцы `token_hash char(64)`). */
object Sha256Digest : SecretDigest {
    override fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Кэш Basic-проверок в памяти процесса (реш. 213): TTL [ttlMs] от момента успешной проверки, не больше [maxEntries]
 * записей (при переполнении кэш очищается — следующая проверка просто пройдёт через bcrypt).
 */
class InMemoryDeviceCredentialCache(
    private val clock: Clock,
    private val ttlMs: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : DeviceCredentialCache {
    private class Entry(val digest: String, val expiresAt: Long)

    private val entries = ConcurrentHashMap<DeviceId, Entry>()

    override fun get(deviceId: DeviceId): String? {
        val entry = entries[deviceId] ?: return null
        return if (entry.expiresAt > clock.nowMillis()) {
            entry.digest
        } else {
            entries.remove(deviceId, entry)
            null
        }
    }

    override fun put(deviceId: DeviceId, passwordDigest: String) {
        if (entries.size >= maxEntries) entries.clear()
        entries[deviceId] = Entry(passwordDigest, clock.nowMillis() + ttlMs)
    }

    override fun invalidate(deviceId: DeviceId) {
        entries.remove(deviceId)
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}
