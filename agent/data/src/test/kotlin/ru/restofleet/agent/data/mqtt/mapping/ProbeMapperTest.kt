package ru.restofleet.agent.data.mqtt.mapping

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.shared.protocol.dto.ProbeReportDto

class ProbeMapperTest {
    private val results = listOf(
        ProbeResult("api", false, null, ProbeError.TIMEOUT, 5000, ProbePhases(14, 38, null, null), ts = 1),
        ProbeResult("kds", true, 200, null, 212, ProbePhases(9, 31, 64, 108), ts = 2),
        ProbeResult("ref", true, 200, null, 95, ProbePhases(null, null, null, null), ts = 3),
    )

    @Test
    fun reportMatchesFixture() {
        assertMatchesFixture(
            "probe-report",
            ProbeReportDto.serializer(),
            ProbeMapper.toDto(ts = 1793865630000, results = results),
        )
    }

    @Test
    fun localNetworkDeniedIsSnakeCaseOnTheWire() {
        assertThat(ProbeMapper.errorToWire(ProbeError.LOCAL_NETWORK_DENIED)).isEqualTo("local_network_denied")
    }

    @Test
    fun everyErrorHasDocumentedWireName() {
        val wire = ProbeError.entries.map { ProbeMapper.errorToWire(it) }

        assertThat(wire).containsExactly(
            "dns_fail", "timeout", "conn_refused", "tls_error", "http_4xx", "http_5xx",
            "no_network", "local_network_denied", "other",
        ).inOrder()
    }

    @Test
    fun wireNameRoundTripsForEveryError() {
        ProbeError.entries.forEach {
            assertThat(ProbeMapper.errorFromWire(ProbeMapper.errorToWire(it))).isEqualTo(it)
        }
    }

    @Test
    fun unknownWireNameBecomesOther() {
        assertThat(ProbeMapper.errorFromWire("quantum_fail")).isEqualTo(ProbeError.OTHER)
        assertThat(ProbeMapper.errorFromWire("")).isEqualTo(ProbeError.OTHER)
    }

    @Test
    fun nullWireNameMeansNoError() {
        assertThat(ProbeMapper.errorFromWire(null)).isNull()
    }
}
