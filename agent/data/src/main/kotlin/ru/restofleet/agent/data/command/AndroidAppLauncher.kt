package ru.restofleet.agent.data.command

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import ru.restofleet.agent.data.R
import ru.restofleet.agent.data.launch.resolveLaunchTarget
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
            LaunchStrategy.VIA_NOTIFICATION -> notify(target, pkg, url)
                ?.let { Outcome.Success(result + ("notificationId" to "$it")) }
                ?: Outcome.Failure(CommandError("notifications_disabled"))
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

    /** Идентификатор опубликованного уведомления или `null`, если уведомления выключены. */
    private fun notify(target: Intent, pkg: String, url: String?): Int? {
        if (!CommandNotifications.enabled(context)) return null
        val id = CommandNotifications.nextId()
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
        val notification = CommandNotifications.builder(context)
            .setContentTitle(context.getString(R.string.command_launch_title))
            .setContentText(text)
            .setContentIntent(content)
            .build()
        CommandNotifications.post(context, id, notification)
        return id
    }
}
