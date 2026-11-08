package ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper

import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerMessage

/** Какой ресурс и с какими аргументами показать для сообщения экрана Device Owner. */
fun DeviceOwnerMessage.toStringSpec(): StringSpec = when (this) {
    DeviceOwnerMessage.HashFetched -> StringSpec(R.string.do_message_hash_fetched)
    is DeviceOwnerMessage.HashFailed -> StringSpec(R.string.do_message_hash_failed, listOf(code))
    is DeviceOwnerMessage.Error -> StringSpec(R.string.do_message_error, listOf(details))
}
