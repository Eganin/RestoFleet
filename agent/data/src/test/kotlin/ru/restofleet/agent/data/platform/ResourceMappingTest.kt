package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResourceMappingTest {

    @Test
    fun bytesToMegabytes() {
        assertThat(ResourceMapping.bytesToMb(0)).isEqualTo(0L)
        assertThat(ResourceMapping.bytesToMb(1_048_575)).isEqualTo(0L)
        assertThat(ResourceMapping.bytesToMb(5L * 1_048_576)).isEqualTo(5L)
    }

    @Test
    fun uptimeInSeconds() {
        assertThat(ResourceMapping.uptimeSec(0)).isEqualTo(0L)
        assertThat(ResourceMapping.uptimeSec(61_999)).isEqualTo(61L)
    }
}
