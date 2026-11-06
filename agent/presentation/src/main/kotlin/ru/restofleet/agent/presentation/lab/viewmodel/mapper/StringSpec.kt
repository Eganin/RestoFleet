package ru.restofleet.agent.presentation.lab.viewmodel.mapper

import androidx.annotation.StringRes
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.lab.viewmodel.LabMessage
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget

/** Строковый ресурс и его аргументы: чистая функция выбора, сам `getString` остаётся в UI. */
data class StringSpec(@param:StringRes val res: Int, val args: List<String> = emptyList())

/** Какой ресурс и с какими аргументами показать для сообщения Lab. */
fun LabMessage.toStringSpec(): StringSpec = when (this) {
    is LabMessage.Exported -> StringSpec(R.string.lab_message_exported, listOf(path))
    is LabMessage.Published -> StringSpec(R.string.lab_message_published, listOf(id.name))
    is LabMessage.Error -> StringSpec(R.string.lab_message_error, listOf(details))
    LabMessage.MqttNotConfigured -> StringSpec(R.string.lab_message_mqtt_not_configured)
    LabMessage.NoResult -> StringSpec(R.string.lab_message_no_result)
}

@StringRes
fun SettingsTarget.labelRes(): Int = when (this) {
    SettingsTarget.NOTIFICATIONS -> R.string.lab_settings_notifications
    SettingsTarget.USAGE_ACCESS -> R.string.lab_settings_usage_access
    SettingsTarget.BATTERY_OPTIMIZATION -> R.string.lab_settings_battery_optimization
}
