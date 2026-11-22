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
 * ещё роль и ресторан из провижининга (пустая строка — нет, реш. 210); с версии 3 (П, V-5) — код и название
 * ресторана. Запись версии 1 (агент MVP, `DEV_CONFIG`) читается без роли, версии 2 — без кода и названия.
 * Неизвестная роль — `null`, учётка остаётся. Без JSON-библиотеки и без зависимостей от protocol; любой сбой
 * разбора — `null` («запись повреждена»).
 */
internal object CredentialCodec {
    private const val V1 = 1
    private const val V2 = 2
    private const val VERSION = 3
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
            it.writeUTF(s.restaurantCode.orEmpty())
            it.writeUTF(s.restaurantName.orEmpty())
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
        if (version !in V1..VERSION) return null
        val host = input.readUTF()
        val port = input.readInt()
        val deviceId = DeviceId.parse(input.readUTF())
        val password = input.readUTF()
        val role = if (version >= V2) input.readUTF() else ""
        val restaurantId = if (version >= V2) input.readUTF() else ""
        val restaurantCode = if (version >= VERSION) input.readUTF() else ""
        val restaurantName = if (version >= VERSION) input.readUTF() else ""
        val valid = input.available() == 0 && port in PORTS && deviceId != null
        return if (valid) {
            BrokerSettings(
                host = host,
                port = port,
                deviceId = deviceId,
                password = password,
                role = DeviceRole.fromCode(role),
                restaurantId = restaurantId.ifEmpty { null },
                restaurantCode = restaurantCode.ifEmpty { null },
                restaurantName = restaurantName.ifEmpty { null },
            )
        } else {
            null
        }
    }
}
