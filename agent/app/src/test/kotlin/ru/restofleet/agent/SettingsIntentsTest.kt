package ru.restofleet.agent

import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.restofleet.agent.presentation.common.SettingsTarget

@RunWith(RobolectricTestRunner::class)
class SettingsIntentsTest {
    private val intents = SettingsIntents("ru.restofleet.agent")

    @Test
    fun notificationsIntentTargetsAppNotificationSettings() {
        val i = intents.notifications()

        assertThat(i.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(i.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo("ru.restofleet.agent")
    }

    @Test
    fun usageAccessIntentOpensUsageAccessSettings() {
        assertThat(intents.usageAccess().action).isEqualTo(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    @Test
    fun batteryIntentRequestsExemptionForPackage() {
        val i = intents.batteryOptimization()

        assertThat(i.action).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(i.data.toString()).isEqualTo("package:ru.restofleet.agent")
    }

    @Test
    fun allIntentsStartInNewTask() {
        listOf(intents.notifications(), intents.usageAccess(), intents.batteryOptimization()).forEach {
            assertThat(it.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        }
    }

    @Test
    fun forTargetPicksTheMatchingIntent() {
        assertThat(intents.forTarget(SettingsTarget.NOTIFICATIONS).action)
            .isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(intents.forTarget(SettingsTarget.USAGE_ACCESS).action)
            .isEqualTo(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        assertThat(intents.forTarget(SettingsTarget.BATTERY_OPTIMIZATION).action)
            .isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }
}
