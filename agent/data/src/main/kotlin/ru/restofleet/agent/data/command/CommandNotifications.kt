package ru.restofleet.agent.data.command

import android.app.Notification
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.restofleet.agent.data.R
import java.util.concurrent.atomic.AtomicInteger

/** Канал `commands` и сквозная нумерация уведомлений C1/C2; одно место для `NotificationUserNotifier` и launcher. */
internal object CommandNotifications {
    const val CHANNEL_ID = "commands"

    // 1 занят уведомлением foreground-сервиса (`AgentNotifications`), поэтому счёт идёт с 1000
    private const val FIRST_ID = 1000
    private val nextId = AtomicInteger(FIRST_ID)

    @DrawableRes
    const val SMALL_ICON = android.R.drawable.ic_dialog_info

    fun nextId(): Int = nextId.getAndIncrement()

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Идемпотентно: повторное создание канала с теми же параметрами ничего не меняет. */
    fun builder(context: Context): NotificationCompat.Builder {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.command_channel_name))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(SMALL_ICON)
            .setAutoCancel(true)
    }

    /** Вызывающий обязан сначала проверить [enabled]: без разрешения система молча отбросит уведомление. */
    @android.annotation.SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, notification: Notification) {
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
