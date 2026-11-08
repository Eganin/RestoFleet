package ru.restofleet.agent.data.mqtt.mapping

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.errorOrNull
import ru.restofleet.shared.kernel.getOrNull
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto

class ShadowMapperTest {
    private val expectedProbes = listOf(
        ProbeTarget("api", "https://api.company.example/health", 5000),
        ProbeTarget("kds", "https://kitchen.company.example/", 5000),
        ProbeTarget("ref", "https://reference.example/", 5000),
    )

    @Test
    fun fixtureMapsToThreeProbesAndVersion12() {
        val shadow = ShadowMapper.fromDto(Fixtures.decode("shadow-desired", ShadowDesiredDto.serializer()))

        assertThat(shadow.version).isEqualTo(12)
        assertThat(shadow.telemetryIntervalSec).isEqualTo(30)
        assertThat(shadow.watchedApps).containsExactly("com.android.chrome")
        assertThat(shadow.probes).containsExactlyElementsIn(expectedProbes).inOrder()
    }

    @Test
    fun payloadIsParsedFromBytes() {
        val shadow = ShadowMapper.parse(Fixtures.text("shadow-desired").toByteArray()).getOrNull()

        assertThat(shadow!!.version).isEqualTo(12)
        assertThat(shadow.probes).hasSize(3)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtra = Fixtures.text("shadow-desired").replaceFirst("{", """{ "futureField": {"a": 1}, """)

        val shadow = ShadowMapper.parse(withExtra.toByteArray()).getOrNull()

        assertThat(shadow!!.version).isEqualTo(12)
        assertThat(shadow.probes).hasSize(3)
    }

    @Test
    fun nonHttpProbesAreSkippedBecauseAgentOnlyProbesHttps() {
        val http = "\"type\": \"http\", \"url\": \"https://kitchen"
        val tcp = Fixtures.text("shadow-desired").replace(http, http.replace("http\"", "tcp\""))

        val shadow = ShadowMapper.parse(tcp.toByteArray()).getOrNull()

        assertThat(shadow!!.probes.map { it.id }).containsExactly("api", "ref").inOrder()
    }

    @Test
    fun brokenJsonIsFailureNotException() {
        val result = ShadowMapper.parse("{ not json".toByteArray())

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(result.errorOrNull()!!.code).isEqualTo("invalid_payload")
    }

    @Test
    fun missingRequiredFieldIsFailure() {
        assertThat(ShadowMapper.parse("""{"version": 1}""".toByteArray()).errorOrNull()).isNotNull()
    }
}
