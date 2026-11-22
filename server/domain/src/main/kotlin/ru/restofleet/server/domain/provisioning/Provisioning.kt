package ru.restofleet.server.domain.provisioning

import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** Одноразовый код регистрации (FR-BE-02). В БД — только SHA-256 кода; сам код отдаётся один раз при выпуске. */
data class ProvisioningToken(
    val id: String,
    val tokenHash: String,
    val restaurantId: RestaurantId,
    val role: DeviceRole,
    /** Имя будущего устройства (необязательно), например «Кухня-1». */
    val name: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val usedAt: Long?,
    val deviceId: DeviceId?,
)

/** Заявка на выпуск кодов (`POST /provisioning/tokens`, `:batch`). */
data class IssueTokensRequest(
    val restaurantId: RestaurantId,
    val role: DeviceRole,
    val name: String? = null,
    val count: Int = 1,
)

/** Выпущенный код: [code] показывается один раз. [toString] код не раскрывает. */
data class IssuedToken(
    val id: String,
    val code: String,
    val restaurantId: RestaurantId,
    val role: DeviceRole,
    val name: String?,
    val expiresAt: Long,
) {
    override fun toString(): String =
        "IssuedToken(id=$id, code=***, restaurantId=${restaurantId.value}, role=$role, name=$name, " +
            "expiresAt=$expiresAt)"
}

/** Заявка агента (`POST /provision`, без JWT). */
data class ProvisionRequest(
    val code: String,
    val deviceInfo: DeviceInfo,
    val agentVersion: String,
) {
    override fun toString(): String = "ProvisionRequest(code=***, deviceInfo=$deviceInfo, agentVersion=$agentVersion)"
}

/** Адрес брокера, который агент получает при провижининге (TLS 8883; host — как его видит устройство). */
data class BrokerEndpoint(val host: String, val port: Int)

/** Учётка нового устройства: [password] отдаётся один раз (P9). [toString] пароль не раскрывает. */
data class ProvisionedDevice(
    val deviceId: DeviceId,
    val password: String,
    val restaurantId: RestaurantId,
    val role: DeviceRole,
    val broker: BrokerEndpoint,
) {
    override fun toString(): String =
        "ProvisionedDevice(deviceId=${deviceId.value}, password=***, restaurantId=${restaurantId.value}, role=$role, " +
            "broker=$broker)"
}

/** Настройки провижининга из конфигурации `server:app`. */
data class ProvisioningSettings(
    /** Публичный адрес брокера для агентов (стенд: `10.0.2.2:8883` для эмулятора). */
    val broker: BrokerEndpoint,
    val tokenTtlMillis: Long = DEFAULT_TOKEN_TTL_MS,
) {
    companion object {
        /** TTL кода — 24 ч (FR-BE-02). */
        const val DEFAULT_TOKEN_TTL_MS: Long = 24L * 60 * 60 * 1000

        /** Предел пакетного выпуска (`tokens:batch`, симулятор). */
        const val MAX_BATCH = 1_000
    }
}

/** Нормализация введённого кода: регистр, пробелы и дефисы не важны (код вводят руками в мастере агента). */
object ProvisioningCode {
    fun normalize(raw: String): String = raw.filterNot { it == '-' || it.isWhitespace() }.uppercase()
}

/**
 * Порт кодов регистрации. [redeem] — атомарное погашение одним запросом
 * (`UPDATE … SET used_at = now WHERE token_hash = ? AND used_at IS NULL AND expires_at > now RETURNING`): из двух
 * одновременных попыток побеждает одна. Вызывается только внутри транзакции.
 */
interface TokenRepository {
    suspend fun insertAll(tokens: List<ProvisioningToken>)

    suspend fun redeem(tokenHash: String, now: Long): ProvisioningToken?

    suspend fun findByHash(tokenHash: String): ProvisioningToken?

    suspend fun attachDevice(tokenId: String, deviceId: DeviceId)
}
