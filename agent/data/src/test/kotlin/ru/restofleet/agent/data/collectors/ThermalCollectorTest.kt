package ru.restofleet.agent.data.collectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.model.ThermalStatus
import ru.restofleet.agent.domain.model.UnavailableReason

@RunWith(RobolectricTestRunner::class)
class ThermalCollectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val real = "samsung/gta9/gta9:14/UP1A:user/release-keys"
    private val emu = "google/sdk_gphone64/emu64a:16/dev-keys"

    private fun collector(api: Int, fingerprint: String = real, hardware: String = "qcom") =
        ThermalCollector(context, { api }, fingerprint, hardware)

    @Test
    @Config(sdk = [26])
    fun api26IsTooLow() {
        assertThat(collector(26).read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.ApiLevelTooLow(29, 26)))
    }

    @Test
    @Config(sdk = [29])
    fun api29HasNoHeadroomMethod() {
        assertThat(collector(29).headroom()).isNull()
        assertThat(collector(29).read())
            .isEqualTo(Reading.Available(ThermalState(ThermalStatus.NONE, null)))
    }

    @Test
    @Config(sdk = [30])
    fun api30ReadsStatusFromPlatform() {
        org.robolectric.Shadows.shadowOf(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .setCurrentThermalStatus(3)
        val reading = collector(30).read() as Reading.Available
        assertThat(reading.value.status).isEqualTo(ThermalStatus.SEVERE)
    }

    @Test
    @Config(sdk = [30])
    fun emulatorWithoutHeadroomIsStub() {
        // Robolectric не оценивает запас (NaN или 0) — на эмуляторе это заглушка
        val reading = collector(30, emu, "ranchu").read()
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.EmulatorStub))
    }

    @Test
    @Config(sdk = [29])
    fun emulatorApi29WithoutHeadroomIsStub() {
        assertThat(collector(29, emu, "ranchu").read()).isEqualTo(Reading.Unavailable(UnavailableReason.EmulatorStub))
    }

    @Test
    @Config(sdk = [30])
    fun apiLevelDeviceIsPlatformSdk() {
        assertThat(ApiLevel.Device.current()).isEqualTo(android.os.Build.VERSION.SDK_INT)
    }
}
