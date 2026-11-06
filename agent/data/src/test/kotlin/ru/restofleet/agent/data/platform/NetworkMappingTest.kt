package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

class NetworkMappingTest {

    @Test
    fun typeByTransports() {
        // 0 = TRANSPORT_CELLULAR, 1 = TRANSPORT_WIFI, 3 = TRANSPORT_ETHERNET, 4 = TRANSPORT_VPN
        val expected = mapOf(
            setOf(1) to NetworkType.WIFI,
            setOf(0) to NetworkType.CELLULAR,
            setOf(3) to NetworkType.ETHERNET,
            emptySet<Int>() to NetworkType.NONE,
            setOf(4) to NetworkType.OTHER,
            setOf(1, 4) to NetworkType.WIFI,
            setOf(0, 4) to NetworkType.CELLULAR,
            setOf(3, 4) to NetworkType.ETHERNET,
        )
        expected.forEach { (transports, type) ->
            assertThat(NetworkMapping.type(transports)).isEqualTo(type)
        }
    }

    @Test
    fun rssiUnknownValuesAreNull() {
        assertThat(NetworkMapping.rssi(-127) as Any?).isNull()
        assertThat(NetworkMapping.rssi(Int.MIN_VALUE) as Any?).isNull()
    }

    @Test
    fun rssiValueIsPreserved() {
        assertThat(NetworkMapping.rssi(-67)).isEqualTo(-67)
    }

    @Test
    fun ssidQuotesAreStripped() {
        assertThat(NetworkMapping.ssid("\"Kitchen\"")).isEqualTo(Reading.Available("Kitchen"))
    }

    @Test
    fun ssidWithoutQuotesIsKept() {
        assertThat(NetworkMapping.ssid("Kitchen")).isEqualTo(Reading.Available("Kitchen"))
    }

    @Test
    fun ssidUnknownOrNullNeedsLocationPermission() {
        val expected = Reading.Unavailable(UnavailableReason.PermissionMissing("ACCESS_FINE_LOCATION"))
        assertThat(NetworkMapping.ssid("<unknown ssid>")).isEqualTo(expected)
        assertThat(NetworkMapping.ssid(null)).isEqualTo(expected)
    }
}
