package ru.restofleet.agent.data.command

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.restofleet.agent.data.R
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.UserNotifier
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * C1: сообщение персоналу уведомлением в канале `commands`. Текст виден в самом уведомлении; отдельного экрана
 * сообщения в MVP нет, поэтому `fullScreenIntent` (только при выданном `FULL_SCREEN_INTENT`) открывает launcher-
 * Activity своего приложения.
 */
class NotificationUserNotifier(
    private val context: Context,
    private val capabilities: CapabilitiesSource,
) : UserNotifier {
    override suspend fun show(text: String, level: Level): Outcome<Map<String, String>, DomainError> {
        if (!CommandNotifications.enabled(context)) return Outcome.Failure(CommandError("notifications_disabled"))
        val fullScreen = if (Permission.FULL_SCREEN_INTENT in capabilities.current().granted) {
            ownLauncherIntent()
        } else {
            null
        }
        val builder = CommandNotifications.builder(context)
            .setContentTitle(context.getString(R.string.command_message_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(category(level))
            .setPriority(priority(level))
        if (fullScreen != null) builder.setFullScreenIntent(fullScreen, true)
        val id = CommandNotifications.nextId()
        CommandNotifications.post(context, id, builder.build())
        return Outcome.Success(mapOf("notificationId" to "$id", "fullScreen" to "${fullScreen != null}"))
    }

    private fun ownLauncherIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(
                context,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun category(level: Level): String =
        if (level == Level.CRITICAL) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE

    private fun priority(level: Level): Int = when (level) {
        Level.INFO -> NotificationCompat.PRIORITY_DEFAULT
        Level.WARNING -> NotificationCompat.PRIORITY_HIGH
        Level.CRITICAL -> NotificationCompat.PRIORITY_MAX
    }
}
