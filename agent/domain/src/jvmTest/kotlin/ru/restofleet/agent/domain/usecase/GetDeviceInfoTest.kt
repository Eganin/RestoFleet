package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.DeviceInfo

class GetDeviceInfoTest {
    @Test
    fun returnsWhatTheSourceReports() = runTest {
        val device = DeviceInfo(36, "Pixel 9a", true, AgentMode.NORMAL, "0.1.0")

        assertThat(GetDeviceInfo { device }()).isEqualTo(device)
    }
}
