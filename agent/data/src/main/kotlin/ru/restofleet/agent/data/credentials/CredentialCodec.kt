package ru.restofleet.agent.data.credentials

import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.DeviceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Простой бинарный формат записи учётки (до шифрования): версия, host, port, deviceId, password; с версии 2 (КМ3) —
 * ещё роль и ресторан из провижининга (пустая строка — нет, реш. 210). Запись версии 1 (агент MVP, `DEV_CONFIG`)
 * читается без роли. Неизвестная роль — `null`, учётка остаётся. Без JSON-библиотеки и без зависимостей от protocol;
 * любой сбой разбора — `null` («запись повреждена»).
 */
internal object CredentialCodec {
    private const val V1 = 1
    private const val VERSION = 2
    private val PORTS = 1..65_535

    fun encode(s: BrokerSettings): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeByte(VERSION)
            it.writeUTF(s.host)
            it.writeInt(s.port)
            it.writeUTF(s.deviceId.value)
            it.writeUTF(s.password)
            it.writeUTF(s.role?.code.orEmpty())
            it.writeUTF(s.restaurantId.orEmpty())
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BrokerSettings? =
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { read(it) }
        } catch (_: IOException) {
            null
        }

    private fun read(input: DataInputStream): BrokerSettings? {
        val version = input.readUnsignedByte()
        if (version != V1 && version != VERSION) return null
        val host = input.readUTF()
        val port = input.readInt()
        val deviceId = DeviceId.parse(input.readUTF())
        val password = input.readUTF()
        val role = if (version == VERSION) input.readUTF() else ""
        val restaurantId = if (version == VERSION) input.readUTF() else ""
        val valid = input.available() == 0 && port in PORTS && deviceId != null
        return if (valid) {
            BrokerSettings(
                host = host,
                port = port,
                deviceId = deviceId,
                password = password,
                role = DeviceRole.fromCode(role),
                restaurantId = restaurantId.ifEmpty { null },
            )
        } else {
            null
        }
    }
}
