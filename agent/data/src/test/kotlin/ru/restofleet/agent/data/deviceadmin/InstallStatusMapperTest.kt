package ru.restofleet.agent.data.deviceadmin

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.shared.kernel.Outcome

class InstallStatusMapperTest {
    private fun failure(status: Int, message: String?) =
        Outcome.Failure(DeviceAdminError.InstallFailed(status, message))

    @Test
    fun successIsZero() {
        assertThat(InstallStatusMapper.map(0, null)).isEqualTo(Outcome.Success(Unit))
        assertThat(InstallStatusMapper.map(0, "ignored")).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun abortedKeepsCodeAndMessage() {
        assertThat(InstallStatusMapper.map(3, "INSTALL_FAILED_ABORTED")).isEqualTo(failure(3, "INSTALL_FAILED_ABORTED"))
    }

    @Test
    fun everyFailureCodeFromOneToSevenKeepsCodeAndMessage() {
        for (status in 1..7) {
            assertThat(InstallStatusMapper.map(status, "m$status")).isEqualTo(failure(status, "m$status"))
        }
    }

    @Test
    fun missingMessageStaysNull() {
        assertThat(InstallStatusMapper.map(6, null)).isEqualTo(failure(6, null))
    }

    @Test
    fun pendingUserActionIsAFailureSignalForDeviceOwner() {
        assertThat(InstallStatusMapper.map(-1, "Need confirm")).isEqualTo(failure(-1, "pending_user_action"))
        assertThat(InstallStatusMapper.map(-1, null)).isEqualTo(failure(-1, "pending_user_action"))
    }

    @Test
    fun unknownCodesAreFailuresWithOriginalCodeAndMessage() {
        assertThat(InstallStatusMapper.map(8, "timeout")).isEqualTo(failure(8, "timeout"))
        assertThat(InstallStatusMapper.map(-2, null)).isEqualTo(failure(-2, null))
        assertThat(InstallStatusMapper.map(99, "x")).isEqualTo(failure(99, "x"))
    }

    @Test
    fun errorCodeContainsStatusButNotMessage() {
        val error = (InstallStatusMapper.map(3, "secret path /data/x") as Outcome.Failure).error
        assertThat(error.code).isEqualTo("install_failed:3")
    }
}
