package ru.restofleet.agent.data.command

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import org.robolectric.Shadows.shadowOf
import ru.restofleet.agent.data.lifecycle.AndroidWizardPermissions
import ru.restofleet.agent.data.lifecycle.DevicePermissions
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

internal fun Context.notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

/** Опубликованные уведомления в порядке возрастания id (порядок публикации). */
internal fun Context.posted(): List<Notification> =
    notificationManager().activeNotifications.sortedBy { it.id }.map { it.notification }

internal fun Context.disableNotifications() = shadowOf(notificationManager()).setNotificationsEnabled(false)

/** Пользователь выключил канал `commands` в настройках (система не даёт приложению поднять importance обратно). */
internal fun Context.disableCommandsChannel() = notificationManager().createNotificationChannel(
    NotificationChannel("commands", "commands", NotificationManager.IMPORTANCE_NONE),
)

/** Реальные разрешения; [notifications] подменяет только ответ «уведомления включены». */
internal class FakePermissions(
    context: Context,
    private val notifications: Boolean,
    private val real: DevicePermissions = AndroidWizardPermissions(context),
) : DevicePermissions by real {
    override fun notificationsEnabled(): Boolean = notifications
}

/** Регистрирует пакет [pkg] (при [withLauncher] — с MAIN/LAUNCHER-активностью `$pkg.Main`). */
internal fun Context.install(pkg: String, withLauncher: Boolean = true) {
    val pm = shadowOf(packageManager)
    pm.installPackage(
        PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        },
    )
    if (withLauncher) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Main"
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            }
        }
        pm.addResolveInfoForIntent(query, resolve)
        pm.addOrUpdateActivity(resolve.activityInfo)
    }
}

internal fun <T : Any> Outcome<T, *>.value(): T = (this as Outcome.Success).value
internal fun Outcome<*, *>.errorCode(): String = ((this as Outcome.Failure).error as DomainError).code
