package ru.restofleet.agent.data.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.DeviceId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class CredentialCodecTest {
    private fun settings(password: String = "pw") =
        BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, password)

    @Test
    fun roundTripKeepsAllFieldsIncludingAwkwardPassword() {
        val s = settings("p\nw=;\u0000ключ ✓")

        assertThat(CredentialCodec.decode(CredentialCodec.encode(s))).isEqualTo(s)
    }

    @Test
    fun truncatedOrEmptyInputIsMalformed() {
        val bytes = CredentialCodec.encode(settings())

        assertThat(CredentialCodec.decode(ByteArray(0))).isNull()
        assertThat(CredentialCodec.decode(bytes.copyOf(bytes.size - 1))).isNull()
    }

    @Test
    fun trailingBytesAreMalformed() {
        assertThat(CredentialCodec.decode(CredentialCodec.encode(settings()) + byteArrayOf(1))).isNull()
    }

    @Test
    fun unknownVersionIsMalformed() {
        assertThat(CredentialCodec.decode(record(version = 3))).isNull()
    }

    @Test
    fun roleAndRestaurantFromProvisioningSurviveTheRoundTrip() {
        val s = settings().copy(role = DeviceRole.ASSEMBLY, restaurantId = "4b1c-r")

        assertThat(CredentialCodec.decode(CredentialCodec.encode(s))).isEqualTo(s)
    }

    @Test
    fun recordOfTheMvpFormatIsReadWithoutRoleAndRestaurant() {
        // Учётка, записанная агентом MVP (версия 1, DEV_CONFIG), после обновления агента читается
        val decoded = CredentialCodec.decode(record(version = 1))

        assertThat(decoded).isEqualTo(settings())
    }

    @Test
    fun unknownRoleCodeIsReadAsNoRole() {
        val decoded = CredentialCodec.decode(record(version = 2, role = "barista", restaurantId = "r"))

        assertThat(decoded!!.role).isNull()
        assertThat(decoded.restaurantId).isEqualTo("r")
    }

    @Test
    fun unsafeDeviceIdInsideRecordIsMalformed() {
        assertThat(CredentialCodec.decode(record(deviceId = "a/b"))).isNull()
    }

    @Test
    fun outOfRangePortInsideRecordIsMalformed() {
        assertThat(CredentialCodec.decode(record(port = 0))).isNull()
    }

    private fun record(
        version: Int = 1,
        port: Int = 8883,
        deviceId: String = "dev-emu-36",
        role: String = "",
        restaurantId: String = "",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeByte(version)
            it.writeUTF("10.0.2.2")
            it.writeInt(port)
            it.writeUTF(deviceId)
            it.writeUTF("pw")
            if (version >= 2) {
                it.writeUTF(role)
                it.writeUTF(restaurantId)
            }
        }
        return out.toByteArray()
    }
}
