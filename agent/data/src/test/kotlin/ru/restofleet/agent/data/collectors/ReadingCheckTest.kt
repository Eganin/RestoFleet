package ru.restofleet.agent.data.collectors

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.shared.kernel.Clock

class ReadingCheckTest {
    private val clock = Clock { 1_000L }

    private suspend fun run(reading: Reading<Int>) =
        ReadingCheck(CheckId.R1, clock, { reading }, { mapOf("v" to it.toString()) }).run()

    private suspend fun runUnavailable(reason: UnavailableReason) = run(Reading.Unavailable(reason))

    @Test
    fun availableIsOkWithRaw() = runTest {
        val result = run(Reading.Available(42))
        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.raw).containsExactly("v", "42")
        assertThat(result.note).isNull()
        assertThat(result.checkedAt).isEqualTo(1_000L)
        assertThat(result.id).isEqualTo(CheckId.R1)
    }

    @Test
    fun emulatorStubIsLimited() = runTest {
        val result = runUnavailable(UnavailableReason.EmulatorStub)
        assertThat(result.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(result.note).isEqualTo("EmulatorStub")
        assertThat(result.raw).isEmpty()
    }

    @Test
    fun apiLevelTooLowIsNotApplicable() = runTest {
        val result = runUnavailable(UnavailableReason.ApiLevelTooLow(29, 26))
        assertThat(result.status).isEqualTo(CheckStatus.NOT_APPLICABLE)
        assertThat(result.note).isEqualTo("ApiLevelTooLow: requires API 29, actual 26")
    }

    @Test
    fun requiresDeviceOwnerIsNotApplicable() = runTest {
        val result = runUnavailable(UnavailableReason.RequiresDeviceOwner)
        assertThat(result.status).isEqualTo(CheckStatus.NOT_APPLICABLE)
        assertThat(result.note).isEqualTo("RequiresDeviceOwner")
    }

    @Test
    fun permissionMissingIsUnavailable() = runTest {
        val result = runUnavailable(UnavailableReason.PermissionMissing("PACKAGE_USAGE_STATS"))
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("PermissionMissing: PACKAGE_USAGE_STATS")
    }

    @Test
    fun noSensorIsUnavailable() = runTest {
        val result = runUnavailable(UnavailableReason.NoSensor)
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("NoSensor")
    }

    @Test
    fun failedIsUnavailableWithMessage() = runTest {
        val result = runUnavailable(UnavailableReason.Failed("boom"))
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("Failed: boom")
    }

    @Test
    fun thrownExceptionBecomesUnavailable() = runTest {
        val check = ReadingCheck<Int>(CheckId.R2, clock, { error("bad") }, { emptyMap() })
        val result = check.run()
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("Failed: bad")
    }
}
