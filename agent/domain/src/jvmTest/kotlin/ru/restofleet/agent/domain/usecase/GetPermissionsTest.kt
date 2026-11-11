package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.Permission

class GetPermissionsTest {
    @Test
    fun grantedPermissionsComeFromCapabilities() = runTest {
        val granted = setOf(Permission.USAGE_ACCESS, Permission.IGNORE_BATTERY_OPT)

        assertThat(GetPermissions { Capabilities(AgentMode.NORMAL, 36, granted) }()).isEqualTo(granted)
    }
}
