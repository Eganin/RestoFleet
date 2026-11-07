package ru.restofleet.agent.data.deviceadmin

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerialCollectorTest {
    private val serialUnknown = Reading.Unavailable(UnavailableReason.Failed("serial_unknown"))

    @Test
    fun serialIsReturned() {
        assertThat(SerialCollector { "R58M123ABC" }.read()).isEqualTo(Reading.Available("R58M123ABC"))
    }

    @Test
    fun unknownIsFailed() {
        assertThat(SerialCollector { "unknown" }.read()).isEqualTo(serialUnknown)
    }

    @Test
    fun blankIsFailed() {
        assertThat(SerialCollector { "  " }.read()).isEqualTo(serialUnknown)
    }

    private fun denied(api: Int) =
        SerialCollector(ApiLevel { api }) { throw SecurityException("requires READ_PRIVILEGED_PHONE_STATE") }.read()

    @Test
    fun securityExceptionOnApi26To28IsMissingReadPhoneState() {
        for (api in 26..28) {
            assertThat(denied(api))
                .isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("READ_PHONE_STATE")))
        }
    }

    @Test
    fun securityExceptionOnApi29PlusMeansNotDeviceOwner() {
        // с API 29 разрешение READ_PHONE_STATE не помогает: серийный номер отдаётся только Device Owner
        for (api in listOf(29, 34, 37)) {
            assertThat(denied(api)).isEqualTo(Reading.Unavailable(UnavailableReason.RequiresDeviceOwner))
        }
    }

    @Test
    fun otherFailuresAreIsolated() {
        val reading = SerialCollector { error("boom") }.read()
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.Failed("boom")))
    }

    @Test
    @Config(sdk = [29])
    fun defaultSourceReadsBuildGetSerial() {
        ShadowBuild.setSerial("SHADOW-SERIAL-1")
        assertThat(SerialCollector().read()).isEqualTo(Reading.Available("SHADOW-SERIAL-1"))
    }
}
