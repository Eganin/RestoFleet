package ru.restofleet.agent.domain.model

import ru.restofleet.shared.kernel.DeviceId

/** Роль устройства в ресторане (FR-AG-10): задаётся при выпуске кода, агент её не выбирает (реш. 210). */
enum class DeviceRole(val code: String) {
    KITCHEN("kitchen"),
    HALL("hall"),
    ASSEMBLY("assembly"),
    ;

    companion object {
        /** `null` — роль неизвестна этой версии агента. */
        fun fromCode(code: String?): DeviceRole? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Регистрация устройства без секрета — то, что видят мастер и экран статуса. [role] и [restaurantId] — `null`, если
 * учётку внесли отладочным `DEV_CONFIG` (без провижининга).
 */
data class Registration(
    val deviceId: DeviceId,
    val role: DeviceRole?,
    val restaurantId: String?,
    val brokerHost: String,
    val brokerPort: Int,
)

/** Состояние регистрации для навигации: учётка есть, нет или хранилище временно не читается. */
sealed interface RegistrationState {
    data class Registered(val registration: Registration) : RegistrationState

    data object NotRegistered : RegistrationState

    /** Учётка, возможно, есть, но Keystore временно недоступен (реш. 174): мастер не показывается. */
    data object Unavailable : RegistrationState
}

/**
 * Ответ бэкенда на провижининг (FR-AG-10, контракт K2): учётка брокера (`deviceId` = username и clientId), пароль —
 * один раз, роль и ресторан — из кода. [toString] пароль не раскрывает.
 */
data class ProvisionedDevice(
    val deviceId: DeviceId,
    val password: String,
    val restaurantId: String,
    val role: DeviceRole,
    val brokerHost: String,
    val brokerPort: Int,
) {
    override fun toString(): String =
        "ProvisionedDevice(deviceId=${deviceId.value}, password=***, restaurantId=$restaurantId, role=$role, " +
            "broker=$brokerHost:$brokerPort)"
}
