package ru.restofleet.agent.data.command

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import org.robolectric.Shadows.shadowOf
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

internal fun Context.notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

internal fun Context.posted(): List<Notification> = shadowOf(notificationManager()).allNotifications

internal fun Context.disableNotifications() = shadowOf(notificationManager()).setNotificationsEnabled(false)

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
