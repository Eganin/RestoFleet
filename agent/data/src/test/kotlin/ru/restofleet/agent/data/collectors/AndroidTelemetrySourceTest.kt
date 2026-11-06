package ru.restofleet.agent.data.collectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.shared.kernel.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidTelemetrySourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = ApiLevel { 30 }
    private val source = AndroidTelemetrySource(
        battery = BatteryCollector(context),
        thermal = ThermalCollector(context, api, "release-keys"),
        network = NetworkCollector(context, api),
        resources = ResourceCollector(context),
        foreground = ForegroundAppCollector(context, Clock { 0L }, api),
        appVersions = AppVersionsCollector(context),
    )

    @Test
    fun missingPackageGivesEmptyList() = runTest {
        assertThat(source.apps(listOf("com.missing"))).isEqualTo(Reading.Available(emptyList<Any>()))
    }

    @Test
    fun installedPackageIsReported() = runTest {
        val reading = source.apps(listOf(context.packageName, "com.missing"))
        assertThat(reading).isInstanceOf(Reading.Available::class.java)
        val apps = (reading as Reading.Available).value
        assertThat(apps.map { it.pkg }).containsExactly(context.packageName)
    }

    @Test
    fun batteryAndResourcesAreAvailable() = runTest {
        assertThat(source.battery()).isInstanceOf(Reading::class.java)
        val resources = source.resources()
        assertThat(resources).isInstanceOf(Reading.Available::class.java)
        assertThat(BatteryState::class.simpleName).isNotNull()
    }

    @Test
    fun networkWithoutActiveNetworkIsNone() = runTest {
        assertThat(source.network()).isInstanceOf(Reading::class.java)
    }

    @Test
    fun thermalAndForegroundDelegate() = runTest {
        assertThat(source.thermal()).isInstanceOf(Reading::class.java)
        assertThat(source.foregroundApp()).isInstanceOf(Reading::class.java)
    }
}
