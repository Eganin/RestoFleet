package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.shared.kernel.DeviceId

class TopicsTest {

    private val id = requireNotNull(DeviceId.parse("dev-emu-36"))

    @Test
    fun buildsAllDeviceTopics() {
        assertThat(Topics.status(id)).isEqualTo("rf/v1/dev/dev-emu-36/status")
        assertThat(Topics.telemetry(id)).isEqualTo("rf/v1/dev/dev-emu-36/telemetry")
        assertThat(Topics.probe(id)).isEqualTo("rf/v1/dev/dev-emu-36/probe")
        assertThat(Topics.events(id)).isEqualTo("rf/v1/dev/dev-emu-36/events")
        assertThat(Topics.shadowReported(id)).isEqualTo("rf/v1/dev/dev-emu-36/shadow/reported")
        assertThat(Topics.shadowDesired(id)).isEqualTo("rf/v1/dev/dev-emu-36/shadow/desired")
        assertThat(Topics.cmdReq(id)).isEqualTo("rf/v1/dev/dev-emu-36/cmd/req")
        assertThat(Topics.cmdRes(id)).isEqualTo("rf/v1/dev/dev-emu-36/cmd/res")
    }

    @Test
    fun extractsDeviceIdFromTopic() {
        assertThat(Topics.deviceIdOf("rf/v1/dev/d1/cmd/req")?.value).isEqualTo("d1")
        assertThat(Topics.deviceIdOf("rf/v1/dev/d1/status")?.value).isEqualTo("d1")
    }

    @Test
    fun deviceIdOfForeignOrMalformedTopicIsNull() {
        val topics = listOf(
            "rf/v2/dev/d1/status",
            "rf/v1/rest/r1/broadcast",
            "rf/v1/dev/",
            "rf/v1/dev/a+b/status",
            "rf/v1/dev/d1",
            "rf/v1/dev/d1/",
        )
        topics.forEach {
            assertWithMessage(it).that(Topics.deviceIdOf(it)).isNull()
        }
    }

    @Test
    fun rfJsonIgnoresUnknownKeysAndOmitsNulls() {
        assertThat(RfJson.configuration.ignoreUnknownKeys).isTrue()
        assertThat(RfJson.configuration.explicitNulls).isFalse()
        assertThat(RfJson.configuration.encodeDefaults).isTrue()
        assertThat(RfJson.configuration.classDiscriminator).isEqualTo("type")
    }

    @Test
    fun parsesDeviceAndChannelFromEveryDeviceTopic() {
        DeviceChannel.entries.forEach { channel ->
            val parsed = Topics.parse("rf/v1/dev/dev-emu-36/${channel.suffix}")
            assertWithMessage(channel.name).that(parsed).isEqualTo(DeviceTopic(id, channel))
        }
    }

    @Test
    fun parseRejectsUnknownSuffixAndForeignTopics() {
        val topics = listOf(
            "rf/v1/dev/d1/shadow",
            "rf/v1/dev/d1/cmd/req/extra",
            "rf/v1/dev/d1/teleport",
            "rf/v2/dev/d1/status",
            "rf/v1/dev/a+b/status",
            "rf/v1/dev/d1",
        )
        topics.forEach { assertWithMessage(it).that(Topics.parse(it)).isNull() }
    }

    @Test
    fun channelTopicMatchesBuilder() {
        assertThat(Topics.of(id, DeviceChannel.SHADOW_REPORTED)).isEqualTo(Topics.shadowReported(id))
        assertThat(Topics.of(id, DeviceChannel.CMD_RES)).isEqualTo(Topics.cmdRes(id))
    }

    @Test
    fun backendSharedSubscriptionsCoverInboundChannels() {
        assertThat(SharedSubscriptions.backend()).containsExactly(
            "\$share/backend/rf/v1/dev/+/status",
            "\$share/backend/rf/v1/dev/+/telemetry",
            "\$share/backend/rf/v1/dev/+/probe",
            "\$share/backend/rf/v1/dev/+/events",
            "\$share/backend/rf/v1/dev/+/shadow/reported",
            "\$share/backend/rf/v1/dev/+/cmd/res",
        )
        assertThat(DeviceChannel.entries.filterNot { it.fromDevice })
            .containsExactly(DeviceChannel.SHADOW_DESIRED, DeviceChannel.CMD_REQ)
    }

    @Test
    fun sharedSubscriptionUsesGivenGroup() {
        assertThat(SharedSubscriptions.of("sim", DeviceChannel.STATUS)).isEqualTo("\$share/sim/rf/v1/dev/+/status")
    }
}
