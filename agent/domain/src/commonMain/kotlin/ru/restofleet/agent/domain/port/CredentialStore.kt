package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.shared.kernel.DeviceId

/**
 * Учётка устройства для брокера: имя пользователя равно [deviceId] (P6). [role] и [restaurantId] приходят из
 * провижининга (реш. 210) и идут в `status.role`; у учётки отладочного `DEV_CONFIG` их нет. Пароль в `toString`
 * маскируется, чтобы он не попал в лог или сообщение об ошибке.
 */
data class BrokerSettings(
    val host: String,
    val port: Int,
    val deviceId: DeviceId,
    val password: String,
    val role: DeviceRole? = null,
    val restaurantId: String? = null,
) {
    override fun toString(): String =
        "BrokerSettings(host=$host, port=$port, deviceId=${deviceId.value}, password=***, role=$role, " +
            "restaurantId=$restaurantId)"
}

/**
 * Защищённое хранилище учётки брокера. `load()` возвращает `null`, если записи нет или её нельзя прочитать
 * окончательно, и бросает, если хранилище временно недоступно: запись цела, чтение стоит повторить позже.
 */
interface CredentialStore {
    suspend fun save(s: BrokerSettings)
    suspend fun load(): BrokerSettings?
}
