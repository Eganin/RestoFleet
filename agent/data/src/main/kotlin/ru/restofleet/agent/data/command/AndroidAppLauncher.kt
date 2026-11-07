package ru.restofleet.agent.data.command

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.restofleet.agent.data.R
import ru.restofleet.agent.data.launch.resolveLaunchTarget
import ru.restofleet.agent.data.lifecycle.DevicePermissions
import ru.restofleet.agent.domain.capability.LaunchStrategy
import ru.restofleet.agent.domain.port.AppLauncher
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * C2: запуск приложения или адреса. `VIA_NOTIFICATION` — уведомление, по нажатию которого система сама запускает
 * Activity (запуск из уведомления разрешён и при фоновых ограничениях API 29+); `DIRECT_*` — `startActivity`
 * из фона: по документации Android это допускается при выданном `SYSTEM_ALERT_WINDOW`, для Device Owner
 * исключение в документации не названо (решение 111; подтверждение — M4).
 */
class AndroidAppLauncher(
    private val context: Context,
    private val permissions: DevicePermissions,
    private val ids: NotificationIds,
    private val starter: (Intent) -> Unit = { context.startActivity(it) },
) : AppLauncher {
    override suspend fun launch(
        pkg: String,
        url: String?,
        strategy: LaunchStrategy,
    ): Outcome<Map<String, String>, DomainError> {
        val target = when (val resolved = resolveLaunchTarget(context.packageManager, pkg, url)) {
            is Outcome.Failure -> return resolved
            is Outcome.Success -> resolved.value.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = mapOf(
            "strategy" to strategy.name,
            "target" to if (url != null) "url" else "package",
            "package" to pkg,
        )
        return when (strategy) {
            LaunchStrategy.VIA_NOTIFICATION -> when (val posted = notify(target, pkg, url)) {
                is Outcome.Success -> Outcome.Success(result + ("notificationId" to "${posted.value}"))
                is Outcome.Failure -> posted
            }
            LaunchStrategy.DIRECT_OVERLAY, LaunchStrategy.DIRECT_DO -> direct(target) ?: Outcome.Success(result)
        }
    }

    /** `null` — активность запущена, иначе причина отказа. */
    @Suppress("SwallowedException") // тип исключения и есть причина отказа
    private fun direct(target: Intent): Outcome<Nothing, DomainError>? =
        try {
            starter(target)
            null
        } catch (e: ActivityNotFoundException) {
            Outcome.Failure(CommandError("activity_not_found"))
        } catch (e: SecurityException) {
            Outcome.Failure(CommandError("launch_not_permitted"))
        }

    /** Идентификатор опубликованного уведомления или причина, по которой его нельзя показать. */
    private fun notify(target: Intent, pkg: String, url: String?): Outcome<Int, CommandError> {
        val builder = CommandNotifications.builder(context)
        return when {
            !permissions.notificationsEnabled() -> Outcome.Failure(CommandError("notifications_disabled"))
            builder == null -> Outcome.Failure(CommandError("channel_disabled"))
            else -> Outcome.Success(publish(builder, target, pkg, url))
        }
    }

    private fun publish(builder: NotificationCompat.Builder, target: Intent, pkg: String, url: String?): Int {
        val id = ids.next()
        val content = PendingIntent.getActivity(
            context,
            id,
            target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (url != null) {
            context.getString(R.string.command_launch_text_url, url)
        } else {
            context.getString(R.string.command_launch_text_package, pkg)
        }
        val notification = builder
            .setContentTitle(context.getString(R.string.command_launch_title))
            .setContentText(text)
            .setContentIntent(content)
            .build()
        CommandNotifications.post(context, id, notification)
        return id
    }
}
