package ru.restofleet.agent.data.deviceadmin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock

class DeviceOwnerCheckTest {
    private fun run(mode: AgentMode) = runBlocking {
        DeviceOwnerCheck(Clock { 42L }, { Capabilities(mode, 36, emptySet()) }).run()
    }

    @Test
    fun idIsD1() {
        assertThat(DeviceOwnerCheck(Clock { 0L }, { Capabilities(AgentMode.NORMAL, 36, emptySet()) }).id)
            .isEqualTo(CheckId.D1)
    }

    @Test
    fun deviceOwnerModeIsOkWithModeInRaw() {
        val result = run(AgentMode.DEVICE_OWNER)

        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.raw).containsExactly("mode", "DEVICE_OWNER")
        assertThat(result.checkedAt).isEqualTo(42L)
    }

    @Test
    fun normalModeIsUnavailableWithHowToAssign() {
        val result = run(AgentMode.NORMAL)

        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.raw).containsExactly("mode", "NORMAL")
        assertThat(result.note).isEqualTo("not_device_owner")
    }
}
