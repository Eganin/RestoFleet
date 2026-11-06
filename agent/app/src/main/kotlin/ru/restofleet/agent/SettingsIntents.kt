package ru.restofleet.agent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget

/** Intent-ы экранов настроек для кнопок L4 Capability Lab. */
class SettingsIntents(private val packageName: String) {
    fun notifications(): Intent = newTask(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    fun usageAccess(): Intent = newTask(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun batteryOptimization(): Intent =
        newTask(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))

    fun forTarget(target: SettingsTarget): Intent = when (target) {
        SettingsTarget.NOTIFICATIONS -> notifications()
        SettingsTarget.USAGE_ACCESS -> usageAccess()
        SettingsTarget.BATTERY_OPTIMIZATION -> batteryOptimization()
    }

    private fun newTask(intent: Intent) = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
