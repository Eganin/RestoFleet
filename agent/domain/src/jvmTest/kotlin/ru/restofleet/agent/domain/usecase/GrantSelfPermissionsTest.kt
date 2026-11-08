package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.fake.FakeCheckResultJournal
import ru.restofleet.agent.domain.fake.FakeDeviceAdmin
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.shared.kernel.Clock

class GrantSelfPermissionsTest {
    private var mode = AgentMode.DEVICE_OWNER
    private val admin = FakeDeviceAdmin()
    private val journal = FakeCheckResultJournal()
    private val grant = GrantSelfPermissions(
        CapabilitiesSource { Capabilities(mode, 36, emptySet()) },
        admin,
        journal,
        Clock { NOW },
    )

    @Test
    fun allGrantedIsOkWithPermissionMap() = runTest {
        admin.grantResult = linkedMapOf("ACCESS_FINE_LOCATION" to true, "POST_NOTIFICATIONS" to true)

        val result = grant()

        assertThat(result).isEqualTo(
            CheckResult(
                CheckId.D6,
                CheckStatus.OK,
                linkedMapOf("ACCESS_FINE_LOCATION" to "true", "POST_NOTIFICATIONS" to "true"),
                null,
                NOW,
            ),
        )
    }

    @Test
    fun notGrantedPermissionMakesTheResultLimitedAndNamesIt() = runTest {
        admin.grantResult = linkedMapOf("ACCESS_FINE_LOCATION" to false, "PACKAGE_USAGE_STATS" to false)

        val result = grant()

        assertThat(result.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(result.raw).containsExactly("ACCESS_FINE_LOCATION", "false", "PACKAGE_USAGE_STATS", "false")
        assertThat(result.note).isEqualTo("не выдано: ACCESS_FINE_LOCATION, PACKAGE_USAGE_STATS")
    }

    @Test
    fun emptyMapIsLimitedBecauseNothingWasGranted() = runTest {
        val result = grant()

        assertThat(result.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(result.note).isEqualTo("нет разрешений для выдачи")
    }

    @Test
    fun withoutDeviceOwnerIsNotApplicableAndPortIsNotCalled() = runTest {
        mode = AgentMode.NORMAL

        val result = grant()

        assertThat(result.status).isEqualTo(CheckStatus.NOT_APPLICABLE)
        assertThat(result.note).isEqualTo("requires_device_owner")
        assertThat(admin.grantCalls).isEqualTo(0)
    }

    @Test
    fun resultIsRecordedInTheJournal() = runTest {
        admin.grantResult = mapOf("ACCESS_FINE_LOCATION" to true)

        val result = grant()

        assertThat(journal.records).containsExactly(result)
    }

    private companion object {
        const val NOW = 1_700_000_000_123L
    }
}
