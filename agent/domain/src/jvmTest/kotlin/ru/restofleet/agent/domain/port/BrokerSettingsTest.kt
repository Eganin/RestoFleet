package ru.restofleet.agent.domain.port

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.shared.kernel.DeviceId

class BrokerSettingsTest {
    @Test
    fun toStringMasksPasswordAndKeepsOtherFields() {
        val s = BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, "s3cr3t-value")

        assertThat(s.toString()).doesNotContain("s3cr3t-value")
        assertThat(s.toString()).contains("password=***")
        assertThat(s.toString()).contains("10.0.2.2")
        assertThat(s.toString()).contains("dev-emu-36")
    }
}
