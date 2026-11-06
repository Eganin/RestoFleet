package ru.restofleet.shared.kernel

import kotlin.jvm.JvmInline

/**
 * Идентификатор устройства — он же MQTT clientId, имя учётки DynSec и сегмент топика.
 * Value object домена: живёт в kernel, чтобы domain агента и бэкенда валидировал id без зависимости от протокола.
 * Допустимы только `[A-Za-z0-9_-]{1,64}`: символы `/`, `+`, `#` сломали бы дерево топиков и ACL.
 */
@JvmInline
value class DeviceId private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun parse(raw: String): DeviceId? = if (PATTERN.matches(raw)) DeviceId(raw) else null
    }
}
