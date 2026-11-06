package ru.restofleet.agent.data.collectors

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.shared.kernel.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ForegroundAppCollectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val collector = ForegroundAppCollector(context, Clock { 10_000L }) { 30 }

    private companion object {
        const val OP_GET_USAGE_STATS = 43
    }

    private fun setMode(mode: Int) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            OP_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            mode,
        )
    }

    @Test
    fun withoutUsageAccessPermissionIsMissing() {
        setMode(AppOpsManager.MODE_ERRORED)
        assertThat(collector.read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("PACKAGE_USAGE_STATS")))
    }

    @Test
    fun resumedEventBecomesForegroundApp() {
        setMode(AppOpsManager.MODE_ALLOWED)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        shadowOf(usm).addEvent("com.android.chrome", 5_000L, UsageEvents.Event.ACTIVITY_RESUMED)
        assertThat(collector.read()).isEqualTo(Reading.Available(ForegroundApp("com.android.chrome", 5_000L)))
    }

    @Test
    fun noEventsIsFailed() {
        setMode(AppOpsManager.MODE_ALLOWED)
        val reading = collector.read()
        assertThat(reading).isInstanceOf(Reading.Unavailable::class.java)
        assertThat((reading as Reading.Unavailable).reason).isInstanceOf(UnavailableReason.Failed::class.java)
    }
}
