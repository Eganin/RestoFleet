package ru.restofleet.agent.data.credentials

import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.DeviceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Простой бинарный формат записи учётки (до шифрования): версия, host, port, deviceId, password.
 * Без JSON-библиотеки и без зависимостей от protocol; любой сбой разбора — `null` («запись повреждена»).
 */
internal object CredentialCodec {
    private const val VERSION = 1
    private val PORTS = 1..65_535

    fun encode(s: BrokerSettings): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeByte(VERSION)
            it.writeUTF(s.host)
            it.writeInt(s.port)
            it.writeUTF(s.deviceId.value)
            it.writeUTF(s.password)
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
        if (input.readUnsignedByte() != VERSION) return null
        val host = input.readUTF()
        val port = input.readInt()
        val deviceId = DeviceId.parse(input.readUTF())
        val password = input.readUTF()
        val valid = input.available() == 0 && port in PORTS && deviceId != null
        return if (valid) BrokerSettings(host, port, deviceId, password) else null
    }
}
