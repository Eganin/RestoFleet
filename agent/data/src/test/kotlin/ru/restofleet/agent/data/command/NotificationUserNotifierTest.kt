package ru.restofleet.agent.data.command

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.command.Level

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationUserNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var granted: Set<Permission> = emptySet()
    private val notifier = NotificationUserNotifier(context) { Capabilities(AgentMode.NORMAL, 34, granted) }

    private fun show(text: String = "Привет", level: Level = Level.INFO) = runBlocking { notifier.show(text, level) }

    @Test
    fun createsHighImportanceCommandsChannelIdempotently() {
        show()
        show()

        val channels = shadowOf(context.notificationManager()).notificationChannels
        assertThat(channels.map { it.id }).containsExactly("commands")
        assertThat(channels.single().importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }

    @Test
    fun postsNotificationWithTheText() {
        val result = show("Закройте смену")

        val n = context.posted().single()
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Закройте смену")
        assertThat(n.channelId).isEqualTo("commands")
        assertThat(n.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        val id = result.value()["notificationId"]!!.toInt()
        assertThat(shadowOf(context.notificationManager()).getNotification(id)).isSameInstanceAs(n)
    }

    @Test
    fun everyMessageGetsItsOwnId() {
        val first = show("a").value()["notificationId"]
        val second = show("b").value()["notificationId"]

        assertThat(first).isNotEqualTo(second)
        assertThat(context.posted()).hasSize(2)
    }

    @Test
    fun levelDefinesCategoryAndPriority() {
        show(level = Level.INFO)
        show(level = Level.WARNING)
        show(level = Level.CRITICAL)

        val (info, warning, critical) = context.posted()
        assertThat(info.category).isEqualTo(Notification.CATEGORY_MESSAGE)
        assertThat(warning.category).isEqualTo(Notification.CATEGORY_MESSAGE)
        assertThat(critical.category).isEqualTo(Notification.CATEGORY_ALARM)
        @Suppress("DEPRECATION")
        assertThat(listOf(info.priority, warning.priority, critical.priority))
            .containsExactly(
                NotificationCompat.PRIORITY_DEFAULT,
                NotificationCompat.PRIORITY_HIGH,
                NotificationCompat.PRIORITY_MAX,
            ).inOrder()
    }

    @Test
    fun fullScreenIntentOnlyWhenPermissionGranted() {
        context.install(context.packageName)
        val without = show().value()
        assertThat(without["fullScreen"]).isEqualTo("false")
        assertThat(context.posted().single().fullScreenIntent).isNull()

        granted = setOf(Permission.FULL_SCREEN_INTENT)
        val with = show().value()
        assertThat(with["fullScreen"]).isEqualTo("true")
        val pending = context.posted().last().fullScreenIntent
        assertThat(pending).isNotNull()
        val saved = shadowOf(pending).savedIntent
        assertThat(saved.component?.className).isEqualTo("${context.packageName}.Main")
        assertThat(shadowOf(pending).flags and android.app.PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
    }

    @Test
    fun noLauncherActivityMeansNoFullScreenIntentButStillShown() {
        granted = setOf(Permission.FULL_SCREEN_INTENT)

        val result = show().value()

        assertThat(result["fullScreen"]).isEqualTo("false")
        assertThat(context.posted().single().fullScreenIntent).isNull()
    }

    @Test
    fun disabledNotificationsFailAndPostNothing() {
        context.disableNotifications()

        val result = show()

        assertThat(result.errorCode()).isEqualTo("notifications_disabled")
        assertThat(context.posted()).isEmpty()
    }
}
