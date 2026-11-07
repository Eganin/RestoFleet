package ru.restofleet.agent.data.command

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.lifecycle.AndroidWizardPermissions
import ru.restofleet.agent.domain.capability.LaunchStrategy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAppLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pkg = "com.android.chrome"
    private val started = mutableListOf<Intent>()
    private var failure: Throwable? = null
    private var now = 1_700_000_000_000L
    private val permissions = AndroidWizardPermissions(context)
    private val ids = NotificationIds { now }
    private val starter: (Intent) -> Unit = { intent ->
        failure?.let { throw it }
        started += intent
    }
    private val launcher = AndroidAppLauncher(context, permissions, ids, starter)

    private fun launch(url: String?, strategy: LaunchStrategy, l: AndroidAppLauncher = launcher) =
        runBlocking { l.launch(pkg, url, strategy) }

    @Test
    fun viaNotificationPublishesNotificationWithUrlIntentLimitedToPackage() {
        context.install(pkg, withLauncher = false)

        val result = launch("https://x.test/menu", LaunchStrategy.VIA_NOTIFICATION).value()

        assertThat(started).isEmpty()
        val n = context.posted().single()
        assertThat(n.channelId).isEqualTo("commands")
        val content = n.contentIntent
        assertThat(shadowOf(content).isActivityIntent).isTrue()
        assertThat(shadowOf(content).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
        val saved = shadowOf(content).savedIntent
        assertThat(saved.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(saved.data.toString()).isEqualTo("https://x.test/menu")
        assertThat(saved.`package`).isEqualTo(pkg)
        assertThat(saved.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        val id = result["notificationId"]!!.toInt()
        assertThat(shadowOf(context.notificationManager()).getNotification(id)).isSameInstanceAs(n)
        assertThat(result).containsExactly(
            "strategy", "VIA_NOTIFICATION", "target", "url", "package", pkg, "notificationId", "$id",
        )
    }

    @Test
    fun viaNotificationWithoutUrlOpensLaunchActivity() {
        context.install(pkg)

        val result = launch(null, LaunchStrategy.VIA_NOTIFICATION).value()

        val saved = shadowOf(context.posted().single().contentIntent).savedIntent
        assertThat(saved.component).isEqualTo(ComponentName(pkg, "$pkg.Main"))
        assertThat(result["target"]).isEqualTo("package")
    }

    @Test
    fun viaNotificationFailsWhenNotificationsAreDisabled() {
        context.install(pkg)
        context.disableNotifications()

        assertThat(launch(null, LaunchStrategy.VIA_NOTIFICATION).errorCode()).isEqualTo("notifications_disabled")
        assertThat(context.posted()).isEmpty()
    }

    @Test
    fun directStrategiesStartActivityInNewTask() {
        context.install(pkg)
        for (strategy in listOf(LaunchStrategy.DIRECT_OVERLAY, LaunchStrategy.DIRECT_DO)) {
            started.clear()

            val result = launch("https://x.test/", strategy).value()

            val intent = started.single()
            assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
            assertThat(intent.`package`).isEqualTo(pkg)
            assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
            assertThat(result["strategy"]).isEqualTo(strategy.name)
            assertThat(result["target"]).isEqualTo("url")
        }
        assertThat(context.posted()).isEmpty()
    }

    @Test
    fun defaultStarterGoesThroughContextStartActivity() {
        context.install(pkg)

        launch(null, LaunchStrategy.DIRECT_DO, AndroidAppLauncher(context, permissions, ids)).value()

        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertThat(intent.component).isEqualTo(ComponentName(pkg, "$pkg.Main"))
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun targetFailuresUseTheCodesOfTheSharedResolver() {
        assertThat(launch(null, LaunchStrategy.DIRECT_DO).errorCode()).isEqualTo("package_not_installed")
        context.install(pkg, withLauncher = false)
        assertThat(launch(null, LaunchStrategy.DIRECT_DO).errorCode()).isEqualTo("no_launch_intent")
        assertThat(launch("http://x.test/", LaunchStrategy.VIA_NOTIFICATION).errorCode()).isEqualTo("invalid_url")
        assertThat(started).isEmpty()
        assertThat(context.posted()).isEmpty()
    }

    @Test
    fun launchRefusalsBecomeFailures() {
        context.install(pkg)
        failure = ActivityNotFoundException()
        assertThat(launch(null, LaunchStrategy.DIRECT_DO).errorCode()).isEqualTo("activity_not_found")
        failure = SecurityException()
        assertThat(launch(null, LaunchStrategy.DIRECT_OVERLAY).errorCode()).isEqualTo("launch_not_permitted")
    }

    @Test
    fun theSinglePermissionsPortDecidesWhetherNotificationsAreEnabled() {
        context.install(pkg)
        val off = AndroidAppLauncher(context, FakePermissions(context, notifications = false), ids, starter)

        assertThat(launch(null, LaunchStrategy.VIA_NOTIFICATION, off).errorCode()).isEqualTo("notifications_disabled")
        assertThat(context.posted()).isEmpty()
    }

    @Test
    fun disabledCommandsChannelFailsViaNotification() {
        context.install(pkg)
        context.disableCommandsChannel()

        assertThat(launch(null, LaunchStrategy.VIA_NOTIFICATION).errorCode()).isEqualTo("channel_disabled")
        assertThat(context.posted()).isEmpty()
        assertThat(launch(null, LaunchStrategy.DIRECT_DO).value()["strategy"]).isEqualTo("DIRECT_DO")
    }

    @Test
    fun notificationIdsAreSharedBetweenAdaptersOfOneProcessAndDifferAfterRestart() {
        context.install(pkg)
        val first = launch(null, LaunchStrategy.VIA_NOTIFICATION).value()["notificationId"]
        now += 5_000
        val restarted = AndroidAppLauncher(context, permissions, NotificationIds { now }, starter)
        val second = launch(null, LaunchStrategy.VIA_NOTIFICATION, restarted).value()["notificationId"]

        assertThat(second).isNotEqualTo(first)
    }
}
