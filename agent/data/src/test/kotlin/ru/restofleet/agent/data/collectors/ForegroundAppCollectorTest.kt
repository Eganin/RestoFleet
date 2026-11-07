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
    private var now = 10_000L
    private var elapsed = 10_000L
    private val collector = ForegroundAppCollector(context, Clock { now }, { elapsed }) { 30 }
    private val usm get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private fun setMode(mode: Int) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
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

    @Test
    @Config(sdk = [26])
    fun legacyCheckOpPathOnApi26() {
        val legacy = ForegroundAppCollector(context, Clock { 10_000L }, { 10_000L }) { 26 }
        setMode(AppOpsManager.MODE_ERRORED)
        assertThat(legacy.read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("PACKAGE_USAGE_STATS")))
    }

    @Test
    fun defaultModeFallsBackToManifestPermission() {
        setMode(AppOpsManager.MODE_DEFAULT)
        val reading = collector.read() as Reading.Unavailable
        // разрешение не выдано -> PermissionMissing, выданное -> дальше без событий Failed
        assertThat(reading.reason).isEqualTo(UnavailableReason.PermissionMissing("PACKAGE_USAGE_STATS"))
    }

    // ---- R8 для приложения, не уходившего с экрана дольше часа

    private fun device(uptimeMs: Long, nowMs: Long) {
        now = nowMs
        elapsed = uptimeMs
    }

    @Test
    fun appResumedTwoHoursAgoIsStillForeground() {
        setMode(AppOpsManager.MODE_ALLOWED)
        device(uptimeMs = 90_000_000, nowMs = 100_000_000)
        shadowOf(usm).addEvent("com.android.chrome", now - 2 * HOUR, UsageEvents.Event.ACTIVITY_RESUMED)

        assertThat(collector.read())
            .isEqualTo(Reading.Available(ForegroundApp("com.android.chrome", now - 2 * HOUR)))
    }

    @Test
    fun secondReadWithoutNewEventsKeepsForegroundApp() {
        setMode(AppOpsManager.MODE_ALLOWED)
        device(uptimeMs = 90_000_000, nowMs = 100_000_000)
        shadowOf(usm).addEvent("com.android.chrome", now - HOUR, UsageEvents.Event.ACTIVITY_RESUMED)
        val first = collector.read()

        device(uptimeMs = 90_000_000 + 2 * HOUR, nowMs = 100_000_000 + 2 * HOUR)
        val second = collector.read()

        assertThat(first).isEqualTo(Reading.Available(ForegroundApp("com.android.chrome", 100_000_000 - HOUR)))
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun switchingFromAToBAndStayingOnBKeepsB() {
        setMode(AppOpsManager.MODE_ALLOWED)
        device(uptimeMs = 90_000_000, nowMs = 100_000_000)
        shadowOf(usm).addEvent("a", now - 3 * HOUR, UsageEvents.Event.ACTIVITY_RESUMED)
        assertThat(collector.read()).isEqualTo(Reading.Available(ForegroundApp("a", now - 3 * HOUR)))

        device(uptimeMs = 90_000_000 + HOUR, nowMs = 100_000_000 + HOUR)
        shadowOf(usm).addEvent("a", now - 30 * MINUTE, UsageEvents.Event.ACTIVITY_PAUSED)
        shadowOf(usm).addEvent("b", now - 30 * MINUTE + 1, UsageEvents.Event.ACTIVITY_RESUMED)
        assertThat(collector.read()).isEqualTo(Reading.Available(ForegroundApp("b", now - 30 * MINUTE + 1)))

        device(uptimeMs = 90_000_000 + 2 * HOUR, nowMs = 100_000_000 + 2 * HOUR)
        val bSince = 100_000_000 + HOUR - 30 * MINUTE + 1
        assertThat(collector.read()).isEqualTo(Reading.Available(ForegroundApp("b", bSince)))
    }

    @Test
    fun pausedAppWithoutNewResumeIsUnavailableAndStaysSo() {
        setMode(AppOpsManager.MODE_ALLOWED)
        device(uptimeMs = 90_000_000, nowMs = 100_000_000)
        shadowOf(usm).addEvent("a", now - 3 * HOUR, UsageEvents.Event.ACTIVITY_RESUMED)
        collector.read()

        device(uptimeMs = 90_000_000 + HOUR, nowMs = 100_000_000 + HOUR)
        shadowOf(usm).addEvent("a", now - MINUTE, UsageEvents.Event.ACTIVITY_PAUSED)

        assertThat(collector.read()).isInstanceOf(Reading.Unavailable::class.java)
        device(uptimeMs = 90_000_000 + 2 * HOUR, nowMs = 100_000_000 + 2 * HOUR)
        assertThat(collector.read()).isInstanceOf(Reading.Unavailable::class.java)
    }

    @Test
    fun firstQueryIgnoresEventsFromBeforeDeviceBoot() {
        setMode(AppOpsManager.MODE_ALLOWED)
        device(uptimeMs = 1_000_000, nowMs = 100_000_000) // загрузка в 99_000_000
        shadowOf(usm).addEvent("old", 99_000_000 - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)

        assertThat(collector.read()).isInstanceOf(Reading.Unavailable::class.java)
    }

    @Test
    fun lateEventWithinOverlapIsPickedUpBySecondQuery() {
        setMode(AppOpsManager.MODE_ALLOWED)
        device(uptimeMs = 90_000_000, nowMs = 100_000_000)
        assertThat(collector.read()).isInstanceOf(Reading.Unavailable::class.java)

        device(uptimeMs = 90_000_000 + 10_000, nowMs = 100_000_000 + 10_000)
        shadowOf(usm).addEvent("late", 100_000_000 - 30_000, UsageEvents.Event.ACTIVITY_RESUMED)

        assertThat(collector.read()).isEqualTo(Reading.Available(ForegroundApp("late", 100_000_000 - 30_000)))
    }

    @Test
    fun secondQueryIsIncrementalNotFromBootAgain() {
        setMode(AppOpsManager.MODE_ALLOWED)
        device(uptimeMs = 90_000_000, nowMs = 100_000_000) // загрузка в 10_000_000
        assertThat(collector.read()).isInstanceOf(Reading.Unavailable::class.java)

        device(uptimeMs = 90_000_000 + 10_000, nowMs = 100_000_000 + 10_000)
        // старше нахлёста (60 с) от прошлого запроса, но после загрузки: окно «от загрузки» его бы увидело
        shadowOf(usm).addEvent("old", 100_000_000 - 120_000, UsageEvents.Event.ACTIVITY_RESUMED)

        assertThat(collector.read()).isInstanceOf(Reading.Unavailable::class.java)
    }

    @Test
    fun missingPermissionDoesNotAdvanceTheQueryWindow() {
        device(uptimeMs = 90_000_000, nowMs = 100_000_000)
        setMode(AppOpsManager.MODE_ERRORED)
        collector.read()
        setMode(AppOpsManager.MODE_ALLOWED)
        shadowOf(usm).addEvent("a", now - 5 * HOUR, UsageEvents.Event.ACTIVITY_RESUMED)

        assertThat(collector.read()).isEqualTo(Reading.Available(ForegroundApp("a", now - 5 * HOUR)))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
