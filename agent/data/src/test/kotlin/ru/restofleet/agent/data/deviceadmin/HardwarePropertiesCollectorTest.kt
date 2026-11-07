package ru.restofleet.agent.data.deviceadmin

import android.os.HardwarePropertiesManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.model.HardwareTemps
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardwarePropertiesCollectorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val real = "samsung/gta9/gta9:14/UP1A:user/release-keys"
    private val emu = "google/sdk_gphone64/emu64a:16/dev-keys"
    private val undefined = HardwarePropertiesManager.UNDEFINED_TEMPERATURE
    private val cpuType = HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU
    private val skinType = HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN
    private val batteryType = HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY
    private val currentSource = HardwarePropertiesManager.TEMPERATURE_CURRENT

    private fun hpm(cpu: FloatArray, skin: FloatArray, battery: FloatArray) = mockk<HardwarePropertiesManager> {
        every { getDeviceTemperatures(cpuType, currentSource) } returns cpu
        every { getDeviceTemperatures(skinType, currentSource) } returns skin
        every { getDeviceTemperatures(batteryType, currentSource) } returns battery
    }

    private fun collector(
        owner: Boolean = true,
        fingerprint: String = real,
        hardware: String = "qcom",
        manager: () -> HardwarePropertiesManager?,
    ) = HardwarePropertiesCollector(context, { owner }, fingerprint, hardware, manager)

    @Test
    fun withoutDeviceOwnerTheManagerIsNeverTouched() {
        var asked = false
        val reading = collector(owner = false) { asked = true; null }.read()
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.RequiresDeviceOwner))
        assertThat(asked).isFalse()
    }

    @Test
    fun missingServiceIsFailed() {
        assertThat(collector { null }.read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.Failed("no_hardware_properties_service")))
    }

    @Test
    fun readsCurrentTemperaturesOfCpuSkinAndBattery() {
        val manager = hpm(floatArrayOf(40.5f, 41f), floatArrayOf(30f), floatArrayOf(28f))
        val reading = collector { manager }.read()
        assertThat(reading).isEqualTo(Reading.Available(HardwareTemps(listOf(40.5f, 41f), listOf(30f), listOf(28f))))
        verify(exactly = 1) { manager.getDeviceTemperatures(cpuType, currentSource) }
    }

    @Test
    fun undefinedValuesAreFilteredOut() {
        val manager = hpm(floatArrayOf(undefined, 40f), floatArrayOf(undefined), floatArrayOf(28f))
        val reading = collector { manager }.read() as Reading.Available
        assertThat(reading.value).isEqualTo(HardwareTemps(listOf(40f), emptyList(), listOf(28f)))
    }

    @Test
    fun noValuesAtAllOnRealHardwareIsNoSensor() {
        val manager = hpm(floatArrayOf(), floatArrayOf(undefined), floatArrayOf())
        assertThat(collector { manager }.read()).isEqualTo(Reading.Unavailable(UnavailableReason.NoSensor))
    }

    @Test
    fun noValuesAtAllOnEmulatorIsStub() {
        val manager = hpm(floatArrayOf(), floatArrayOf(), floatArrayOf())
        assertThat(collector(fingerprint = emu, hardware = "ranchu") { manager }.read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.EmulatorStub))
    }

    @Test
    fun securityExceptionMeansTheRoleWasLost() {
        val manager = mockk<HardwarePropertiesManager> {
            every { getDeviceTemperatures(any(), any()) } throws SecurityException("no")
        }
        assertThat(collector { manager }.read()).isEqualTo(Reading.Unavailable(UnavailableReason.RequiresDeviceOwner))
    }

    @Test
    fun otherFailuresAreIsolated() {
        val manager = mockk<HardwarePropertiesManager> {
            every { getDeviceTemperatures(any(), any()) } throws IllegalStateException("hal died")
        }
        assertThat(collector { manager }.read()).isEqualTo(Reading.Unavailable(UnavailableReason.Failed("hal died")))
    }
}
