package ru.restofleet.agent.data.credentials

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId

class CredentialsCheckTest {
    private fun check(state: StoredCredentials) = CredentialsCheck(Clock { 42L }) { state }

    @Test
    fun idIsL5() {
        assertThat(check(StoredCredentials.Missing).id).isEqualTo(CheckId.L5)
    }

    @Test
    fun presentRecordIsOkAndRawHasOnlyDeviceIdAndEndpoint() = runTest {
        val s = BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, "pw-9f3c-value")

        val r = check(StoredCredentials.Present(s)).run()

        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw).containsExactly("deviceId", "dev-emu-36", "endpoint", "10.0.2.2:8883")
        assertThat(r.toString()).doesNotContain("pw-9f3c-value")
        assertThat(r.checkedAt).isEqualTo(42L)
    }

    @Test
    fun missingRecordIsNotCheckedWithReason() = runTest {
        val r = check(StoredCredentials.Missing).run()

        assertThat(r.status).isEqualTo(CheckStatus.NOT_CHECKED)
        assertThat(r.note).contains("no credentials saved")
        assertThat(r.raw).isEmpty()
    }

    @Test
    fun unreadableRecordIsUnavailableWithCause() = runTest {
        val r = check(StoredCredentials.Unreadable("AEADBadTagException")).run()

        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("AEADBadTagException")
        assertThat(r.raw).containsExactly("cause", "AEADBadTagException")
    }
}
