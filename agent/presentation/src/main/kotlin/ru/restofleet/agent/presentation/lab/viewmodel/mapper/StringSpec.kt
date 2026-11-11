package ru.restofleet.agent.presentation.lab.viewmodel.mapper

import androidx.annotation.StringRes
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.lab.viewmodel.LabMessage
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabCommand
import ru.restofleet.agent.presentation.common.SettingsTarget

/** Какой ресурс и с какими аргументами показать для сообщения Lab. */
fun LabMessage.toStringSpec(): StringSpec = when (this) {
    is LabMessage.Exported -> StringSpec(R.string.lab_message_exported, listOf(path))
    is LabMessage.Published -> StringSpec(R.string.lab_message_published, listOf(id.name))
    is LabMessage.Error -> StringSpec(R.string.lab_message_error, listOf(details))
    LabMessage.MqttNotConfigured -> StringSpec(R.string.lab_message_mqtt_not_configured)
    LabMessage.NoResult -> StringSpec(R.string.lab_message_no_result)
    is LabMessage.ForeignTopic -> StringSpec(R.string.lab_message_foreign_topic, listOf(reason))
    is LabMessage.DebugEventsSent ->
        StringSpec(R.string.lab_message_debug_events, listOf(count.toString(), batchId))
}

@StringRes
fun SettingsTarget.labelRes(): Int = when (this) {
    SettingsTarget.NOTIFICATIONS -> R.string.lab_settings_notifications
    SettingsTarget.USAGE_ACCESS -> R.string.lab_settings_usage_access
    SettingsTarget.BATTERY_OPTIMIZATION -> R.string.lab_settings_battery_optimization
}

@StringRes
fun LabCommand.labelRes(): Int = when (this) {
    LabCommand.SHOW_MESSAGE -> R.string.lab_command_show_message
    LabCommand.LAUNCH_APP -> R.string.lab_command_launch_app
    LabCommand.LAUNCH_APP_DELAYED -> R.string.lab_command_launch_app_delayed
    LabCommand.BEEP -> R.string.lab_command_beep
}
