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
    private val notOwner = DeviceOwnerRole { false }
    private val serialUnknown = Reading.Unavailable(UnavailableReason.Failed("serial_unknown"))

    @Test
    fun serialIsReturned() {
        val collector = SerialCollector(role = notOwner, getSerial = { "R58M123ABC" })
        assertThat(collector.read()).isEqualTo(Reading.Available("R58M123ABC"))
    }

    @Test
    fun unknownIsFailed() {
        assertThat(SerialCollector(role = notOwner, getSerial = { "unknown" }).read()).isEqualTo(serialUnknown)
    }

    @Test
    fun blankIsFailed() {
        assertThat(SerialCollector(role = notOwner, getSerial = { "  " }).read()).isEqualTo(serialUnknown)
    }

    private fun denied(api: Int, deviceOwner: Boolean = false) =
        SerialCollector(ApiLevel { api }, DeviceOwnerRole { deviceOwner }) {
            throw SecurityException("requires READ_PRIVILEGED_PHONE_STATE")
        }.read()

    @Test
    fun securityExceptionOnApi26To28IsMissingReadPhoneStateForAnyRole() {
        for (api in 26..28) {
            for (owner in listOf(true, false)) {
                assertThat(denied(api, owner))
                    .isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("READ_PHONE_STATE")))
            }
        }
    }

    @Test
    fun deviceOwnerWithoutReadPhoneStateOnApi29PlusNeedsThePermission() {
        // решение 118: на API 36 владелец устройства получает серийный номер только при выданном READ_PHONE_STATE
        for (api in listOf(29, 34, 36, 37)) {
            assertThat(denied(api, deviceOwner = true))
                .isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("READ_PHONE_STATE")))
        }
    }

    @Test
    fun nonDeviceOwnerOnApi29PlusRequiresDeviceOwner() {
        for (api in listOf(29, 34, 37)) {
            assertThat(denied(api, deviceOwner = false))
                .isEqualTo(Reading.Unavailable(UnavailableReason.RequiresDeviceOwner))
        }
    }

    @Test
    fun otherFailuresAreIsolated() {
        val reading = SerialCollector(role = notOwner, getSerial = { error("boom") }).read()
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.Failed("boom")))
    }

    @Test
    @Config(sdk = [29])
    fun defaultSourceReadsBuildGetSerial() {
        ShadowBuild.setSerial("SHADOW-SERIAL-1")
        assertThat(SerialCollector(role = notOwner).read()).isEqualTo(Reading.Available("SHADOW-SERIAL-1"))
    }
}
