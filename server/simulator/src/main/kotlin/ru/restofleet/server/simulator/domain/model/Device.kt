package ru.restofleet.server.simulator.domain.model

/** Роль устройства (реш. 210): её задаёт код провижининга, симулятор получает роль в ответе `/provision`. */
enum class DeviceRole(val code: String) {
    KITCHEN("kitchen"),
    HALL("hall"),
    ASSEMBLY("assembly"),
    ;

    companion object {
        fun fromCode(code: String): DeviceRole? = entries.firstOrNull { it.code == code }
    }
}

/** Учётка виртуального устройства из ответа `/provision`; [toString] пароль не раскрывает. */
data class DeviceCredentials(
    val deviceId: String,
    val password: String,
    val restaurantId: String,
    val role: DeviceRole,
) {
    override fun toString(): String =
        "DeviceCredentials(deviceId=$deviceId, password=***, restaurantId=$restaurantId, role=${role.code})"
}

/** Место устройства в раскладке: ресторан и роль. */
data class Slot(val restaurantId: String, val role: DeviceRole)

/** Состав одного ресторана: по умолчанию 2 кухни, 3 зала, 1 сборка (`1-coursework.md` 4.6). */
data class RoleMix(val kitchen: Int = 2, val hall: Int = 3, val assembly: Int = 1) {
    init {
        require(kitchen >= 0 && hall >= 0 && assembly >= 0) { "role counts must be >= 0" }
        require(kitchen + hall + assembly > 0) { "role mix must not be empty" }
    }

    /** Роли одного ресторана по порядку: кухни, залы, сборки. */
    fun pattern(): List<DeviceRole> =
        List(kitchen) { DeviceRole.KITCHEN } + List(hall) { DeviceRole.HALL } + List(assembly) { DeviceRole.ASSEMBLY }
}

/** Первые [devices] учёток кэша: прогон на N устройств всегда берёт одни и те же (детерминированно). */
fun List<DeviceCredentials>.forRun(devices: Int): List<DeviceCredentials> {
    require(size >= devices) { "only $size devices registered, $devices needed: run `register` first" }
    return take(devices)
}
