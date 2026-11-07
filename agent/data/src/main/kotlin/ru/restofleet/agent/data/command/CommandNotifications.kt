package ru.restofleet.agent.data.command

import android.app.Notification
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.restofleet.agent.data.R

/** Канал `commands` и сквозная нумерация уведомлений C1/C2; одно место для `NotificationUserNotifier` и launcher. */
internal object CommandNotifications {
    const val CHANNEL_ID = "commands"

    @DrawableRes
    const val SMALL_ICON = android.R.drawable.ic_dialog_info

    /**
     * Builder уведомления в канале `commands`; канал создаётся идемпотентно. `null`, если пользователь выключил
     * сам канал (`IMPORTANCE_NONE`): приложение не может вернуть его обратно, а уведомление пропало бы молча.
     */
    fun builder(context: Context): NotificationCompat.Builder? {
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.command_channel_name))
            .build()
        manager.createNotificationChannel(channel)
        val importance = manager.getNotificationChannelCompat(CHANNEL_ID)?.importance
        return if (importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            null
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(SMALL_ICON).setAutoCancel(true)
        }
    }

    /** Вызывающий обязан сначала проверить разрешения на уведомления: иначе система молча их отбросит. */
    @android.annotation.SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, notification: Notification) {
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
