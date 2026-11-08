package ru.restofleet.agent.presentation.lab.viewmodel.mapper

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.lab.viewmodel.LabMessage
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabCommand
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget

class StringSpecTest {
    @Test
    fun exportedUsesPathAsArgument() {
        assertThat(LabMessage.Exported("/files/r.json").toStringSpec())
            .isEqualTo(StringSpec(R.string.lab_message_exported, listOf("/files/r.json")))
    }

    @Test
    fun publishedUsesCheckName() {
        assertThat(LabMessage.Published(CheckId.R1).toStringSpec())
            .isEqualTo(StringSpec(R.string.lab_message_published, listOf("R1")))
    }

    @Test
    fun errorUsesDetails() {
        assertThat(LabMessage.Error("denied").toStringSpec())
            .isEqualTo(StringSpec(R.string.lab_message_error, listOf("denied")))
    }

    @Test
    fun messagesWithoutArguments() {
        assertThat(LabMessage.MqttNotConfigured.toStringSpec())
            .isEqualTo(StringSpec(R.string.lab_message_mqtt_not_configured))
        assertThat(LabMessage.NoResult.toStringSpec()).isEqualTo(StringSpec(R.string.lab_message_no_result))
    }

    @Test
    fun everySettingsTargetHasItsOwnLabel() {
        assertThat(SettingsTarget.NOTIFICATIONS.labelRes()).isEqualTo(R.string.lab_settings_notifications)
        assertThat(SettingsTarget.USAGE_ACCESS.labelRes()).isEqualTo(R.string.lab_settings_usage_access)
        assertThat(SettingsTarget.BATTERY_OPTIMIZATION.labelRes())
            .isEqualTo(R.string.lab_settings_battery_optimization)
    }

    @Test
    fun everyLabCommandHasItsOwnLabel() {
        assertThat(LabCommand.SHOW_MESSAGE.labelRes()).isEqualTo(R.string.lab_command_show_message)
        assertThat(LabCommand.LAUNCH_APP.labelRes()).isEqualTo(R.string.lab_command_launch_app)
        assertThat(LabCommand.LAUNCH_APP_DELAYED.labelRes()).isEqualTo(R.string.lab_command_launch_app_delayed)
        assertThat(LabCommand.BEEP.labelRes()).isEqualTo(R.string.lab_command_beep)
    }
}
