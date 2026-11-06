package ru.restofleet.agent.data.collectors

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.ThermalStatus
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidTelemetrySourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = ApiLevel { 30 }
    private var dispatches = 0
    private val countingIo = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }
    }
    private val source = AndroidTelemetrySource(
        battery = BatteryCollector(context),
        thermal = ThermalCollector(context, api, "release-keys"),
        network = NetworkCollector(context, api),
        resources = ResourceCollector(context),
        foreground = ForegroundAppCollector(context, Clock { 0L }, api),
        appVersions = AppVersionsCollector(context),
        io = countingIo,
    )

    @Test
    fun everyBlockingReadingGoesThroughTheIoDispatcher() = runTest {
        source.battery()
        source.thermal()
        source.network()
        source.resources()
        source.foregroundApp()
        source.apps(emptyList())

        assertThat(dispatches).isEqualTo(6)
    }

    @Test
    fun missingPackageGivesEmptyList() = runTest {
        assertThat(source.apps(listOf("com.missing"))).isEqualTo(Reading.Available(emptyList<AppVersion>()))
    }

    @Test
    fun installedPackageIsReported() = runTest {
        val reading = source.apps(listOf(context.packageName, "com.missing"))
        assertThat(reading).isInstanceOf(Reading.Available::class.java)
        val apps = (reading as Reading.Available).value
        assertThat(apps.map { it.pkg }).containsExactly(context.packageName)
    }

    @Test
    fun batteryDelegatesToCollector() = runTest {
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra("level", 40).putExtra("scale", 100),
        )
        val value = (source.battery() as Reading.Available).value
        assertThat(value.levelPct).isEqualTo(40)
    }

    @Test
    fun resourcesDelegateToCollector() = runTest {
        assertThat((source.resources() as Reading.Available).value.apiLevel).isEqualTo(30)
    }

    @Test
    fun networkWithoutActiveNetworkIsNone() = runTest {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        org.robolectric.Shadows.shadowOf(cm).setActiveNetworkInfo(null)
        assertThat(source.network()).isEqualTo(Reading.Available(NetworkReader.NONE))
    }

    @Test
    fun thermalDelegatesToCollector() = runTest {
        // отпечаток "release-keys" — не эмулятор, статус читается как есть
        val value = (source.thermal() as Reading.Available).value
        assertThat(value.status).isEqualTo(ThermalStatus.NONE)
    }

    @Test
    fun foregroundWithoutUsageAccessIsMissing() = runTest {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        org.robolectric.Shadows.shadowOf(appOps).setMode(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
            android.app.AppOpsManager.MODE_ERRORED,
        )
        assertThat(source.foregroundApp())
            .isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("PACKAGE_USAGE_STATS")))
    }
}
