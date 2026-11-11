package ru.restofleet.agent.presentation.common

import androidx.compose.runtime.Immutable
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.setup.viewmodel.model.WIZARD_PERMISSIONS

/** Строка «подпись — значение» (мастер, статус); значение — тоже ресурс, сырой текст — через `value_raw`. */
@Immutable
data class InfoRow(val label: StringSpec, val value: StringSpec)

/** Строка разрешения: что за разрешение, выдано ли, какой экран настроек его открывает. */
@Immutable
data class PermissionRow(val target: SettingsTarget, val label: StringSpec, val state: StringSpec, val badge: Badge)

internal fun raw(text: String): StringSpec = StringSpec(R.string.value_raw, listOf(text))

internal fun DeviceRole?.toStringSpec(): StringSpec = StringSpec(
    when (this) {
        DeviceRole.KITCHEN -> R.string.role_kitchen
        DeviceRole.HALL -> R.string.role_hall
        DeviceRole.ASSEMBLY -> R.string.role_assembly
        null -> R.string.role_unknown
    },
)

/** Строки регистрации без секрета: устройство, роль, ресторан (у учётки `DEV_CONFIG` его нет — прочерк). */
internal fun Registration.toRows(): List<InfoRow> = listOf(
    InfoRow(StringSpec(R.string.label_device_id), raw(deviceId.value)),
    InfoRow(StringSpec(R.string.label_role), role.toStringSpec()),
    InfoRow(StringSpec(R.string.label_restaurant), restaurantId?.let(::raw) ?: StringSpec(R.string.value_none)),
)

/** Три разрешения мастера (FR-AG-04); [granted] = `null` — ещё не прочитаны или источник упал. */
internal fun permissionRows(granted: Set<Permission>?): List<PermissionRow> =
    WIZARD_PERMISSIONS.map { (permission, target) ->
        val (state, badge) = when {
            granted == null -> R.string.perm_unknown to Badge.NA
            permission in granted -> R.string.perm_granted to Badge.OK
            else -> R.string.perm_missing to Badge.WARN
        }
        PermissionRow(target, StringSpec(permission.label()), StringSpec(state), badge)
    }

private fun Permission.label(): Int = when (this) {
    Permission.POST_NOTIFICATIONS -> R.string.perm_notifications
    Permission.USAGE_ACCESS -> R.string.perm_usage_access
    else -> R.string.perm_battery
}
