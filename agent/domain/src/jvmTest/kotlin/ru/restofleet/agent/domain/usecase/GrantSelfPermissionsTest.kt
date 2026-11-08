package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import java.util.concurrent.CancellationException
import kotlin.test.assertFailsWith

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
        assertThat(result.note).isEqualTo("not_granted:ACCESS_FINE_LOCATION,PACKAGE_USAGE_STATS")
    }

    @Test
    fun emptyMapIsLimitedBecauseNothingWasGranted() = runTest {
        val result = grant()

        assertThat(result.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(result.note).isEqualTo("nothing_to_grant")
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

    @Test
    fun portExceptionIsIsolatedAsUnavailableAndRecorded() = runTest {
        admin.grantBefore = { throw IllegalStateException("boom") }

        val result = grant()

        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("exception:IllegalStateException")
        assertThat(result.raw).isEmpty()
        assertThat(journal.records).containsExactly(result)
    }

    @Test
    fun strayCancellationFromThePortIsAFailureNotACancel() = runTest {
        admin.grantBefore = { throw CancellationException("stray") }

        assertThat(grant().note).isEqualTo("exception:CancellationException")
    }

    @Test
    fun cancellationOfTheCallerPropagatesAndNothingIsRecorded() = runTest {
        lateinit var outer: Job
        admin.grantBefore = {
            outer.cancel()
            throw CancellationException("cancelled by caller") // без suspend-точки
        }
        var outcome: Result<CheckResult>? = null
        outer = launch { outcome = runCatching { grant() } }

        runCurrent()

        assertThat(outcome!!.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(journal.records).isEmpty()
    }

    @Test
    fun errorIsNotSwallowed() = runTest {
        admin.grantBefore = { throw OutOfMemoryError("oom") }

        assertFailsWith<OutOfMemoryError> { grant() }
    }

    private companion object {
        const val NOW = 1_700_000_000_123L
    }
}
