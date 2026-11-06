package ru.restofleet.agent

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Канал и уведомление foreground-сервиса: IMPORTANCE_LOW, ongoing. */
class AgentNotifications(private val context: Context) {
    fun build(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_name))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "agent_service"
        const val NOTIFICATION_ID = 1
    }
}
