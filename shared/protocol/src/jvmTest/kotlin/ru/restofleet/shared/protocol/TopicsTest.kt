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
}
