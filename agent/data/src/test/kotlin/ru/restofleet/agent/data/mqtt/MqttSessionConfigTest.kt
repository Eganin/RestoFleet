package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.data.mqtt.mapping.Fixtures
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.DeviceStateDto
import ru.restofleet.shared.protocol.dto.StatusDto

class MqttSessionConfigTest {
    private val settings = BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, "secret-pw")
    private val clock = Clock { 1_793_865_600_000 }

    @Test
    fun usesSessionParametersOfTheBrief() {
        val config = MqttSessionConfigFactory.from(settings, "k-1", clock)

        assertThat(config.keepAliveSec).isEqualTo(30)
        assertThat(config.cleanStart).isFalse()
        assertThat(config.sessionExpirySec).isEqualTo(86_400)
        assertThat(config.clientId).isEqualTo("dev-emu-36")
        assertThat(config.host).isEqualTo("10.0.2.2")
        assertThat(config.port).isEqualTo(8883)
        assertThat(config.connId).isEqualTo("k-1")
    }

    @Test
    fun willIsRetainedQosOneOnStatusTopic() {
        val config = MqttSessionConfigFactory.from(settings, "k-1", clock)

        assertThat(config.willTopic).isEqualTo("rf/v1/dev/dev-emu-36/status")
        assertThat(config.willRetain).isTrue()
        assertThat(config.willQos).isEqualTo(1)
    }

    @Test
    fun willPayloadIsOfflineLwtWithTheSameConnId() {
        val config = MqttSessionConfigFactory.from(settings, "k-7f3a9c", clock)

        val will = RfJson.decodeFromString(StatusDto.serializer(), config.willPayload.decodeToString())

        assertThat(will).isEqualTo(
            StatusDto(DeviceStateDto.OFFLINE, ts = 1_793_865_600_000, connId = "k-7f3a9c", reason = "lwt"),
        )
        assertThat(RfJson.parseToJsonElement(config.willPayload.decodeToString()))
            .isEqualTo(Fixtures.json("status-offline-lwt"))
    }

    @Test
    fun configDoesNotCarryThePassword() {
        val config = MqttSessionConfigFactory.from(settings, "k-1", clock)

        assertThat(config.toString()).doesNotContain("secret-pw")
        assertThat(config.willPayload.decodeToString()).doesNotContain("secret-pw")
    }

    @Test
    fun everyConnectionGetsAFreshConnIdAndWillMatchesIt() {
        var n = 0
        val configs = ConnectionConfigs(settings, IdGenerator { "k-${++n}" }, clock)

        val first = configs.next()
        val second = configs.next()

        assertThat(first.connId).isEqualTo("k-1")
        assertThat(second.connId).isEqualTo("k-2")
        assertThat(second.willPayload.decodeToString()).contains("\"connId\":\"k-2\"")
        assertThat(first.willPayload.decodeToString()).contains("\"connId\":\"k-1\"")
    }

    @Test
    fun equalConfigsAreEqualByWillContent() {
        val a = MqttSessionConfigFactory.from(settings, "k-1", clock)
        val b = MqttSessionConfigFactory.from(settings, "k-1", clock)

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
